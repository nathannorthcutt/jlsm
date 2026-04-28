---
{
  "id": "encryption.primitives-lifecycle",
  "version": 14,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [
    "encryption"
  ],
  "requires": [
    "encryption.primitives-variants",
    "encryption.ciphertext-envelope",
    "schema.document-construction"
  ],
  "invalidates": [
    "encryption.primitives-dispatch.R3",
    "encryption.primitives-dispatch.R5"
  ],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "per-field-pre-encryption",
    "per-field-key-binding",
    "encryption-key-rotation",
    "unencrypted-to-encrypted-migration",
    "index-access-pattern-leakage",
    "three-tier-key-hierarchy",
    "dek-scoping-granularity",
    "kms-integration-model",
    "tenant-key-revocation-and-external-rotation"
  ],
  "kb_refs": [
    "systems/security/encryption-key-rotation-patterns",
    "systems/security/jvm-key-handling-patterns",
    "systems/security/client-side-encryption-patterns",
    "systems/security/three-level-key-hierarchy",
    "systems/security/dek-revocation-vs-rotation",
    "systems/security/dek-caching-policies-multi-tenant",
    "algorithms/encryption/index-access-pattern-leakage"
  ],
  "open_obligations": [
    "implement-f41-lifecycle"
  ],
  "_migrated_from": [
    "F41"
  ]
}
---

# encryption.primitives-lifecycle — Encryption Lifecycle

## Requirements

### Per-field pre-encryption signaling
R10. Key derivation must use HKDF-SHA256. The extract step must compute `PRK = HMAC-SHA256(salt, IKM=dekBytes)` where salt defaults to `0x00{32}` (all-zero, 32 bytes) and `dekBytes` is the unwrapped DEK material resolved from the DekHandle. The expand step must compute `OKM = HMAC-SHA256(PRK, info || 0x01)` truncated to 32 bytes.

R10a. The HKDF salt must be configurable via EncryptionKeyHolder's constructor. The default all-zero salt is acceptable for single-deployment scenarios. For multi-tenant or multi-deployment environments, the caller should provide a unique salt (e.g., a deployment identifier hash). When provided, the salt replaces the all-zero default in the extract step. When a caller supplies a non-default salt, its length must be at least 32 bytes (the HashLen of HKDF-SHA256 per RFC 5869 §3.1). A caller-supplied salt shorter than 32 bytes must be rejected at construction with IllegalArgumentException before any registry interaction. The default all-zero 32-byte salt remains acceptable. Rationale: RFC 5869 §3.1 phrases the HashLen guidance as "ideally, the salt value is a random or pseudorandom value with length HashLen" — it is a recommendation, not a mandate. jlsm tightens this to a MUST because the library cannot audit callers' salt sources, and an undersized salt weakens HKDF-Extract below its nominal security level. `[UNVERIFIED: RFC 5869 §3.1 recommends (not mandates) HashLen; the MUST here is a jlsm policy choice above the RFC baseline — source confirmation RFC-5869 is a future research gate]`.

R10b. The HKDF salt must be recorded in the per-tenant sharded key registry (R71) alongside the wrapped DEK entries. On EncryptionKeyHolder construction, if a salt is provided and a registry already exists, the constructor must verify that the provided salt matches the registry's recorded salt. A mismatch must throw IllegalArgumentException identifying the salt mismatch (without revealing salt bytes beyond a hash prefix). This prevents silent key derivation mismatch when salt is misconfigured across instances — particularly dangerous for OPE and DCPE fields where wrong-key decryption produces plausible but incorrect values rather than authentication failures.

R11. The info parameter for HKDF-Expand must bind the derivation to the full four-tuple DEK identity `(tenantId, domainId, tableId, dekVersion)` per `dek-scoping-granularity` ADR. The info bytes must be the UTF-8 encoding of the literal prefix `"jlsm-field-key:"` followed by length-prefixed components: `"jlsm-field-key:" || 4-byte-BE(tenantIdUtf8.length) || tenantIdUtf8 || 4-byte-BE(domainIdUtf8.length) || domainIdUtf8 || 4-byte-BE(tableNameUtf8.length) || tableNameUtf8 || 4-byte-BE(fieldNameUtf8.length) || fieldNameUtf8 || 4-byte-BE(dekVersion)`. Length prefixes are mandatory to prevent canonicalization collisions (`tenant=a,table=bc` vs `tenant=ab,table=c`). All length fields must be non-negative 32-bit big-endian integers.

R12. Calling `deriveFieldKey` twice with the same `(tenantId, domainId, tableName, fieldName, dekVersion)` tuple on the same key holder instance must return MemorySegments whose byte contents are identical. The derivation must be deterministic given the same DEK and info string.

R12a. Two EncryptionKeyHolder instances constructed from identical DEK bytes, identical salt, and identical info construction must produce byte-identical derived field keys for the same `(tenantId, domainId, tableName, fieldName, dekVersion)` tuple. The derivation is purely a function of the DEK, salt, and info components.

R13. Calling `deriveFieldKey` with different `fieldName` values (same other inputs) must produce outputs that differ in at least one byte. Two derived keys for distinct fields must not collide.

R14. Calling `deriveFieldKey` with different `tableName`, `domainId`, or `tenantId` values (same `fieldName` and DEK) must produce outputs that differ in at least one byte. Cross-table, cross-domain, and cross-tenant derivations must not collide.

R15. `deriveFieldKey` must reject null `DekHandle`, null or empty `tableName`, or null or empty `fieldName` with NullPointerException / IllegalArgumentException as appropriate. The DekHandle must carry non-null `tenantId` and `domainId` validated at DekHandle construction (R81).

R16. All intermediate byte arrays created during HKDF computation (PRK, HMAC inputs, HMAC outputs) must be zeroed in a finally block before the method returns. No intermediate key material may survive on the heap after derivation completes. Zeroing must be null-safe — if an array reference is null (because computation did not reach that step), it must be skipped.

R16a. For encryption variants that require keys longer than 256 bits (e.g., AES-SIV requires 512 bits), the key derivation must perform multiple HKDF-Expand steps with incrementing counter bytes (0x01, 0x02, ...) and concatenate the outputs to reach the required key length. Each expand step must use the same PRK and info prefix but a different final counter byte.

R16b. The per-field derived key (from HKDF) serves as the data encryption key for that field. In the envelope encryption model (R17–R21 as amended by R71–R82b), the DEK resolved from the DekHandle is used as the input keying material to HKDF. When the DEK rotates, all per-field derived keys change because the IKM and the `dekVersion` info component both change. Callers provide a KmsClient (R80+) that unwraps the tenant KEK, which unwraps the domain KEK, which unwraps the DEK; the unwrapped DEK is then used as the HKDF master key input.

### Key hierarchy: three-tier envelope

R16c. EncryptionSpec must expose a `requiredKeyBits()` method returning the key length in bits needed for that variant. Deterministic (AES-SIV) must return 512. Opaque (AES-GCM) must return 256. OrderPreserving (Boldyreva OPE) must return 256. DistancePreserving (DCPE/SAP) must return 256. None must return 0. The key derivation routine must call `requiredKeyBits()` to determine how many HKDF-Expand steps to perform. When `requiredKeyBits()` returns 0 (None variant), the key derivation routine must return null and must not invoke HKDF. Callers must check the return value before using the derived key.

R44. The EncryptionSpec sealed interface must expose a `leakageProfile()` method returning a LeakageProfile record. The default implementation must return `LeakageProfile.NONE`.

R44a. The leakage profile documentation must include a migration-window section noting that during the transition from unencrypted to encrypted (R38), the proportion of encrypted vs. unencrypted records in each SSTable reveals the migration timeline. Full convergence (all SSTables compacted) eliminates this leakage. The documentation must also note that JCA SecretKeySpec retains an internal copy of key bytes that cannot be zeroed by the library — this is a known residual risk.

R45. LeakageProfile must be a record with boolean fields: `frequency`, `searchPattern`, `accessPattern`, `volume`, `order`, plus a `LeakageLevel` enum field and a `String` description field. The `LeakageLevel` enum must have values: NONE, L1, L2, L3, L4.

R46. The Deterministic variant must return a LeakageProfile with `frequency=true`, `searchPattern=true`, `accessPattern=true`, `volume=true`, `order=false`, level L4. The description must state that identical plaintexts produce identical ciphertexts, leaking frequency distribution.

R47. The OrderPreserving variant must return a LeakageProfile with `frequency=true`, `searchPattern=true`, `accessPattern=true`, `volume=true`, `order=true`, level L4. The description must note that ciphertext ordering reveals plaintext ordering.

R48. The DistancePreserving variant must return a LeakageProfile with `frequency=false`, `searchPattern=false`, `accessPattern=true`, `volume=true`, `order=false`, and a level appropriate to the approximate distance leakage.

R49. The Opaque variant must return a LeakageProfile with all boolean fields `false` except `volume=true` (ciphertext length leaks plaintext length modulo block size). Level must be L1.

R50. The None variant must return `LeakageProfile.NONE` with all boolean fields `false`, level NONE, and description stating no encryption is applied.

### Power-of-2 response padding

R55. `deriveFieldKey` called on a closed EncryptionKeyHolder must throw IllegalStateException before performing any derivation.

R56. Constructing a key registry with a DEK version of 0 or negative must throw IllegalArgumentException. DEK versions must be positive integers.

R57. Attempting to activate a DEK version that does not exist in the registry for the given scope must throw IllegalStateException identifying the requested version and scope without revealing key material.

R58. Wrapping a DEK with a null or zero-length domain KEK must throw NullPointerException or IllegalArgumentException respectively, before any cryptographic operation begins.

R59. HKDF derivation must reject DEK material shorter than 16 bytes with IllegalArgumentException. Keys shorter than 128 bits do not meet minimum security requirements for HKDF input keying material.

R59a. When the DEK is shorter than 32 bytes but at least 16 bytes, the implementation must log a warning that the effective security level is limited to the DEK's entropy, not the 256-bit derived key length.

R60. When compaction encounters a ciphertext whose version tag is valid but whose decryption fails (authentication tag mismatch), it must throw an IOException wrapping the underlying crypto exception. Corrupt ciphertext must not be silently dropped or re-encrypted.

R60a. The compaction task must not register the output SSTable in the manifest until all records have been successfully re-encrypted and written. The partial output SSTable must never be visible to readers. If compaction encounters a decryption failure (R60), the compaction task must delete the partial output SSTable from the filesystem before propagating the IOException. Since the output was never registered, no manifest update is needed. Input SSTables must not be removed from the manifest. The failed record's SSTable path and byte offset must be included in the IOException message.

R61. Schema version mismatch during migration (SSTable schema version is newer than the reader's schema) must throw IllegalStateException. Forward-incompatible schemas must be rejected explicitly.

### Concurrency

R62. EncryptionKeyHolder must be safe for concurrent use from multiple threads. The `deriveFieldKey` method must not require external synchronization. Multiple threads may derive keys simultaneously, and threads may derive keys for different tenants concurrently without cross-tenant blocking.

R62a. `deriveFieldKey` must be atomic with respect to `close()`. If `close()` is called while `deriveFieldKey` is executing, `deriveFieldKey` must either complete successfully using the original key material or throw IllegalStateException. It must never use partially-zeroed or fully-zeroed key material to produce a derived key.

R63. The per-tenant key registry shards must support concurrent reads from reader threads while a single writer updates each shard. Shard updates must use atomic commit per R20; readers that opened the old shard file before commit must continue to function correctly using the old shard contents.

R64. DEK version lookup during read operations must be wait-free: a single volatile-reference read of an immutable map followed by a hash lookup. The implementation must use copy-on-write semantics — mutations create a new immutable map and publish it via a volatile reference swap. Per-tenant registry reloads (after rotation) may block briefly but must not hold locks that prevent concurrent reads from completing with the previously loaded shard. DEK version lookup timing must be constant-time with respect to whether the version exists — the implementation must perform the same operations regardless of outcome.

R65. The `isFieldPreEncrypted(int)` method on JlsmDocument must be safe to call concurrently from multiple threads. The bitset is a final field set at construction; no synchronization is required.

### Resource lifecycle and key zeroization

R66. EncryptionKeyHolder must implement AutoCloseable. On close, it must zero all off-heap MemorySegments holding tenant KEK handle material (transient), domain KEK material, DEK material, and derived field keys, then release the backing Arena. Close must be idempotent.

R67. When a derived field key MemorySegment is no longer needed (key holder closing, or field removed from schema), the MemorySegment must be filled with zeros before the Arena is closed. Zeroing must use `MemorySegment.fill((byte) 0)`.

R68. Unwrapped domain KEK and DEK material held in memory for decryption must be stored in off-heap MemorySegments allocated from a shared Arena, not in on-heap byte arrays. On-heap copies created temporarily for JCA cipher initialization must be zeroed in a finally block immediately after the cipher is initialized.

R68a. The JCA SecretKeySpec retains an internal copy of key bytes that cannot be zeroed by the library. The implementation must minimize the lifetime of SecretKeySpec instances — construct them immediately before use and null the reference immediately after `Cipher.init()` returns.

R69. When a DEK or domain KEK is pruned from the in-memory registry (R30–R31) or expired from the cache (R91), its off-heap MemorySegment must be zeroed before the segment is released. The zeroing must happen even if an exception occurs during the pruning operation.

R70. The per-tenant key registry shard files on disk must contain only wrapped (encrypted) key material. Unwrapped DEK or domain KEK bytes must never be written to any shard file.

R70a. The per-tenant key registry shard files and any temporary files created during atomic updates must be created with owner-only permissions (0600 on POSIX systems, equivalent on other platforms). The implementation must set permissions before writing any key material to the file.

### Key cache lifecycle

R71. The encryption system must support three tenant encryption flavors per `three-tier-key-hierarchy` ADR:

- **`none`** — no encryption; no `EncryptionKeyHolder` is constructed for tables with no encrypted fields. Fields whose `EncryptionSpec` is the `none` variant pass through unencrypted regardless of flavor.
- **`local`** — `EncryptionKeyHolder` composes `LocalKmsClient`, a jlsm-shipped reference `KmsClient` implementation backed by filesystem key material with OS-enforced owner-only permissions. **Documented insecure for production** — supports rotation mechanics for test rigour but provides no HSM, no audit trail, no hardware-protected keys.
- **`external`** — `EncryptionKeyHolder` composes a third-party `KmsClient` plugin (AWS KMS / GCP KMS / Vault / KMIP / custom). Tenant KEK lives in the tenant's KMS; jlsm never materialises it persistently.

R71a. The flavor selection is per-tenant. A single jlsm deployment may serve tenants with different flavors simultaneously; jlsm's internal code paths branch only on the `KmsClient` implementation behavior, not on the flavor label.

R71b. The `LocalKmsClient` implementation's Javadoc must begin with a `@implNote` block stating "NOT FOR PRODUCTION. This reference implementation supports rotation mechanics for test rigour but provides no HSM, no audit trail, and no hardware-protected keys. Production deployments must use a flavor-3 `KmsClient` backed by a real KMS (AWS KMS, GCP KMS, HashiCorp Vault, KMIP, etc.)." The class must also expose a runtime property `isProductionReady()` returning `false`; production harnesses may check this and refuse to start.

R71b-1. To support test rigour for revocation-class behaviours (R83 read-path semantics, R83b domain-KEK destruction, R83c tenant-tier upgrade, R76a-2 unclassified-error escalation), `LocalKmsClient` must additionally expose test-only revocation-simulation APIs:

- `simulateTenantKekRevocation(KekRef tenantKekRef, RevocationKind kind)` — `kind ∈ {Disabled, Destroyed, AccessDenied}`. After invocation, all subsequent `unwrapKek` calls on the affected `tenantKekRef` throw `KmsPermanentException` with the corresponding error class per R76a-1.
- `simulateDomainKekRevocation(KekRef domainKekRef, RevocationKind kind)` — same semantics scoped to a domain KEK.
- `restoreKek(KekRef ref)` — clears any simulated revocation, returning the KEK to the healthy state. Used by tests covering R77 recovery transitions and R79 polling-detected-usable.
- `simulateUnclassifiedException(KekRef ref, RuntimeException exception)` — injects a one-shot RTE on the next call, used to exercise R76a-2's escalation discipline.

These methods must satisfy **all** of the following gating conditions (per P4-14 — `assert` alone is bypassable in production with `-ea` and via reflection):

1. **Package-private to `jlsm.encryption.local`** — limits compile-time exposure to library-internal callers; defence-in-depth, not a sole gate.
2. **Runtime check (NOT `assert`) that throws `UnsupportedOperationException`** when invoked outside test scope. The check must execute on every invocation; assertions are disabled in production by default and `-ea` is occasionally enabled in production by accident, so `assert` is insufficient.
3. **Test-scope determination via `StackWalker`** — the runtime check uses `StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(...)` to verify the immediate caller is a class within a test classpath (e.g., declared in a test source set, or matching a documented test-package pattern such as `*Test`, `*IT`, `*.test.*`). Reflection-based invocation from a non-test class must fail the check.
4. **Production-build assertion** that the simulation methods are not on the call graph — implementations must include a build-time check (e.g., a static-analysis test asserting no production-package class transitively references the simulation methods).

This aligns with `feedback_exercise_processes_pre_ga`: revocation flows must be exerciseable before users are bound. The simulation surface is `LocalKmsClient`-only; flavor-3 (`external`) plugins simulate revocation by configuring their underlying KMS, not via this API.

R71b-2. Simulation methods (`simulateTenantKekRevocation`, `simulateDomainKekRevocation`, `restoreKek`, `simulateUnclassifiedException`) must be **atomic with respect to in-flight `wrapKek` / `unwrapKek` / `isUsable` calls** on the affected `KekRef`. Implementations must use a `VarHandle` or `AtomicReference` to publish the simulated state so concurrent calls observe the transition cleanly. A simulation method that races with an in-flight unwrap must either (a) complete and have the unwrap observe the new state, or (b) be observed by the unwrap as not-yet-applied — never produce a half-applied state where the unwrap returns inconsistent results. The simulation surface is for test rigour; consistent observable transitions are essential for tests asserting failure-detection latency and state-machine correctness.

### Encrypt-once-at-ingress invariant

R72. Plaintext for encryptable field values must be bounded to the ingress window per `three-tier-key-hierarchy` ADR: from client submission through per-field encryption completion. After the per-field ciphertext is produced, the plaintext Arena must be closed (zeroizing the plaintext). No plaintext of encryptable field values may live in MemTable, WAL, or SSTable storage.

R73. MemTable must hold per-field ciphertext produced at ingress, not plaintext. Queries on encrypted fields must decrypt the per-field ciphertext on read. This diverges from the common LSM pattern where MemTable holds plaintext.

R74. Per-field ciphertext produced at ingress must be **reused unchanged** through WAL → MemTable → SSTable boundaries. The DEK used at ingress (the table's current DEK at ingress time) must be the same DEK whose version tag appears in the resulting SSTable. No decrypt-then-re-encrypt occurs on flush.

R74a. Primary-key fields remain **plaintext** in all storage (MemTable, WAL, SSTable). This preserves sort order and range-scan semantics. Non-primary-key encrypted fields rely on the OPE / DET / DCPE / Opaque variants from `primitives-variants` to support query operations within their documented leakage profile.

R74b. The WAL record envelope must cover only metadata (schema ref, opcode, timestamps) — encrypted field payload bytes in WAL records must be the same per-field ciphertext that flushes to SSTable. No additional encryption layer wraps the already-ciphertext field payload.

### WAL encryption mapping

R9. EncryptionKeyHolder must provide a `deriveFieldKey(DekHandle dek, String tableName, String fieldName)` method that returns a MemorySegment containing a derived key of the length required by the field's EncryptionSpec variant (R16c). The derived key must be copied into a caller-supplied Arena by `deriveFieldKey`. The caller is responsible for zeroing and closing its own Arena. This prevents use-after-zero: close() zeros the internal master key and cached derivation state, but previously-returned derived key segments in caller-owned Arenas remain valid until the caller releases them. If no caller Arena is provided, the method must allocate from an internal shared Arena — in this case, close() must wait for all in-flight `deriveFieldKey` callers to complete (per R62a) before zeroing the internal Arena's segments.

---


## Design Narrative

### Intent

This spec extends F03 (Field-Level In-Memory Encryption) with the full encryption lifecycle: per-field pre-encryption signaling, automatic per-field key derivation, a three-tier envelope key hierarchy with per-tenant KMS isolation, rotation at every tier via cascading lazy rewrap, online encryption migration, leakage mitigation, tenant-driven rekey for external-KMS deployments, and a strong plaintext-bounded-to-ingress posture. Together, these capabilities transform jlsm's encryption from a static encrypt-at-rest feature into a production-grade multi-tenant system that supports rotation without downtime, schema evolution that adds or removes encryption from fields online, and informed caller decisions about leakage tradeoffs.

### v5 — Three-tier hierarchy

The v4 two-tier model (caller KEK → library DEK) is amended in v5 to a three-tier envelope per `three-tier-key-hierarchy` ADR: Tenant KEK → Data-domain KEK → DEK. The third tier is justified by sub-tenant blast-radius isolation — tenants may group tables into domains at different sensitivity levels, and a DEK compromise is bounded to its domain rather than propagating across a tenant's full dataset. Per-tenant KMS isolation is always-on when encryption is enabled; under flavor 3 (BYO-KMS), the Tenant KEK lives in the tenant's own KMS and is never materialised at rest by jlsm.

### Per-field pre-encryption

The previous boolean pre-encryption flag forced all-or-nothing: either every encrypted field was pre-encrypted by the caller, or none were. This is insufficient for mixed scenarios where a client encrypts some fields (e.g., PII handled by a client-side encryption SDK) while the library encrypts others (e.g., searchable encrypted indices). The bitset representation costs zero overhead on the common path (a single `long` comparison against `0L`) and supports schemas up to 64 fields without additional allocation. The 64-field limit is acceptable for the current schema model; if schemas grow beyond 64 fields, the bitset can be extended to `long[]` or `BitSet` under a future revision.

### Per-field key derivation (HKDF hybrid)

HKDF-SHA256 derives per-field keys from the table DEK, with the info string including `(tenantId, domainId, tableName, fieldName, dekVersion)` length-prefixed. This deterministic derivation makes the encrypt-once-at-ingress invariant possible: a field's ciphertext is reproducible across WAL → MemTable → SSTable boundaries because the derived field key depends only on persistent identifiers, not on ephemeral state. Cross-tenant, cross-domain, and cross-table key collisions are prevented by the length-prefixed info structure (blocks canonicalization attacks like `tenant=a,table=bc` vs `tenant=ab,table=c`).

### Envelope encryption with cascading lazy rewrap

Three-tier wrapping (Tenant KEK → Domain KEK → DEK) makes rotation scalable. Rotation at tier N produces a new tier-N reference without synchronously touching tier N+1 — tier N+1 entries rewrap opportunistically on next access. This bounds the work per operation even when tenants have thousands of domains. The grace-period invariant (retention of retired tier references must exceed WAL retention) protects against undecryptable unreplayed WAL.

### Per-tenant sharded registry

The registry is sharded per-tenant. Each shard is self-contained (atomic commit, CRC-32C trailer, owner-only permissions) so one tenant's corruption or operator error does not affect others. Shards are lazy-loaded on first domain open; startup time does not scale with tenant count.

### Wire format version tag

The 4-byte DEK version tag (R22) is scoped to the SSTable's declared `(tenantId, domainId, tableId)` (R23a). Cross-scope reads are rejected at the read boundary (R22b). This keeps the wire tag compact while preserving tenant isolation at the ciphertext level.

### Compaction-driven re-encryption and migration

Compaction is the rewrite engine for both DEK rotation (R25) and encryption-on / encryption-off migration (R38, R39). Schema version tags in SSTable footers disambiguate the encryption state of mixed-schema data during migration windows.

### Tenant encryption flavors

Three flavors (`none`, `local`, `external`) give jlsm a coherent story from development to production. `local` is a shipped reference `KmsClient` backed by filesystem keys for dev/test; `external` is the production posture via BYO-KMS plugin. Flavor selection is per-tenant; a single jlsm deployment may serve tenants in mixed flavors.

### Plaintext bounded to ingress

MemTable holds per-field ciphertext, not plaintext. Plaintext exists only during the ingress window (client submission through per-field encryption completion) and is zeroized immediately after. Primary keys remain plaintext to preserve sort order. Queries on encrypted fields rely on `primitives-variants`' existing leakage profiles. WAL envelope encryption covers metadata only; field payload bytes inside WAL records are the same ciphertext that flushes to SSTable.

### Three-state failure machine and rekey API

Under flavor 3, the tenant's KMS is outside jlsm's trust boundary. The three-state machine (`healthy` / `grace-read-only` / `failed`) is a defensive posture: transient KMS errors ride out the backoff policy; permanent errors (disabled/deleted KEK) enter grace-read-only with a bounded window, then fail. The rekey API is the normative coordination path: tenant operators call it before revoking old KEKs, with a proof-of-control sentinel preventing replay.

### Leakage mitigation

The leakage profile documentation on EncryptionSpec serves two purposes: (1) it gives callers structured data to evaluate encryption schemes against their threat model, and (2) it makes leakage an explicit part of the API contract rather than an implicit assumption. Power-of-2 response padding mitigates volume attacks at modest bandwidth cost (at most 2x result count). Per-field HKDF keys (already mandated by the key derivation requirements) eliminate the cheapest attack vector: cross-field frequency correlation.

### Wire format implications

The 4-byte DEK version tag added to every encrypted field value is a wire format change. Data encrypted under F03's single-key model (no version tag) is not readable by an F41-era reader without a migration step. This migration is handled by the same compaction-driven mechanism: pre-F41 SSTables carry a schema version tag indicating the old encryption format, and compaction re-encrypts them with the versioned ciphertext format.

### What this spec does NOT cover

- **Client-side encryption SDK** — how external callers discover per-field encryption requirements and manage their own keys (handled by `client-side-encryption-sdk`).
- **`KmsClient` SPI precise method signatures and cache/retry defaults** — normatively defined by `kms-integration-model` ADR; this spec cross-references it.
- **Concrete sharded-registry file layout** (per-domain files vs log-structured) — implementation-level; constrained by R19b but not pinned here.
- **`(domainId, dekVersion)` wire-tag byte layout** — the v5 design scopes the wire tag to SSTable footer identity (R23a); a future wire-tag expansion is not required.
- **Forced immediate rotation** — priority compaction scheduling for compliance deadlines.
- **Updatable encryption** — re-encryption without decryption via update tokens (theoretical optimization, not needed for v1).
- **ORAM-based access pattern hiding** — deferred until adaptive ORAM achieves < 5x overhead.
- **Tenant lifecycle / decommission** — deferred (see `tenant-lifecycle` deferred ADR).

### Adversarial falsification history

**Pass 2 — 2026-04-15** (32 findings): structured adversarial review. All promoted. See v4 notes below for detailed list.

**Pass 3 — 2026-04-15** (11 fix-consequence findings): depth review. All promoted.

**Pass 4 — 2026-04-15** (verification): zero critical findings; cross-fix interactions validated.

**Pass 5 — 2026-04-21**: v5 amendments (three-tier structure, per-tenant sharded registry, cascading lazy rewrap, plaintext-bounded-to-ingress, `_wal` domain mapping, three-state failure machine, rekey API, normative `KmsClient` SPI). 3 Critical + 7 High + 6 Medium + 7 Low findings identified. All Critical and High fixes applied in v6 (see v6 amendment notes below).

---

## Verification Notes

### Amended: v13 — 2026-04-27 — WD-03 Pass 4 fix-consequence-of-fix-consequence amendments

Pass 4 (depth pass on the v12 amendments) surfaced 28 fix-consequence-of-fix-consequence findings (5 Critical / 9 High / 8 Medium / 6 Low / 2 Uncertain), all accepted with recommendations on P4-29 (idempotent close all states) and P4-30 (sealed marker JPMS module scope). v13 amendments do not invalidate any v11 / v12 R-numbers; all changes are tightenings or additions to existing R-families.

**Critical fixes (Pass 4):**

- **P4-1 — R83b-1a quiescence-barrier upper bound.** Configurable bound default = cache TTL (R91), 24h ceiling, hard maximum 48h. Beyond bound: emit `quiescenceBarrierTimeout`, forcibly zero, force in-flight readers to fail with `DomainKekRevokedException`. Bounds plaintext residency to ≤48h after upstream destruction.
- **P4-2 — R83b-2 manifest+WAL caching/scoping/coalescing.** Negative-result cache per `(tenantId, domainId)` invalidated by R37c manifest commit-completion hook; WAL scan scoped to segments newer than registry's last commit; concurrent calls coalesce via per-key future; configurable scan timeout (30s) for unbounded backends.
- **P4-3 — R76b-2 in-process state initialised from durable record.** On `EncryptionKeyHolder` construction, the in-process state machine reads the most recent `state-transition` durable record per tenant; state + grace-window timestamp adopted from durable; eventSeq initialised to `lastEmittedEventSeq + 1`. R83a's failure counter remains in-process (intentional separation: state durable, count in-process).
- **P4-4 — R37b-1 TIMED_OUT bound pinned at rotation start.** Implementation records the R37a bound alongside rotation-start timestamp in registry rotation metadata. Subsequent dynamic config changes do not retroactively reclassify in-flight rotations.
- **P4-5 — R83e-1 wrap-roundtrip atomic w.r.t. close().** Same discipline as R62a for `deriveFieldKey`: `close()` waits for in-flight roundtrips to drain, OR roundtrip throws `IllegalStateException` before invoking KMS — never half-applied state where plaintext copy cannot be zeroed. Protects R66's zero-before-release invariant.

**High fixes (Pass 4):**

- **P4-6 + P4-30 — R83-1 sealing discipline.** Plugin authors wrap into existing `KmsPermanentException` / `KekRevokedException`; new `NonRetryable` types require spec amendment AND `jlsm.core` source change. Seal pinned to module `jlsm.core`. Third-party plugins cannot extend the seal.
- **P4-7 — R83i-2 deployer-secret discipline for KekRefDescriptor.** Cluster-wide consistency (mismatch rejected at construction or surfaced as WARN); storage co-located with R79c-1 jitter key; rotation requires deployer-driven correlation table; descriptors must NOT be persisted (observability ephemera).
- **P4-8 + P4-23 — R83b-2a token nonce + canonical-clock validation.** 16-byte random nonce; per-process consumed-token bloom filter; single-canonical-clock validation with 5min ± 30s grace; `forceShredRegistryTokenReplay` event on replay attempts.
- **P4-9 + P4-28 — R83g-1 per-tenant durable revoked-deks set + 10K bound.** Per-tenant durable set survives across rekey-pair boundaries; 10K bound default, exceeding triggers `permanentlySkippedSetOverflow` event + transition to `failed` + reject further DEK creation until `forceShredRegistry` invoked.
- **P4-10 + P4-21 — R76b-1a tenant-state-progress file discipline.** Path per R82b family; 0600 permissions per R70a; CRC-32C trailer per R19a; CRC mismatch initialises tenant to conservative `failed` state.
- **P4-11 — R77 auto-recover-from-failed flag is per-tenant.** Configurable via `flavorConfig.tenant(tenantId).autoRecoverFromFailed(...)`; multi-process must coordinate via shared config; inconsistency detected at construction.
- **P4-12 — R79c-1 entropy + secret-context.** ≥256-bit entropy `deployerInstanceId`; UUIDs combined with secret salt before HMAC keying; co-located with R83i-2 secret.
- **P4-13 — R83h centralised factory.** `R83ExceptionFactory.tenantAndDeployer(...)` is the only sanctioned construction path; static analysis bans direct subclass instantiation; factory handles stack truncation, correlation-id minting, KMS cause sanitisation, and KekRefDescriptor transformation in one place.
- **P4-14 — R71b-1 StackWalker runtime check.** Replaces `assert isTestMode()` (bypassable in production with `-ea`) with mandatory runtime check via `StackWalker` verifying caller is in test scope; throws `UnsupportedOperationException` otherwise; build-time assertion that simulation methods are not on production call graph.

**Medium fixes (Pass 4):**

- **P4-15 — R83b-1a tombstone vs R83g per-DEK distinction.** Tombstone is per-`(tenantId, domainId)` short-circuit; R83g per-DEK entries populated lazily on demand for finer-grained observability.
- **P4-16 — Read-path failure type matrix concurrent-class footnote.** Precedence applies at observation point; concurrent CAS-loser reads see post-transition state, not the original revocation type.
- **P4-17 — R37b-2 (and all KmsObserver events) deployer-only flag + redacted-variant construction.** Mirrors R83h's tenant-vs-deployer split for event payloads.
- **P4-18 — R79e aggregate polling cost surfaced.** Implementations expose `pollsScheduledPerSec`, `pollsDeferredPerSec` metrics so deployers can size KMS quotas accurately.
- **P4-19 — ConvergenceState monotonicity.** Once REVOKED, terminal — consumers must abort any prior CONVERGED-based work.
- **P4-20** — withdrawn (Pass 4 prompt transcription error).
- **P4-22 — R83e-1 wrap-roundtrip outside R34a shard lock.** Lock acquired only for the persistence step; defends R34a's concurrency model from being blocked by KMS round-trips.

**Low fixes (Pass 4):**

- **P4-23** — single canonical clock for `RegistryShredConfirmation` validation (covered by P4-8 above).
- **P4-24** — `eventSeq` cross-category ordering note: monotonic within `(tenantId, eventCategory)` only; cross-category ordering uses wall-clock `timestamp`.
- **P4-25** — R83c probe-cache TTL vs detection-epoch orthogonality note: cache TTL governs read freshness, epoch governs probe coalescence.
- **P4-26 — R71b-2 simulation atomicity.** Simulation methods atomic w.r.t. in-flight wrap/unwrap/isUsable via `VarHandle` or `AtomicReference`.
- **P4-27** — R83b-2 manifest-rebuild guard: auto-provisioning forbidden during rebuild window; throws `RegistryStateException(JLSM-ENC-83B2-MANIFEST-REBUILD)`.
- **P4-28** — covered with P4-9 above.

**Recommendations on uncertain findings (Pass 4):**

- **P4-29 — R37b-3 idempotent close all states.** Idempotent silent-no-op applies to all post-registration-end paths: explicit close, holder-close, convergence-fired-and-delivered, GC-reaped, any combination. Pure no-op once registration is non-pending.
- **P4-30 — R83-1 sealed marker JPMS module scope.** Pinned to `jlsm.core`; permitted subclasses live in `jlsm.core`; third-party modules wrap into existing types. Documented above in R83-1's tightening.

**Verification impact:**

- **No v11 / v12 R-number is invalidated.** All v13 changes extend or tighten existing R-families: R37b-1 monotonicity + bound-pinning, R37b-3 all-states, R76b-1 cross-category note, R76b-1a (new) file discipline, R76b-2 (new) durable-init, R77 per-tenant flag scope, R79c-1 entropy/secret-context, R79e (new) aggregate cost, R71b-1 StackWalker, R71b-2 (new) simulation atomicity, R83-1 sealing/JPMS, R83b-1a quiescence bound + R83g granularity, R83b-2 caching/scoping/manifest-rebuild guard, R83b-2a nonce/canonical-clock, R83c-2 epoch-orthogonality note, R83e-1 close atomicity + outside-shard-lock, R83g-1 durable revoked-deks set + 10K bound, R83h centralised factory, R83i-2 (new) secret discipline, R37b-2 deployer-only event flag, matrix concurrent-class footnote.
- **No `@spec` annotation is broken** — every R-number from prior versions retains its identity and obligation; new clauses tighten without redirecting.
- **Three additional public-API surfaces:** `RetryDisposition.NonRetryable` JPMS scope clarified (`jlsm.core` only); `R83ExceptionFactory.tenantAndDeployer(...)` mandated as sole construction surface; `forceShredRegistry` token discipline tightened.
- **One additional persistent file family:** `tenant-state-progress` per R76b-1a (path/permissions/CRC pinned).

**Post-fix disposition:** State remains DRAFT pending Pass 6 confirmation of v9's amendments (R10a salt floor, R75 `_wal` reservation, R80a Purpose codes, R91 24h cache TTL). Per user directive, Pass 5 is not run after v13 — the spec ships at v13 DRAFT for WD-03 implementation. Implementation tracking: obligation `implement-f41-lifecycle` now encompasses the v13 surface for the WD-03 sections.

### Amended: v12 — 2026-04-27 — WD-03 Pass 3 fix-consequence amendments

Pass 3 (depth pass on the v11 amendments) surfaced 28 fix-consequence findings (5 Critical / 8 High / 7 Medium / 5 Low / 3 Uncertain), all accepted with recommendations on D-26 (checked-exception discipline), D-27 (tri-state ConvergenceState), and D-28 (LocalKmsClient revocation simulation). These changes amend v11's surface in place; no v11 R-number is invalidated.

**Critical fixes (Pass 3):**

- **D-1 — `RetryDisposition.NonRetryable` sealed marker interface (R83-1).** Added a sealed marker shared by `KmsPermanentException` (and its `KekRevokedException` subtree), `RegistryStateException`, `DekNotFoundException`, and `TenantKekUnavailableException` so operator alarm code can `catch (RetryDisposition.NonRetryable)` uniformly across the four read-path failure families even though their declared parents differ. The Read-path failure type matrix gains a Parent-type column for callers that need finer discrimination.
- **D-2 — R83b-2 manifest+WAL consultation (replaces auto-provision check).** R83b-2's auto-provisioning check now requires (a) registry-empty for `(tenant, domain)`, (b) no live SSTable footer references the scope, AND (c) no unreplayed WAL segment references DEKs in the domain. Closes the silent-crypto-shredding-via-destroyed-registry attack where the destroyed registry was itself the witness used to satisfy the prior single-clause check.
- **D-3 — R83c-2 eager-probe rate-limit coordination.** Eager Tenant-KEK probes per R83c share R79c's per-instance aggregate rate limit and coalesce per tenant per detection epoch. Originating reads use the most recent cached classification when probes are deferred. Prevents reactive-probe DDoS amplification during multi-tenant outages.
- **D-4 — R78g-2 continuation-token discriminator.** Distinguishes "start new rekey" (`continuationToken=null`) from "resume in-flight rekey" (`continuationToken=non-null`). Resume succeeds only if the progress file's `(oldKekRef, newKekRef, rekeyEpoch)` matches the token; resume emits `rekeyResumed` (not `rekeyStarted`). Closes the false-positive that previously made crashed rekeys unresumable.
- **D-5 — R83b-1a epoch-based reclamation for cache eager-invalidation.** Cache invalidation inserts a tombstone first; zeroisation per R69 is deferred until a quiescence barrier confirms no in-flight reader still holds the segment. In-flight readers check the tombstone post-decrypt and throw `DomainKekRevokedException` instead of returning data. Eliminates the zeroed-key-derived-plaintext-garbage race.

**High fixes (Pass 3):**

- **D-6 — R76b-1 per-category eventSeq + seqDurability.** All `KmsObserver` events carry `(tenantId, eventCategory)`-scoped `eventSeq` plus a `seqDurability ∈ {durable, in-process}` field. `state-transition` and `rekey` are durable (persisted across restart); `polling` and `unclassified-error` are in-process. Lets consumers apply the right dedup strategy.
- **D-7 — R37b-2 `convergenceRegistrationDropped` event.** Emitted via `KmsObserver` (polling category) when a weakly-held registration is GC-reaped before convergence; lets operators detect silently-abandoned index rebuilds.
- **D-8 — R83g-1 resumed-operations discipline.** Resumed rekey aborts on `oldKekRef`-self-revocation; non-source domain-KEK revocations skip the affected DEK and record in the progress file's `permanently-skipped` set so subsequent retries don't repeat the wasted KMS call. Emits `rekeyPermanentlySkipped` event after resume completes.
- **D-9 — R83i-1 `KekRefDescriptor` opaque transform.** Library-emitted observability and exception payloads use a 16-byte HMAC-SHA256 hash prefix (keyed by deployer secret) + provider class + region tag instead of raw `KekRef.toString()`. Caller code retains its existing `KekRef.toString()` access — no binary-compat break.
- **D-10 — R76/R77 reconciliation.** Resolved the internal contradiction: `grace-read-only → healthy` is automatic via polling-detected-usable; `failed → healthy` is automatic only when `auto-recover-from-failed = true` (default `false`), otherwise rekey is required. Default-on polling drives `grace-read-only` recovery without auto-resolving the more sensitive `failed` state.
- **D-11 — R76a-2 unclassified-error escalation + rate limit.** After 100 `kmsUnclassifiedError` events in 60s without intervening success, escalate to permanent-class for that tenant. Rate-limit log emission to 1/sec/tenant. Bounds the silent-retry duration and protects the deployer's log pipeline.
- **D-12 — R83e-1 wrap-direction symmetry.** Every `wrapKek` call must be followed by an immediate `unwrapKek` byte-equality verify before persisting `wrappedBytes` to the registry. One extra KMS call per DEK creation; defends against malicious/buggy plugins corrupting the registry with garbage `wrappedBytes` that would later lock the tenant in `failed` permanently.
- **D-13 — R83h split exception instances.** Two distinct exception instances at the failure boundary: tenant-visible (no cause chain, `setStackTrace` truncated, opaque correlation id) and deployer-internal (full scope, full cause chain). Defends against reflective serializers (Jackson, Logback `ThrowableProxyConverter`) that walk all exception fields beyond `getMessage()`.

**Medium fixes (Pass 3):**

- **D-14 — R83b-1b flavor-2 filesystem polling.** `LocalKmsClient` polls `mtime` of the Tenant KEK file at the same default cadence; absence/permission-denied triggers R83b-1's eager-invalidation path. Flavor-1 (`none`) has no KEK and therefore no revocation path (intentional).
- **D-15 — R37b-3 idempotent handle close.** After holder close drops registrations, subsequent `handle.close()` calls are no-ops. No coordination required; per-handle volatile boolean guards re-entry.
- **D-16 — R83a-1 saturation observability event.** When the saturating counter reaches `Long.MAX_VALUE`, emit `revocationCounterSaturated` once per tenant per process lifetime so operators detect the rare counter-precision-lost state.
- **D-17 — R83c probe-cache TTL `min(cadence/4, 5min)`.** Caps probe-result staleness independently of cadence; long-cadence deployments (compliance regimes mandating multi-hour polling) don't lose Tenant-KEK-revocation detection latency.
- **D-18 + D-27 — R37b-1 `ConvergenceState` sealed enum.** `convergenceStateFor(scope, oldDekVersion)` returns `{PENDING, CONVERGED, TIMED_OUT, REVOKED}`; derivable from durable manifest + rotation-start state. Boolean `isConvergedFor` retained as deprecated alias for one minor version.
- **D-19 — R78g eventSeq fix.** `rekeyResumed` resumes from `lastEmittedEventSeq + 1`, NOT 0. Resolves the contradiction in v11 where "starting at 0 on rekeyResumed" conflicted with "gap-free post-restart emission".
- **D-20 — R79c-1 deterministic jitter.** Jitter offset = `HMAC-SHA256(deployerInstanceId, tenantId)[0..3] mod cadence`; stable across `EncryptionKeyHolder` recreations, prevents Spring-`@RefreshScope`-driven re-roll alignment opportunities.

**Low fixes (Pass 3):**

- **D-21 — R83c-1 atomic CAS on state transition.** `VarHandle`/`AtomicReference` semantics for the state field; one transitioning read throws `TenantKekRevokedException`, all concurrent observers see the post-transition state and throw `TenantKekUnavailableException`.
- **D-22 — R78g partial-shard-commit clarification.** Partial commits are not "completed" for event-suppression purposes; the next attempt's shard-commit event uses a fresh gap-free `eventSeq`.
- **D-23 — R79d cost-of-polling note.** `tenantsCount * 96` KMS calls per day at default cadence; deployers must size quotas accordingly. Default-on is a security-over-cost tradeoff.
- **D-24 — R37b polling cadence default `min(60s, manifest-cycle/10)`.** Implementations that subscribe to R37c's hook must not poll. Eliminates double-cost when both surfaces are wired.
- **D-25 — R83b-2a stable error code + `forceShredRegistry` recovery API.** Operator runbook lookup via stable code (`JLSM-ENC-83B2-REGISTRY-DESTROYED`); sanctioned recovery surface with a deployer-confirmation-token-gated destruction path.

**Recommendations on uncertain findings (Pass 3):**

- **D-26 — `KekRevokedException` checked.** Pinned to extend `IOException` via `KmsPermanentException`. Aligns with `RegistryStateException`, `DekNotFoundException`, and the existing `IOException`-declaring read API surface.
- **D-27 — `convergenceStateFor` sealed enum.** Adopted (see D-18 above). Boolean alias retained one minor version for migration.
- **D-28 — `LocalKmsClient` revocation simulation.** Adopted (R71b-1). Test-only API gated by an `assert isTestMode()` runtime check; package-private to `jlsm.encryption.local`.

**Verification impact:**

- **No v11 R-number is invalidated.** All v12 changes are tightenings or additions to existing R-families (R37b → R37b-1/2/3; R76b → R76b-1; R76a-1 → R76a-2; R77 reconciled in place; R78g eventSeq tightened; R78g-2 split into start/resume; R79c → R79c-1; R83 → R83-1; R83a → R83a-1; R83b-1 → R83b-1a/b; R83b-2 → R83b-2a; R83c → R83c-2; R83c-1 → atomic-CAS clause; R83e → R83e-1; R83g → R83g-1; R83h replaced with split-instance text; R83i → R83i-1; R71b → R71b-1).
- **No `@spec` annotation is broken** — every R-number from prior versions retains its identity and obligation; the new clauses tighten without redirecting.
- **Three new public types added to `jlsm.encryption`:** `RetryDisposition.NonRetryable` (sealed marker, R83-1), `KekRefDescriptor` (R83i-1), `ConvergenceState` (sealed enum, R37b-1).
- **Three new test-mode methods on `LocalKmsClient`:** `simulateTenantKekRevocation`, `simulateDomainKekRevocation`, `restoreKek`, `simulateUnclassifiedException` (R71b-1).
- **One new operator-only API:** `forceShredRegistry(TenantId, DomainId, RegistryShredConfirmation)` (R83b-2a).

**Post-fix disposition:** State remains DRAFT pending Pass 6 confirmation of v9's amendments (R10a salt floor, R75 `_wal` reservation, R80a Purpose codes, R91 24h cache TTL) and pending Pass 4 if user elects further depth (5 Critical + 8 High suggests Pass 4 may surface more fix-consequence findings, though with diminishing returns past Pass 3 per the spec-author skill's empirical guidance).

### Amended: v11 — 2026-04-27 — WD-03 spec advances (DEK revocation read-path + convergence detection + rekey observability + polling discipline)

WD-03 of `implement-encryption-lifecycle` (DEK lifecycle + KEK rotation) advances the spec via direct annotation of the existing R29–R37, R76–R76c, R78–R78f, R79–R79b, R80–R80b, plus the gap closures surfaced during `/feature-domains` analysis and exhausted via Pass 2 adversarial falsification (34 findings: 7 Critical / 8 High / 9 Medium / 7 Low / 3 Uncertain — all accepted with the recommendations on F-32 / F-33 / F-34).

**Changes:**

- **R37a, R37b, R37b-1, R37c added (new):** convergence detection bounded delay (configurable in `[100ms, 24h]`, default = manifest-update cycle + 5min slack, observable timeout signals); registration via `AutoCloseable` handle held by `WeakReference`; in-process scope (no replay, restart loses registrations); synchronous `isConvergedFor` query for late attachers; manifest commit-completion hook as the sanctioned encryption-side coupling point.
- **R76a-1 added (new):** normative provider-state-to-class mapping table (AWS / GCP / Vault) covering `KMSInvalidStateException`, `FAILED_PRECONDITION`, `IncorrectKeyException`, throttling, unknown plugin errors. Closes F-7 plugin classification ambiguity.
- **R83 — R83i added (new section "DEK revocation read-path"):** explicit loud-fail semantics for permanent KMS revocation outcomes. Pinned exception hierarchy `KekRevokedException extends KmsPermanentException` with subclasses `TenantKekRevokedException` and `DomainKekRevokedException`. Counter dedup per detection epoch (not per affected DEK fan-out). Domain-KEK destruction observation latency bounded by cache TTL + polling cadence. Eager Tenant-KEK probe to upgrade classification on first domain-tier permanent failure. Convergence vs revocation suppression rule. KMS plugin plaintext validation at SPI boundary. Pool/arena cleanup on revocation throw. Cached-revocation outcome until rekey. Cause-chain sanitisation. Tenant-visible vs deployer-internal scope tuple discipline. Read-path failure type matrix with precedence (`TenantKekUnavailableException` > `KekRevokedException` > `RegistryStateException` > `DekNotFoundException`).
- **R83b-2 added (new):** registry-side destruction (`WrappedDomainKek` entry missing while DEKs reference it) throws `RegistryStateException` rather than silently auto-provisioning a fresh domain KEK. Closes F-20 silent crypto-shredding via registry mishandling.
- **R83c-1 added (new):** sequential precedence — transitioning read throws `TenantKekRevokedException`; subsequent reads while tenant is `failed` throw `TenantKekUnavailableException`. Closes F-33 arbitration.
- **R78g, R78g-1, R78g-2, R78g-3 added (new):** rekey progress observability via `KmsObserver` (mandatory) — events `rekeyStarted` / `rekeyResumed` / `rekeyShardCommitted` / `rekeyWitnessProgress` / `rekeyCompleted` with monotonic `rekeyEpoch` + gap-free `eventSeq`; "rekey resume" distinct from "rekey start" on crash recovery; one-rekey-at-a-time enforcement; opaque `KekRef.toString()` discipline.
- **R79c added (new):** polling cadence bounds — per-tenant minimum 30s default with 10s hard floor (no escape hatches); per-tenant phase jitter; per-instance aggregate rate limit (default 100 polls/sec) with deferral semantics.
- **R79d added (new):** opt-in polling defaulted to ON for flavor-3 tenants (per F-32 arbitration); opt-out emits WARN log with the cache-TTL-only revocation-latency rationale.
- **kb_refs extended:** `systems/security/dek-revocation-vs-rotation` and `systems/security/dek-caching-policies-multi-tenant` added — both KB entries are AUTHORITATIVE for R83 and R83b-1 respectively.

**Verification impact:**

- **No existing requirement is invalidated.** All additions extend existing R-families (R37 → R37a/b/c; R76a → R76a-1; R78 → R78g; R79 → R79c/d) or open a new section (R83 series). Prior text and `@spec` annotations remain valid.
- **No breaking change to public exception hierarchy** beyond the additions. Existing `TenantKekUnavailableException` retains its semantics (R76 state-machine signal) and is now joined by `KekRevokedException` (R83 KMS-layer fault) and `RegistryStateException` (R83b-2 registry inconsistency); precedence is documented in the Read-path failure type matrix.
- **Three normative cross-references added:** R83 → `dek-revocation-vs-rotation` KB; R83b-1 → `dek-caching-policies-multi-tenant` KB; R76a-1 → AWS/GCP/Vault SDK error semantics (annotated `[UNVERIFIED]` for plugin-author future verification).
- **Two existing implementation surfaces extended (no new modules):**
  - `EncryptionKeyHolder` gains `isConvergedFor(scope, oldDekVersion)` and `registerConvergence(...)` methods (per R37b/R37b-1).
  - The manifest gains a narrowly-scoped post-commit hook surface (R37c) that the encryption-layer convergence detector subscribes to.

**Post-fix disposition:**

- State remains DRAFT pending Pass 3 (depth pass on v11 fix-consequence findings) and Pass 6 confirmation of the v9 amendments (R10a salt floor, R75 `_wal` reservation, R80a Purpose codes, R91 24h cache TTL).
- Implementation tracking: obligation `implement-f41-lifecycle` now encompasses the v11 surface for the WD-03 sections.
- Pass 3 prediction: ≥10 fix-consequence findings expected from the F-1 epoch-dedup vs F-22 crash-recovery interaction, the F-3 exception-hierarchy vs F-17 precedence-matrix alignment, the F-6 cache-bounded-revocation vs R79b "polling is opt-in" framing flip in R79d, and the F-2 plaintext-validation vs wrap-direction symmetry.

### Amended: v10 — 2026-04-25 — audit reconciliation (R22c added: SSTable read path threads ReadContext)

Audit reconciliation work (audit run `implement-encryption-lifecycle--wd-02/audit/run-001`, findings F-R1.contract_boundaries.3.1 and F-R1.contract_boundaries.3.5) surfaced a structural gap in the SSTable read path: the reader held a populated `ReadContext` but never threaded it into the deserializer, so the `sstable.footer-encryption-scope` R3e dispatch gate was effectively bypassed at the read boundary even though all the necessary state existed. The audit captured this as "spec silent on the plumbing path" — R3e mandates the membership check before any DEK touch, but no requirement specified how the reader delivers its `ReadContext` to the deserializer.

**Changes:**

- **R22c added (new):** the serializer interface invoked from the SSTable read path must accept the reader's `ReadContext` as a parameter on `deserialize`; the SSTable reader's typed-get path must thread its own `ReadContext` to the deserialize call so that the R3e gate is invoked structurally on every read. Serializer implementations that do not require the gate must accept and ignore the parameter (no behavior change for non-encrypted schemas).

**Verification impact:**

- No existing requirement is invalidated. R22c is additive to R22, R22a, R22b — naming the threading contract that R3e of `sstable.footer-encryption-scope` and R22b of this spec rely on but never specified.
- No existing `@spec` annotation is broken. R22 / R22a / R22b retain their identities; R22c sits adjacent in the same numbering family.
- Implementation impact: `Serializer` (or equivalent) interface signatures gain the `ReadContext` parameter on `deserialize`. `TrieSSTableReader` typed-get sites are updated to pass the per-read `ReadContext`. Non-encrypted deserializers gain a one-line ignore of the parameter.

**Post-fix disposition:** State remains DRAFT (continues v9's promotion pending). R22c is a tightening (gap closure), not a scope change; the WD-02 audit reconciliation surface is the first verification venue.

### Amended: v9 — 2026-04-23 — audit-driven Pass 6 amendments (promoted APPROVED → DRAFT)

Audit reconciliation from `implement-encryption-lifecycle--wd-01` run-001 surfaced four contract invariants that were present only in source or convention, not in the spec. This amendment promotes each to an enforceable spec invariant. State demoted APPROVED → DRAFT pending adversarial Pass 6 confirmation; the four amendments are tightening (spec-gap closures per audit reconciliation), not scope changes.

**Changes:**

- **R10a extended** — Minimum HKDF salt length when caller-supplied: at least 32 bytes (HashLen of HKDF-SHA256 per RFC 5869 §3.1). jlsm tightens the RFC's "ideally HashLen" recommendation to a MUST because the library cannot audit caller salt sources. Default all-zero 32-byte salt remains acceptable. `[UNVERIFIED]` annotation retained — the tightening is a jlsm policy above the RFC baseline. Closes finding `F-R1.contract_boundaries.1.13`.
- **R75 extended** — `_wal` DomainId reservation promoted from javadoc convention to runtime invariant: the public `DomainId` constructor must reject `_wal` with IllegalArgumentException; the synthetic `_wal` domain is constructed only via a sanctioned `DomainId.forWal()` factory (package-private or stack-gated). Prevents registry-collision shadowing attacks. Closes finding `F-R1.contract_boundaries.5.1`.
- **R80a extended** — `Purpose` integer codes pinned as a persistence-format contract: `domain_kek=1, dek=2, rekey_sentinel=3, health_check=4`. These values MUST NOT change; reordering or inserting values with lower codes breaks every previously-wrapped DEK. AAD encoding per R80 must bind the stable `code()`, never the ordinal. Closes finding `F-R1.contract_boundaries.4.1`.
- **R91 extended** — Cache TTL upper bound set to ≤24 hours, finite, implementation-enforced. Prevents `Instant.plus` arithmetic overflow and guarantees R69 / R91 zeroisation within a bounded window regardless of deployer configuration. `[UNVERIFIED]` annotation on the 24h value — a future research step should align with AWS Encryption SDK CMM and Google Tink KeysetHandle defaults before the ADR pins a normative bound. Closes finding `F-R1.contract_boundaries.1.14`; normative bound cross-referenced to `kms-integration-model` ADR.

**Verification impact:**

- No existing requirement is invalidated. All four changes are additive to existing R-numbers; prior text preserved verbatim.
- No existing `@spec` annotation is broken. R10a / R75 / R80a / R91 identities remain stable.
- Two `[UNVERIFIED]` annotations (R10a RFC-5869 characterization, R91 24h bound) are future research gates — neither blocks implementation but both should be resolved before a future APPROVED promotion that pins the bounds normatively in the ADR.
- Downstream specs currently referencing `encryption.primitives-lifecycle` in their `requires` are unaffected at the R-identity level; the new clauses tighten existing obligations rather than introduce new cross-spec contracts.

**Post-fix disposition:** State demoted APPROVED → DRAFT; re-promotion to APPROVED requires adversarial Pass 6 on the four amended clauses. Implementation tracking: obligation `implement-f41-lifecycle` now encompasses the v9 surface. WD-01 audit remediation is complete on the code side; this spec amendment captures the contract invariants the fixes enforce.

### Amended: v8 — 2026-04-23 — R22/R22a extracted to encryption.ciphertext-envelope

Structural refactor to publish the per-field ciphertext wire format as a narrowly-scoped interface-contract spec that downstream domains (`sstable`, `wal`, `serialization`, `query`) can import without pulling in the broader lifecycle, rotation, and registry-sharding surface of `encryption.primitives-lifecycle`. Extraction satisfies WD-02's "Interface-contract spec published for cross-domain consumption" acceptance criterion within the `implement-encryption-lifecycle` work group.

**Changes:**

- **New spec created** — `encryption.ciphertext-envelope` v1 (APPROVED), with frontmatter `_extracted_from: "encryption.primitives-lifecycle (R22, R22a)"`. Contains R1 (per-variant byte layouts + cross-tier uniformity invariant R1a + integer-encoding invariant R1c + byte-count consistency invariant R1b), R2/R2a/R2b (version tag validity, constant-time lookup path, version-not-found semantics), R3/R3a (consumer interface and pre-encrypted layout conformance), R4 (explicit scope boundary listing out-of-scope concerns).
- **R22 rewritten as normative pointer** to `encryption.ciphertext-envelope.R1` and R1a. Preserves F41.R22 identity; existing `@spec F41.R22` annotations (14+ sites across `FieldEncryptionDispatch`, `CiphertextValidator`, `DcpeSapEncryptor`, `DcpeSapEncryptorTest`) remain valid. Behavioural contract is unchanged — the annotation target still asserts the same wire-format requirement, now via a one-hop normative reference.
- **R22a rewritten as normative pointer** to `encryption.ciphertext-envelope.R2`, R2a, R2b. Same preservation argument as R22.
- **Frontmatter `requires` extended** to include `encryption.ciphertext-envelope`. primitives-lifecycle now depends on the extracted wire-format spec at the manifest level.
- **R23, R23a, R24, R25–R27 retained** in primitives-lifecycle. Reader-side scope validation (R22b), SSTable footer metadata (R23a), and compaction-driven re-encryption semantics (R25–R27) are lifecycle concerns, not wire format, and remain canonically here.

**Verification impact:**

- No existing requirement is invalidated. R22 and R22a continue to assert the same contract, now by normative reference.
- No existing `@spec` annotation is broken. The F41.R22 identity still exists and still carries the wire-format obligation.
- Consumers of `encryption.ciphertext-envelope` (future `sstable.*` or `wal.*` spec amendments) can depend on the narrow wire-format contract without importing the full lifecycle surface.

**Post-fix disposition:** State remains APPROVED. Implementation tracking: obligation `implement-f41-lifecycle` now spans primitives-lifecycle v8 + ciphertext-envelope v1 jointly. WD-02 of the work group can cite `encryption.ciphertext-envelope` as the "Interface-contract spec published" deliverable.

### Amended: v7 — 2026-04-23 — dangling reference cleanup (R91 defined, R89 citation fix)

Additive cleanup of broken cross-references surfaced during WD-05 (runtime concerns) pre-implementation audit. No existing requirement is invalidated; no implementation `@spec` annotation is affected.

**Changes:**

- **R91 added (new)** under a new subsection "### Key cache lifecycle" placed between "Resource lifecycle and key zeroization" (R66–R70a) and "Tenant encryption flavors" (R71+). R91 formalises the in-memory cache TTL/eviction semantics that R69 ("pruned from the in-memory registry or expired from the cache (R91)") and R76 ("cached domain KEKs for their remaining TTL (R91)") already reference as an existing requirement. Concrete defaults (TTL duration, LRU policy) remain normatively owned by `kms-integration-model` ADR per the reference-don't-duplicate pattern established in R80.
- **R91a added (new)** — per-tenant eviction scoping, making the isolation invariant from `three-tier-key-hierarchy` ADR explicit at the cache layer.
- **R91b added (new)** — cache-key tuple shape, preventing wrong-tuple cache hits that could return a DEK wrapped for a different `(tenant, domain, table, version)` scope.
- **R91c added (new)** — cache availability during `grace-read-only` state, clarifying that cached entries remain usable while new KMS unwraps are blocked.
- **R76 text corrected** — "(per `kms-integration-model` R89)" → "(per `kms-integration-model` ADR)". ADRs do not use requirement numbering; the original citation was a broken reference to a non-existent R89 in the ADR.
- **R76a text corrected** — same fix: "the backoff policy of `kms-integration-model` R89" → "the backoff policy of `kms-integration-model` ADR".

**Rationale:** The v5 amendment introduced R69 and R76 with forward references to "R91", but R91 was never drafted in v5 or v6. The v6 Low-priority cleanup addressed similar stale-reference issues (L1: "R85" → R78b; L2: "R71–R85" → R71–R82b) but missed R91/R89. This amendment completes that cleanup.

**Post-fix disposition:** State remains APPROVED. Implementation tracking: obligation `implement-f41-lifecycle` now encompasses R91, R91a–c. Implementation work on WD-03 (DEK lifecycle + rotation) and WD-05 (runtime concerns) of the `implement-encryption-lifecycle` work group proceeds against the v7 surface.

### Amended: v6 — 2026-04-21 — Pass 5 adversarial findings addressed (promoted DRAFT → APPROVED)

Pass 5 adversarial review of the v5 amendment surface identified 3 Critical, 7 High, 6 Medium, and 7 Low findings. The 3 Critical and 7 High findings are addressed in v6; plus cleanup of Low-priority stale references (L1, L2, L7). Medium findings are tracked for a future v6.1 amendment but are not blocking for APPROVED.

**Critical fixes:**

- **C1 — Grace-period invariant enforcement (R75b).** R75c added: the invariant is enforced via the on-disk liveness witness of R78e. The per-tenant counter of SSTables + WAL segments depending on `oldKekRef` includes `_wal` domain WAL segments, so rekey completion (and tenant-side KEK deletion eligibility) is mechanically gated on WAL retention.
- **C2 — Rekey completion witnessing (R78e).** R78e rewritten: rekey completes only when BOTH registry shards are fully migrated AND the on-disk liveness witness counter reaches zero. The `rekey` API returns a "awaiting on-disk witness" continuation token until the counter drains, preventing premature "complete" signalling to tenant operators.
- **C3 — `purpose` reserved-values list (R80a).** R80a rewritten with a closed enum: `domain_kek` / `dek` / `rekey_sentinel` / `health_check`. jlsm rejects any value outside this set. Third-party `KmsClient` implementations are prohibited from adding values; extension requires a spec amendment. R80a-1 added: `tableId` and `dekVersion` are required in the encryption context for `purpose=dek` specifically, preventing cross-table DEK-blob swap within the same `(tenant, domain)`.

**High fixes:**

- **H1 — Lock starvation under cascading rewrap.** R32c added: streaming rekey per R32a / R78b must release the exclusive shard lock between batches; each batch capped by a configurable max-hold-time budget (default 250 ms). Prevents rotation from starving DEK creation unboundedly.
- **H2 — R19b vs R82b layout consistency.** R82b rewritten: tenant-level file discovery by `tenantId` is mandatory for both per-domain-file and log-structured layouts. Per-domain addressability within a tenant is layout-dependent (filesystem path for per-domain-file; internal index for log-structured). R19b's layout optionality is constrained by this invariant.
- **H3 — "Reader's expected scope" source of truth.** R22b rewritten: expected scope is materialised from the caller's `Table` handle obtained via catalog lookup; tautological derivation from the same SSTable footer is explicitly forbidden.
- **H4 — Proof-of-control unwrap ambiguity.** R78a rewritten: jlsm must independently invoke `KmsClient.unwrapKek` for BOTH old and new sentinels and compare nonces byte-for-byte. The "or verify the provided re-wrap" alternative is deleted.
- **H5 — New DEK during domain rotation.** R32b-1 added: rotation exclusive lock is scoped to the specific `(tenantId, domainId)` being rotated; concurrent DEK creation within the rotating domain is rejected or queued; other domains and tenants are not blocked.
- **H6 — `rekey-progress` file permissions.** R78c-1 added: 0600 permissions mandated, matching R70a for registry shards.
- **H7 — Atomic "rekey complete" marker.** R78f added: a `rekey-complete` marker is written atomically to the tenant's shard recording `{completedKekRef, timestamp}`. Crash recovery uses the marker as the single source of truth for rekey completion.

**Low fixes:**

- **L1** — R32a's reference to "R85" corrected to R78b.
- **L2** — R16b's reference to "R71–R85" corrected to R71–R82b; "R86+" corrected to R80+.
- **L7** — R71b's `@ApiStatus.Experimental` (JetBrains-specific) replaced with Javadoc `@implNote` + runtime `isProductionReady()` property.

**Medium findings (tracked for v6.1):**

- **M1** — Extend R30a to cover captured DEK versions held by in-flight compactions.
- **M2** — R5c pre-encryption lookup path needs DekHandle/table-handle resolution.
- **M3** — Verification Note on `wal.encryption` was added in v5; M3 is effectively resolved.
- **M4** — R64 wait-free semantics should be amended to describe per-shard immutable-map swap cadence.
- **M5** — R80a-1 addresses the `tableId` / `dekVersion` gap for `purpose=dek` (addressed in v6; M5 effectively resolved for the critical case).
- **M6** — R76 transition-to-`failed` ambiguity on partial cache state.

These Medium items do not block APPROVED state; they are minor contract clarifications that can be resolved in a v6.1 amendment during implementation if they surface.

**Post-fix disposition:** State promoted DRAFT → APPROVED. Implementation tracking: obligation `implement-f41-lifecycle` now encompasses the full v6 surface.

### Amended: v5 — 2026-04-21 — three-tier hierarchy + tenant-aware registry

Scope of v5 amendment:

- **R17** — rewritten from two-tier (caller KEK + library DEKs) to three-tier (Tenant KEK → Domain KEK → DEK). Supersedes the two-tier assumption in `encryption-key-rotation` and `per-field-key-binding` ADRs.
- **R17a** — new: per-field derivation is a fourth conceptual layer, ephemeral and reproducible, not persisted.
- **R10**, **R11** — HKDF IKM is now the unwrapped DEK (resolved from DekHandle). `info` extended to include `tenantId`, `domainId`, and `dekVersion` length-prefixed. Blocks canonicalization collisions.
- **R9**, **R12**, **R12a**, **R14**, **R15** — signatures updated to accept `DekHandle` and reflect the four-tuple identity.
- **R18**, **R18a**, **R19**, **R19a**, **R19b** (new), **R20**, **R20a**, **R21** — registry schema rewritten for per-tenant shards keyed by `(tenantId, domainId, tableId, dekVersion)`. Atomic commit per-shard. Lazy-load.
- **R22**, **R22b** (new), **R23a** — ciphertext scope now bound to SSTable footer `(tenantId, domainId, tableId)`; cross-scope reads rejected.
- **R25**, **R25a**, **R25b**, **R26** — compaction paths scoped by `(tenantId, domainId, tableId)`.
- **R29** — DEK generation wraps under the domain KEK (not directly under the caller's KEK).
- **R32**, **R32a** (new), **R32b** (new), **R33**, **R33a** (new), **R34**, **R34a** — rotation rewritten as cascading lazy rewrap at each tier, with grace-period-exceeds-WAL-retention invariant.
- **R57**, **R58**, **R63** — scope-aware validation and registry concurrency.
- **R66**, **R68**, **R69** — zeroization extends to domain KEK, tenant KEK handles, and cache eviction.
- **R70** — shard files on disk.
- **R71–R71b** (new): three tenant encryption flavors (`none` / `local` / `external`).
- **R72–R74b** (new): encrypt-once-at-ingress invariant; MemTable holds ciphertext; per-field ciphertext reused across WAL → MemTable → SSTable; primary keys remain plaintext; WAL envelope covers metadata only.
- **R75–R75b** (new): WAL `_wal` domain mapping; F42 compatibility via internal resolution (no F42 amendment required; Verification Note on `wal.encryption` pending).
- **R76–R77** (new): three-state failure machine per tenant (`healthy` / `grace-read-only` / `failed`) with N=5 / 1h defaults.
- **R78–R78e** (new): rekey API with proof-of-control sentinel, streaming paginated execution, crash-resumable progress, dual-reference migration.
- **R79–R79b** (new): opt-in polling per tenant.
- **R80–R80b** (new): `KmsClient` SPI contract (normative signature surface; cache/retry defaults in `kms-integration-model`).
- **R81–R81a** (new): DekHandle contract.
- **R82–R82b** (new): per-tenant sharded registry guarantees.

This amendment is substantial. State remains DRAFT until adversarial Pass 5 completes. Implementation tracking: existing obligation `implement-f41-lifecycle` remains valid; it now encompasses the v5 surface.

### Amended: v4 — 2026-04-17 — state demotion APPROVED → DRAFT

Discovered during F03 spec-verify: F41 has zero code coverage.

Searched codebase for `deriveFieldKey`, `HKDF`, `preEncryptedBitset`, `keyRegistry`, `DekVersion`, `LeakageProfile` — only match is a single comment in `DataTransformationAdversarialTest.java` flagging that the GCM key truncation lacks HKDF (not an implementation). The full F41 surface area — per-field HKDF derivation, KEK/DEK envelope hierarchy, key registry with atomic updates, 4-byte DEK version tag on ciphertext, compaction-driven re-encryption, schema version tag in SSTable footer, `leakageProfile()` method, power-of-2 response padding, KEK rotation, dual-read rotation window, online encryption migration — is architecture captured as spec text but not implemented.

F41's APPROVED state was misleading: the spec passed three adversarial review passes but was never verified against implementation. The state is demoted to DRAFT to reflect that no code backs these contracts. A new obligation `implement-f41-lifecycle` is registered to track the full implementation project.

#### Amendments applied in v4

- **R22** — Ciphertext format amended to specify detached 16-byte HMAC-SHA256 authentication tags for OPE and DCPE. This closes the authenticity gap identified in F03 v1 where wrong-key OPE/DCPE decryption produced plausible but incorrect values. New OPE on-disk format is 29 bytes; new DCPE on-disk format is `8 + N*4 + 20` bytes. GCM and SIV layouts unchanged (inline authentication is inherent to those schemes). See F03.R78 and F03.R79 for MAC derivation details.

#### Partial code implementation scheduled in F03 verification (Phase 5)

The F03 verification (2026-04-17) includes code changes that partially implement F41 under the current single-key model (no DEK version tag, no KEK hierarchy):

- **MAC wrapping for OPE/DCPE** — implements F41.R22's detached tag requirement for OPE and DCPE ciphertext.
- **DCPE serializer integration** — implements F03.R50/R51 by moving DCPE encryption into `DocumentSerializer`, with per-vector perturbation seed and MAC stored in the serialized format.
- **Finally-block key zeroing** — implements F03.R81 / F41.R16 / F41.R68 zeroization discipline for intermediate key arrays.

When the full F41 is implemented, the on-disk ciphertext format will need a migration pass to add the 4-byte DEK version tag prefix specified in R22. Existing data written in the interim will need to be re-encrypted via the compaction-driven migration mechanism (R25, R38). This migration is tracked under `implement-f41-lifecycle`.

#### Known gaps remaining in DRAFT state

All of F41 except the MAC-wrapping portion of R22 remains unimplemented. The full list (as of v4; v5 adds R71–R82b and amends many prior sections):

- R1–R8 — per-field pre-encryption bitset on JlsmDocument
- R9–R16c — HKDF-SHA256 key derivation (code currently uses single-pass HMAC-Expand)
- R17–R24 (now amended for three-tier) — three-tier hierarchy, key registry, DEK version tag prefix, CRC-32C checksum, scope-binding
- R25–R28 — compaction-driven re-encryption
- R29–R31 — DEK lifecycle and pruning
- R32–R34a (now amended for cascading rewrap) — rotation
- R35–R37 — dual-read rotation window + DET/OPE index rebuild
- R38–R43 — online encryption migration
- R44–R50 — leakage profile documentation
- R51–R54 — power-of-2 response padding
- R55–R65 — various validation and concurrency requirements
- R66–R70a — resource lifecycle requirements beyond R16/R68 finally-block zeroing
- **R71–R82b (v5 additions)** — three flavors, encrypt-once invariant, `_wal` domain mapping, three-state failure machine, rekey API, polling, KmsClient SPI contract, DekHandle contract, sharded registry contract

Obligations registered: `implement-f41-lifecycle`.
