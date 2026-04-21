---
{
  "id": "encryption.primitives-lifecycle",
  "version": 5,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [
    "encryption"
  ],
  "requires": [
    "encryption.primitives-variants",
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

R10a. The HKDF salt must be configurable via EncryptionKeyHolder's constructor. The default all-zero salt is acceptable for single-deployment scenarios. For multi-tenant or multi-deployment environments, the caller should provide a unique salt (e.g., a deployment identifier hash). When provided, the salt replaces the all-zero default in the extract step.

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

R16b. The per-field derived key (from HKDF) serves as the data encryption key for that field. In the envelope encryption model (R17–R21 as amended by R71–R85), the DEK resolved from the DekHandle is used as the input keying material to HKDF. When the DEK rotates, all per-field derived keys change because the IKM and the `dekVersion` info component both change. Callers provide a KmsClient (R86+) that unwraps the tenant KEK, which unwraps the domain KEK, which unwraps the DEK; the unwrapped DEK is then used as the HKDF master key input.

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

R22. Every encrypted field value must be prefixed with a 4-byte big-endian DEK version tag in plaintext. The version tag alone does not carry tenant or domain identity — the reader derives `(tenantId, domainId, tableId)` from the SSTable footer metadata (R23a). Cross-scope reads must be rejected (R22b). The ciphertext layout per encryption variant is:

- `[4B DEK version | 12B IV/nonce | encrypted payload | 16B GCM auth tag]` for AES-GCM (Opaque). The GCM tag provides inline authentication.
- `[4B DEK version | 16B synthetic IV | ciphertext]` for AES-SIV (Deterministic). The S2V synthetic IV provides inline authentication.
- `[4B DEK version | 1B length prefix | 8B OPE ciphertext long | 16B detached HMAC-SHA256 tag]` for OPE (OrderPreserving) — total 29 bytes. The 16-byte detached tag is computed per F03.R78 and binds the OPE ciphertext to the UTF-8 field name and the DEK version. MAC verification must run before the OPE inverse.
- `[4B DEK version | 8B perturbation seed | N*4B encrypted floats | 16B detached HMAC-SHA256 tag]` for DCPE (DistancePreserving) — total `8 + N*4 + 20` bytes (4 version + 8 seed + 4N ciphertext + 16 tag). The 16-byte detached tag is computed per F03.R79 and binds the seed and encrypted vector to the UTF-8 field name and the DEK version. MAC verification must run before the DCPE inverse.

Detached MAC tags for OPE and DCPE close the authenticity gap identified in F03 v1 where wrong-key decryption produced plausible but incorrect values. Tags for GCM and SIV are inherent to those schemes and require no additional wrapping.

R22a. The 4-byte version tag must contain a positive integer (1 or greater). If the reader encounters a version tag of 0 or a negative value (when interpreted as signed big-endian int), it must throw IOException indicating corrupt ciphertext. This applies to both normal reads and compaction re-encryption reads. The version-0/negative check must be performed as part of the same lookup path as the hash map check (R64) — the implementation must not branch to a separate error path before performing the map lookup. Version 0 is never inserted into the map, so both version-0 and version-not-found follow the same code path, preserving the constant-time property of R64.

R22b. The reader must resolve the DEK by the tuple `(tenantId, domainId, tableId, dekVersion)` where the first three components are derived from the SSTable's footer metadata (R23a) and the fourth from the ciphertext's version tag. If the SSTable's declared `(tenantId, domainId, tableId)` does not match the reader's expected scope (i.e., the SSTable was mis-routed to a different tenant's or domain's read path), decryption must throw IllegalStateException before any DEK lookup. This enforces per-tenant isolation at the read boundary.

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

R32a. **Tenant KEK rotation** (tier 1, flavor 3: tenant-driven via `rekey` API from `tenant-key-revocation-and-external-rotation` ADR): on invocation, re-wraps the tenant's domain KEKs under the new Tenant KEK in streaming per-shard batches (R85). Does not re-wrap DEKs (tier 3) or touch data on disk. Domain KEK blobs remain valid under both old and new Tenant KEK during the migration window (dual-reference per R84).

R32b. **Domain KEK rotation** (tier 2): on invocation, unwraps the domain KEK from the Tenant KEK, generates a fresh domain KEK, re-wraps every DEK within that domain under the new domain KEK, and persists the updated shard. DEK cipher material on disk is not touched. This is O(DEKs-in-domain), bounded by the domain's size.

R33. After any tier rotation, the retired reference must be added to a retired-references set in the registry. The retired reference must not be deleted from the KMS or zeroized in memory until the rewrap cascade has completed for at least one access cycle — old wrapped entries require the old reference to unwrap during the migration window.

R33a. For Tenant KEK rotation (flavor 3), the retired-reference retention window must exceed WAL retention per the grace-period invariant from `three-tier-key-hierarchy` ADR. Otherwise, unreplayed WAL segments that were encrypted under the old domain KEK (which was wrapped under the old Tenant KEK) become undecryptable.

R34. Rotation at any tier must be atomic with respect to the per-tenant registry shard it writes to: either all wrapped entries at that shard are updated, or none. A crash during rotation must leave the shard in a consistent state with the previous state still usable.

R34a. Rotation must hold an exclusive lock on the specific registry shard being modified for the duration of the shard-scoped update. Concurrent DEK creation within the same shard must acquire a shared lock; rotation must acquire an exclusive lock. Rotations in different tenants' shards must not lock each other (per-tenant isolation).

### Dual-read during rotation window

R35. During a key rotation window (when SSTables contain ciphertext under both old and new DEK versions, or wrapped entries exist under both old and new KEK references), every read must be able to decrypt using any DEK version and unwrap via either retired or current KEK reference present in the registry. The reader must not assume all ciphertext uses the current DEK.

R36. DET-encrypted indexed fields must be marked as "rotation-pending" when a new DEK version is activated and the previous version is still referenced by live SSTables. During the rotation-pending window, queries on DET/OPE-indexed encrypted fields may return incomplete results. This limitation must be documented on the rotation API.

R37. After rotation converges (no live SSTables reference old DEK versions within the scope), DET/OPE indices affected by the rotation must be rebuilt. The library must provide a mechanism to detect convergence and trigger index rebuild.

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

### Tenant encryption flavors

R71. The encryption system must support three tenant encryption flavors per `three-tier-key-hierarchy` ADR:

- **`none`** — no encryption; no `EncryptionKeyHolder` is constructed for tables with no encrypted fields. Fields whose `EncryptionSpec` is the `none` variant pass through unencrypted regardless of flavor.
- **`local`** — `EncryptionKeyHolder` composes `LocalKmsClient`, a jlsm-shipped reference `KmsClient` implementation backed by filesystem key material with OS-enforced owner-only permissions. **Documented insecure for production** — supports rotation mechanics for test rigour but provides no HSM, no audit trail, no hardware-protected keys.
- **`external`** — `EncryptionKeyHolder` composes a third-party `KmsClient` plugin (AWS KMS / GCP KMS / Vault / KMIP / custom). Tenant KEK lives in the tenant's KMS; jlsm never materialises it persistently.

R71a. The flavor selection is per-tenant. A single jlsm deployment may serve tenants with different flavors simultaneously; jlsm's internal code paths branch only on the `KmsClient` implementation behavior, not on the flavor label.

R71b. The `LocalKmsClient` implementation must be annotated `@ApiStatus.Experimental` (or equivalent) and its Javadoc must state that it is not for production.

### Encrypt-once-at-ingress invariant

R72. Plaintext for encryptable field values must be bounded to the ingress window per `three-tier-key-hierarchy` ADR: from client submission through per-field encryption completion. After the per-field ciphertext is produced, the plaintext Arena must be closed (zeroizing the plaintext). No plaintext of encryptable field values may live in MemTable, WAL, or SSTable storage.

R73. MemTable must hold per-field ciphertext produced at ingress, not plaintext. Queries on encrypted fields must decrypt the per-field ciphertext on read. This diverges from the common LSM pattern where MemTable holds plaintext.

R74. Per-field ciphertext produced at ingress must be **reused unchanged** through WAL → MemTable → SSTable boundaries. The DEK used at ingress (the table's current DEK at ingress time) must be the same DEK whose version tag appears in the resulting SSTable. No decrypt-then-re-encrypt occurs on flush.

R74a. Primary-key fields remain **plaintext** in all storage (MemTable, WAL, SSTable). This preserves sort order and range-scan semantics. Non-primary-key encrypted fields rely on the OPE / DET / DCPE / Opaque variants from `primitives-variants` to support query operations within their documented leakage profile.

R74b. The WAL record envelope must cover only metadata (schema ref, opcode, timestamps) — encrypted field payload bytes in WAL records must be the same per-field ciphertext that flushes to SSTable. No additional encryption layer wraps the already-ciphertext field payload.

### WAL encryption mapping

R75. For WAL encryption per F42 (`wal.encryption`), each tenant carries a synthetic **`_wal` data domain** per `three-tier-key-hierarchy` ADR. WAL metadata-envelope ciphertext is encrypted under a DEK belonging to the `_wal` domain; field payload bytes embedded in WAL records are the per-field ciphertext already produced at ingress (R74b) and are not encrypted again by the WAL envelope.

R75a. F42's "KEK" input parameter at WAL builder construction must resolve internally to the tenant's `_wal` domain DEK-resolver. No F42 spec amendment is required; the mapping is an implementation-level resolution documented in this spec and in a Verification Note on `wal.encryption`.

R75b. Retiring a retired Tenant KEK (R33a) must not precede the replay or compaction of WAL segments encrypted under the `_wal` domain's DEKs whose wrapping chain depends on the retired Tenant KEK. This is the grace-period invariant from `three-tier-key-hierarchy` ADR enforced at WAL retention.

### Three-state failure machine (flavor 3)

R76. Per `tenant-key-revocation-and-external-rotation` ADR, each tenant under flavor 3 must have one of three operational states, tracked per-tenant in the `EncryptionKeyHolder`:

- **`healthy`** — Tenant KEK unwrap operations succeed; all reads and writes proceed normally.
- **`grace-read-only`** — N consecutive permanent-class unwrap failures (default N=5, configurable) with jittered exponential backoff (per `kms-integration-model` R89). Writes are rejected with `TenantKekUnavailableException`; reads continue using cached domain KEKs for their remaining TTL (R91).
- **`failed`** — Grace window exhausts (default 1 hour, configurable), OR all cached domain KEKs have TTL-expired. Reads and writes both rejected until the tenant rekeys to a usable Tenant KEK.

R76a. Permanent-class KMS errors (AccessDenied, KeyDisabled, KeyNotFound) count toward N. Transient-class errors (throttling, timeout, 5xx) do not count toward N; they are retried per the backoff policy of `kms-integration-model` R89 and only escalate to a permanent-class outcome if the retry budget is exhausted.

R76b. State transitions must be observable: emit `tenantKekStateTransition` structured log events and `tenantKekState` metric (gauge per tenant) via the `KmsObserver` interface (`kms-integration-model` R93).

R76c. One tenant entering `grace-read-only` or `failed` must not affect other tenants' operations (per-tenant isolation from `three-tier-key-hierarchy`).

R77. Recovery from `grace-read-only` or `failed` to `healthy` occurs when:
- A rekey API call (R78) succeeds with a usable new Tenant KEK, OR
- Opt-in polling (R79) detects that the current Tenant KEK is usable again (transient outage resolved)

### Rekey API (flavor 3)

R78. `EncryptionKeyHolder` must expose a `rekey(TenantId, KekRef oldKekRef, KekRef newKekRef, RekeySentinel proofOfControl, ContinuationToken token)` API per `tenant-key-revocation-and-external-rotation` ADR.

R78a. `proofOfControl` must contain: a nonce-bound plaintext unwrapped under `oldKekRef`, the same nonce re-wrapped under `newKekRef`, and a timestamp. jlsm must verify both operations before accepting the rekey — independently invoking the `KmsClient` to unwrap the old sentinel and to re-wrap (or verify the provided re-wrap) under the new KEK. Freshness window is 5 minutes maximum.

R78b. The rekey operation must be streaming and paginated: a single invocation processes a bounded batch of domains (default 100, configurable) and returns a `ContinuationToken`. Callers iterate until the token is null.

R78c. Rekey execution must be resumable across crashes. A per-tenant rekey-progress file records `{oldKekRef, newKekRef, nextShardIndex, startedAt}` and is updated after each shard commit. A crashed rekey resumes at the next uncompleted shard. Stale progress files (>24h) must emit an observable event.

R78d. During an in-progress rekey, each affected registry shard carries dual-reference entries `(kekRef, wrappedBlob)`: the existing entry under `oldKekRef` plus a new entry under `newKekRef`. Reads prefer `newKekRef`, falling back to `oldKekRef` if unwrap fails (shard not yet migrated). Writes always use `newKekRef` once rekey has started for the tenant.

R78e. Rekey completes when all shards for the tenant carry `newKekRef` entries only. At that point, the `oldKekRef` entries may be garbage-collected from the registry shard; the tenant operator may then disable/delete the old KEK in the tenant's KMS.

### Opt-in polling (flavor 3)

R79. Per-tenant opt-in polling may be enabled by the deployer; when enabled, the polling loop invokes `KmsClient.isUsable(tenantKekRef)` on a configurable cadence (default 15 minutes) and updates the tenant's state machine (R76) based on the result.

R79a. Polling failure classification must follow the same permanent-vs-transient rules as R76a. Transient failures do not count toward N; permanent failures do.

R79b. Polling must be per-tenant — one tenant's polling load must not affect other tenants' KMS quota or jlsm's thread pool capacity.

### KmsClient SPI contract (normative)

R80. The `KmsClient` SPI must expose the following operations:

- `WrapResult wrapKek(MemorySegment plaintextKek, KekRef kekRef, Map<String,String> encryptionContext)`
- `UnwrapResult unwrapKek(ByteBuffer wrappedBytes, KekRef kekRef, Map<String,String> encryptionContext)`
- `boolean isUsable(KekRef kekRef)`
- `void close()` — implements AutoCloseable; releases connection pool resources

Detailed method contracts, exception hierarchy (`KmsTransientException`, `KmsPermanentException`, `KmsRateLimitExceededException`), timeout / retry / cache defaults, encryption-context contents, and observability contract live in `kms-integration-model` ADR. This spec references that ADR as normative.

R80a. `encryptionContext` passed on every wrap/unwrap must include at minimum: `tenantId`, `domainId`, `purpose` (one of `domain_kek`, `rekey_sentinel`, or other reserved values). jlsm must not include plaintext key material in the context. The KMS binds these as AAD; the wrap cannot be cross-wired between tenants or domains even if an attacker obtains wrapped bytes.

R80b. `KmsClient` implementations must be thread-safe. Connection pooling is the implementation's responsibility — jlsm does not manage KMS connection state, retry queues, or circuit-breaker logic (`kms-integration-model` R88).

### DekHandle contract

R81. A `DekHandle` returned by `EncryptionKeyHolder` must carry (at minimum): non-null `tenantId`, non-null `domainId`, non-null `tableId`, and non-negative `dekVersion`. DekHandle construction must validate these and throw IllegalArgumentException for missing or invalid values.

R81a. DekHandle must not expose the unwrapped DEK bytes directly. Access to the DEK material must be gated by `EncryptionKeyHolder.deriveFieldKey` (R9) so that the caller never holds raw DEK references beyond the ingress encryption path.

### Sharded registry contract

R82. The per-tenant registry is organised as one or more shard files per tenant per `three-tier-key-hierarchy` ADR. The concrete layout (per-domain files versus log-structured) is deferred to implementation but must honour R19b (atomic per-shard commit, independent per-tenant fault domains, lazy-load).

R82a. Shards for different tenants must be stored in separate files, never interleaved, so a filesystem-level corruption or permission error affecting one tenant's shard does not leak into any other tenant's key material.

R82b. Tenant shard file paths must be derivable from `(tenantId, domainId?)` via a deterministic, documented function so operational tools (backup, compliance scans) can locate a tenant's shards without maintaining a separate index.

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

**Pass 5 — 2026-04-21 (in-progress)**: v5 amendments introduce three-tier structure, per-tenant sharded registry, cascading lazy rewrap, plaintext-bounded-to-ingress, `_wal` domain mapping, three-state failure machine, rekey API, and normative `KmsClient` SPI. Adversarial review of the v5 amendment surface is pending before state promotion DRAFT → APPROVED.

---

## Verification Notes

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
