---
{
  "id": "F45",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": ["encryption", "engine"],
  "requires": ["F03", "F41", "F14"],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": ["client-side-encryption-sdk", "per-field-pre-encryption", "pre-encrypted-flag-persistence"],
  "kb_refs": ["systems/security/client-side-encryption-patterns", "systems/security/jvm-key-handling-patterns", "systems/security/encryption-key-rotation-patterns"],
  "open_obligations": []
}
---

# F45 -- Client-Side Encryption SDK

## Requirements

### KeyVault SPI

R1. The library must define a `KeyVault` interface with a single method `resolve(String keyId)` that returns a `MemorySegment` containing the unwrapped DEK material for the given key identifier. The returned MemorySegment must be owned by the KeyVault implementation; the SDK must not close or zero it. The KeyVault is responsible for its own key lifecycle.

R2. The `resolve` method must throw `KeyNotFoundException` (extending `IllegalStateException`) when the requested key identifier is not found in the vault. The exception message must include the key identifier without revealing any key material.

R3. The `resolve` method must throw `NullPointerException` when `keyId` is null.

R4. `KeyVault` must extend `AutoCloseable`. The `close()` method must release all cached key material. Implementations are expected to zero key segments on close per the patterns in F41 R66-R69.

R5. The `KeyVault` interface must not declare any KMS-specific methods. Integration with AWS KMS, HashiCorp Vault, or other key management systems is the caller's responsibility when implementing the `KeyVault` interface.

R6. The library must provide a `LocalKeyVault` implementation that stores keys in off-heap `MemorySegment` instances backed by an `Arena.ofShared()`. `LocalKeyVault` must support registering key ID to key material mappings at construction time. It must zero all key segments on `close()`.

R7. `LocalKeyVault.resolve()` on a closed vault must throw `IllegalStateException`.

### EncryptionConfig -- per-field encryption configuration

R8. The SDK must define an `EncryptionConfig` record that binds a field name to an `EncryptionSpec` variant and a `keyId` string. The `keyId` identifies which key in the `KeyVault` should be used for that field.

R9. `EncryptionConfig` must reject null field name, null `EncryptionSpec`, or null `keyId` at construction with `NullPointerException`.

R10. When the `EncryptionSpec` is the `None` variant, the `keyId` must be ignored and encryption must not be applied. The `EncryptionConfig` constructor must not reject a `None` spec with any keyId value.

R11. The SDK must accept a `List<EncryptionConfig>` at construction. Duplicate field names in the list must be rejected with `IllegalArgumentException` identifying the duplicate.

### Auto-encrypt interceptor

R12. The SDK must provide an `encrypt(String tableName, JlsmSchema schema, Object... nameValuePairs)` method that produces a `JlsmDocument` with pre-encrypted fields. The `tableName` is required for HKDF info string construction per F41 R11. For each field in the `EncryptionConfig` list whose `EncryptionSpec` is not `None`, the method must: (a) resolve the DEK from the `KeyVault` using the field's `keyId`, (b) derive a per-field key using HKDF-SHA256 per F41 R10-R11 with the DEK as input keying material and the `tableName` as the table component of the info string, (c) encrypt the field value using the field's `EncryptionSpec` variant by delegating to the corresponding F03 encryptor (AES-SIV for Deterministic, AES-GCM for Opaque, OPE for OrderPreserving, DCPE for DistancePreserving), and (d) mark the field as pre-encrypted in the resulting `JlsmDocument`.

R12a. The `encrypt` method must reject a null `tableName` with `NullPointerException` and an empty `tableName` with `IllegalArgumentException`.

R13. The `encrypt` method must produce a `JlsmDocument` using `JlsmDocument.preEncrypted(schema, preEncryptedFieldNames, nameValuePairs...)` per F41 R2. The `preEncryptedFieldNames` set must contain exactly the field names from the `EncryptionConfig` list whose `EncryptionSpec` is not `None`.

R14. The SDK must bridge its KeyVault-managed keys into the library's DEK version system (F41 R17-R21) so that SDK-encrypted ciphertext is format-compatible with library-encrypted ciphertext. The SDK constructor must accept an optional `KeyRegistry` reference. When a `KeyRegistry` is provided, the SDK must operate in **registry-integrated mode**; when absent, it must operate in **standalone mode**.

R14a. **Registry-integrated mode:** On construction, the SDK must register each unique `keyId` from the `EncryptionConfig` list with the `KeyRegistry`. For each keyId, the SDK must: (a) resolve the DEK material from the `KeyVault`, (b) wrap the DEK using the registry's current KEK (per F41 R17), and (c) persist a wrapped DEK entry in the registry with a new version number (per F41 R18-R20). The SDK must cache a bidirectional mapping: keyId to assigned DEK version number (for encrypt) and DEK version number to keyId (for decrypt). The `encrypt` method must prepend the assigned 4-byte big-endian DEK version tag (F41 R22 format) to each encrypted field's ciphertext. This ensures compaction (F41 R25) can look up and re-encrypt SDK-encrypted fields using the standard version-tag-based path.

R14b. **Standalone mode** (no `KeyRegistry` provided): The SDK must prepend a SDK-specific key tag to each encrypted field's ciphertext. The key tag format must be: `[4-byte big-endian 0x00000000 sentinel] [4-byte big-endian keyId UTF-8 byte length] [keyId UTF-8 bytes]`. The leading 4-byte zero sentinel is distinguishable from any valid F41 DEK version tag (F41 R22a requires version > 0). Documents produced in standalone mode must be registered with the library's key registry (via R14c) before the first compaction pass. If standalone-mode ciphertext reaches the library's reader or compactor without prior registration, F41 R22a's existing version-0 error path will reject it with `IOException` indicating corrupt ciphertext. The SDK's documentation must warn that standalone-mode documents require registration before they are readable by the library.

R14c. The SDK must provide a `registerKeys(KeyRegistry registry)` method for deferred registration. This method must perform the same registration as R14a for all keyIds in the `EncryptionConfig` list. After registration, the SDK must switch from standalone to registry-integrated mode for all subsequent encrypt calls. Existing documents produced in standalone mode (with zero-sentinel key tags) are not compatible with the library's read or compaction paths (F41 R22a rejects version 0 as corrupt). The SDK must provide a `convertDocument(String tableName, JlsmSchema schema, JlsmDocument document)` method that reads standalone-mode ciphertext, decrypts it, and re-encrypts it using the registered DEK version tags. Callers must convert all standalone-mode documents before writing them to a table. The `convertDocument` method must throw `IllegalStateException` if the SDK is not in registry-integrated mode. `convertDocument` must be safe for concurrent use from multiple threads without external synchronization.

R14d. The `registerKeys` method must be exclusive with respect to `encrypt`, `decrypt`, and `convertDocument`. While `registerKeys` is executing, no encrypt, decrypt, or convertDocument call may start; and `registerKeys` must wait for all in-flight operations to complete before proceeding. This prevents a mode transition (standalone to registry-integrated) from occurring while an operation is assembling ciphertext, which would cause mixed key tag formats within a single document.

R14e. The SDK's key registration (R14a) captures a snapshot of the KeyVault's key material at construction time. If the external KMS rotates the key behind a keyId after construction, the SDK will continue using the originally registered key material for encryption. To rotate keys, the caller must construct a new SDK instance with the new key material. The SDK must not attempt to detect or handle external key rotation automatically. This limitation must be documented on the SDK class.

R15. The `encrypt` method must validate that the `KeyVault` returns key material of at least 16 bytes (F41 R59). If the key material is shorter, the method must throw `IllegalArgumentException`.

R16. Intermediate key arrays created during per-field key derivation must be zeroed in a finally block per F41 R16.

R17. The `encrypt` method must be safe for concurrent use from multiple threads without external synchronization.

### Auto-decrypt interceptor

R18. The SDK must provide a `decrypt(String tableName, JlsmSchema schema, JlsmDocument document)` method that returns a new `JlsmDocument` with decrypted field values. The `tableName` is required for HKDF info string construction per F41 R11. For each field in the `EncryptionConfig` list whose `EncryptionSpec` is not `None`, the method must: (a) verify the field value is a `byte[]` -- if it is not `byte[]`, the field is not encrypted and must be passed through unchanged, (b) read the leading 4 bytes to determine the key tag format: if nonzero, interpret as a DEK version tag (F41 R22) and resolve the key via the SDK's keyId-to-version mapping (R14a); if zero, interpret as a standalone-mode tag (R14b) and extract the embedded keyId, (c) resolve the DEK from the `KeyVault` using the determined keyId, (d) derive the per-field key using HKDF-SHA256 with the `tableName`, and (e) decrypt the field value.

R18a. The `decrypt` method must reject a null `tableName` with `NullPointerException` and an empty `tableName` with `IllegalArgumentException`.

R18b. If a field value is `byte[]` but has fewer than 4 bytes, the `decrypt` method must throw `IllegalArgumentException` identifying the field name and stating the minimum ciphertext length. A valid encrypted field value must contain at least the 4-byte key tag prefix.

R19. The `decrypt` method must handle documents where some fields are encrypted and others are not. Fields not in the `EncryptionConfig` list must be passed through unchanged.

R20. The `decrypt` method must throw `IllegalStateException` when a field's key tag cannot be resolved: either the DEK version tag references a version not mapped to any keyId in the SDK's registry, or the resolved keyId is not available in the `KeyVault`. The exception message must identify the field name and the unresolvable version or keyId without revealing key material.

R21. The `decrypt` method must be safe for concurrent use from multiple threads without external synchronization.

### Key caching

R22. The SDK must cache resolved DEK material to avoid repeated `KeyVault.resolve()` calls. The cache must have a configurable TTL (default: 5 minutes).

R23. Cached key material must be stored in off-heap `MemorySegment` instances allocated from an `Arena.ofShared()`. On cache eviction or SDK close, evicted key segments must be zeroed before release. Zeroing must not occur while any in-flight encrypt or decrypt operation holds a reference to the segment.

R23a. The cache must use reference counting to protect cached key segments from concurrent eviction. Each cache lookup must increment the reference count. The caller must decrement the reference count in a finally block after the encrypt or decrypt operation completes. Eviction must zero and release the segment only when the reference count reaches zero. This prevents a concurrent cache eviction or SDK close from zeroing a key segment that is actively being used for encryption or decryption, which would produce silently corrupt ciphertext or an authentication tag failure.

R24. The cache must be bounded by a configurable maximum entry count (default: 100). When the cache is full, the least recently used entry must be evicted.

R25. The cache must be safe for concurrent access from multiple threads. Cache reads must not block cache writes.

### SDK lifecycle

R26. The SDK must implement `AutoCloseable`. On `close()`, it must: (a) set a closed flag to prevent new operations from starting, (b) wait for all in-flight encrypt and decrypt operations to complete (per the reference counting mechanism in R23a), (c) zero all cached key material, (d) release the backing Arena. Close must be idempotent.

R26a. `encrypt` and `decrypt` must be atomic with respect to `close()`. If `close()` is called while an encrypt or decrypt operation is executing, the in-flight operation must either complete successfully using the key material it acquired before close, or throw `IllegalStateException` if it had not yet acquired its key material. An in-flight operation must never use partially-zeroed or fully-zeroed key material to produce output.

R27. Any method call on a closed SDK must throw `IllegalStateException`.

R28. The SDK must not close the caller-provided `KeyVault` on its own close. The `KeyVault` lifecycle is managed by the caller.

### Leakage documentation

R29. The SDK must expose a `leakageProfile(String fieldName)` method that returns the `LeakageProfile` (F41 R44-R50) for the field's `EncryptionSpec` variant. If the field is not in the `EncryptionConfig` list, the method must return `LeakageProfile.NONE`.

R30. The SDK's documentation must state that pre-encrypted fields are indistinguishable from library-encrypted fields in the SSTable (F41 R1a). The SDK provides caller control over encryption keys, not different ciphertext formats.

### Input validation

R31. The `encrypt` method must reject a null schema with `NullPointerException`. The `decrypt` method must reject a null schema with `NullPointerException`.

R32. The `encrypt` method must reject a field name in `EncryptionConfig` that does not exist in the provided schema with `IllegalArgumentException` identifying the unknown field.

R33. The `decrypt` method must reject a null document with `NullPointerException`.

R34. The SDK constructor must reject a null `KeyVault` with `NullPointerException`.

R35. The SDK constructor must reject a null `EncryptionConfig` list with `NullPointerException` and an empty list with `IllegalArgumentException`.

### SDK and library encryption interaction

R36. When the SDK encrypts a field (pre-encryption), the library's serializer must not apply library encryption to that field. The pre-encrypted bitset (F41 R1-R7) is the mechanism that prevents double encryption. Fields pre-encrypted by the SDK and fields not in the SDK's `EncryptionConfig` list that have a non-None `EncryptionSpec` in the schema are handled independently: the SDK encrypts its configured fields, and the library encrypts the remaining fields with non-None specs. The same field must not be encrypted by both the SDK and the library.

R37. The `encrypt` method must reject any field name in the `EncryptionConfig` list whose `EncryptionSpec` variant differs from the field's `EncryptionSpec` in the provided `JlsmSchema`. The SDK's encryption variant must match the schema's declared variant for each field to ensure the ciphertext format is consistent with the library's expectations for that field type. If the variants differ, the method must throw `IllegalArgumentException` identifying the field and the mismatched variants.

R38. The SDK must not apply encryption to fields in the `EncryptionConfig` list whose schema-declared `EncryptionSpec` is the `None` variant. If a field appears in the `EncryptionConfig` list with a non-None `EncryptionSpec` but the schema declares the field as `None`, the `encrypt` method must throw `IllegalArgumentException`. This prevents the SDK from encrypting fields that the library expects to be plaintext, which would cause decryption failures during compaction and reads.

### Key material hygiene

R39. The SDK must not include raw key material in any exception message, log output, or `toString()` representation. Exception messages must describe the error condition (e.g., "key resolution failed for keyId 'user-key-1'") without revealing key bytes.

R40. The SDK's `toString()` method must return a fixed string that does not vary with key content, cache state, or configuration details that could reveal which keys are loaded.

## Cross-References

- Spec: F41 -- Encryption Lifecycle (key hierarchy, pre-encryption, HKDF derivation)
- Spec: F03 -- Field-Level In-Memory Encryption (encryption dispatch, AES-SIV/GCM)
- Spec: F14 -- JlsmDocument (preEncrypted factory methods)
- ADR: .decisions/client-side-encryption-sdk/adr.md
- ADR: .decisions/per-field-pre-encryption/adr.md
- ADR: .decisions/pre-encrypted-flag-persistence/adr.md (closed -- per-field markers directed here)
- KB: .kb/systems/security/client-side-encryption-patterns.md
- KB: .kb/systems/security/jvm-key-handling-patterns.md

---

## Design Narrative

### Intent

This spec defines the client-side encryption SDK -- a higher-level API that wraps jlsm's encryption primitives into a schema-driven auto-encrypt/decrypt workflow for external consumers. The SDK follows the pattern established by MongoDB CSFLE and AWS Database Encryption SDK: callers declare which fields are encrypted with what algorithm and key, and the SDK transparently handles encryption on writes and decryption on reads.

### Why KeyVault as SPI

The library must not implement KMS integration directly -- doing so would couple jlsm to specific cloud providers and add external runtime dependencies (violating the project's "no external runtime dependencies" principle). Instead, the `KeyVault` interface lets callers inject their own KMS-backed implementation. `LocalKeyVault` provides a zero-dependency option for testing and single-deployment scenarios.

### Why per-field keyId

F41's per-field key derivation uses HKDF from a single master key. The SDK extends this to support per-field key identifiers: different fields can use different DEKs from the KeyVault. This enables multi-tenant scenarios where different data owners control different fields' encryption keys, while still using the library's HKDF derivation for sub-key generation within each field.

### Why key caching

KeyVault implementations may call external KMS services (AWS KMS, HashiCorp Vault) with non-trivial latency. Caching avoids per-field, per-document KMS calls. The 5-minute default TTL balances freshness against latency. Off-heap storage with zeroing on eviction maintains the key hygiene guarantees from F41.

### What this spec does NOT cover

- KMS integration -- caller's responsibility via KeyVault SPI
- Query-time token generation for searchable encryption -- covered by F03 R60-R65 (SSE) and deferred encrypted query features
- Key rotation mechanics -- covered by F41 R29-R37
- Encryption of non-field data (WAL, SSTable blocks) -- covered by F42

### Adversarial hardening (Pass 1 -- 2026-04-15)

21 findings from structured adversarial review across 6 lenses (cryptographic correctness,
resource safety, concurrency, edge cases, information leakage, integration boundaries).
5 critical, 5 high, 5 medium, 6 low. All 5 critical findings promoted to requirements.

Critical findings and fixes:
1. **Version tag impedance mismatch (C2/C3/I2/I3):** The original spec required SDK-encrypted
   fields to use F41's DEK version tags, but the SDK's KeyVault is a separate key source with
   no access to the library's key registry. Compaction would fail on SDK-encrypted fields.
   Fix: R14 rewritten with registry-integrated and standalone modes. Registry-integrated mode
   registers SDK keys in the library's DEK registry so version tags are format-compatible.
   Standalone mode uses a zero-sentinel prefix. R14a-R14c define the registration bridge.
2. **Missing tableName parameter (C4):** HKDF info string construction (F41 R11) requires
   tableName, but the original encrypt/decrypt methods had no tableName parameter.
   Fix: R12 and R18 amended to accept `String tableName`. R12a and R18a added for validation.
3. **Cache eviction zeroing race (R1):** Concurrent cache eviction could zero a key segment
   while another thread is using it for encryption, producing silently corrupt ciphertext.
   Fix: R23 amended, R23a added requiring reference counting for cached key segments.
4. **Close/operation atomicity (R2):** No guarantee that close() wouldn't invalidate key
   material mid-operation, allowing partially-zeroed keys to produce corrupt output.
   Fix: R26 amended to drain in-flight operations before zeroing. R26a added for atomicity.
5. **Decrypt on non-encrypted data (E3):** No type guard before attempting decryption.
   Decrypting a plaintext String or Integer field would read arbitrary bytes as a version tag.
   Fix: R18 amended to check field value is `byte[]` before decryption; non-byte[] passed through.

High findings logged (not promoted to CRITICAL, addressed as new requirements):
- SDK/library double-encryption risk (E2): R36-R38 added to prevent double encryption.
- Key material hygiene not specified for SDK itself: R39-R40 added.
- LocalKeyVault resolve/close race (R4): covered by vault being caller-managed (R28, F41 R66).
- EncryptionSpec variant mismatch between config and schema (E2): R37 added.
- Cache TTL expiration mid-operation (T2): mitigated by reference counting (R23a).

### Adversarial verification (Pass 2 -- 2026-04-15)

11 verification checks on Pass 1 fixes. 1 critical found, 2 high, 2 medium.

Critical: R14c standalone compaction migration was incompatible with F41 R22a -- the compactor
rejects version 0 as corrupt before any SDK-specific logic could intercept it. Fix: R14c
rewritten to provide a `convertDocument` method instead of relying on compaction to handle
the conversion. Callers must convert standalone-mode documents before writing to a table.

High:
- R14a mapping was unidirectional (keyId->version only); decrypt needs version->keyId.
  Fix: R14a amended to require bidirectional mapping.
- registerKeys mode transition races with in-flight encrypt operations.
  Fix: R14d added requiring registerKeys to be exclusive with encrypt/decrypt/convertDocument.

Medium:
- R14b exception type mismatch (IllegalStateException vs F41 R22a's IOException).
  Fix: R14b rewritten to reference F41 R22a's existing IOException error path.
- R18 decrypt on byte[] < 4 bytes would ArrayIndexOutOfBounds.
  Fix: R18b added for minimum ciphertext length validation.

### Adversarial verification (Pass 3 -- 2026-04-15)

6 verification checks on Pass 2 fixes. Zero critical findings. All fixes verified for
internal consistency, cross-fix interactions, and dangling dependencies. convertDocument
thread safety added. R14e (formerly R14d) key rotation documentation requirement confirmed
consistent.
