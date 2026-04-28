---
{
  "id": "encryption.primitives-lifecycle.key-hierarchy",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [
    "encryption"
  ],
  "requires": [],
  "invalidates": [],
  "decision_refs": [],
  "kb_refs": [],
  "parent_spec": "encryption.primitives-lifecycle",
  "_split_from": "encryption.primitives-lifecycle"
}
---

# encryption.primitives-lifecycle.key-hierarchy — Key Hierarchy — Three-Tier Envelope and Sharded Registry

This spec was carved from `encryption.primitives-lifecycle` during a domain subdivision. The
parent retains cross-cutting requirements that span multiple sub-domains;
the requirements below are specific to this concern.

R17. The encryption system must support a three-tier key hierarchy per `three-tier-key-hierarchy` ADR:

- **Tier 1 — Tenant KEK** — owned by the tenant (flavor 3) or the deployer (flavor 2); held in the tenant's KMS via the `KmsClient` SPI (R86+). Never materialised at rest by jlsm.
- **Tier 2 — Data-domain KEK** — owned by jlsm; wrapped under the Tenant KEK using AES-KW or AES-KWP (RFC 3394/5649). Stored in the per-tenant sharded registry (R71). Unwrapped into an Arena-backed MemorySegment on first domain open, cached per `kms-integration-model` TTL.
- **Tier 3 — DEK** — owned by jlsm; uniquely identified by `(tenantId, domainId, tableId, dekVersion)` per `dek-scoping-granularity` ADR. Wrapped under the Data-domain KEK using AES-GCM with the tenant+domain encryption context bound as AAD. Stored in the per-tenant sharded registry.

Tenant isolation is always-on when encryption is enabled. No shared-KEK mode across tenants exists.

R17a. Per-field encryption derives from the DEK via HKDF (R9–R16c). The three tier levels wrap; the per-field derivation is a fourth conceptual layer that does not create a persisted key — field keys are ephemeral and reproducible.

R18. Each DEK must have a unique integer version identifier, scoped per `(tenantId, domainId, tableId)`. Version identifiers must be 32-bit signed positive integers assigned in strictly increasing order within each table's version space. The version must never be reused within the same `(tenantId, domainId, tableId)` scope.

R18a. When the current version within a `(tenantId, domainId, tableId)` scope is Integer.MAX_VALUE, generating a new DEK for that table must throw IllegalStateException stating that the version space is exhausted for the identified scope.

R19. The key registry must be a persistent structure **sharded per-tenant** per `three-tier-key-hierarchy` ADR and `dek-scoping-granularity` ADR. Each shard is keyed by `(tenantId, domainId, tableId, dekVersion)` and records: the wrapped DEK material (wrapped under the domain KEK), the domain KEK version used for wrapping, the tenant KEK version the domain KEK was wrapped under at the time of DEK creation, and the creation timestamp. Each tenant's shard must contain a map from `(domainId, tableId, dekVersion)` to wrapped DEK entry, plus a map from `(domainId)` to wrapped domain KEK entry, plus the active tenant KEK reference.

R19a. Each registry shard file must include a trailing CRC-32C checksum covering all preceding bytes. On load, the checksum must be verified before any DEK or domain KEK entries are parsed. A checksum mismatch must throw IOException identifying the registry file path.

R19b. The concrete per-tenant sharded registry layout (per-domain files versus log-structured merge-of-registries) is deferred to implementation but must satisfy: (a) atomic per-shard commit (temp+fsync+rename or equivalent), (b) independent per-tenant fault domains (one tenant's shard corruption does not affect others), (c) lazy-load — shards are read on first domain open, not enumerated at startup.

R20. Each registry shard must be updated atomically using the pattern appropriate to the chosen layout: write to a temporary file, fsync, rename for per-domain files; or append-only log records with an atomic commit marker for log-structured. A crash during a shard update must leave the previous shard contents intact.

R20a. On startup, for each orphaned temporary shard file (matching the temp file naming pattern), the loader must: (1) verify the CRC-32C checksum (R19a), (2) if valid and the temp file contains a strictly newer state (higher max DEK version within its scope) than the current shard, complete the interrupted rename (promote the temp file to the shard path), (3) if invalid or older, delete the temp file. The comparison must use the DEK version map scoped to the shard, not file timestamps. The temp file naming pattern must be documented.

R21. For each `(tenantId, domainId, tableId)` tuple, exactly one DEK version must be designated as the current (active) version for that table. All new writes to that table must use the table's current DEK. Reads must accept any DEK version present in the registry scoped to the table.

### Ciphertext format with version tag

R80. The `KmsClient` SPI must expose the following operations:

- `WrapResult wrapKek(MemorySegment plaintextKek, KekRef kekRef, Map<String,String> encryptionContext)`
- `UnwrapResult unwrapKek(ByteBuffer wrappedBytes, KekRef kekRef, Map<String,String> encryptionContext)`
- `boolean isUsable(KekRef kekRef)`
- `void close()` — implements AutoCloseable; releases connection pool resources

Detailed method contracts, exception hierarchy (`KmsTransientException`, `KmsPermanentException`, `KmsRateLimitExceededException`), timeout / retry / cache defaults, encryption-context contents, and observability contract live in `kms-integration-model` ADR. This spec references that ADR as normative.

R80a. `encryptionContext` passed on every wrap/unwrap must include at minimum: `tenantId`, `domainId`, and `purpose`. The `purpose` field is an enumerated value from a closed set defined by this spec:

- `domain_kek` — wrapping of a data-domain KEK under the tenant KEK (tier 1 → tier 2)
- `dek` — wrapping of a DEK under its domain KEK (tier 2 → tier 3)
- `rekey_sentinel` — proof-of-control sentinel for rekey (R78a)
- `health_check` — sentinel blob for opt-in polling (R79)

jlsm must **reject** any `purpose` value not in this closed set with IllegalArgumentException before invoking the `KmsClient`. Third-party `KmsClient` implementations must not add `purpose` values of their own. Extending the set requires a spec amendment. Additional context keys (e.g., `tableId`, `dekVersion` per R80a-1) are permitted and extensible, but `purpose` is closed.

The integer codes representing `purpose` values in any persisted or AAD-bound form are a **persistence-format contract** and must be stable across jlsm versions. Implementations must expose a total `code()` accessor on `Purpose` whose values are pinned by spec:

- `domain_kek` = **1**
- `dek` = **2**
- `rekey_sentinel` = **3**
- `health_check` = **4**

These integer codes MUST NOT change. The ordinal position of the `Purpose` enumeration (for implementations that use enums) must not be used as a persistence token — reordering enum constants or inserting a value with a lower code would silently invalidate every previously-wrapped DEK. Adding a new `purpose` value to the closed set must assign the next unused integer code and must not reorder existing codes. The AAD encoding required by R80 must include the stable `code()` value, never the ordinal.

R80a-1. For `purpose=dek` wraps and unwraps, the `encryptionContext` must additionally include `tableId` and `dekVersion` (the latter as decimal UTF-8 of the version integer) so the KMS AAD binding prevents cross-table DEK-blob swap within the same `(tenant, domain)`. For `purpose=domain_kek`, `purpose=rekey_sentinel`, and `purpose=health_check`, `tableId` and `dekVersion` are not applicable and must not be included.

jlsm must not include plaintext key material in the context. The KMS binds these as AAD; a wrap under one context cannot be unwrapped under another — the wrap cannot be cross-wired between tenants, domains, purposes, or (for `dek` wraps) tables.

R80b. `KmsClient` implementations must be thread-safe. Connection pooling is the implementation's responsibility — jlsm does not manage KMS connection state, retry queues, or circuit-breaker logic (`kms-integration-model` R88).

### DekHandle contract

R81. A `DekHandle` returned by `EncryptionKeyHolder` must carry (at minimum): non-null `tenantId`, non-null `domainId`, non-null `tableId`, and non-negative `dekVersion`. DekHandle construction must validate these and throw IllegalArgumentException for missing or invalid values.

R81a. DekHandle must not expose the unwrapped DEK bytes directly. Access to the DEK material must be gated by `EncryptionKeyHolder.deriveFieldKey` (R9) so that the caller never holds raw DEK references beyond the ingress encryption path.

### Sharded registry contract

R82. The per-tenant registry is organised as one or more shard files per tenant per `three-tier-key-hierarchy` ADR. The concrete layout (per-domain files versus log-structured) is deferred to implementation but must honour R19b (atomic per-shard commit, independent per-tenant fault domains, lazy-load).

R82a. Shards for different tenants must be stored in separate files, never interleaved, so a filesystem-level corruption or permission error affecting one tenant's shard does not leak into any other tenant's key material.

R82b. Each tenant's shard file(s) must be derivable from `tenantId` alone via a deterministic, documented function, so operational tools (backup, compliance scans, tenant-decommission) can locate all of a tenant's shards without maintaining a separate index. Per-domain addressability within a tenant's shards is layout-dependent:

- **Per-domain file layout** (one file per `(tenantId, domainId)`): the function also derives per-domain paths from `(tenantId, domainId)`. Layout satisfies R30c atomic manifest-snapshot+compaction-set coordination via per-file rename semantics.
- **Log-structured layout** (one append-only log per tenant across domains): per-domain addressability is provided by an internal index rather than filesystem paths. Layout satisfies R30c via atomic commit markers; operational tools navigate via the index.

Both layouts must satisfy: tenant-level file discovery by `tenantId` without a separate index (R82b as stated), atomic per-shard commit (R20), independent per-tenant fault domains (R82a), and lazy-load (R19b). R19b's layout optionality is constrained by this requirement — a layout that does not support tenant-level discovery-by-`tenantId` is not permitted.

## Cross-References

- Spec: F03 — Field-Level In-Memory Encryption (parent encryption contracts)
- Spec: F14 — JlsmDocument (document model, preEncrypted factory methods)
- Spec: wal.encryption (F42) — consumes DEK from `_wal` domain per R75
- ADR: .decisions/three-tier-key-hierarchy/adr.md
- ADR: .decisions/dek-scoping-granularity/adr.md
- ADR: .decisions/kms-integration-model/adr.md
- ADR: .decisions/tenant-key-revocation-and-external-rotation/adr.md
- ADR: .decisions/per-field-pre-encryption/adr.md
- ADR: .decisions/per-field-key-binding/adr.md (amended by three-tier-key-hierarchy)
- ADR: .decisions/encryption-key-rotation/adr.md (amended by three-tier-key-hierarchy)
- ADR: .decisions/unencrypted-to-encrypted-migration/adr.md
- ADR: .decisions/index-access-pattern-leakage/adr.md
- KB: .kb/systems/security/three-level-key-hierarchy.md
- KB: .kb/systems/security/encryption-key-rotation-patterns.md
- KB: .kb/systems/security/jvm-key-handling-patterns.md
- KB: .kb/systems/security/client-side-encryption-patterns.md
- KB: .kb/systems/security/dek-revocation-vs-rotation.md (authoritative for R83/R83b)
- KB: .kb/systems/security/dek-caching-policies-multi-tenant.md (authoritative for R83b-1)
- KB: .kb/algorithms/encryption/index-access-pattern-leakage.md



---

## Notes
