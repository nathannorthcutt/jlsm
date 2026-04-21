---
title: "Three-Level Key Hierarchy (Root KEK → Domain KEKs → DEKs)"
aliases: ["three-tier key hierarchy", "root KEK", "domain KEK", "envelope wrapping", "HKDF derivation", "per-table DEK", "per-object DEK"]
topic: "systems"
category: "security"
tags: ["encryption", "key-hierarchy", "envelope-encryption", "kek", "dek", "hkdf", "key-wrap", "rfc-3394", "rfc-5649", "aes-siv", "domain-separation", "key-rotation"]
complexity:
  time_build: "O(1) per key at each level"
  time_query: "O(levels) unwrap chain on open; O(1) steady-state with cached DEK"
  space: "O(tables) wrapped DEK metadata + O(domains) wrapped domain KEKs + 1 root KEK"
research_status: "active"
confidence: "high"
last_researched: "2026-04-20"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/"
  - "modules/jlsm-core/src/main/java/jlsm/core/io/"
related:
  - "systems/security/encryption-key-rotation-patterns.md"
  - "systems/security/jvm-key-handling-patterns.md"
  - "systems/security/wal-encryption-approaches.md"
  - "systems/security/client-side-encryption-patterns.md"
decision_refs: ["implement-encryption-lifecycle"]
sources:
  - url: "https://datatracker.ietf.org/doc/html/rfc5869"
    title: "RFC 5869 — HMAC-based Extract-and-Expand Key Derivation Function (HKDF)"
    accessed: "2026-04-20"
    type: "rfc"
  - url: "https://datatracker.ietf.org/doc/rfc9709/"
    title: "RFC 9709 — Encryption Key Derivation in CMS Using HKDF with SHA-256 (Jan 2025)"
    accessed: "2026-04-20"
    type: "rfc"
  - url: "https://www.rfc-editor.org/rfc/rfc3394"
    title: "RFC 3394 — AES Key Wrap Algorithm"
    accessed: "2026-04-20"
    type: "rfc"
  - url: "https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-38F.pdf"
    title: "NIST SP 800-38F — Recommendation for Block Cipher Modes of Operation: Methods for Key Wrapping"
    accessed: "2026-04-20"
    type: "standard"
  - url: "https://github.com/cockroachdb/cockroach/blob/master/docs/RFCS/20171220_encryption_at_rest.md"
    title: "CockroachDB RFC — Encryption at Rest (store key + data key)"
    accessed: "2026-04-20"
    type: "rfc"
  - url: "https://docs.aws.amazon.com/encryption-sdk/latest/developer-guide/how-it-works.html"
    title: "AWS Encryption SDK — How It Works (envelope + encryption context + HKDF)"
    accessed: "2026-04-20"
    type: "docs"
  - url: "https://docs.cloud.google.com/kms/docs/envelope-encryption"
    title: "Google Cloud KMS — Envelope Encryption"
    accessed: "2026-04-20"
    type: "docs"
  - url: "https://developers.google.com/tink/client-side-encryption"
    title: "Google Tink — Client-Side Encryption with Cloud KMS (KmsEnvelopeAead)"
    accessed: "2026-04-20"
    type: "docs"
  - url: "https://developer.hashicorp.com/vault/docs/secrets/transit"
    title: "HashiCorp Vault Transit — Key Derivation, Convergent Encryption, Rewrap"
    accessed: "2026-04-20"
    type: "docs"
  - url: "https://www.mongodb.com/docs/manual/core/csfle/fundamentals/manage-keys/"
    title: "MongoDB CSFLE — CMK/DEK hierarchy and rewrapManyDataKey"
    accessed: "2026-04-20"
    type: "docs"
---

# Three-Level Key Hierarchy (Root KEK → Domain KEKs → DEKs)

## summary

A three-level hierarchy separates the concerns of **root trust** (a single root
KEK held in a KMS/HSM), **blast-radius containment** (per-domain KEKs — per
tenant, per table family, or per partition), and **bulk data encryption**
(per-table or per-object DEKs). Each level wraps the level below using a
symmetric key-wrap primitive. Rotating any level re-wraps only the level
immediately below it — never the data — so rotation is O(keys), not O(bytes).
Context binding via HKDF `info` fields and AEAD associated data cryptographically
pins each DEK to its scope (tenant, table, partition), making cross-scope
ciphertext reuse a detectable error rather than a silent leak.

## how-it-works

### three-level-structure

```
  ┌───────────────────────────────────────────────┐
  │  ROOT KEK  (held in KMS/HSM; never leaves)    │  Level 1
  │  - One per deployment                         │
  │  - Rotated via KMS alias swap                 │
  └───────────────┬───────────────────────────────┘
                  │ wraps
                  ▼
  ┌───────────────────────────────────────────────┐
  │  DOMAIN KEKs  (one per tenant / table family) │  Level 2
  │  - Stored wrapped under root KEK              │
  │  - Rotated by re-wrapping DEK registry        │
  └───────────────┬───────────────────────────────┘
                  │ wraps
                  ▼
  ┌───────────────────────────────────────────────┐
  │  DEKs  (per table / per SSTable / per object) │  Level 3
  │  - Used for AES-GCM bulk encryption           │
  │  - Rotated via compaction re-encryption       │
  └───────────────────────────────────────────────┘
```

### why-three-levels

| Level | Purpose | Rotation cost | Blast radius |
|-------|---------|---------------|--------------|
| 1. Root KEK | Anchors trust; rarely rotated | Re-wrap each domain KEK (O(domains), tens to hundreds) | Full deployment |
| 2. Domain KEK | Isolates tenants/data families | Re-wrap each DEK in domain (O(DEKs in domain)) | One tenant or family |
| 3. DEK | Encrypts bulk data | Re-encrypt data via compaction (O(bytes in scope)) | One table/object |

Two levels conflate tenant isolation with bulk encryption — rotating a KEK
either forces a re-encrypt of all data or leaves tenant boundaries fuzzy.
Four+ levels add rotation cost without meaningful separation of concern.

### derivation-vs-random-generation

Two mechanisms produce level-2 and level-3 keys:

- **Deterministic derivation** (HKDF): `DEK = HKDF(domainKEK, salt, info=context)`
  where `context` encodes `(tenant-id, table-id, purpose)`. Reproducible —
  the same context always yields the same DEK. Enables convergent encryption
  (deduplication across identical plaintexts under the same context) and
  removes the need to persist the DEK separately.
- **Random + wrap**: generate a random DEK, wrap with the KEK, store the
  wrapped bytes in a registry. Non-reproducible — the registry is authoritative.

**Recommended for jlsm**: **hybrid**. Derive the *identity* of a DEK from
context (so a lost registry can be reconstructed from the KEK), but wrap and
store the concrete DEK bytes in a registry for fast open (avoids an HKDF pass
on every unwrap). This follows Vault Transit's keyring model.

### hkdf-context-binding-rules

RFC 5869 HKDF has two inputs beyond the master secret: `salt` (extractor
randomizer) and `info` (context binder). The `info` parameter is the key
mechanism for **domain separation** — two derivations with different `info`
produce cryptographically independent keys even from the same master key.

**jlsm context encoding** (recommended, following AWS Encryption SDK pattern):

```
info = len(tenant_id) || tenant_id
    || len(table_id)  || table_id
    || len(purpose)   || purpose      // e.g. "sstable-dek" / "wal-dek" / "index-dek"
    || len(version)   || version      // DEK rotation epoch, big-endian u32
```

Length-prefixing every component prevents **canonicalization attacks** where
`"tenant=a, table=bc"` and `"tenant=ab, table=c"` would otherwise produce the
same `info` blob and hence the same DEK.

### wrapping-primitive-choice

| Primitive | Ciphertext overhead | IV/nonce required | Notes |
|-----------|--------------------:|-------------------|-------|
| **AES-KW (RFC 3394)** | +8 bytes | No | Key must be multiple of 8 bytes; deterministic (same input → same wrap); no RNG dependency |
| **AES-KWP (RFC 5649)** | +8 bytes + padding | No | Handles arbitrary key sizes; deterministic |
| **AES-GCM** | +12 (nonce) + 16 (tag) = +28 bytes | Yes | Requires unique nonce; strong cryptanalysis record; fast with AES-NI |
| **AES-SIV (RFC 5297)** | +16 bytes | Optional (nonce misuse resistant) | Provable key-wrap security; nonce reuse is safe |

**Recommended for jlsm**:

- **Level 1 → 2 (root wraps domain KEK)**: **AES-KW/KWP** — deterministic,
  no RNG dependency, minimal overhead. Fits the KMS `Encrypt`/`Decrypt` API
  surface cleanly (most KMS implementations use AES-GCM internally, which is
  also fine here — the choice is between equivalent options).
- **Level 2 → 3 (domain KEK wraps DEK)**: **AES-GCM** with a random 12-byte
  nonce per wrap. Associated data = the HKDF `info` blob. Binding AD to the
  wrap means a DEK cannot be unwrapped under a different context even if
  an attacker swaps wrapped bytes across tables.
- **Avoid AES-SIV for wrap-only** unless you need nonce-misuse resistance —
  it's stronger than needed and less widely available in JCA providers.

### rotation-invariants

**Rotating the root KEK (level 1)**:
1. New root KEK provisioned in KMS (new version/alias).
2. For each wrapped domain KEK in the registry: `plaintext = unwrap(oldRoot, wrappedDomainKek); newWrapped = wrap(newRoot, plaintext); registry.update(domainId, newWrapped)`.
3. Domain KEK plaintexts are unchanged; DEKs are unchanged; **no data is re-encrypted**.
4. Convergence: instantaneous (registry update is atomic).

**Rotating a domain KEK (level 2)**:
1. New domain KEK generated, wrapped under active root KEK, added to registry as new version.
2. For each DEK in this domain's DEK registry: unwrap under old domain KEK, re-wrap under new domain KEK.
3. DEK plaintexts are unchanged; **no data is re-encrypted**.
4. Old domain KEK may be deleted once no wrapped DEK references it.

**Rotating a DEK (level 3)**:
1. New DEK generated (or derived with incremented version), wrapped under active domain KEK.
2. Existing SSTables retain the old DEK version tag in their header; compaction reads with old DEK and writes with new DEK.
3. Data must be physically re-encrypted — delegated to compaction (see `encryption-key-rotation-patterns.md`).
4. Old DEK retained until no SSTable references it.

**Critical invariant**: **rotating level N never forces re-encryption at level N+1 or below**. Only the immediate-below wrapping is refreshed. This is the whole point of the hierarchy.

### scoping-per-table-vs-per-object

| Granularity | DEK count | Rotation cost | Best for |
|-------------|-----------|---------------|----------|
| Per-table | O(tables) | Rewrite all SSTables in table on DEK rotation | Most workloads; simplest mental model |
| Per-SSTable | O(SSTables) | Natural fit: new SSTable = new DEK; old ones phased out by compaction | High-churn LSM; zero-cost rotation at write time |
| Per-object (per-doc) | O(documents) | Per-record re-encrypt | Strict per-record legal/audit boundaries (e.g., GDPR per-subject erasure via key shred) |

**Recommended for jlsm**: **per-table DEK** as the primary scope, with an
**SSTable-header DEK version tag** to allow seamless transition to a
newly-rotated DEK. Per-SSTable DEKs derivable from `(tableDEK, sstableId)` via
HKDF if cryptographic SSTable-level isolation is later required.

### storage-layout

```
  KMS  (external, e.g. AWS KMS, Vault Transit)
    └── Root KEK vN  (never persisted locally in plaintext)

  On-disk registry (single file, alongside manifest):
    domain-kek-registry.cbor
    ├── [domainId, version, wrappedBytes (under root KEK), createdTs]
    └── ...

    dek-registry.cbor
    ├── [tableId, dekVersion, wrappedBytes (under domainKEK), contextInfo, createdTs]
    └── ...

  SSTable header:
    ├── dekVersion: u32          // points into dek-registry
    ├── nonce prefix (12B)       // for per-block GCM nonce derivation
    └── ...
```

**Atomic update pattern**: both registries are CBOR files written via
`write-temp → fsync → rename`. Never mutate in place. This matches
CockroachDB's `COCKROACHDB_REGISTRY` approach and is compatible with
jlsm's existing SSTable manifest update pattern.

### jvm-key-material-lifecycle

At each level, keys must live in `Arena`-allocated `MemorySegment` (see
`jvm-key-handling-patterns.md`) with explicit zeroization. Specifically:

- **Root KEK**: never unwrapped to JVM memory — KMS performs the unwrap of
  domain KEKs via `Decrypt` API; only the domain KEK plaintext enters the JVM.
- **Domain KEK**: held in an `Arena.ofShared()` segment for the lifetime of
  the domain open; zeroed on close.
- **DEK**: held in a segment for the lifetime of the table open; zeroed on
  close. HKDF-derived sub-DEKs (per-SSTable or per-block) are derived on
  demand into short-lived confined arenas and zeroed immediately after use.

## extended-detail

Reference-design comparison (CockroachDB / AWS Encryption SDK / Tink /
MongoDB CSFLE / Vault Transit / GCP KMS), complexity analysis, full
tradeoffs, edge-case catalog, and a working jlsm code skeleton are in
[three-level-key-hierarchy-detail.md](three-level-key-hierarchy-detail.md).

## sources

1. [RFC 5869 — HKDF](https://datatracker.ietf.org/doc/html/rfc5869) — canonical HKDF spec; `info` parameter for context binding.
2. [RFC 9709 — HKDF in CMS](https://datatracker.ietf.org/doc/rfc9709/) — 2025 IETF standard for HKDF-SHA-256 encryption-key derivation.
3. [RFC 3394 — AES Key Wrap](https://www.rfc-editor.org/rfc/rfc3394) — deterministic AE key-wrap, +8B overhead, no RNG needed.
4. [NIST SP 800-38F — Key Wrapping](https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-38F.pdf) — authoritative comparison of key-wrap modes.
5. [CockroachDB Encryption-at-Rest RFC](https://github.com/cockroachdb/cockroach/blob/master/docs/RFCS/20171220_encryption_at_rest.md) — store-key + data-key + compaction-driven re-encryption.
6. [AWS Encryption SDK — How It Works](https://docs.aws.amazon.com/encryption-sdk/latest/developer-guide/how-it-works.html) — envelope + encryption context + HKDF `info` binding.
7. [Google Cloud KMS — Envelope Encryption](https://docs.cloud.google.com/kms/docs/envelope-encryption) — KEK/DEK pattern with KMS-held KEK.
8. [Google Tink — KmsEnvelopeAead](https://developers.google.com/tink/client-side-encryption) — embedded wrapped DEK in ciphertext.
9. [Vault Transit — Derivation and Rewrap](https://developer.hashicorp.com/vault/docs/secrets/transit) — context-scoped derivation and `rewrap` endpoint.
10. [MongoDB CSFLE — Key Management](https://www.mongodb.com/docs/manual/core/csfle/fundamentals/manage-keys/) — CMK + DEK + `rewrapManyDataKey`.

---
*Researched: 2026-04-20 | Next review: 2026-10-20*
