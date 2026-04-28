---
{
  "id": "encryption.primitives-lifecycle",
  "version": 13,
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

R1. JlsmDocument must carry a `long` pre-encrypted bitset instead of a boolean flag. When the bitset value is `0L`, no field is pre-encrypted. Bit N set (where N is the zero-based field index) means field N was pre-encrypted by the caller. The pre-encrypted bitset is set at construction and must not be modified thereafter; it must be stored in a final field. The bitset must be interpreted as an unsigned bit vector — the sign bit (bit 63) is a valid field marker. No validity check may reject a bitset solely because it is negative when interpreted as a signed long.

R1a. The pre-encrypted bitset must not be persisted in the SSTable. It is a transient construction-time flag consumed by the serializer. After serialization, pre-encrypted and library-encrypted fields must be indistinguishable in the on-disk format.

R2. JlsmDocument must provide a factory method that accepts a JlsmSchema, a `Set<String>` of pre-encrypted field names, and name-value pairs. The factory must compute the bitset from the field names by mapping each name to its field index and setting the corresponding bit. For every field whose bit is set in the pre-encrypted bitset, the factory must verify the corresponding value is `byte[]`. Non-null values that are not `byte[]` must be rejected with IllegalArgumentException.

R3. The existing `preEncrypted(schema, nameValuePairs...)` factory method must remain backward-compatible. It must compute the bitset by setting bits for all fields whose EncryptionSpec is not the none variant, producing the same behavior as the previous boolean `true` flag.

R4. The `preEncrypted(schema, preEncryptedFieldNames, nameValuePairs...)` factory must reject any field name in `preEncryptedFieldNames` whose EncryptionSpec is the none variant. The rejection must throw IllegalArgumentException naming the offending field.

R5. When a field's bit is set in the pre-encrypted bitset, its value must be a `byte[]` containing raw ciphertext. The serializer must write this ciphertext directly without applying library encryption. Type validation must be skipped for pre-encrypted fields.

R5a. Pre-encrypted `byte[]` values must have a length of at least 4 bytes (the DEK version tag size). The factory method must reject undersized `byte[]` with IllegalArgumentException stating the minimum required length.

R5b. If a field's bit is set in the pre-encrypted bitset but its value is null, the factory must throw IllegalArgumentException. A pre-encrypted field must either be present with `byte[]` ciphertext or have its bit unset (indicating absence).

R5c. When writing a pre-encrypted field, the serializer must validate that the first 4 bytes of the `byte[]` contain a DEK version present in the key registry. The version existence check must use the same wait-free volatile-reference map specified in R64, not a registry file read. This ensures pre-encrypted writes are never blocked by KEK rotation (R34a). If the version is not found, the write must throw IllegalArgumentException. The serializer must not attempt decryption (the caller is trusted to have encrypted correctly), but the version tag must reference an existing DEK.

R6. When a field's bit is not set in the pre-encrypted bitset and the field has a non-none EncryptionSpec, the serializer must apply library encryption as specified by the field's EncryptionSpec.

R7. The `isFieldPreEncrypted(int fieldIndex)` method must evaluate `(preEncryptedBitset & (1L << fieldIndex)) != 0` and return the result. This method must be package-private for use by DocumentAccess and the serializer.

R8. If a schema contains more than 64 fields and any field beyond index 63 is marked pre-encrypted, the factory method must throw IllegalArgumentException stating that the bitset cannot represent field indices above 63.

### Per-field key derivation (HKDF)

R9. EncryptionKeyHolder must provide a `deriveFieldKey(DekHandle dek, String tableName, String fieldName)` method that returns a MemorySegment containing a derived key of the length required by the field's EncryptionSpec variant (R16c). The derived key must be copied into a caller-supplied Arena by `deriveFieldKey`. The caller is responsible for zeroing and closing its own Arena. This prevents use-after-zero: close() zeros the internal master key and cached derivation state, but previously-returned derived key segments in caller-owned Arenas remain valid until the caller releases them. If no caller Arena is provided, the method must allocate from an internal shared Arena — in this case, close() must wait for all in-flight `deriveFieldKey` callers to complete (per R62a) before zeroing the internal Arena's segments.

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

R16c. EncryptionSpec must expose a `requiredKeyBits()` method returning the key length in bits needed for that variant. Deterministic (AES-SIV) must return 512. Opaque (AES-GCM) must return 256. OrderPreserving (Boldyreva OPE) must return 256. DistancePreserving (DCPE/SAP) must return 256. None must return 0. The key derivation routine must call `requiredKeyBits()` to determine how many HKDF-Expand steps to perform. When `requiredKeyBits()` returns 0 (None variant), the key derivation routine must return null and must not invoke HKDF. Callers must check the return value before using the derived key.

R16b. The per-field derived key (from HKDF) serves as the data encryption key for that field. In the envelope encryption model (R17–R21 as amended by R71–R82b), the DEK resolved from the DekHandle is used as the input keying material to HKDF. When the DEK rotates, all per-field derived keys change because the IKM and the `dekVersion` info component both change. Callers provide a KmsClient (R80+) that unwraps the tenant KEK, which unwraps the domain KEK, which unwraps the DEK; the unwrapped DEK is then used as the HKDF master key input.

### Key hierarchy: three-tier envelope

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

R22. Every encrypted field value persisted to MemTable, WAL, or SSTable must conform to the per-variant byte layout canonically specified in `encryption.ciphertext-envelope` R1 (and the cross-tier uniformity invariant R1a). The 4-byte plaintext DEK version prefix, per-variant payload bytes, and authentication-tag placement (inline GCM/SIV tags; detached OPE/DCPE HMAC tags) are defined there. This requirement preserves the F41.R22 identity as a normative pointer — existing `@spec F41.R22` annotations remain valid and may additionally cite `encryption.ciphertext-envelope.R1` for precision. The reader derives `(tenantId, domainId, tableId)` from the SSTable footer metadata (R23a); cross-scope reads must be rejected (R22b).

R22a. The 4-byte DEK version tag validity rules (positive-integer-only; IOException on 0 or negative values; constant-time failure path sharing the R64 wait-free lookup path) are canonically specified in `encryption.ciphertext-envelope` R2 and R2a. Version-not-found semantics (IllegalStateException identifying the `(tenantId, domainId, tableId)` scope without revealing key material) are canonically specified in `encryption.ciphertext-envelope` R2b and are also asserted locally as R24.

R22b. The reader must resolve the DEK by the tuple `(tenantId, domainId, tableId, dekVersion)` where the first three components are derived from the SSTable's footer metadata (R23a) and the fourth from the ciphertext's version tag. The reader's **expected scope** must be materialised from the caller's `Table` handle obtained via catalog lookup — not inferred from the same SSTable footer it is validating (which would be tautological). If the SSTable's declared `(tenantId, domainId, tableId)` does not match the `Table` handle's scope (i.e., the SSTable was mis-routed to a different tenant's or domain's read path), decryption must throw IllegalStateException before any DEK lookup. This enforces per-tenant isolation at the read boundary.

R22c. **SSTable read path must thread the `ReadContext` to deserialize.** The serializer interface invoked from the SSTable read path must accept the reader's `ReadContext` (the per-read DEK-version dispatch gate, `sstable.footer-encryption-scope` R3e) as a parameter on `deserialize`. The SSTable reader's typed-get path must thread its own `ReadContext` into the deserialize call so that the R3e dispatch gate is invoked structurally on every read; the `ReadContext` must not be held by the reader and silently dropped before the deserializer runs. Serializer implementations that do not require the dispatch gate (non-encrypted schemas, or implementations that delegate the membership check to a downstream component) must accept the parameter and ignore it — no behavior change is mandated for non-encrypted paths. The threading discipline operates at the API surface where envelope DEK-version fields first become reachable from disk bytes; bypassing this surface (e.g., reading bytes through a separate path that constructs no `ReadContext`) is a spec violation under R22b. Validation must be a runtime conditional, not a Java `assert`.

R23. The 4-byte version tag must be readable without decryption. No part of the version tag may be encrypted.

R23a. Each SSTable's footer metadata must record: the `(tenantId, domainId, tableId)` scope identifying which table this SSTable belongs to, and the set of DEK versions used for encrypted fields within this SSTable. During compaction of multiple input SSTables, the output SSTable records only the current DEK version (since all fields are re-encrypted to the current version). This metadata enables the manifest to answer "which SSTables reference DEK version V for table T?" without scanning ciphertext, and anchors R22b's cross-scope check.

R24. A ciphertext whose 4-byte version tag references a DEK version not present in the registry for the SSTable's scope must cause decryption to throw IllegalStateException with a message identifying the missing version number and the `(tenantId, domainId, tableId)` scope without revealing any key material.

### Compaction-driven re-encryption

R25. During compaction, for every encrypted field in a record, the compaction task must: (a) read the 4-byte DEK version tag, (b) look up the corresponding DEK in the registry scoped by the input SSTable's `(tenantId, domainId, tableId)`, (c) decrypt with that DEK, (d) re-encrypt with the current (active) DEK for the scope, and (e) write the re-encrypted ciphertext with the current DEK's version tag.

R25a. The compaction re-encryption path must not rely on the pre-encrypted bitset. The compactor must pass re-encrypted field data to the SSTable writer through a compaction-specific write path that accepts raw ciphertext bytes per field. This path must be distinct from the JlsmDocument-based write path (which uses the pre-encrypted bitset) and must accept an array or map of field-index-to-ciphertext entries. The SSTable writer must write these bytes verbatim without consulting the field's EncryptionSpec. This path must support schemas of any size (no 64-field limit). The SSTable writer must still record the schema version tag (R40), DEK version set (R23a), and scope identifier (R23a) in the footer metadata regardless of which write path is used.

R25b. The compaction task must capture the current DEK version (for the scope being compacted) at task start and use it for all re-encryption within that task. The capture must read the current version from the volatile-reference immutable map (R64), not from the locked registry. This ensures compaction startup is never blocked by KEK rotation (R34a) or cascading rewrap (R82). The output SSTable records this single DEK version in its footer metadata (R23a). If the DEK rotates during compaction, the output SSTable will use the pre-rotation version, and a subsequent compaction pass will re-encrypt to the new version. Convergence detection (R37) must not treat output SSTables from recent compaction as converged — they may reference the pre-rotation DEK by design.

R26. If a record's encrypted field already uses the current DEK version for its scope, compaction must not decrypt and re-encrypt that field. It must copy the ciphertext unchanged. This avoids unnecessary cryptographic operations when no rotation has occurred.

R27. Re-encryption during compaction must not block reads or writes to the table. Compaction operates on immutable SSTable inputs and produces new SSTable outputs; no lock on the active write path is required. Compaction from one tenant must not block compaction for another tenant (per-tenant isolation invariant from `three-tier-key-hierarchy`).

R28. After a compaction run completes and the output SSTable replaces the input SSTables in the manifest, the compaction task must not delete old DEK entries from the registry. DEK pruning is a separate operation (R30).

### DEK lifecycle

R29. A new DEK must be generated for a `(tenantId, domainId, tableId)` scope by: producing 32 bytes from a SecureRandom instance, wrapping the DEK with the domain KEK using AES-GCM with the tenant+domain encryption context as AAD (R87), assigning the next sequential version number within the scope (R18), and persisting the wrapped entry to the per-tenant registry shard (R19).

R30. A DEK version may be pruned from the registry only when no live SSTable in the manifest references that DEK version within its scope. The pruning check must scan the manifest's SSTable list filtering by `(tenantId, domainId, tableId)`. Premature pruning must be prevented: if any SSTable still contains ciphertext tagged with that DEK version in the same scope, the DEK must remain.

R30a. DEK pruning must not remove a DEK version while any in-flight compaction task references an SSTable containing that version. The pruning check must consider both the live manifest and the set of SSTables currently being compacted (the compaction input set).

R30b. The pruning scan must operate on a consistent snapshot of the manifest. If the manifest uses an atomic-swap update model, the scan must read a single snapshot and not interleave with concurrent manifest mutations.

R30c. The pruning operation must read the manifest snapshot and the in-flight compaction input set as a single atomic operation. The implementation must hold a read lock that prevents new compaction tasks from registering inputs between the manifest snapshot read and the compaction input set read. Without this atomicity guarantee, a new compaction could register an SSTable referencing a DEK version that the pruning scan has already determined is unreferenced.

R31. When a DEK entry is pruned from the registry, the unwrapped key material (if held in memory) must be zeroed before release. The wrapped key material must be removed from the persisted registry shard in a subsequent atomic shard update.

### KEK rotation (cascading lazy rewrap)

R32. KEK rotation at any tier must follow the cascading lazy rewrap model per `three-tier-key-hierarchy` ADR: a rotation at tier N produces a new tier-N reference; wrapped entries at tier N+1 are rewrapped opportunistically on next access (not synchronously). No rotation imposes a global barrier or synchronous O(domains) or O(DEKs) rewrap.

R32a. **Tenant KEK rotation** (tier 1, flavor 3: tenant-driven via `rekey` API from `tenant-key-revocation-and-external-rotation` ADR): on invocation, re-wraps the tenant's domain KEKs under the new Tenant KEK in streaming per-shard batches (R78b). Does not re-wrap DEKs (tier 3) or touch data on disk. Domain KEK blobs remain valid under both old and new Tenant KEK during the migration window (dual-reference per R78d).

R32c. Streaming rekey per R32a / R78b must release the exclusive shard lock between batches. Each batch must complete within a configurable max-hold-time budget (default 250 ms). If a batch does not complete within the budget, the implementation must split the batch, commit the processed prefix, release the lock, and reacquire before continuing. This prevents rotation from starving DEK creation (R34a shared lock) for unbounded time.

R32b. **Domain KEK rotation** (tier 2): on invocation, unwraps the domain KEK from the Tenant KEK, generates a fresh domain KEK, re-wraps every DEK within the rotating domain under the new domain KEK, and persists the updated shard. DEK cipher material on disk is not touched. This is O(DEKs-in-domain), bounded by the domain's size.

R32b-1. Domain KEK rotation must hold an exclusive lock scoped to the specific `(tenantId, domainId)` being rotated. The lock must cover **every DEK entry in the rotating domain** — concurrent DEK creation (R29) for the rotating `(tenantId, domainId)` must be rejected or queued for the rotation's duration. DEK creation in other `(tenantId, domainId)` scopes (same tenant, different domain; different tenant entirely) must not be blocked by this lock.

R33. After any tier rotation, the retired reference must be added to a retired-references set in the registry. The retired reference must not be deleted from the KMS or zeroized in memory until the rewrap cascade has completed for at least one access cycle — old wrapped entries require the old reference to unwrap during the migration window.

R33a. For Tenant KEK rotation (flavor 3), the retired-reference retention window must exceed WAL retention per the grace-period invariant from `three-tier-key-hierarchy` ADR. Otherwise, unreplayed WAL segments that were encrypted under the old domain KEK (which was wrapped under the old Tenant KEK) become undecryptable.

R34. Rotation at any tier must be atomic with respect to the per-tenant registry shard it writes to: either all wrapped entries at that shard are updated, or none. A crash during rotation must leave the shard in a consistent state with the previous state still usable.

R34a. Rotation must hold an exclusive lock on the specific registry shard being modified for the duration of the shard-scoped update. Concurrent DEK creation within the same shard must acquire a shared lock; rotation must acquire an exclusive lock. Rotations in different tenants' shards must not lock each other (per-tenant isolation).

### Dual-read during rotation window

R35. During a key rotation window (when SSTables contain ciphertext under both old and new DEK versions, or wrapped entries exist under both old and new KEK references), every read must be able to decrypt using any DEK version and unwrap via either retired or current KEK reference present in the registry. The reader must not assume all ciphertext uses the current DEK.

R36. DET-encrypted indexed fields must be marked as "rotation-pending" when a new DEK version is activated and the previous version is still referenced by live SSTables. During the rotation-pending window, queries on DET/OPE-indexed encrypted fields may return incomplete results. This limitation must be documented on the rotation API.

R37. After rotation converges (no live SSTables reference old DEK versions within the scope), DET/OPE indices affected by the rotation must be rebuilt. The library must provide a mechanism to detect convergence and trigger index rebuild.

R37a. Convergence detection (R37) must trigger within a bounded wall-clock delay after the last live SSTable referencing the rotated-away DEK version is removed from the manifest. The bound must be (a) configurable in the range `[100ms, 24h]`, (b) defaulted to the configured manifest-update cycle plus a 5-minute slack window to absorb tail latency in remote-backend manifest commits, and (c) observable: implementations must emit a `R37aBoundApproaching` structured event at the configured bound minus 10% (carrying the elapsed wall-clock time) and a `R37aBoundExhausted` event at the bound. After the bound, registered consumers (R37b) must receive an explicit timeout signal — silent hang is forbidden. The 24h ceiling matches R91's cache TTL ceiling so that no encryption-system delay exceeds a known operational bound.

R37b. Consumers awaiting convergence (e.g., DET/OPE index rebuild) must be able to register interest before convergence occurs and must be notified when convergence happens. Registration must return an `AutoCloseable` handle whose `close()` removes the registration. Implementations must hold consumer references via `WeakReference` (or equivalent) so a consumer leaked by its owner does not pin the library; weakly-held registrations cleared by the GC must be silently dropped from the notification set. Registrations are in-process state and do not survive `EncryptionKeyHolder.close()` (R66) or process restart — `close()` must drop all pending registrations atomically. Internal polling, if used as the implementation strategy, must be bounded with cadence in `[100ms, 60s]`; default cadence is `min(60s, manifest-update-cycle / 10)` when the manifest publishes its update-cycle metric, otherwise 5 seconds. Implementations that subscribe to R37c's manifest commit-completion hook must not poll — the hook is the primary signal; polling is a fallback only when the hook surface is unavailable. External consumers must not be required to poll the manifest themselves.

R37b-1. Implementations must additionally expose a synchronous query — `ConvergenceState convergenceStateFor(scope, oldDekVersion)` — returning the current convergence state for a given `(tenantId, domainId, tableId, oldDekVersion)` tuple. `ConvergenceState` is a sealed enum with values `{PENDING, CONVERGED, TIMED_OUT, REVOKED}`:

- `PENDING` — at least one live SSTable in the manifest still references the version, AND R37a's bound has not been exceeded since rotation start.
- `CONVERGED` — no live SSTable in the manifest references the version (the convergence trigger fired or would fire on next manifest scan).
- `TIMED_OUT` — at least one live SSTable still references the version, AND the wall-clock time elapsed since rotation start exceeds **the R37a bound in effect at rotation start** (NOT the currently-configured bound). Per P4-4 fix, the implementation must record the R37a bound alongside the rotation-start timestamp in the registry's rotation metadata. A subsequent dynamic config change to R37a's bound must not retroactively change `TIMED_OUT` classifications for in-flight rotations — rotations started after the new configuration takes effect use the new bound. This state must be derivable from durable state (manifest + rotation-start timestamp + rotation-start bound, all recorded in registry) without process-lifetime memory.
- `REVOKED` — the rotated-from DEK has been observed as permanently revoked per R83g; the convergence event was suppressed per R83d.

**Monotonicity invariant.** `ConvergenceState` is monotonic per `(scope, oldDekVersion)` only in one direction: once the state becomes `REVOKED`, it cannot transition back to any other state. Transitions follow the partial order `PENDING → CONVERGED → REVOKED` and `PENDING → TIMED_OUT → REVOKED`. Consumers must treat `REVOKED` as terminal — observing `CONVERGED` and then `REVOKED` for the same scope means the convergence event was suppressed retroactively per R83d's "revocation is authoritative" rule, and any index-rebuild work begun on the prior `CONVERGED` observation must be aborted (the rebuild target is no longer recoverable).

Consumers registering after convergence has already fired must observe that state via this query rather than via the registration callback. The convergence event for an already-converged tuple must not be replayed via callback, since the library has no per-consumer durable record of which events were already delivered. Consumers responsible for crash recovery must call `convergenceStateFor` on startup before re-registering for the `PENDING` set; consumers observing `TIMED_OUT` must escalate operationally (rotation has stalled and requires investigation).

A deprecated alias `boolean isConvergedFor(scope, oldDekVersion)` returning `state == CONVERGED` is permitted for one minor version to ease migration; new code must use `convergenceStateFor`.

R37b-2. When a weakly-held registration (R37b) is reaped by the GC before its convergence fires, implementations must emit a `convergenceRegistrationDropped` event via `KmsObserver` (per R76b-1's `polling` category — in-process durability). The event payload must include the registered `(tenantId, domainId, tableId, oldDekVersion)` so operators can detect cases where index rebuild was silently abandoned. Consumers that need guaranteed delivery must hold the registration handle in a strongly-referenced field for the registration's full lifetime, or alternatively call `convergenceStateFor` (R37b-1) on a recovery cycle to detect missed convergences.

**Event-payload tenant-visibility discipline (P4-17).** This event — and all `KmsObserver` events that include scope information — must follow the tenant-vs-deployer split paralleling R83h's exception discipline. Each event carries a deployer-only flag `isDeployerInternal: true|false`; if the observability pipeline routes events to a tenant-visible surface, the event payload must redact `domainId`, `tableId`, and `oldDekVersion`, retaining only `tenantId` plus an opaque correlation id. Implementations must construct the redacted variant at event emission time, not rely on downstream filtering — reflective serializers (Jackson, Logback) walk all event fields and could leak the unredacted form. The redacted-variant construction follows the same centralised-factory pattern as R83h's exception construction.

R37b-3. Idempotent silent-no-op `handle.close()` must apply to **all post-registration-end states**, not just the holder-close-driven drop. The handle's `close()` must be a pure no-op once the registration is no longer pending in the holder's set, regardless of which path removed the registration. The exhaustive list of registration-end paths:

- (a) Explicit `handle.close()` — first invocation removes the registration; subsequent invocations no-op.
- (b) `EncryptionKeyHolder.close()` (R66) drops the registration during holder shutdown.
- (c) Convergence fires and the per-event delivery completes the registration's lifecycle (the registration's callback invocation also discards the registration).
- (d) GC reaping of the weakly-held consumer (R37b's WeakReference clearing) — the registration is dropped from the notification set; the handle is now stale.
- Any combination of the above (e.g., GC-reaping followed by explicit `handle.close()`).

In every case, the handle's `close()` is a pure no-op once the registration is non-pending. The handle must not throw `IllegalStateException` because `EncryptionKeyHolder.close()` is the canonical lifecycle endpoint and consumers must not be required to coordinate close ordering with the holder. Implementations must use a per-handle volatile boolean (or AtomicReference to a sealed-state enum) to guard against re-entry; the boolean must observe state transitions atomically with the registration removal so concurrent close-and-deliver paths cannot leak a fired-but-not-removed registration.

R37c. Convergence detection must be wired through the manifest's commit-completion notification surface so the trigger fires synchronously with the manifest commit that removes the last referencing SSTable. The manifest must publish a narrowly-scoped post-commit hook for encryption-layer convergence detectors to subscribe to; convergence detection must not require the manifest to scan SSTable contents for DEK version references. The post-commit subscription is the only sanctioned coupling point between the manifest and the encryption layer for convergence purposes.

### Compaction-driven encryption migration

R38. When a schema update adds EncryptionSpec to a previously unencrypted field, all new writes must immediately encrypt that field with the current DEK for the table's scope. Existing SSTables with unencrypted data for that field must be encrypted during compaction.

R39. When a schema update removes EncryptionSpec from a previously encrypted field, all new writes must write that field in plaintext. Existing SSTables with encrypted data for that field must be decrypted during compaction.

R40. Each SSTable must carry a schema version tag in its footer metadata that identifies the encryption configuration at write time. The reader must use this tag to determine whether a field requires decryption for a given SSTable.

R40a. When concurrent schema updates and DEK rotations can produce records with different encryption states within a single SSTable, the SSTable footer must record both the minimum and maximum schema versions present. During reads, the reader must check the per-field ciphertext to determine encryption state when min and max schema versions differ. The presence or absence of the 4-byte DEK version tag (R22) combined with the field's current EncryptionSpec is sufficient to disambiguate encrypted from plaintext data for each record.

R41. The reader must handle mixed SSTables during migration: some SSTables may have a field encrypted and others may have the same field unencrypted. The schema version tag per SSTable determines the correct interpretation.

R42. Migration must not block reads or writes. The table must remain fully available during the entire migration window. Convergence time is bounded by a full compaction cycle.

R43. Migration must be bidirectional: adding encryption and removing encryption must use the same compaction-driven mechanism. The direction is determined by comparing the SSTable's schema version tag against the current schema's encryption configuration.

### Leakage profile documentation

R44. The EncryptionSpec sealed interface must expose a `leakageProfile()` method returning a LeakageProfile record. The default implementation must return `LeakageProfile.NONE`.

R44a. The leakage profile documentation must include a migration-window section noting that during the transition from unencrypted to encrypted (R38), the proportion of encrypted vs. unencrypted records in each SSTable reveals the migration timeline. Full convergence (all SSTables compacted) eliminates this leakage. The documentation must also note that JCA SecretKeySpec retains an internal copy of key bytes that cannot be zeroed by the library — this is a known residual risk.

R45. LeakageProfile must be a record with boolean fields: `frequency`, `searchPattern`, `accessPattern`, `volume`, `order`, plus a `LeakageLevel` enum field and a `String` description field. The `LeakageLevel` enum must have values: NONE, L1, L2, L3, L4.

R46. The Deterministic variant must return a LeakageProfile with `frequency=true`, `searchPattern=true`, `accessPattern=true`, `volume=true`, `order=false`, level L4. The description must state that identical plaintexts produce identical ciphertexts, leaking frequency distribution.

R47. The OrderPreserving variant must return a LeakageProfile with `frequency=true`, `searchPattern=true`, `accessPattern=true`, `volume=true`, `order=true`, level L4. The description must note that ciphertext ordering reveals plaintext ordering.

R48. The DistancePreserving variant must return a LeakageProfile with `frequency=false`, `searchPattern=false`, `accessPattern=true`, `volume=true`, `order=false`, and a level appropriate to the approximate distance leakage.

R49. The Opaque variant must return a LeakageProfile with all boolean fields `false` except `volume=true` (ciphertext length leaks plaintext length modulo block size). Level must be L1.

R50. The None variant must return `LeakageProfile.NONE` with all boolean fields `false`, level NONE, and description stating no encryption is applied.

### Power-of-2 response padding

R51. Query options must support an opt-in `padResults` flag. When enabled, the query executor must pad the result list size to the next power of 2 by appending distinguishable padding entries.

R52. Padding entries must be distinguishable from real results. JlsmDocument must provide an `isPadding()` method (or equivalent marker) that returns `true` for padding entries and `false` for real documents.

R53. When the actual result count is already a power of 2, no padding must be added. When the actual result count is 0, no padding must be added.

R54. The padding size must be computed as: if actual count is 0, padded count is 0; if actual count is 1, padded count is 1; if actual count is a power of 2 (and greater than 1), padded count equals actual count; otherwise padded count is `Integer.highestOneBit(actual) << 1`. The padded count must never exceed Integer.MAX_VALUE.

### Input validation and error handling

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

R91. Unwrapped domain KEKs and DEKs held in the in-memory cache must expire after a configurable TTL. The default TTL, per-tenant LRU scoping under memory pressure, and observability metrics are specified in the `kms-integration-model` ADR; this spec references that ADR as normative for the concrete defaults and eviction policy. On TTL expiry or LRU eviction, the off-heap MemorySegment holding key material must be zeroised per R69 before the segment is released. Zeroisation must occur even if an exception is thrown during eviction. The configurable TTL must carry a finite, implementation-enforced upper bound not exceeding **24 hours**. The bound must be finite so that TTL-based expiry arithmetic (e.g., `Instant.plus(ttl)`) cannot overflow or produce a past/invalid expiry time for any supported `Instant` within the lifetime of a holder, and so that R69 / R91 zeroisation is guaranteed to occur within a bounded window regardless of deployer configuration. A lower maximum is permitted for deployments that require shorter cache residency; cross-reference `kms-integration-model` ADR for the rationale and default bound. `[UNVERIFIED: the 24h upper bound is the audit-landed constant; a future research step should align this with comparable library defaults (AWS Encryption SDK CMM, Google Tink KeysetHandle) before the ADR pins a normative value — future research gate]`.

R91a. Cache eviction must be per-tenant — one tenant's cache pressure or eviction storm must not evict another tenant's cached entries, consistent with the per-tenant isolation invariant from `three-tier-key-hierarchy` ADR.

R91b. Cache entries are keyed by the four-tuple `(tenantId, domainId, tableId, dekVersion)` for DEKs and `(tenantId, domainId)` for domain KEKs. A cache lookup must match the full tuple; a partial match must not return a cached entry.

R91c. The cache must remain usable while a tenant is in the `grace-read-only` state (R76): reads against cached entries within their remaining TTL must succeed. New unwrap attempts against the KMS are blocked during `grace-read-only` per R76's state semantics, but no additional cache-side guard is required.

### Tenant encryption flavors

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

R75. For WAL encryption per F42 (`wal.encryption`), each tenant carries a synthetic **`_wal` data domain** per `three-tier-key-hierarchy` ADR. WAL metadata-envelope ciphertext is encrypted under a DEK belonging to the `_wal` domain; field payload bytes embedded in WAL records are the per-field ciphertext already produced at ingress (R74b) and are not encrypted again by the WAL envelope. The `_wal` domain identifier is **reserved** and must be runtime-enforced: the public `DomainId` constructor must reject the string `_wal` with IllegalArgumentException from any application caller. Construction of the `_wal` domain is permitted only via a sanctioned internal factory path — `DomainId.forWal()` — that is either package-private to the jlsm WAL subsystem or gated on caller identity (e.g., via `StackWalker`) so that only internal code can construct it. Violating constructions from application callers must throw IllegalArgumentException. This promotes the reservation from a naming convention / javadoc note into a runtime invariant, preventing registry-collision attacks where an application-authored domain shadows the jlsm-internal WAL domain and aliases WAL DEKs into application-visible storage.

R75a. F42's "KEK" input parameter at WAL builder construction must resolve internally to the tenant's `_wal` domain DEK-resolver. No F42 spec amendment is required; the mapping is an implementation-level resolution documented in this spec and in a Verification Note on `wal.encryption`.

R75b. Retiring a retired Tenant KEK (R33a) must not precede the replay or compaction of WAL segments encrypted under the `_wal` domain's DEKs whose wrapping chain depends on the retired Tenant KEK. This is the grace-period invariant from `three-tier-key-hierarchy` ADR enforced at WAL retention.

R75c. The grace-period invariant in R75b is enforced via the **on-disk liveness witness** mechanism of R78e: the per-tenant counter of SSTables and WAL segments whose wrapping chain depends on `oldKekRef` includes `_wal` domain WAL segments. Rekey completion (R78e, R78f) cannot complete — and therefore the old Tenant KEK cannot be marked eligible for tenant-side deletion — until that counter reaches zero. This gives the invariant a mechanical enforcement path: tenant operators relying on the `rekey` API to signal "safe to delete old KEK" inherit the protection automatically.

R75d. For the polling path (R79) that does NOT invoke a rekey (transient outage detection, not migration), no grace-period enforcement is required — polling does not transition the tenant KEK to retired.

### Three-state failure machine (flavor 3)

R76. Per `tenant-key-revocation-and-external-rotation` ADR, each tenant under flavor 3 must have one of three operational states, tracked per-tenant in the `EncryptionKeyHolder`:

- **`healthy`** — Tenant KEK unwrap operations succeed; all reads and writes proceed normally.
- **`grace-read-only`** — N consecutive permanent-class unwrap failures (default N=5, configurable) with jittered exponential backoff (per `kms-integration-model` ADR). Writes are rejected with `TenantKekUnavailableException`; reads continue using cached domain KEKs for their remaining TTL (R91).
- **`failed`** — Grace window exhausts (default 1 hour, configurable), OR all cached domain KEKs have TTL-expired. Reads and writes both rejected until the tenant rekeys to a usable Tenant KEK.

R76a. Permanent-class KMS errors (AccessDenied, KeyDisabled, KeyNotFound, KeyDestroyed) count toward N. Transient-class errors (throttling, timeout, 5xx) do not count toward N; they are retried per the backoff policy of `kms-integration-model` ADR and only escalate to a permanent-class outcome if the retry budget is exhausted.

R76a-1. `KmsClient` plugins must implement the following provider-state-to-class mapping. Plugin authors are responsible for translating provider-specific error types and codes into the canonical class:

| Provider state | Class |
|---|---|
| AWS `AccessDeniedException`, GCP `PERMISSION_DENIED`, Vault 403 | Permanent |
| AWS `DisabledException`, AWS `KMSInvalidStateException(state=Disabled)`, GCP `FAILED_PRECONDITION(state=DISABLED)`, Vault `disabled` | Permanent |
| AWS `NotFoundException`, GCP `NOT_FOUND`, Vault 404 | Permanent |
| AWS `KMSInvalidStateException(state=PendingDeletion)`, GCP `FAILED_PRECONDITION(state=DESTROY_SCHEDULED)` | Permanent (despite reversibility — the read attempt itself cannot succeed; if the operator cancels deletion, opt-in polling per R79 restores the tenant) |
| AWS `KMSInvalidStateException(state=PendingImport)`, AWS `KeyUnavailableException` | Transient |
| AWS `IncorrectKeyException`, AWS `InvalidCiphertextException` | Permanent (ciphertext-key mismatch — never recoverable for that ciphertext) |
| AWS `ThrottlingException`, GCP `RESOURCE_EXHAUSTED`, AWS 5xx, Vault 5xx | Transient |
| Unknown / unclassified `RuntimeException` from plugin | Transient (with degraded confidence — `KmsObserver` must emit a `kmsUnclassifiedError` event at WARN level; jlsm must not count toward N until the error is classified or surfaced via observability so the deployer can update the plugin) — but see R76a-2 for escalation discipline |

`[UNVERIFIED: provider error-code names as of 2026-04 — plugin authors must verify against the SDK version in use; jlsm tightens this table via amendment if upstream renames occur]`

R76a-2. When `kmsUnclassifiedError` events for a single `(tenantId, plugin-class)` exceed a configurable threshold (default 100 events within 60 seconds) without an intervening successful unwrap, the implementation must escalate the failure to permanent-class for that tenant: emit `tenantKekStateTransition(grace-read-only, reason=unclassified-error-storm)` and increment the R76 counter. This bounds the silent-retry duration that an unclassified-RuntimeException-throwing plugin would otherwise produce. The threshold and observation window are configurable per `kms-integration-model` ADR. Implementations must additionally rate-limit `kmsUnclassifiedError` log emissions to at most 1 per second per tenant (deduplicating subsequent occurrences within the second) so a high-frequency plugin bug cannot DDOS the deployer's log pipeline.

R76b. State transitions must be observable: emit `tenantKekStateTransition` structured log events and `tenantKekState` metric (gauge per tenant) via the `KmsObserver` interface (`kms-integration-model` R93).

R76b-1. All `KmsObserver` events must carry an `eventSeq` field unique within the event's `(tenantId, eventCategory)` scope. `eventCategory` is one of:

- `state-transition` — R76b transitions; **durable** (must persist across restart so consumer dedup works post-recovery).
- `rekey` — R78g events; **durable** via the rekey-progress file's `lastEmittedEventSeq` (R78g).
- `polling` — convergence registrations (R37b-2), polling diagnostics; **in-process** (resets on restart).
- `unclassified-error` — R76a-1 unknown-class events; **in-process** (resets on restart).

Each category uses a separate counter. Categories whose events are durably consequential to operator behaviour (`state-transition`, `rekey`) must persist the counter alongside related state (the rekey-progress file for rekey; a dedicated `tenant-state-progress` file for state-transition — see R76b-1a below). Categories that are advisory (`polling`, `unclassified-error`) may use in-process counters. Every event payload must declare its volatility via field `seqDurability ∈ {durable, in-process}` so consumers can apply the appropriate dedup strategy.

**Cross-category ordering (P4-24).** `eventSeq` is monotonic *within* a `(tenantId, eventCategory)` pair but NOT across categories. Consumers cannot order a `state-transition` event relative to a `rekey` event by `eventSeq` alone — the two counters are independent. When ordering across categories matters (e.g., "did the rekey complete before or after the tenant entered grace-read-only?"), consumers must use the wall-clock `timestamp` field that all `KmsObserver` events must include. The timestamp is monotonic within a single process per the JVM clock, but consumers reading durable events post-restart must accept that durable categories restart from `lastEmittedEventSeq + 1` while in-process categories restart from 0 — cross-category ordering of the first post-restart events is not preserved.

R76b-1a. The `tenant-state-progress` file required by R76b-1 must conform to the same shard-discipline invariants as the per-tenant registry shards:

- **Path:** deterministically derivable from `tenantId` per R82b's function family — specifically `<registry-root>/<tenant-shard-dir>/state-progress.bin` for per-domain-file layouts, or as a designated record-type within the tenant log for log-structured layouts. Operational tools (backup, compliance scans) must be able to enumerate all of a tenant's state-progress files from `tenantId` alone without a separate index.
- **Permissions:** owner-only (0600 on POSIX, equivalent on other platforms) per R70a's discipline. Permissions must be set before any state-transition record is written.
- **Atomic commit:** per R20's atomic-commit primitive (write-temp-then-rename, or per-layout equivalent). A crash mid-write must leave the file in a recoverable prior state.
- **Integrity:** CRC-32C trailer per R19a's discipline. On read, a checksum mismatch must throw `IOException` with a stable error code identifying the file. The in-process state machine, when initialising from a CRC-mismatched record per R76b-2, must initialise affected tenants to a conservative state (`failed`) rather than `healthy`, surfacing the integrity failure to operators rather than silently masking it.

Reuse of R20's atomic-commit primitive is intentional — if R20's implementation evolves (e.g., to a log-structured backend), the state-progress file must follow the same evolution. This coupling ensures consistency across all tenant-scoped persistent artifacts.

R76b-2. On `EncryptionKeyHolder` construction, the in-process state machine must be initialised by reading the most recent `state-transition` record from the durable `tenant-state-progress` file (R76b-1a) for each tenant. If the durable record indicates `grace-read-only` or `failed`, the in-process state machine must adopt that state and its associated grace-window timestamp. The initial in-process `eventSeq` for the `state-transition` category must be initialised to the durable `lastEmittedEventSeq + 1`. This ensures durable state-transition records and in-process state machine observations agree at every observation point.

R83a's failure counter — documented as in-process per R83a — does **not** transitively become durable. It remains zero on restart. This separation is intentional: the *current state* is durable (so reads against `failed` tenants continue to fail across restart), but the *count of failures-toward-N* that drove transitions to that state is dedup-bookkeeping that need not survive restart. A restarted process inherits the state but starts a fresh detection-epoch counter.

R76c. One tenant entering `grace-read-only` or `failed` must not affect other tenants' operations (per-tenant isolation from `three-tier-key-hierarchy`).

R77. Recovery transitions:

- **From `grace-read-only` to `healthy`:** automatic when polling (R79) detects `KmsClient.isUsable(tenantKekRef) == true`, OR when a successful `rekey` API call (R78) installs a usable new Tenant KEK.
- **From `failed` to `healthy`:** automatic only when the deployer-configured **per-tenant** flag `auto-recover-from-failed = true` (default `false`) AND polling (R79) detects `KmsClient.isUsable(tenantKekRef) == true`. With the default `false`, recovery from `failed` requires an explicit successful `rekey` call (R78) — even if the Tenant KEK becomes healthy on its own. The conservative default reflects that `failed` may indicate undelivered cache invalidations or operator action that requires explicit confirmation; a regional KMS outage that auto-resolves should not silently re-enable a tenant whose data may have been operationally written off.

The `auto-recover-from-failed` flag is **per-tenant** in scope, configurable on `EncryptionKeyHolder` construction via `flavorConfig.tenant(tenantId).autoRecoverFromFailed(boolean)`. Multi-process deployments serving the same tenant on multiple nodes must coordinate flag values via shared configuration (e.g., a config service); inconsistent flag values across nodes serving the same tenant must be detected at construction and rejected with `IllegalStateException` if the implementation has cross-node visibility, or surfaced as a WARN log to `jlsm.encryption.config` when only the local value is known. Holder-instance scope is forbidden — it would create inconsistent recovery semantics across multi-tenant deployments where some tenants are operationally allowed to auto-recover and others are not.

This reconciles R76's "`failed` requires rekey" framing with R79d's polling-default-on posture: polling drives `grace-read-only → healthy` automatically (the common transient-outage case), while `failed → healthy` is operator-gated per-tenant. The two-clause OR in earlier drafts of R77 is replaced by the explicit per-state recovery rules above.

### DEK revocation read-path (explicit loud-fail semantics)

R83. The library must distinguish three classes of read-path KEK unwrap outcomes:

- **Healthy** — unwrap succeeds; data is readable normally.
- **Transient failure** — KMS returned a transient error class per R76a / R76a-1; the implementation retries per `kms-integration-model` backoff; reads continue against cached unwrapped material per R91c.
- **Permanent revocation** — KMS returned a permanent error class per R76a / R76a-1 (AccessDenied, KeyDisabled, KeyNotFound, KeyDestroyed, IncorrectKeyException, InvalidCiphertextException, KMSInvalidStateException(PendingDeletion), and equivalents per the R76a-1 table). The implementation must not silently retry the unwrap, return null, or return wrong-key garbage. The read must fail with a public `KekRevokedException` (defined in package `jlsm.encryption`) extending `KmsPermanentException`. Both `KmsPermanentException` and `KekRevokedException` must extend `IOException` (i.e. they are **checked exceptions**), aligning with `RegistryStateException` (R83b-2) and `DekNotFoundException` (R57); read API surfaces in encryption boundaries already declare `IOException`, so the checked discipline is already paid. Subclasses for tenant-tier and domain-tier revocation must extend `KekRevokedException` and inherit its `KmsPermanentException` lineage. Implementations must not introduce parallel revocation hierarchies under unrelated parent types.

R83-1. To support uniform operator alarm wiring across the diverse parent types in the read-path failure type matrix, the spec defines a sealed marker interface `RetryDisposition.NonRetryable` in package `jlsm.encryption` whose only legitimate implementations are the four matrix exception families: `KmsPermanentException` (and its `KekRevokedException` subtree), `RegistryStateException`, `DekNotFoundException`, and `TenantKekUnavailableException`. The marker carries no methods; it exists solely so operator code can write `catch (RetryDisposition.NonRetryable e) { alert(); }` and pick up every non-retryable read-path failure regardless of its parent type. The matrix's "Parent type" column (see Read-path failure type matrix below) records each exception's declared parent so callers can construct correct `catch` chains when finer discrimination is needed.

**Sealing discipline (P4-6, P4-30).** The seal is a contract that adding a new alarm-class exception requires an explicit spec amendment — this is intentional to prevent silent alarm-class drift across `KmsClient` plugins and future jlsm versions. Plugin authors who want plugin-specific permanent exceptions to participate in `NonRetryable` alarms must:

- (a) **Wrap their exception** in `KmsPermanentException` (or `KekRevokedException` for revocation-class faults) at the plugin's boundary — the wrapper inherits `NonRetryable` via existing seal members. Plugin authors must NOT introduce parallel exception hierarchies that bypass the seal.
- (b) Use the per-plugin diagnostic field on `KmsPermanentException` (e.g., a `pluginErrorCode` accessor) to surface plugin-specific detail without adding new sealed members.

**JPMS module scope (P4-30).** The seal is pinned to module `jlsm.core` — all permitted subclasses must reside in `jlsm.core`. Java sealed types require all permitted subclasses in the same module unless the sealed type explicitly opts out via `non-sealed` intermediates (which would defeat the alarm-class invariant). Adding a new permitted type therefore requires (i) a `jlsm.core` source change to extend the `permits` clause, AND (ii) a spec amendment per the prior paragraph. Third-party modules (KmsClient plugins distributed as separate JPMS modules) cannot extend the seal; they must wrap into existing types.

R83a. The `KekRevokedException` thrown by R83 must register a permanent-class failure against the tenant's three-state machine (R76, R76a) at most **once per detection epoch**, where a detection epoch is bounded by the polling cadence (R79) for the tenant or 60 seconds, whichever is shorter. Within an epoch, additional reads against revoked DEKs in the same tenant must be coalesced into a single counter increment regardless of how many distinct `(domainId, tableId, dekVersion)` tuples are affected. Rationale: domain-KEK destruction is a single fault in the tenant's KMS but produces fan-out at jlsm's read path; R76's `N` threshold counts faults, not affected DEK cardinality. The dedup contract holds within a single `EncryptionKeyHolder` lifetime — JVM restart resets the failure counter and dedup set per the in-process state model of `tenant-key-revocation-and-external-rotation` ADR; the first revocation observation in a fresh process counts as one regardless of pre-restart history. Implementations must not persist the counter or dedup set. The counter must be implemented as `long` and saturated at `Long.MAX_VALUE`; overflow into negative values is forbidden.

R83a-1. When the saturating R83a counter reaches `Long.MAX_VALUE`, the implementation must emit a `revocationCounterSaturated` event via `KmsObserver` once per tenant per process lifetime (idempotent on subsequent emission attempts within the same process). The event signals to the operator that the counter has lost fidelity and any further N-threshold-derived state changes should be cross-checked against the underlying KMS state. Saturation is not expected under normal operation; its occurrence indicates either an attack or a deployment where polling cadence × N-increment-rate exceeds `Long.MAX_VALUE`. The event uses the `polling` category per R76b-1 (in-process durability).

R83b. Domain-KEK destruction (the multi-tenant crypto-shredding primitive per `dek-revocation-vs-rotation` KB and `three-tier-key-hierarchy` ADR) is observed at the read path through R83's permanent revocation outcome. When a domain KEK is destroyed by the operator, every DEK in that domain becomes permanently unwrappable; reads under those DEKs must produce R83's `DomainKekRevokedException` (subclass of `KekRevokedException`). The library does not provide a separate "explicit per-DEK disable" surface — domain-KEK lifecycle is the canonical revocation surface.

R83b-1. Domain-KEK destruction observation latency is bounded by `cache TTL (R91) + polling cadence (R79c)`. The unwrapped domain-KEK plaintext is cached per R91 and continues to function for in-flight reads/writes until cache TTL expiry regardless of upstream KMS revocation status. Implementations must (a) document this lag in the public rotation API, (b) eagerly invalidate the cached domain-KEK entry for a `(tenantId, domainId)` scope when polling (R79) detects `isUsable=false` for the wrapping KEK, and (c) emit a WARN-level log event when polling is *not* enabled on a flavor-3 tenant since revocation latency is then bounded only by cache TTL (up to 24h per R91). New writes under a cache-still-valid-but-upstream-revoked domain KEK are an explicitly accepted operational consequence of the lazy/cached model and must be surfaced in the deployer-facing API documentation.

R83b-1a. Eager cache invalidation per R83b-1 must use an **epoch-based reclamation** pattern to prevent in-flight readers from receiving zeroed-segment reads:

- The polling thread inserts a **tombstone entry** into the cache marking the `(tenantId, domainId)` as revoked. Insertion is atomic (CoW or volatile-reference swap per R64).
- The cached `MemorySegment` is **NOT zeroed** at the moment of tombstone insertion. Zeroisation per R69 is deferred until a quiescence barrier — e.g., an epoch counter advance that confirms no reader registered before the tombstone insertion is still in-flight.
- In-flight readers that obtained a `MemorySegment` reference *before* the tombstone insertion must, after their decryption attempt, check the tombstone for the corresponding `(tenantId, domainId)`. If the tombstone is present, they must NOT return decrypted plaintext to the caller — they must throw `DomainKekRevokedException` per R83b. This enforces R83's loud-fail discipline at the read boundary even when the cache layer raced ahead.
- Once the quiescence barrier confirms no pre-tombstone reader holds the segment, the implementation zeroes the segment (R69) and removes the tombstone (or replaces it with a permanent-revocation marker for R83g's caching).

The quiescence barrier must enforce a **configurable upper bound** (default = `cache TTL` from R91, ceiling 24h, hard maximum 48h) after tombstone insertion (P4-1). If the barrier has not advanced within the bound, the implementation must:

- (a) Emit a `quiescenceBarrierTimeout` event via `KmsObserver` (`state-transition` category, durable per R76b-1) carrying the count of pre-tombstone readers still in-flight and the time elapsed since tombstone insertion.
- (b) **Forcibly zero** the segment regardless of in-flight reader presence.
- (c) Cause any subsequent decryption attempt by the still-in-flight readers to throw `DomainKekRevokedException` instead of returning data — the post-decrypt tombstone check (clause 3 above) already enforces this; readers must additionally null-check the segment contents (or detect the forced-zero via a sentinel-bit) before returning to the caller, since the decryption may have already produced output buffers from zeroed key material.

The forced-zero path must **NOT** wait for reader cooperation; key plaintext residency time after upstream destruction is bounded by `cache TTL + polling cadence + quiescence-timeout` and must not exceed 48h under any configuration. This bounds the security exposure of an unbounded long-running scan that holds a cached domain KEK after the upstream KEK has been destroyed.

**Relationship to R83g cached-revocation (P4-15).** The per-`(tenantId, domainId)` tombstone of R83b-1a and the per-`(tenantId, domainId, tableId, dekVersion)` cached-revocation entries of R83g are **distinct data structures with a lazy-population relationship**:

- The tombstone is the per-domain short-circuit. Once inserted, all DEK lookups in the affected domain bypass the KMS regardless of dekVersion. The tombstone exists for the full lifetime of the domain-KEK revocation (cleared only by R83g's clearing conditions: operator-driven invalidation or successful rekey).
- R83g's per-DEK entries are populated **on demand** — each unique `(tableId, dekVersion)` whose unwrap attempt is short-circuited by the tombstone produces a per-DEK entry recording the specific tuple. This per-DEK entry is what R83a's epoch counter dedups against, what R83g's "must not re-contact KMS" enforces, and what R83b-2's manifest-witness scan can consult.

A reader observing the tombstone throws `DomainKekRevokedException` immediately; the implementation MAY (but is not required to) populate the corresponding R83g per-DEK entry at that moment. The tombstone alone is sufficient for short-circuiting; per-DEK entries provide finer-grained observability for tooling that wants to enumerate revoked DEKs.

This pattern preserves R69's zero-before-release at the operational level while eliminating the in-flight-reader race that would otherwise expose plaintext-derived-from-zeroed-key garbage.

R83b-1b. Flavor-2 (`local`) deployments must implement an equivalent revocation-detection mechanism: poll the filesystem `mtime` of the Tenant KEK file at the same default cadence (15 minutes) and treat absence-of-file or permission-denied as a permanent-class signal triggering R83b-1's eager invalidation path. Flavor-2 polling inherits R79c's bounds (10s floor, jitter, aggregate rate limit) so deployers' test rigs do not stampede the filesystem. Flavor-1 (`none`) has no KEK and therefore no revocation path; this is intentional and not a gap. R83b-1's mandate to invalidate cached entries on revocation detection applies to both flavor-2 (filesystem-polling-driven) and flavor-3 (KMS-polling-driven) — the cache invariant is flavor-independent.

R83b-2. Registry-side destruction (loss of the `WrappedDomainKek` entry while the upstream KMS-side KEK remains alive) is operationally distinct from KMS-side destruction. On `openDomain` for a `(tenantId, domainId)` where any DEK exists in the same shard but the wrapping `WrappedDomainKek` is absent, the operation must throw `RegistryStateException` (subclass of `IOException`, marker `RetryDisposition.NonRetryable` per R83-1) identifying the inconsistency.

Auto-provisioning a fresh domain KEK for `(tenantId, domainId)` is permitted only when **all three** of the following hold:

1. No DEK entry exists in any registry shard for that `(tenantId, domainId)`, AND
2. No live SSTable in the manifest records `(tenantId, domainId)` as its scope (per `sstable.footer-encryption-scope` R3a-c — the footer's scope tuple is the witness), AND
3. No unreplayed WAL segment for the tenant references a DEK in that domain (per the on-disk liveness counter from R75c).

The check must consult the **manifest and WAL**, not just the registry. If clause 1 is satisfied but clauses 2 or 3 are not, the registry has been destroyed while live data still depends on the destroyed wrapping KEK; auto-provisioning a fresh KEK in that case would silently shred the existing data and is forbidden. Throw `RegistryStateException` instead with the message identifying which clause was violated (e.g., `RegistryStateException: registry shard absent but manifest contains 47 SSTables under (tenantId=t, domainId=d) — recovery required`).

**Caching, scoping, and coalescing (P4-2).** The manifest+WAL scan is `O(SSTables) + O(WAL segments)` and on remote backends (S3, GCS) translates to `O(N)` LIST/HEAD/GET requests. Per-`openDomain` invocation of the unscoped scan is operationally infeasible. Implementations must:

- **Scope the WAL scan** to segments whose maximum-timestamp post-dates the most recent registry commit timestamp. Segments older than the registry's last commit are guaranteed not to reference DEKs in a now-empty registry. This prunes the typical N=50,000 segment scan to the recent slice.
- **Cache the negative result** as `auto-provision-permitted` per `(tenantId, domainId)` until invalidated. The cache is invalidated by the manifest's commit-completion hook (R37c) when a commit affects `(tenantId, domainId)`. The cache survives within a single `EncryptionKeyHolder` lifetime; restart re-runs the scan once.
- **Coalesce concurrent calls** for the same `(tenantId, domainId)` to a single in-flight scan via a per-`(tenantId, domainId)` future/promise; the second caller waits on the first's result. Implementations must not pay the scan cost N times for N concurrent callers.
- **Surface unbounded scan cost.** Implementations on backends with no segment-timestamp index (and therefore no way to scope the WAL scan) must either degrade gracefully via a configurable scan timeout (default 30s) — beyond which `RegistryStateException(JLSM-ENC-83B2-SCAN-TIMEOUT)` is thrown — or refuse `auto-provision` entirely on those backends, requiring operator-driven recovery via R83b-2a's `forceShredRegistry` API. Hanging on the scan is forbidden.

**Manifest-rebuild guard (P4-27).** Auto-provisioning is forbidden during any manifest-rebuild window. The implementation must detect manifest-rebuild state via a global flag (or equivalent durable marker — e.g., a `manifest-rebuild-in-progress` lock-file in the manifest directory) and refuse auto-provisioning while the flag is set, throwing `RegistryStateException(JLSM-ENC-83B2-MANIFEST-REBUILD)`. During manifest rebuild, the manifest's view of "live SSTables" is incomplete; clause 2 of R83b-2's auto-provision predicate could be trivially satisfied for a scope whose live SSTables haven't been re-registered yet. Auto-provisioning during this window would re-introduce the silent-crypto-shred attack the rest of R83b-2 prevents.

R83b-2a. The `RegistryStateException` thrown by R83b-2 must include a stable error code (e.g., `JLSM-ENC-83B2-REGISTRY-DESTROYED`, `JLSM-ENC-83B2-MANIFEST-REBUILD`, `JLSM-ENC-83B2-SCAN-TIMEOUT`) suitable for operator runbook lookups, in addition to its descriptive message. The implementation must additionally expose a `forceShredRegistry(TenantId, DomainId, RegistryShredConfirmation)` operator-only API that explicitly destroys all SSTables, WAL segments, and registry entries in the affected scope.

**`RegistryShredConfirmation` token discipline (P4-8, P4-23):**

The token must contain:

- The affected `(tenantId, domainId)` scope tuple — required, validated byte-for-byte against the API call.
- A timestamp within **5 minutes ± 30 seconds** of the API invocation — the 30-second grace handles modest clock skew across multi-process clusters but is not a relay window. The clock used for validation is the **single canonical clock of the process performing the destructive action** (NOT the token-generation process); multi-process token relay is unsupported. If the deployer's process generated a token on host A and the destructive action happens on host B, B's clock validation may reject the token if the inter-host skew exceeds 30 seconds — this is intentional defense against clock-relay attacks.
- A **16-byte cryptographically-random nonce** — required, validated as not-previously-consumed.

**Single-use enforcement.** The library must maintain a per-process bloom filter (or equivalent presence map) of recently-consumed token nonces. Each generated token has a unique nonce. On `forceShredRegistry` invocation, the nonce is checked against the consumed-set; if present, the call must throw `IllegalStateException(JLSM-ENC-83B2-TOKEN-REPLAY)`. The consumed-set must persist for at least the token TTL (5min30s) plus a safety margin (1h) so a replay attempt within the realistic clock-skew window is rejected. The consumed-set may be in-process (resets on restart) — restart resets the protection window to zero, which is acceptable because the token TTL is short (a stale token after restart is rejected by clock validation).

**Audit observability.** The API must emit a `forceRegistryShred` audit event via `KmsObserver` (`state-transition` category, durable per R76b-1) recording the `(tenantId, domainId)`, the operator identity (if available via the calling thread's security context), the elapsed-since-token-generation timestamp, and a confirmation of the shred's completion. Token replay attempts must additionally emit a `forceShredRegistryTokenReplay` event at WARN level for security observability.

Without this surface, deployers facing the registry-destroyed-while-data-live state would have no sanctioned recovery path; with this surface, the recovery path is explicitly auditable and replay-resistant.

R83c. Tenant-tier revocation (Tenant KEK destruction in flavor 3) must be detectable distinctly from domain-tier revocation. When the implementation observes a permanent unwrap failure for a domain KEK, it must additionally probe Tenant KEK usability via `KmsClient.isUsable(tenantKekRef)` before classifying the failure as domain-tier. If the Tenant KEK probe also fails permanently, the revocation is tenant-tier and the read path must throw `TenantKekRevokedException` (subclass of `KekRevokedException`) — even if other domain KEKs in the tenant are still cache-valid. This eager-probe upgrade prevents the same root-cause fault (Tenant KEK destruction) from being misclassified as a churn of domain-tier events as cached domain KEKs expire one by one. The probe result is itself cached for an interval equal to `min(polling cadence (R79) / 4, 5 minutes)` — defaulting to 3.75 minutes when polling cadence is 15 minutes, capping at 5 minutes for any longer cadence. The 5-minute cap bounds probe-result staleness independently of cadence, so long-cadence deployments do not lose Tenant-KEK-revocation detection latency. `TenantKekRevokedException` identifies the tenant scope but not specific `(domainId, tableId, dekVersion)` (the failure is upstream of any specific DEK).

R83c-1. The relationship between `TenantKekRevokedException` (R83c) and `TenantKekUnavailableException` (R76) is sequential: the **transitioning read** (the read whose failure causes the tenant to enter `failed` state) throws `TenantKekRevokedException`. **Subsequent reads** in the same tenant, while the tenant remains in `failed` state, throw `TenantKekUnavailableException` (R76's process-level state-machine signal). This ordering is observable to callers — a single root-cause fault produces one revocation-class exception followed by N unavailability-class exceptions, not a churn of revocations.

When multiple reads concurrently observe the N-th and (N+k)-th permanent failures during the transition to `failed`, exactly one read — atomically determined via the state machine's compare-and-set state-field operation with proper memory-fence semantics — is the "transitioning read" and throws `TenantKekRevokedException`; all others observe the post-transition state and throw `TenantKekUnavailableException`. The state-machine implementation must use `VarHandle` or `AtomicReference` semantics so the transition is observable as a single happens-before edge to all concurrent readers.

R83c-2. Eager Tenant-KEK probes per R83c must share the per-instance aggregate rate limit (R79c default 100 polls/sec) with scheduled polls. When the aggregate budget is exhausted, eager probes must be **coalesced per tenant**: multiple domain-tier failures observed within one detection epoch (R83a) trigger at most one Tenant-KEK probe per tenant. Probes deferred by the rate limit must not block the originating read — the read must propagate `KekRevokedException` (domain-tier subclass) using the most recent classification cached for that tenant (initially "domain-tier" until the first probe completes); the probe completes asynchronously and updates the classification cache for subsequent reads. This prevents a multi-tenant outage from amplifying through the eager-probe path into a self-inflicted KMS DDoS that would flap healthy tenants to `failed`.

**Probe-cache TTL vs detection-epoch orthogonality (P4-25).** R83c's probe-cache TTL bounds *cache freshness* — how long a cached classification is trusted before a fresh probe is needed. The detection epoch (R83a + R83c-2) bounds *probe coalescence* — how many probes per tenant are issued per fault batch. The two are orthogonal: cache TTL governs reads against the classification cache, epoch governs probe issuance. A cached classification observed within its TTL serves reads regardless of epoch boundaries; a probe issued within an epoch coalesces additional probe requests in the same epoch even if the cache TTL would otherwise have expired. Implementations must not conflate the two — for example, treating a cache-miss as an automatic probe-issue would defeat coalescing.

R83d. Convergence detection (R37) and revocation detection (R83) must not be conflated. When the implementation observes both convergence-eligibility (no live SSTable references the rotated-away version) and revocation (the wrapping KEK is permanently unusable) for the same `(scope, oldDekVersion)`, the revocation event is authoritative and the convergence event must be **suppressed**. The implementation must check the revocation status before emitting a convergence event for any `(scope, oldDekVersion)`; if revocation is concurrently or previously detected, emit only the revocation event with an additional flag `convergedAtRevocation=true` so consumers can distinguish "rotation completed normally" from "rotation completed because the old KEK was destroyed".

R83e. Implementations must not assume `KmsClient` plugins return correct plaintext on `UnwrapResult` success. The library must validate KMS-returned plaintext at the SPI boundary: (a) the returned `MemorySegment` must have the expected byte length for the wrapped key type (32 bytes for DEK, configured length for domain KEK); (b) any further integrity available via the wrap envelope (e.g., AES-KW round-trip self-check, AAD AEAD verification on a wrap-format that uses `EncryptionContext` as AAD per R80a) must be performed before the unwrapped material is cached or used. A misbehaving plugin that returns success-with-wrong-bytes must surface either as a mapped `KmsPermanentException` (for length / AAD validation failures) or via the AEAD failure path (R60); silent acceptance is forbidden. The validation logic resides in the library wrapper around `KmsClient`, not in the plugin itself, so all plugins inherit the protection.

R83e-1. Implementations must validate `wrapKek` outputs symmetrically with R83e's unwrap validation: after every `wrapKek(plaintext, ref, ctx)` call, the library must immediately call `unwrapKek(wrappedBytes, ref, ctx)` and verify byte-for-byte equality with the original plaintext **before persisting** the `wrappedBytes` to the registry. Mismatch must surface as `KmsPermanentException` with an explicit message identifying the plugin class and `wrap-roundtrip-failure` mode, and must NOT result in a registry write. The wrap-roundtrip cost is one extra KMS call per DEK creation — acceptable because DEK creation is rare relative to reads, and this is the only on-write defense against a malicious or buggy plugin that returns garbage `wrappedBytes` and corrupts the persistent registry. The plaintext copy used for the roundtrip comparison must be zeroed in a `finally` block immediately after the comparison completes (R16 zeroisation discipline applied at the SPI boundary).

**Atomicity vs `close()` (P4-5).** The wrap-roundtrip pair (`wrapKek` + `unwrapKek` + byte-equality verify + plaintext-copy zeroisation) and the subsequent registry persistence must be atomic with respect to `EncryptionKeyHolder.close()`, with the same discipline as R62a for `deriveFieldKey`: if `close()` is invoked while the roundtrip is executing, the roundtrip must either complete and persist (using the original plaintext copy and zeroing it in `finally`) or throw `IllegalStateException` *before* invoking the KMS — never produce a partial state where `wrapKek` has been called but the plaintext copy cannot be zeroed because the Arena is already released. R66 must wait for in-flight DEK-creation roundtrips to drain before zeroing the internal Arena, paralleling R62a's discipline for in-flight `deriveFieldKey` calls. This protects R66's "zero-before-release" invariant against a JVM-allocator-leak surface that would otherwise let plaintext key material persist on freed memory.

**Lock discipline (P4-22).** The wrap-roundtrip pair and equality verify must execute **outside** any registry shard lock (R34a). The persistence step — registering the verified `wrappedBytes` in the registry shard — is the only step that requires the shared lock. Implementations must structure DEK creation as: (1) acquire plaintext, (2) wrap+unwrap+verify+zero outside any lock, (3) acquire the shared shard lock per R34a, (4) persist the verified `wrappedBytes`, (5) release the lock. Holding the shard lock during the (potentially-network-bound) roundtrip would block concurrent DEK creation and KEK rotation in the same shard for the duration of two KMS round-trips, defeating R34a's concurrency model.

R83f. Throwing R83's exception (or any subclass) must leave no pooled or arena-backed resources unreleased. Read-path code that traverses the SSTable footer, acquires `ArenaBufferPool` buffers, or allocates from a per-read internal `Arena` must release/close those resources in a `finally` block before R83 propagates. The caller's externally-supplied `Arena` (per R9) is the caller's responsibility to close — the library does not close caller arenas — but no internal pool resource may survive the throw. Implementations must include a regression test that asserts pool occupancy is unchanged after N consecutive R83 throws (where N is at least the configured pool capacity).

R83g. Once a permanent revocation has been observed for a `(tenantId, domainId, tableId, dekVersion)` four-tuple within a process lifetime, all subsequent unwrap attempts for the same tuple must short-circuit-fail with R83 **without re-contacting the KMS**. The cached-revocation entry must be cleared only by (a) explicit operator-driven cache invalidation, or (b) successful `rekey` API completion that retires the affected scope. This prevents misclassified-transient errors from being recovered by caller-side retry loops, and ensures R83 is terminal at the *outcome* level, not just the *single-event* level. The cached-revocation entry must not persist across process restart (consistent with R83a's in-process scope).

R83g-1. Crash-resumed operations (R78c rekey resume) must follow R83's loud-fail discipline on every encountered DEK. Because R83g's revocation cache is in-process, a resumed rekey starts with an empty cache and would otherwise hit O(DEKs) wasted KMS calls re-detecting the same revocation across a damaged scope. To bound this:

- A resumed rekey that observes a permanent revocation on `oldKekRef` itself (the rekey's source KEK) must abort the resume with `IllegalStateException` rather than continuing per-shard. The rekey is operationally meaningless if the source KEK is destroyed; the operator must either restore the source KEK or invoke `forceShredRegistry` (R83b-2a) to discard the affected scope.
- A resumed rekey that observes revocation on a non-source domain KEK (e.g., a domain-tier KEK destroyed independently of the rekey) must surface as `DomainKekRevokedException` and **skip the affected DEK from the rekey**, recording the skip in the progress file's `permanently-skipped` set so subsequent retries do not repeat the wasted KMS call. After resume completes, the implementation must emit a `rekeyPermanentlySkipped` event via `KmsObserver` listing the skipped DEKs so the operator can investigate.
- The per-rekey-pair `permanently-skipped` set is durable (recorded in the progress file alongside `nextShardIndex`) so post-restart resumes inherit it. This set is cleared along with the progress file when the `rekey-complete` marker (R78f) is written.

**Per-tenant durable revoked-deks set (P4-9).** In addition to the per-rekey-pair `permanently-skipped` set, the implementation must maintain a **per-tenant durable `permanently-revoked-deks` set in the registry shard** (alongside the regular DEK entries — e.g., as a parallel list with the same atomic-commit primitive per R20). This set persists across rekey-pair boundaries: a DEK skipped in rekey N because its wrapping KEK was destroyed is also recorded in the tenant-scoped set, so rekey N+1 (using a fresh `oldKekRef → newerKekRef`) does not re-discover the same revocation by attempting to wrap the skipped DEK and observing the destruction again. Without this set, each successive rekey pays O(revoked-DEKs) wasted KMS calls re-discovering the same revocation.

The per-tenant `permanently-revoked-deks` set is cleared only by `forceShredRegistry` (R83b-2a) for the affected scope, or by an operator-driven cache invalidation API (parallel to R83g's clearing conditions for the in-process cache).

**Bounds (P4-28).** Both the per-rekey-pair `permanently-skipped` set and the per-tenant durable `permanently-revoked-deks` set must be **bounded** (default 10,000 entries per tenant). When the bound is exceeded:

- (a) Emit a `permanentlySkippedSetOverflow` event via `KmsObserver` (`state-transition` category, durable per R76b-1) recording the bound, the set size, and the affected tenant.
- (b) Transition the tenant to `failed` state (R76) — a tenant accumulating >10K permanently-revoked DEKs has experienced a structural fault that requires operator investigation.
- (c) Reject further DEK creation for the tenant until the operator invokes `forceShredRegistry` (R83b-2a) to discard the affected scope or otherwise reduces the set size below the bound.

The bound prevents pathological tenants from inflating the durable set unboundedly, which would balloon registry shard size and resume-time scan cost without bound.

R83h. The `KekRevokedException` and its subclasses must enforce confidentiality via **two distinct exception instances** constructed at the read-path failure boundary:

- A **tenant-visible instance** for surfaces that may be exposed to the tenant (e.g., multi-tenant SaaS error responses). This instance has `setCause(null)` (no chained cause), and its `getMessage()` includes only `tenantId` and an opaque correlation id; `dekVersion`, `domainId`, `tableId`, and any provider-specific metadata are redacted. The opaque correlation id maps (deployer-side) to the audit channel where the full diagnostic record lives.
- A **deployer-internal instance** for the named-logger `jlsm.encryption.scope`. This instance retains the full scope tuple `(tenantId, domainId, tableId, dekVersion)` in its `getMessage()` and may chain the underlying KMS plugin exception via `getCause()` for stack-trace and SDK-field diagnostics.

Implementations must construct the two instances at the moment of failure, before either reaches a logger, exception serializer, or RPC boundary. **Emitting one instance to both surfaces and relying on string sanitization is not sufficient defense** against reflective serializers (Jackson, Gson, Logback `ThrowableProxyConverter` with custom converters) that walk all exception fields beyond `getMessage()` — modern Java exception types from KMS SDKs include fields like `getResponseHeaders() : Map<String,String>`, `getRequestId() : String`, `getStatusCode() : int`, `getCause() : Throwable`, etc., and reflective walkers serialize all of them. The split-instance design ensures the tenant-visible instance has no diagnostic payload to leak.

The exception's `getStackTrace()` may retain provider stack frames on the deployer-internal instance only (these do not contain key material in standard JDK / AWS SDK / Vault stacks). The tenant-visible instance must have its stack trace truncated to the public API entry point (e.g., `EncryptionKeyHolder.deriveFieldKey`, `EncryptionKeyHolder.openDomain`, `EncryptionKeyHolder.rekey`, and shallower) — internal frames are stripped via `setStackTrace(...)` before throwing.

**Centralised factory (P4-13).** Implementations must centralise the dual-instance construction in a single factory, e.g., `R83ExceptionFactory.tenantAndDeployer(tenantId, ScopeTuple, KmsCause)` returning a record `(TenantVisibleException tenant, DeployerInternalException deployer)`. R83 throw sites must construct via the factory only — direct `new TenantKekRevokedException(...)` or `new DomainKekRevokedException(...)` call sites must be banned by static analysis (e.g., a build-time check via ArchUnit or an equivalent static rule on the test classpath). This eliminates the maintenance trap where a future code path forgets to construct one of the two instances; the factory enforces dual construction at every throw site by construction.

The factory must additionally handle stack-trace truncation, opaque correlation-id minting, KMS cause sanitisation (per the bullet list above), and `KekRefDescriptor` transformation (R83i-1) — all in one place. Adding a new R83 throw site requires only a factory call; adding a new R83 subclass requires a factory method extension. This centralisation also makes the security-critical confidentiality logic auditable in a single source location.

R83i. Neither the tenant-visible nor the deployer-internal exception instance (R83h) may include any byte-form of `wrappedBytes`, `MemorySegment` plaintext, or HMAC digest of key material in `getMessage()`, `getCause().getMessage()`, or any structured-payload field exposed to consumers. `KekRef` values must use the opaque `KekRefDescriptor` form per R83i-1 below.

R83i-1. `KekRef` instances passed into R78g event payloads, R83 exception payloads (deployer-internal **and** tenant-visible), and any other observable surface must be transformed to a `KekRefDescriptor` value containing only:

- A 16-byte hash prefix of the canonical `KekRef.toString()`, computed as HMAC-SHA256 keyed by a deployer-configured secret to prevent rainbow-table reverse-lookups.
- The KMS provider class name (`AwsKms`, `GcpKms`, `Vault`, `LocalKms`).
- A non-confidential region/zone tag (e.g., `us-east-1`) when the plugin can supply one without leaking account context.

The `KmsClient` SPI must expose `KekRefDescriptor describeKekRef(KekRef ref)` defaulting to the hash-prefix form; plugin authors may override to provide richer descriptors that conform to the no-byte-fingerprint clause of R83i. The library must never log raw `KekRef.toString()` in observability or exception surfaces — `describeKekRef` is the only sanctioned path. This avoids both (a) leaking AWS account context via raw ARNs, and (b) breaking binary compatibility with existing callers that may rely on `KekRef.toString()` for their own logging — `KekRef.toString()` retains its current format for direct caller use; only library-emitted observability and exceptions are constrained to use `KekRefDescriptor`.

R83i-2. **Deployer-secret discipline (P4-7).** The deployer-configured secret used for `KekRefDescriptor` HMAC keying must satisfy:

- **Cluster-wide consistency.** All jlsm processes serving the same tenants must use the same secret so the same `KekRef` produces the same descriptor across processes; otherwise cross-process correlation in the deployer's observability pipeline breaks. Implementations that can detect inconsistency (e.g., via a configuration service that rejects mismatch) must reject construction with `IllegalStateException(JLSM-ENC-83I2-SECRET-MISMATCH)`. Implementations without cross-process visibility must surface a WARN log at construction so deployers configure the secret consistently.
- **Storage context** equivalent to R79c-1's `deployerInstanceId` — both are deployer secrets whose compromise weakens privacy properties; co-location in a deployer-managed secret store is recommended.
- **Rotation discipline.** Rotating the secret breaks descriptor stability — historical descriptors emitted under the old secret cannot be matched against descriptors emitted under the new secret. Rotation is permitted but requires deployer-driven re-emission of historical descriptors via a shared correlation table maintained outside jlsm (the deployer records `(old-descriptor, KekRef, new-descriptor)` triples during the rotation window). The library does not maintain this table — it is operator policy. Rotation events must emit a `kekRefDescriptorSecretRotated` audit event via `KmsObserver` (state-transition category) so deployers can mark the rotation boundary in observability pipelines.
- **No persisted descriptors.** The library must not persist `KekRefDescriptor` values to disk (registry shards, progress files, etc.) under any circumstance. Descriptors are observability ephemera; persisting them would make rotation a multi-stage data migration. All persistent artifacts use raw `KekRef` (untransformed) which is shielded from observability surfaces by the `describeKekRef` discipline above.

#### Read-path failure type matrix

The encryption read path throws four distinct exception families. The matrix below specifies precedence (when multiple apply, surface the higher-precedence one first), the declared parent type (so callers can construct correct `catch` chains), and ownership.

All four families implement the sealed marker interface `RetryDisposition.NonRetryable` (R83-1) so operator alarm code may write `catch (RetryDisposition.NonRetryable e) { alert(); }` and pick up every non-retryable read-path failure regardless of the concrete parent type. Finer discrimination uses the per-family parent.

| Exception | Defining requirement | Parent type | Marker | Meaning | Tenant scope? |
|---|---|---|---|---|---|
| `TenantKekUnavailableException` | R76 (state machine) | `IOException` | `RetryDisposition.NonRetryable` | Tenant in `grace-read-only` or `failed` state — process-level state-machine signal, applies tenant-wide regardless of which DEK is being read | Tenant-wide |
| `KekRevokedException` (and subclasses `TenantKekRevokedException`, `DomainKekRevokedException`) | R83 / R83b / R83c | `KmsPermanentException` (which extends `IOException`) | `RetryDisposition.NonRetryable` | Per-DEK or per-tenant upstream KMS revocation — fault originated at the KMS layer | Per-DEK or per-tenant |
| `RegistryStateException` | R83b-2 / R83b-2a | `IOException` | `RetryDisposition.NonRetryable` | Registry-side inconsistency (wrapped entry missing while DEKs reference it, manifest/WAL still references the destroyed scope) — requires operator intervention via `forceShredRegistry` or backup restore | Per-`(tenant, domain)` |
| `DekNotFoundException` | R57 | `IOException` | `RetryDisposition.NonRetryable` | DEK version not in the registry — caller error or stale client view of the registry | Per-DEK |

All four exceptions extend `IOException` (i.e. checked exceptions) — the encryption read API surface already declares `IOException`, so the checked discipline is paid uniformly.

Precedence (highest first):
1. `TenantKekUnavailableException` — if the tenant is already in `failed` or `grace-read-only` (write path), surface that; do not probe the KMS further.
2. `KekRevokedException` — per-DEK or per-tenant revocation-class fault from the KMS.
3. `RegistryStateException` — inconsistent registry state from a partially-recovered or operator-altered shard.
4. `DekNotFoundException` — registry pruning normal lifecycle, or a caller-side stale view.

Implementations must check higher-precedence conditions before invoking the unwrap path that could throw lower-precedence exceptions.

**Footnote (P4-16) — concurrent-class transitions.** Precedence applies to a single read's *observation point*. When concurrent reads observe the same root-cause fault and the atomic CAS in R83c-1 selects exactly one as the transitioning read, the matrix's precedence is overridden for non-transitioning concurrent reads: those reads observe the post-transition state and throw `TenantKekUnavailableException` (the higher-precedence type) even though the root-cause was a `TenantKekRevokedException` event. This is intentional — only the transitioning read carries the revocation-class signal; subsequent observers see the state-machine signal. The matrix precedence describes the discrimination order at a single point in time, not the ordering across a fault's full propagation.

### Rekey API (flavor 3)

R78. `EncryptionKeyHolder` must expose a `rekey(TenantId, KekRef oldKekRef, KekRef newKekRef, RekeySentinel proofOfControl, ContinuationToken token)` API per `tenant-key-revocation-and-external-rotation` ADR.

R78a. `proofOfControl` must contain: a nonce-bound plaintext wrapped under `oldKekRef` (the "old sentinel"), the same nonce wrapped under `newKekRef` (the "new sentinel"), and a timestamp. jlsm must verify both operations by **independently invoking `KmsClient.unwrapKek(oldSentinel, oldKekRef, ctx)` AND `KmsClient.unwrapKek(newSentinel, newKekRef, ctx)` — comparing the unwrapped nonce bytes for byte-for-byte equality**. A structural inspection of the provided wrapped bytes without an actual KMS unwrap call is insufficient; both unwraps are required. Freshness window is 5 minutes maximum (timestamp must be within `now - 5min` and `now`).

R78b. The rekey operation must be streaming and paginated: a single invocation processes a bounded batch of domains (default 100, configurable) and returns a `ContinuationToken`. Callers iterate until the token is null.

R78c. Rekey execution must be resumable across crashes. A per-tenant rekey-progress file records `{oldKekRef, newKekRef, nextShardIndex, startedAt}` and is updated after each shard commit. A crashed rekey resumes at the next uncompleted shard. Stale progress files (>24h) must emit an observable event.

R78c-1. The rekey-progress file and any temporary files created during its atomic updates must be created with owner-only permissions (0600 on POSIX systems, equivalent on other platforms). Although `oldKekRef` and `newKekRef` are references (ARNs / paths / IDs) rather than key material, they identify KMS resources that a reader could probe to infer tenant-specific key-management topology; permission discipline matches R70a for registry shards.

R78d. During an in-progress rekey, each affected registry shard carries dual-reference entries `(kekRef, wrappedBlob)`: the existing entry under `oldKekRef` plus a new entry under `newKekRef`. Reads prefer `newKekRef`, falling back to `oldKekRef` if unwrap fails (shard not yet migrated). Writes always use `newKekRef` once rekey has started for the tenant.

R78e. Rekey completes when **BOTH** of the following are true:

1. **Registry migration**: every registry shard for the tenant carries `newKekRef` entries only (no `oldKekRef` entries remain); AND
2. **On-disk liveness witness**: no live SSTable in the manifest and no unreplayed WAL segment for the tenant references a DEK whose wrapping chain transitively depends on `oldKekRef`. "Transitively depends" means: the DEK's wrapping entry in the registry at the time that SSTable or WAL was written recorded a domain KEK that was itself wrapped under `oldKekRef`.

The implementation must track the on-disk liveness witness by maintaining, for each tenant, a monotonic counter of SSTables and WAL segments whose wrapping chain depends on `oldKekRef`; the counter decrements on SSTable compaction completion and WAL segment retirement. R78e is satisfied only when the counter reaches zero.

Before R78e is satisfied, the `rekey` API must not return a null `continuationToken`. Instead it must return a `continuationToken` with an explicit "awaiting on-disk witness" marker so the caller polls to completion. Once R78e is satisfied, the API returns null, and only then may the `oldKekRef` entries be garbage-collected from the registry shard and the tenant operator may disable/delete the old KEK in the tenant's KMS.

R78f. Rekey completion must be recorded atomically via a `rekey-complete` marker written to the tenant's shard (via the same atomic commit mechanism per R20). The marker records `{completedKekRef, timestamp}`. On crash recovery, the presence of the marker means rekey is complete for that KekRef; its absence (even when all shards appear migrated) means the on-disk witness was never confirmed and rekey remains incomplete. The marker makes the "rekey complete" transition idempotent and crash-safe; retries of R78e after recovery must be O(1) registry reads, not a full rescan.

R78g. Rekey progress must be observable via the `KmsObserver` interface (per `kms-integration-model` ADR R93) — `KmsObserver` is the canonical observability plane and is mandatory. Implementations may *additionally* emit structured log events as a deployer convenience, but `KmsObserver` is required so deployers wiring observability against the canonical plane receive all R78g events from any compliant implementation. The events emitted are:

- `rekeyStarted` — emitted on initial rekey invocation (the first call with a non-null `oldKekRef`/`newKekRef` for that tenant when no `rekey-complete` marker exists for the pair).
- `rekeyResumed` — emitted on crash recovery resumption (R78c). Distinct from `rekeyStarted` so consumers can distinguish a fresh rekey from a resumed one. Re-emitting `rekeyStarted` on resume is forbidden.
- `rekeyShardCommitted` — emitted on each per-shard commit completion. Carries `shardIndex` and `totalShards`. For shards already completed in a prior run (per R78c's progress file), this event must not be re-emitted on resume.
- `rekeyWitnessProgress` — emitted three times during on-disk witness counter draining (R78e): at start (`witnessCount=initialCount`), at midpoint (`witnessCount = floor(initialCount/2)` where `initialCount` is the value at rekey start; subsequent counter increments do not retrigger the midpoint event), and at zero. The `initialCount` field is the snapshot taken at rekey start.
- `rekeyCompleted` — emitted when both R78e clauses are satisfied and the `rekey-complete` marker (R78f) is written. Carries the final `tenantId`, `oldKekRef`, `newKekRef`.

Each event must include `tenantId`, `oldKekRef` (as `KekRefDescriptor` per R83i-1), `newKekRef` (same), a per-tenant monotonic `rekeyEpoch` (incremented at every fresh rekey start; stable across resume so consumers can group resumed events with their initiating epoch), and a `(tenantId, rekeyEpoch)`-scoped `eventSeq` that is strictly monotonic and gap-free.

`eventSeq` starts at 0 on `rekeyStarted`. `rekeyResumed` does NOT reset `eventSeq` — it resumes from `lastEmittedEventSeq + 1` (where `lastEmittedEventSeq` is read from the rekey-progress file per R78c). Subsequent shard-commit and witness-progress events continue monotonically. Consumers must treat `rekeyResumed` as a continuation marker, not a reset. The progress file's `lastEmittedEventSeq` must only advance after the corresponding event has been delivered to the `KmsObserver` (assume a synchronous observer for spec purposes); on resume, only events with `seq ≤ lastEmittedEventSeq` are suppressed and emission resumes from `lastEmittedEventSeq + 1`.

Partial-shard commits (where a shard's bytes were partially written before crash but the atomic rename did not complete) are NOT considered "completed" for R78g event-suppression purposes; the next attempt's `rekeyShardCommitted(shardIndex=N)` event uses a fresh `eventSeq` (gap-free continuation from `lastEmittedEventSeq + 1`).

R78g-1. A consumer observing a `rekeyCompleted` event for a `(tenantId, rekeyEpoch)` pair must treat it as authoritative-and-final only if it has also observed an `eventSeq < final` event of type `rekeyWitnessProgress` with `witnessCount=0` for the same `rekeyEpoch`. An out-of-order `rekeyCompleted` arrival must be rejected by the consumer. Implementations are not responsible for enforcing consumer behaviour, but the spec contract on event ordering enables correct consumer-side validation.

R78g-2. At most one rekey may be in-flight per tenant at any time. The in-flight rekey is identified by `(tenantId, oldKekRef, newKekRef)` and the persistent progress file (R78c). The library must distinguish two call shapes:

- **Start new rekey** — `rekey(tenantId, oldKekRef, newKekRef, sentinel, continuationToken=null)`. Must throw `IllegalStateException(rekeyEpoch=N)` if a progress file already exists for the same tenant whose `(oldKekRef, newKekRef)` pair differs OR whose `(oldKekRef, newKekRef)` pair matches but is incomplete (no `rekey-complete` marker per R78f). Otherwise emits `rekeyStarted` with a freshly-incremented `rekeyEpoch`.
- **Resume in-flight rekey** — `rekey(tenantId, oldKekRef, newKekRef, sentinel, continuationToken=non-null)`. Must succeed only if (a) a progress file exists for the same `(tenantId, oldKekRef, newKekRef)`, (b) the continuation token's recorded `rekeyEpoch` matches the progress file's recorded `rekeyEpoch`, AND (c) no `rekey-complete` marker exists for that pair. Mismatched continuation tokens (epoch drift, refs mismatch, completion-marker present) must throw `IllegalArgumentException` identifying the specific mismatch. Successful resume emits `rekeyResumed` (NOT `rekeyStarted`).

This eliminates concurrent-epoch event interleaving while permitting crash-resume per R78c.

R78g-3. **(Subsumed by R83i-1.)** `KekRef` values included in any R78g event payload must use the `KekRefDescriptor` form per R83i-1; raw `KekRef.toString()` is forbidden in observability surfaces.

### Opt-in polling (flavor 3)

R79. Per-tenant opt-in polling may be enabled by the deployer; when enabled, the polling loop invokes `KmsClient.isUsable(tenantKekRef)` on a configurable cadence (default 15 minutes) and updates the tenant's state machine (R76) based on the result.

R79a. Polling failure classification must follow the same permanent-vs-transient rules as R76a. Transient failures do not count toward N; permanent failures do.

R79b. Polling must be per-tenant — one tenant's polling load must not affect other tenants' KMS quota or jlsm's thread pool capacity.

R79c. Polling cadence must enforce the following bounds:

- **Per-tenant minimum interval:** 30 seconds default; configurable by the deployer; **hard floor 10 seconds**. Configurations below the floor must be rejected at `EncryptionKeyHolder` construction with `IllegalArgumentException` identifying the offending tenant and the requested cadence. The IAE must also be logged at ERROR level to the deployer-named logger `jlsm.encryption.config` so that frameworks that swallow the IAE (e.g., generic Spring exception handling) do not silently mask the misconfiguration. The 10-second floor is a hard ceiling — implementations must not provide system-property, environment-variable, or other escape hatches that allow per-tenant cadence below 10s; changing the floor itself requires a spec amendment.
- **Per-tenant phase jitter:** the first poll for each tenant must occur at a wall-clock offset within `[0, cadence)`. Subsequent polls then proceed at the configured cadence. This prevents thundering-herd alignment across tenants on the same KMS endpoint.
- **Per-instance aggregate rate limit:** total polls per second across all tenants on a single `EncryptionKeyHolder` instance must not exceed a configurable limit (default 100 polls/sec). When the limit would be exceeded, polls must be deferred (not dropped) — deferred polls do not violate the per-tenant minimum because the floor is a *minimum interval*, not a *maximum staleness* guarantee. The aggregate rate limit also covers eager Tenant-KEK probes per R83c-2 so reactive-probe bursts do not amplify around it.

Rationale: per-tenant minimum alone does not prevent burst storms when many tenants align cadence; jitter + aggregate rate limit together bound KMS quota consumption to the deployer's allocation.

R79c-1. The jitter offset for each tenant must be **deterministic** given the tenant identity and the deployer instance: `offset = HMAC-SHA256(deployerInstanceId, tenantId)[0..3] mod cadenceMillis`. `deployerInstanceId` is a deployer-configured value that ensures different deployer instances draw different offsets but the same instance draws the same offset for a given tenant across `EncryptionKeyHolder` recreations. Naive uniform-random draws (re-rolled on every holder construction) produce fresh alignment opportunities under high recreation rate (Spring `@RefreshScope`, hot-reload deployments) and are forbidden — the jitter must be stable across the deployer's lifetime.

`deployerInstanceId` discipline (from P4-12 — defends against tenant-correlation side-channel attacks):

- **Entropy:** at least 256 bits. Short identifiers (UUIDs at 122 bits, Kubernetes pod UIDs at 122 bits) must be combined with a 256-bit secret salt before use as the HMAC key (e.g., `HMAC-SHA256(secret-salt, podUid)` materialises the effective `deployerInstanceId`). Using a raw UUID is forbidden — an attacker who learns the UUID via observability/log correlation could predict every tenant's jitter offset, defeating the thundering-herd defence and enabling timing-correlation between observed traffic and tenants.
- **Storage context:** the `deployerInstanceId` must be stored in the same security context as the `KekRefDescriptor` HMAC secret (R83i-2) — both are deployer secrets whose compromise weakens privacy properties. Co-rotation is recommended; rotating one without the other is permitted but must be documented as an explicit choice (rotating the jitter key alone changes scheduling timing globally; rotating the descriptor key alone breaks descriptor stability per R83i-2).
- **Persistence:** durable across process restart (e.g., written to a deployer-managed secret at provisioning) so jitter remains stable across the deployer's lifetime per the requirement above.

R79e. **Aggregate polling cost.** The total polling cost for an `EncryptionKeyHolder` instance is the sum of all polling sources, all subject to R79c's per-instance rate limit (default 100 polls/sec, 8.6M/day):

- **Flavor-3 KMS polls (R79d)** — `tenantsCount * 96` calls/day at default 15min cadence.
- **Flavor-2 filesystem polls (R83b-1b)** — `tenantsCount * 96` filesystem `stat` calls/day at default 15min cadence (negligible cost vs network polls).
- **Eager Tenant-KEK probes (R83c-2)** — bursty; coalesced per tenant per detection epoch; bounded by R79c's aggregate rate limit.
- **R37b convergence-detection internal polls** — bounded `[100ms, 60s]`; default `min(60s, manifest-cycle/10)`; skipped when R37c's manifest commit-completion hook is wired.

Implementations must expose an aggregate-polling-budget metric per `EncryptionKeyHolder` instance (e.g., `pollsScheduledPerSec`, `pollsDeferredPerSec`) so deployers can size KMS quotas accurately. At the default settings on a 100K-tenant deployment, polling pressure approaches AWS KMS's 5500 ops/sec per-region default quota; deployers operating near this scale must either request a quota increase, increase polling cadence, opt out per R79d, or shard tenants across multiple holder instances.

R79d. Opt-in polling **must be enabled by default for flavor-3 tenants** (per F-32 arbitration: the `dek-revocation-vs-rotation` KB position that pull-based revocation detection alone is insufficient unless polling is on the read-path-revocation-detection critical path). Deployers may opt out by explicit configuration; opt-out must emit a WARN-level log to `jlsm.encryption.config` with the rationale that revocation latency is then bounded only by cache TTL (R91, up to 24h) — there is no other revocation-detection path. Webhook-based KMS push notification is a future ADR (`tenant-key-revocation-and-external-rotation` deferred sub-decisions) and is not part of v11.

**Polling cost note.** At the default 15-minute cadence, polling generates `tenantsCount * 96` KMS calls per day per `EncryptionKeyHolder` instance. AWS KMS, GCP Cloud KMS, and Vault Enterprise charge per-API-call; a 100K-tenant deployment generates ~10M polls/day. Deployers must size KMS quotas accordingly. The R79d-default-on choice is a security-over-cost tradeoff (per F-32 arbitration). Cost-sensitive deployers may opt out via the documented configuration flag with the understanding that revocation latency becomes cache-TTL bounded.

### KmsClient SPI contract (normative)

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
