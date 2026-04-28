---
{
  "id": "encryption.primitives-lifecycle.ciphertext-format",
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

# encryption.primitives-lifecycle.ciphertext-format — Ciphertext Format and Pre-Encryption Signalling

This spec was carved from `encryption.primitives-lifecycle` during a domain subdivision. The
parent retains cross-cutting requirements that span multiple sub-domains;
the requirements below are specific to this concern.

R1. JlsmDocument must carry a `long` pre-encrypted bitset instead of a boolean flag. When the bitset value is `0L`, no field is pre-encrypted. Bit N set (where N is the zero-based field index) means field N was pre-encrypted by the caller. The pre-encrypted bitset is set at construction and must not be modified thereafter; it must be stored in a final field. The bitset must be interpreted as an unsigned bit vector — the sign bit (bit 63) is a valid field marker. No validity check may reject a bitset solely because it is negative when interpreted as a signed long.

R1a. The pre-encrypted bitset must not be persisted in the SSTable. It is a transient construction-time flag consumed by the serializer. After serialization, pre-encrypted and library-encrypted fields must be indistinguishable in the on-disk format.

R2. JlsmDocument must provide a factory method that accepts a JlsmSchema, a `Set<String>` of pre-encrypted field names, and name-value pairs. The factory must compute the bitset from the field names by mapping each name to its field index and setting the corresponding bit. For every field whose bit is set in the pre-encrypted bitset, the factory must verify the corresponding value is `byte[]`. Non-null values that are not `byte[]` must be rejected with IllegalArgumentException.

R22. Every encrypted field value persisted to MemTable, WAL, or SSTable must conform to the per-variant byte layout canonically specified in `encryption.ciphertext-envelope` R1 (and the cross-tier uniformity invariant R1a). The 4-byte plaintext DEK version prefix, per-variant payload bytes, and authentication-tag placement (inline GCM/SIV tags; detached OPE/DCPE HMAC tags) are defined there. This requirement preserves the F41.R22 identity as a normative pointer — existing `@spec F41.R22` annotations remain valid and may additionally cite `encryption.ciphertext-envelope.R1` for precision. The reader derives `(tenantId, domainId, tableId)` from the SSTable footer metadata (R23a); cross-scope reads must be rejected (R22b).

R22a. The 4-byte DEK version tag validity rules (positive-integer-only; IOException on 0 or negative values; constant-time failure path sharing the R64 wait-free lookup path) are canonically specified in `encryption.ciphertext-envelope` R2 and R2a. Version-not-found semantics (IllegalStateException identifying the `(tenantId, domainId, tableId)` scope without revealing key material) are canonically specified in `encryption.ciphertext-envelope` R2b and are also asserted locally as R24.

R22b. The reader must resolve the DEK by the tuple `(tenantId, domainId, tableId, dekVersion)` where the first three components are derived from the SSTable's footer metadata (R23a) and the fourth from the ciphertext's version tag. The reader's **expected scope** must be materialised from the caller's `Table` handle obtained via catalog lookup — not inferred from the same SSTable footer it is validating (which would be tautological). If the SSTable's declared `(tenantId, domainId, tableId)` does not match the `Table` handle's scope (i.e., the SSTable was mis-routed to a different tenant's or domain's read path), decryption must throw IllegalStateException before any DEK lookup. This enforces per-tenant isolation at the read boundary.

R22c. **SSTable read path must thread the `ReadContext` to deserialize.** The serializer interface invoked from the SSTable read path must accept the reader's `ReadContext` (the per-read DEK-version dispatch gate, `sstable.footer-encryption-scope` R3e) as a parameter on `deserialize`. The SSTable reader's typed-get path must thread its own `ReadContext` into the deserialize call so that the R3e dispatch gate is invoked structurally on every read; the `ReadContext` must not be held by the reader and silently dropped before the deserializer runs. Serializer implementations that do not require the dispatch gate (non-encrypted schemas, or implementations that delegate the membership check to a downstream component) must accept the parameter and ignore it — no behavior change is mandated for non-encrypted paths. The threading discipline operates at the API surface where envelope DEK-version fields first become reachable from disk bytes; bypassing this surface (e.g., reading bytes through a separate path that constructs no `ReadContext`) is a spec violation under R22b. Validation must be a runtime conditional, not a Java `assert`.

R23. The 4-byte version tag must be readable without decryption. No part of the version tag may be encrypted.

R23a. Each SSTable's footer metadata must record: the `(tenantId, domainId, tableId)` scope identifying which table this SSTable belongs to, and the set of DEK versions used for encrypted fields within this SSTable. During compaction of multiple input SSTables, the output SSTable records only the current DEK version (since all fields are re-encrypted to the current version). This metadata enables the manifest to answer "which SSTables reference DEK version V for table T?" without scanning ciphertext, and anchors R22b's cross-scope check.

R24. A ciphertext whose 4-byte version tag references a DEK version not present in the registry for the SSTable's scope must cause decryption to throw IllegalStateException with a message identifying the missing version number and the `(tenantId, domainId, tableId)` scope without revealing any key material.

### Compaction-driven re-encryption

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



---

## Notes
