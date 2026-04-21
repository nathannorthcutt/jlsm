---
{
  "id": "encryption.primitives-lifecycle",
  "version": 4,
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
    "index-access-pattern-leakage"
  ],
  "kb_refs": [
    "systems/security/encryption-key-rotation-patterns",
    "systems/security/jvm-key-handling-patterns",
    "systems/security/client-side-encryption-patterns",
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

R9. EncryptionKeyHolder must provide a `deriveFieldKey(String tableName, String fieldName)` method that returns a MemorySegment containing a derived key of the length required by the field's EncryptionSpec variant (R16c). The derived key must be copied into a caller-supplied Arena by `deriveFieldKey`. The caller is responsible for zeroing and closing its own Arena. This prevents use-after-zero: close() zeros the internal master key and cached derivation state, but previously-returned derived key segments in caller-owned Arenas remain valid until the caller releases them. If no caller Arena is provided, the method must allocate from an internal shared Arena — in this case, close() must wait for all in-flight `deriveFieldKey` callers to complete (per R62a) before zeroing the internal Arena's segments.

R10. Key derivation must use HKDF-SHA256. The extract step must compute `PRK = HMAC-SHA256(salt, IKM=masterKeyBytes)` where salt defaults to `0x00{32}` (all-zero, 32 bytes). The expand step must compute `OKM = HMAC-SHA256(PRK, info || 0x01)` truncated to 32 bytes.

R10a. The HKDF salt must be configurable via EncryptionKeyHolder's constructor. The default all-zero salt is acceptable for single-deployment scenarios. For multi-tenant or multi-deployment environments, the caller should provide a unique salt (e.g., a deployment identifier hash). When provided, the salt replaces the all-zero default in the extract step.

R10b. The HKDF salt must be recorded in the key registry alongside the wrapped DEK entries. On EncryptionKeyHolder construction, if a salt is provided and a registry already exists, the constructor must verify that the provided salt matches the registry's recorded salt. A mismatch must throw IllegalArgumentException identifying the salt mismatch (without revealing salt bytes beyond a hash prefix). This prevents silent key derivation mismatch when salt is misconfigured across instances — particularly dangerous for OPE and DCPE fields where wrong-key decryption produces plausible but incorrect values rather than authentication failures.

R11. The info parameter for HKDF-Expand must be the UTF-8 encoding of the string `"jlsm-field-key:" + tableName + ":" + fieldName`. The colon delimiters must be literal ASCII 0x3A characters. To prevent ambiguity when table or field names contain colons, the table name and field name must each be length-prefixed in the info string: the info bytes must be `"jlsm-field-key:" || 4-byte-BE(tableNameUtf8.length) || tableNameUtf8 || 4-byte-BE(fieldNameUtf8.length) || fieldNameUtf8`.

R12. Calling `deriveFieldKey` twice with the same `tableName` and `fieldName` on the same key holder instance must return MemorySegments whose byte contents are identical. The derivation must be deterministic given the same master key and info string.

R12a. Two EncryptionKeyHolder instances constructed from identical master key bytes and identical salt must produce byte-identical derived field keys for the same tableName and fieldName combination. The derivation is purely a function of the master key, salt, table name, and field name.

R13. Calling `deriveFieldKey` with different `fieldName` values (same `tableName` and master key) must produce outputs that differ in at least one byte. Two derived keys for distinct fields must not collide.

R14. Calling `deriveFieldKey` with different `tableName` values (same `fieldName` and master key) must produce outputs that differ in at least one byte. Two derived keys for fields with the same name in different tables must not collide.

R15. `deriveFieldKey` must reject null `tableName` or null `fieldName` with NullPointerException. It must reject empty `tableName` or empty `fieldName` with IllegalArgumentException.

R16. All intermediate byte arrays created during HKDF computation (PRK, HMAC inputs, HMAC outputs) must be zeroed in a finally block before the method returns. No intermediate key material may survive on the heap after derivation completes. Zeroing must be null-safe — if an array reference is null (because computation did not reach that step), it must be skipped.

R16a. For encryption variants that require keys longer than 256 bits (e.g., AES-SIV requires 512 bits), the key derivation must perform multiple HKDF-Expand steps with incrementing counter bytes (0x01, 0x02, ...) and concatenate the outputs to reach the required key length. Each expand step must use the same PRK and info prefix but a different final counter byte.

R16c. EncryptionSpec must expose a `requiredKeyBits()` method returning the key length in bits needed for that variant. Deterministic (AES-SIV) must return 512. Opaque (AES-GCM) must return 256. OrderPreserving (Boldyreva OPE) must return 256. DistancePreserving (DCPE/SAP) must return 256. None must return 0. The key derivation routine must call `requiredKeyBits()` to determine how many HKDF-Expand steps to perform. When `requiredKeyBits()` returns 0 (None variant), the key derivation routine must return null and must not invoke HKDF. Callers must check the return value before using the derived key.

R16b. The per-field derived key (from HKDF) serves as the data encryption key for that field. In the envelope encryption model (R17-R21), the current DEK is used as the input keying material to HKDF. When the DEK rotates, all per-field derived keys change because the IKM changes. Callers provide the KEK; the library unwraps the current DEK and uses it as the HKDF master key input.

### Key hierarchy: KEK and versioned DEKs

R17. The encryption system must support a two-tier key hierarchy. The caller provides a KEK (Key Encryption Key). The library manages DEKs (Data Encryption Keys) that are wrapped (encrypted) by the KEK and stored in a key registry.

R18. Each DEK must have a unique integer version identifier. Version identifiers must be 32-bit signed positive integers assigned in strictly increasing order. The version must never be reused.

R18a. When the current version is Integer.MAX_VALUE, generating a new DEK must throw IllegalStateException stating that the version space is exhausted.

R19. The key registry must be a persistent structure stored alongside the SSTable manifest. It must contain: the active KEK version, a map from DEK version to wrapped DEK entry, and a set of retired KEK versions. Each wrapped DEK entry must record the wrapped key material, the KEK version used for wrapping, and the creation timestamp.

R19a. The key registry file must include a trailing CRC-32C checksum covering all preceding bytes. On load, the checksum must be verified before any DEK entries are parsed. A checksum mismatch must throw IOException identifying the registry file path.

R20. The key registry must be updated atomically: write to a temporary file, fsync the temporary file, then rename to the target path. A crash during registry update must leave the previous registry intact.

R20a. On startup, for each orphaned temporary registry file (matching the temp file naming pattern), the loader must: (1) verify the CRC-32C checksum (R19a), (2) if valid and the temp file contains a strictly newer state (higher max DEK version) than the current registry, complete the interrupted rename (promote the temp file to the registry path), (3) if invalid or older, delete the temp file. The comparison must use the DEK version map, not file timestamps. The temp file naming pattern must be documented.

R21. At any point in time, exactly one DEK version must be designated as the current (active) version. All new writes must use the current DEK. Reads must accept any DEK version present in the registry.

### Ciphertext format with version tag

R22. Every encrypted field value must be prefixed with a 4-byte big-endian DEK version tag in plaintext. The reader must use this tag to look up the correct DEK before decryption. The ciphertext layout per encryption variant is:

- `[4B DEK version | 12B IV/nonce | encrypted payload | 16B GCM auth tag]` for AES-GCM (Opaque). The GCM tag provides inline authentication.
- `[4B DEK version | 16B synthetic IV | ciphertext]` for AES-SIV (Deterministic). The S2V synthetic IV provides inline authentication.
- `[4B DEK version | 1B length prefix | 8B OPE ciphertext long | 16B detached HMAC-SHA256 tag]` for OPE (OrderPreserving) — total 29 bytes. The 16-byte detached tag is computed per F03.R78 and binds the OPE ciphertext to the UTF-8 field name and the DEK version. MAC verification must run before the OPE inverse.
- `[4B DEK version | 8B perturbation seed | N*4B encrypted floats | 16B detached HMAC-SHA256 tag]` for DCPE (DistancePreserving) — total `8 + N*4 + 20` bytes (4 version + 8 seed + 4N ciphertext + 16 tag). The 16-byte detached tag is computed per F03.R79 and binds the seed and encrypted vector to the UTF-8 field name and the DEK version. MAC verification must run before the DCPE inverse.

Detached MAC tags for OPE and DCPE close the authenticity gap identified in F03 v1 where wrong-key decryption produced plausible but incorrect values. Tags for GCM and SIV are inherent to those schemes and require no additional wrapping.

R22a. The 4-byte version tag must contain a positive integer (1 or greater). If the reader encounters a version tag of 0 or a negative value (when interpreted as signed big-endian int), it must throw IOException indicating corrupt ciphertext. This applies to both normal reads and compaction re-encryption reads. The version-0/negative check must be performed as part of the same lookup path as the hash map check (R64) — the implementation must not branch to a separate error path before performing the map lookup. Version 0 is never inserted into the map, so both version-0 and version-not-found follow the same code path, preserving the constant-time property of R64.

R23. The 4-byte version tag must be readable without decryption. No part of the version tag may be encrypted.

R23a. Each SSTable's footer metadata must record the set of DEK versions used for encrypted fields within that SSTable. During compaction of multiple input SSTables, the output SSTable records only the current DEK version (since all fields are re-encrypted to the current version). This metadata enables the manifest to answer "which SSTables reference DEK version V?" without scanning ciphertext.

R24. A ciphertext whose 4-byte version tag references a DEK version not present in the key registry must cause decryption to throw IllegalStateException with a message identifying the missing version number without revealing any key material.

### Compaction-driven re-encryption

R25. During compaction, for every encrypted field in a record, the compaction task must: (a) read the 4-byte DEK version tag, (b) look up the corresponding DEK, (c) decrypt with that DEK, (d) re-encrypt with the current (active) DEK, and (e) write the re-encrypted ciphertext with the current DEK's version tag.

R25a. The compaction re-encryption path must not rely on the pre-encrypted bitset. The compactor must pass re-encrypted field data to the SSTable writer through a compaction-specific write path that accepts raw ciphertext bytes per field. This path must be distinct from the JlsmDocument-based write path (which uses the pre-encrypted bitset) and must accept an array or map of field-index-to-ciphertext entries. The SSTable writer must write these bytes verbatim without consulting the field's EncryptionSpec. This path must support schemas of any size (no 64-field limit). The SSTable writer must still record the schema version tag (R40) and DEK version set (R23a) in the footer metadata regardless of which write path is used.

R25b. The compaction task must capture the current DEK version at task start and use it for all re-encryption within that task. The capture must read the current version from the volatile-reference immutable map (R64), not from the locked registry. This ensures compaction startup is never blocked by KEK rotation (R34a). The output SSTable records this single DEK version in its footer metadata (R23a). If the DEK rotates during compaction, the output SSTable will use the pre-rotation version, and a subsequent compaction pass will re-encrypt to the new version. Convergence detection (R37) must not treat output SSTables from recent compaction as converged — they may reference the pre-rotation DEK by design.

R26. If a record's encrypted field already uses the current DEK version, compaction must not decrypt and re-encrypt that field. It must copy the ciphertext unchanged. This avoids unnecessary cryptographic operations when no rotation has occurred.

R27. Re-encryption during compaction must not block reads or writes to the table. Compaction operates on immutable SSTable inputs and produces new SSTable outputs; no lock on the active write path is required.

R28. After a compaction run completes and the output SSTable replaces the input SSTables in the manifest, the compaction task must not delete old DEK entries from the registry. DEK pruning is a separate operation (R30).

### DEK lifecycle

R29. A new DEK must be generated by producing 32 bytes from a SecureRandom instance, wrapping the DEK with the current KEK using AES-GCM (the KEK wraps the DEK), assigning the next sequential version number, and persisting the wrapped entry to the key registry.

R30. A DEK version may be pruned from the registry only when no live SSTable in the manifest references that DEK version. The pruning check must scan the manifest's SSTable list. Premature pruning must be prevented: if any SSTable still contains ciphertext tagged with that DEK version, the DEK must remain.

R30a. DEK pruning must not remove a DEK version while any in-flight compaction task references an SSTable containing that version. The pruning check must consider both the live manifest and the set of SSTables currently being compacted (the compaction input set).

R30b. The pruning scan must operate on a consistent snapshot of the manifest. If the manifest uses an atomic-swap update model, the scan must read a single snapshot and not interleave with concurrent manifest mutations.

R30c. The pruning operation must read the manifest snapshot and the in-flight compaction input set as a single atomic operation. The implementation must hold a read lock that prevents new compaction tasks from registering inputs between the manifest snapshot read and the compaction input set read. Without this atomicity guarantee, a new compaction could register an SSTable referencing a DEK version that the pruning scan has already determined is unreferenced.

R31. When a DEK entry is pruned from the registry, the unwrapped key material (if held in memory) must be zeroed before release. The wrapped key material must be removed from the persisted registry in a subsequent atomic registry update.

### KEK rotation

R32. KEK rotation must re-wrap all existing DEK entries under the new KEK. This is an O(DEK-count) operation, not an O(data-size) operation. The data on disk is not touched during KEK rotation.

R33. After KEK rotation, the old KEK version must be added to the set of retired KEK versions in the registry. The old KEK must not be deleted from the caller's key management until all DEK entries have been re-wrapped under the new KEK and the registry update is persisted.

R34. KEK rotation must be atomic with respect to the registry: either all DEK entries are re-wrapped under the new KEK and the registry is updated, or none are. A crash during KEK rotation must leave the registry in a consistent state with the previous KEK still active.

R34a. KEK rotation must hold an exclusive lock on the key registry for the entire duration of the re-wrap operation. No new DEK may be created or persisted while KEK rotation is in progress. DEK creation (R29) must acquire a shared lock, and KEK rotation must acquire an exclusive lock.

### Dual-read during rotation window

R35. During a key rotation window (when SSTables contain ciphertext under both old and new DEK versions), every read must be able to decrypt using any DEK version present in the registry. The reader must not assume all ciphertext uses the current DEK.

R36. DET-encrypted indexed fields must be marked as "rotation-pending" when a new DEK version is activated and the previous version is still referenced by live SSTables. During the rotation-pending window, queries on DET/OPE-indexed encrypted fields may return incomplete results. This limitation must be documented on the rotation API.

R37. After rotation converges (no live SSTables reference old DEK versions), DET/OPE indices affected by the rotation must be rebuilt. The library must provide a mechanism to detect convergence and trigger index rebuild.

### Compaction-driven encryption migration

R38. When a schema update adds EncryptionSpec to a previously unencrypted field, all new writes must immediately encrypt that field with the current DEK. Existing SSTables with unencrypted data for that field must be encrypted during compaction.

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

R57. Attempting to activate a DEK version that does not exist in the registry must throw IllegalStateException identifying the requested version without revealing key material.

R58. Wrapping a DEK with a null or zero-length KEK must throw NullPointerException or IllegalArgumentException respectively, before any cryptographic operation begins.

R59. HKDF derivation must reject master keys shorter than 16 bytes with IllegalArgumentException. Keys shorter than 128 bits do not meet minimum security requirements for HKDF input keying material.

R59a. When the master key is shorter than 32 bytes but at least 16 bytes, the implementation must log a warning that the effective security level is limited to the master key's entropy, not the 256-bit derived key length.

R60. When compaction encounters a ciphertext whose version tag is valid but whose decryption fails (authentication tag mismatch), it must throw an IOException wrapping the underlying crypto exception. Corrupt ciphertext must not be silently dropped or re-encrypted.

R60a. The compaction task must not register the output SSTable in the manifest until all records have been successfully re-encrypted and written. The partial output SSTable must never be visible to readers. If compaction encounters a decryption failure (R60), the compaction task must delete the partial output SSTable from the filesystem before propagating the IOException. Since the output was never registered, no manifest update is needed. Input SSTables must not be removed from the manifest. The failed record's SSTable path and byte offset must be included in the IOException message.

R61. Schema version mismatch during migration (SSTable schema version is newer than the reader's schema) must throw IllegalStateException. Forward-incompatible schemas must be rejected explicitly.

### Concurrency

R62. EncryptionKeyHolder must be safe for concurrent use from multiple threads. The `deriveFieldKey` method must not require external synchronization. Multiple threads may derive keys simultaneously.

R62a. `deriveFieldKey` must be atomic with respect to `close()`. If `close()` is called while `deriveFieldKey` is executing, `deriveFieldKey` must either complete successfully using the original key material or throw IllegalStateException. It must never use partially-zeroed or fully-zeroed key material to produce a derived key.

R63. The key registry must support concurrent reads from reader threads while a single writer updates it. The registry update must use atomic file rename; readers that opened the old registry file before the rename must continue to function correctly using the old registry contents.

R64. DEK version lookup during read operations must be wait-free: a single volatile-reference read of an immutable map followed by a hash lookup. The implementation must use copy-on-write semantics — mutations create a new immutable map and publish it via a volatile reference swap. Registry reloads (after rotation) may block briefly but must not hold locks that prevent concurrent reads from completing with the previously loaded registry. DEK version lookup timing must be constant-time with respect to whether the version exists — the implementation must perform the same operations regardless of outcome.

R65. The `isFieldPreEncrypted(int)` method on JlsmDocument must be safe to call concurrently from multiple threads. The bitset is a final field set at construction; no synchronization is required.

### Resource lifecycle and key zeroization

R66. EncryptionKeyHolder must implement AutoCloseable. On close, it must zero all off-heap MemorySegments holding master key material and all derived field keys, then release the backing Arena. Close must be idempotent.

R67. When a derived field key MemorySegment is no longer needed (key holder closing, or field removed from schema), the MemorySegment must be filled with zeros before the Arena is closed. Zeroing must use `MemorySegment.fill((byte) 0)`.

R68. Unwrapped DEK material held in memory for decryption must be stored in off-heap MemorySegments allocated from a shared Arena, not in on-heap byte arrays. On-heap copies created temporarily for JCA cipher initialization must be zeroed in a finally block immediately after the cipher is initialized.

R68a. The JCA SecretKeySpec retains an internal copy of key bytes that cannot be zeroed by the library. The implementation must minimize the lifetime of SecretKeySpec instances — construct them immediately before use and null the reference immediately after `Cipher.init()` returns.

R69. When a DEK is pruned from the in-memory registry (R30-R31), its off-heap MemorySegment must be zeroed before the segment is released. The zeroing must happen even if an exception occurs during the pruning operation.

R70. The key registry file on disk must contain only wrapped (encrypted) DEK material. Unwrapped DEK bytes must never be written to the registry file.

R70a. The key registry file and any temporary files created during atomic updates must be created with owner-only permissions (0600 on POSIX systems, equivalent on other platforms). The implementation must set permissions before writing any key material to the file.

## Cross-References

- Spec: F03 — Field-Level In-Memory Encryption (parent encryption contracts)
- Spec: F14 — JlsmDocument (document model, preEncrypted factory methods)
- ADR: .decisions/per-field-pre-encryption/adr.md
- ADR: .decisions/per-field-key-binding/adr.md
- ADR: .decisions/encryption-key-rotation/adr.md
- ADR: .decisions/unencrypted-to-encrypted-migration/adr.md
- ADR: .decisions/index-access-pattern-leakage/adr.md
- KB: .kb/systems/security/encryption-key-rotation-patterns.md
- KB: .kb/systems/security/jvm-key-handling-patterns.md
- KB: .kb/systems/security/client-side-encryption-patterns.md
- KB: .kb/algorithms/encryption/index-access-pattern-leakage.md

---

## Design Narrative

### Intent

This spec extends F03 (Field-Level In-Memory Encryption) with the full encryption lifecycle: per-field pre-encryption signaling, automatic per-field key derivation, key rotation via envelope encryption, online encryption migration, and leakage mitigation. Together, these capabilities transform jlsm's encryption from a static encrypt-at-rest feature into a production-grade system that supports key rotation without downtime, schema evolution that adds or removes encryption from fields online, and informed caller decisions about leakage tradeoffs.

### Per-field pre-encryption

The previous boolean pre-encryption flag forced all-or-nothing: either every encrypted field was pre-encrypted by the caller, or none were. This is insufficient for mixed scenarios where a client encrypts some fields (e.g., PII handled by a client-side encryption SDK) while the library encrypts others (e.g., searchable encrypted indices). The bitset representation costs zero overhead on the common path (a single `long` comparison against `0L`) and supports schemas up to 64 fields without additional allocation. The 64-field limit is acceptable for the current schema model; if schemas grow beyond 64 fields, the bitset can be extended to `long[]` or `BitSet` under a future revision.

### Per-field key derivation

F03's key handling used a single master key for all fields in a table, with ad-hoc key adaptation (concatenation for upsizing, truncation for downsizing). F03 explicitly noted this as a known limitation: "should be replaced with HKDF when key rotation is implemented." This spec replaces the ad-hoc approach with HKDF-SHA256, deriving a cryptographically independent key per field. The info string includes both table name and field name to prevent cross-table key collision. This change invalidates F03.R20 (deterministic key derivation by concatenation) and F03.R22 (opaque key derivation by truncation) — both are superseded by the HKDF derivation in R10.

### Envelope encryption and key rotation

Direct master key rotation would require re-encrypting the entire dataset — O(data size) write amplification. Envelope encryption reduces this to O(DEK count): the KEK wraps short DEK entries, and rotating the KEK means re-wrapping a few hundred bytes rather than terabytes of data. Actual data re-encryption is deferred to compaction, which already reads and rewrites SSTables. The 4-byte plaintext version tag on each ciphertext enables the reader to select the correct DEK without trial decryption.

The compaction-driven approach means rotation is eventual, not immediate. Convergence time is bounded by a full compaction cycle. If compliance requires immediate rotation, a priority compaction trigger would be needed — this is an operational concern deferred to a future ADR.

### Online encryption migration

Adding encryption to a field (or removing it) reuses the same compaction-driven mechanism as key rotation. The SSTable schema version tag tells the reader whether a given SSTable's fields are encrypted or not. During the migration window, some SSTables have the field encrypted and others do not; the reader handles both transparently. This avoids a stop-the-world migration pass and requires zero additional I/O beyond normal compaction.

The bidirectional nature of the mechanism (encrypt-to-unencrypt and unencrypt-to-encrypt use the same code path) reduces implementation surface and testing burden.

### Leakage mitigation

The leakage profile documentation on EncryptionSpec serves two purposes: (1) it gives callers structured data to evaluate encryption schemes against their threat model, and (2) it makes leakage an explicit part of the API contract rather than an implicit assumption. Power-of-2 response padding mitigates volume attacks at modest bandwidth cost (at most 2x result count). Per-field HKDF keys (already mandated by the key derivation requirements) eliminate the cheapest attack vector: cross-field frequency correlation.

### Wire format implications

The 4-byte DEK version tag added to every encrypted field value is a wire format change. Data encrypted under F03's single-key model (no version tag) is not readable by an F41-era reader without a migration step. This migration is handled by the same compaction-driven mechanism: pre-F41 SSTables carry a schema version tag indicating the old encryption format, and compaction re-encrypts them with the versioned ciphertext format.

### What this spec does NOT cover

- **Client-side encryption SDK** — how external callers discover per-field encryption requirements and manage their own keys (deferred to `client-side-encryption-sdk`)
- **External KMS integration** — callers provide the KEK; integration with AWS KMS, HashiCorp Vault, etc. is the caller's responsibility
- **Forced immediate rotation** — priority compaction scheduling for compliance deadlines
- **Updatable encryption** — re-encryption without decryption via update tokens (theoretical optimization, not needed for v1)
- **ORAM-based access pattern hiding** — deferred until adaptive ORAM achieves < 5x overhead

### Adversarial falsification (Pass 2 — 2026-04-15)

32 findings from structured adversarial review (all mandatory probes). All promoted.
Critical: empty byte[] pre-encrypted ciphertext (R5a), derived key lifecycle unassigned
(R9 amended), schema version + DEK rotation race (R40a), KEK rotation + DEK creation race
(R34a), compaction + DEK pruning race (R30a). High: pre-encrypted type validation (R2
amended), compaction bitset limit (R25a), version tag 0 (R22a), requiredKeyBits mapping
(R16c), close/deriveFieldKey race (R62a), manifest snapshot for pruning (R30b), compaction
partial failure cleanup (R60a), registry file checksum (R19a), current DEK temporal scope
(R25b), pre-encrypted version tag validation (R5c), bitset persistence leak (R1a),
registry file permissions (R70a), configurable HKDF salt (R10a). Medium: sign bit (R1
amended), null pre-encrypted value (R5b), master key length warning (R59a), JCA key copy
(R68a), orphaned temp files (R20a), null-safe zeroing (R16 amended), wait-free DEK
lookup (R64 amended), migration timeline leakage (R44a), configurable salt (R10a). Low:
version exhaustion (R18a), cross-instance derivation (R12a), LeakageProfile descriptions
(R46-R50), bitset immutability (R1 amended), timing side-channel (R64 amended).

### Adversarial depth pass (Pass 3 — 2026-04-15)

11 fix-consequence findings from structured depth review. All promoted.
Critical: pruning manifest+compaction atomicity gap (R30c), derived key use-after-zero
from shared Arena (R9 amended to caller-supplied Arena). High: KEK rotation blocks
compaction startup (R25b amended — use volatile map), compaction write path unspecified
after bitset removal (R25a amended — dedicated ciphertext write path), salt not persisted
(R10b), partial output registered before success (R60a amended), orphaned temp file
blind deletion (R20a amended — CRC-verified promotion). Medium: convergence detection
(R25b amended), R5c write-path version check (R5c amended — use volatile map). Low:
requiredKeyBits 0 for None (R16c amended), R22a timing side channel (R22a amended).

### Adversarial verification (Pass 4 — 2026-04-15)

Zero critical findings. All 10 Pass 3 fixes verified for internal consistency,
cross-fix interactions, and dangling dependencies. Low: R25a cross-reference to R40
added for schema version tag handling in compaction write path.

---

## Verification Notes

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

All of F41 except the MAC-wrapping portion of R22 remains unimplemented. The full list:

- R1–R8 — per-field pre-encryption bitset on JlsmDocument
- R9–R16c — HKDF-SHA256 key derivation (code currently uses single-pass HMAC-Expand)
- R17–R24 — KEK/DEK hierarchy, key registry, DEK version tag prefix, CRC-32C checksum
- R25–R28 — compaction-driven re-encryption
- R29–R31 — DEK lifecycle and pruning
- R32–R34a — KEK rotation
- R35–R37 — dual-read rotation window + DET/OPE index rebuild
- R38–R43 — online encryption migration
- R44–R50 — leakage profile documentation
- R51–R54 — power-of-2 response padding
- R55–R65 — various validation and concurrency requirements
- R66–R70a — resource lifecycle requirements beyond R16/R68 finally-block zeroing

Obligations registered: `implement-f41-lifecycle`.
