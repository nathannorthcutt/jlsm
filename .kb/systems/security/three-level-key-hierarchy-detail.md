---
title: "Three-Level Key Hierarchy — Extended Detail"
topic: "systems"
category: "security"
parent: "three-level-key-hierarchy.md"
last_researched: "2026-04-20"
---

# Three-Level Key Hierarchy — Extended Detail

Extension of `three-level-key-hierarchy.md` covering reference-design
comparison, complexity analysis, tradeoffs, and an implementation sketch.
Read the parent file first for the core model, HKDF rules, and rotation
invariants.

## reference-designs-compared

| System | Level 1 | Level 2 | Level 3 | Rotation mechanism | Derivation |
|--------|---------|---------|---------|--------------------|-----------|
| **CockroachDB** | Store key (user-supplied, rotated via node restart) | Data keys (registry, weekly auto-rotate) | — (two-level) | Re-wrap data-key registry under new store key; compaction re-encrypts | None — random DEKs |
| **AWS Encryption SDK** | KMS CMK (root) | — (direct) | Per-message DEK | KMS re-encrypt API; data not touched on CMK rotation | HKDF-expand of DEK with encryption context as `info` |
| **Google Tink KmsEnvelopeAead** | Cloud KMS key | — (direct) | Per-message DEK (random) | KMS-level rotation; wrapped DEK stored in ciphertext | None at DEK level; HKDF used inside algorithm suite |
| **MongoDB CSFLE** | Customer Master Key (KMS) | — (direct) | Per-field DEK in Key Vault | `rewrapManyDataKey()` re-wraps DEKs under new CMK | None — random DEKs per field |
| **HashiCorp Vault Transit** | Vault master key | Per-key "keyring" versions | Derived per-context keys (optional) | `rewrap` endpoint decrypts-and-re-encrypts under newest version | HKDF with user-supplied `context` for derived keys |
| **Google Cloud KMS (envelope)** | KEK in Cloud KMS (HSM) | — (direct) | Per-workload DEK | KMS version rotation; wrapped DEK re-wrap on demand | None at DEK level |

### key-observations-for-jlsm

1. Most production systems use **two levels** (CMK + DEK). The **third level** (domain KEK) is what enables **multi-tenant blast-radius containment** without needing a per-tenant KMS key — essential if jlsm deployments will host multiple isolated data domains under one root KEK.
2. **AWS Encryption SDK and Vault Transit** are the two that bind context via HKDF — this pattern is what `info`-parameter binding looks like in production. AWS uses "encryption context" as AD in AES-GCM *and* as HKDF info.
3. **CockroachDB's compaction-driven re-encryption** is the closest LSM-native rotation model — directly applicable to jlsm.
4. **MongoDB's `rewrapManyDataKey`** is the cleanest API for bulk re-wrap — a useful shape for jlsm's own rotation tooling.

## implementation-notes

### jlsm-design-sketch

```java
// Three-level holder pattern
final class KeyHierarchy implements AutoCloseable {
    private final KmsClient kms;                          // wraps root KEK
    private final Map<DomainId, DomainKekHolder> domains; // level 2
    // DEKs are owned by each table, unwrapped on table open

    DekHolder openTableDek(DomainId domainId, TableId tableId, int dekVersion) {
        DomainKekHolder domainKek = domains.computeIfAbsent(domainId, this::openDomain);
        WrappedDek wrapped = dekRegistry.find(tableId, dekVersion);
        byte[] info = buildHkdfInfo(domainId, tableId, "sstable-dek", dekVersion);
        byte[] plaintextDek = aesGcmUnwrap(domainKek.segment(), wrapped, info /* = AD */);
        try {
            return new DekHolder(plaintextDek);   // copies to Arena
        } finally {
            Arrays.fill(plaintextDek, (byte) 0);
        }
    }

    private DomainKekHolder openDomain(DomainId id) {
        WrappedDomainKek wrapped = domainKekRegistry.find(id);
        byte[] plaintext = kms.decrypt(wrapped.bytes()); // KMS unwraps root→domain
        try {
            return new DomainKekHolder(plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }
}
```

### edge-cases-and-gotchas

- **HKDF `salt` vs `info` confusion**: salt is for extractor randomization (can be empty if the input key is already uniform — which wrapped DEKs are); `info` is for domain separation. **Do not** put context in salt — it won't provide domain separation.
- **Wrapped-key integrity**: always use authenticated wrap (AES-GCM with AD, or AES-KW which is AE by design). Never use raw AES-CBC to wrap — bit-flip attacks on DEKs.
- **Version monotonicity**: DEK version must be monotone per-table. Reusing a version number under a rotated domain KEK creates ciphertext-context mismatch at unwrap (wrong AD).
- **KMS outage at table open**: domain KEK unwrap requires KMS. Mitigate by caching unwrapped domain KEKs in memory with a configurable TTL; renew proactively before TTL.
- **Orphan wrapped DEKs after domain-KEK rotation**: if a DEK re-wrap step fails mid-rotation, the registry may have mixed versions. The rotation protocol must be resumable: store a `rotation-in-progress` marker with the old+new domain KEK versions, and retry re-wrap of any DEK still wrapped under the old version.
- **Context parameter drift**: if the HKDF `info` schema changes (new field added), old DEKs derive differently under the new schema. Fix: version the schema itself inside `info` (e.g., prepend a schema-version byte).

## complexity-analysis

### build-phase
- Root KEK: managed by KMS; no local cost.
- Domain KEK: one random generation + one KMS wrap (≈ 1 KMS call).
- DEK: one random generation + one AES-GCM wrap + HKDF call for context info (≈ μs locally).

### query-phase
- Table open: one KMS call (domain KEK unwrap, cacheable) + one local unwrap (DEK).
- Steady-state encrypt/decrypt: no hierarchy cost; direct AES-GCM under cached DEK.

### memory-footprint
- Root KEK: 0 (remains in KMS/HSM).
- Domain KEKs: 32 bytes × #open domains, in off-heap Arena.
- DEKs: 32 bytes × #open tables, in off-heap Arena.
- Registries: tens of KB per thousand tables; CBOR-encoded on disk, parsed on demand.

## tradeoffs

### strengths

- **Rotation cost is bounded at each level** — rotating root KEK doesn't touch data; rotating domain KEK doesn't touch data; rotating DEK uses existing compaction.
- **Tenant/domain isolation** — compromising one domain KEK doesn't compromise others.
- **Context binding prevents cross-scope ciphertext misuse** — HKDF `info` + AEAD AD make mis-routing a detectable error.
- **KMS surface is minimized** — only domain KEKs are wrapped by the root KEK; KMS is not on the hot path for data operations.

### weaknesses

- **More metadata** than two-level (domain KEK registry in addition to DEK registry).
- **KMS dependency at domain open** — cachable but needs fallback policy.
- **HKDF context schema versioning** is an additional moving part if the scope definition evolves.
- **Three levels = three rotation protocols** to implement and test; more surface area for bugs.

### compared-to-alternatives

- **Two-level (CMK + DEK)**: simpler but no multi-tenant isolation layer. If jlsm will always be single-tenant, two-level suffices.
- **Flat (single key)**: no rotation without full re-encrypt; unsuitable for any production system.
- **Four+ levels**: extra levels (e.g., region → cluster → domain → table → object) add rotation cost without meaningful isolation gains for a single-process library.

## practical-usage

### when-to-use

- Multi-tenant jlsm deployments (one root KEK, per-tenant domain KEK).
- Deployments with distinct data families (e.g., PII vs analytics) that need independent rotation cadences.
- Any deployment that wants to rotate the root (KMS) key without touching data.

### when-not-to-use

- Single-tenant, single-schema embedded usage where a two-level (KMS-CMK → table-DEK) model is simpler and sufficient.
- Per-record legal boundaries where per-object DEKs (four-effective-levels with key-shred erasure) are required — the three-level model is the base; add per-object derivation on top.

## code-skeleton

```java
// Derive HKDF info deterministically with length-prefixing
static byte[] buildHkdfInfo(String tenantId, String tableId,
                            String purpose, int dekVersion) {
    // info = schemaVer || len(tenant) || tenant || len(table) || table
    //      || len(purpose) || purpose || dekVersion
    // All length prefixes are 2-byte big-endian; schemaVer is 1 byte.
    // ...
}

// Wrap a DEK under a domain KEK with context as AD
byte[] wrapDek(MemorySegment domainKekSegment, byte[] dekPlaintext, byte[] info) {
    byte[] nonce = new byte[12];
    secureRandom.nextBytes(nonce);
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE,
           new SecretKeySpec(domainKekSegment.toArray(JAVA_BYTE), "AES"),
           new GCMParameterSpec(128, nonce));
    c.updateAAD(info);  // binds DEK to context
    byte[] ct = c.doFinal(dekPlaintext);
    return concat(nonce, ct);   // [12B nonce | ciphertext | 16B tag]
}
```

---
*Parent: [three-level-key-hierarchy.md](three-level-key-hierarchy.md)*
