---
{
  "id": "encryption.primitives-dispatch",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "encryption"
  ],
  "requires": [],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "field-encryption-api-design",
    "encrypted-index-strategy",
    "pre-encrypted-document-signaling"
  ],
  "kb_refs": [
    "algorithms/encryption/searchable-encryption-schemes",
    "algorithms/encryption/vector-encryption-approaches",
    "systems/security/jvm-key-handling-patterns"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F03"
  ]
}
---

# encryption.primitives-dispatch — Primitives Dispatch

## Requirements

### Encryption dispatch

R1. The encryption dispatch table must be constructed once per serializer instance from the schema and key holder. After construction, the dispatch table must be immutable and safe to use from multiple threads without synchronization.

R2. If no key holder is provided (null), the dispatch table must produce null encryptors and decryptors for all fields regardless of their encryption specifications. Encryption specifications on field definitions must be silently ignored when no key holder is present.

R3. For deterministic encryption, the dispatch must use AES-SIV with a 512-bit key. If the key holder provides a 256-bit key, the dispatch must derive a 512-bit key by concatenating the 256-bit key with itself. The intermediate key arrays used during derivation must be zeroed after the encryptor is constructed.

*Note: invalidated by F41.R10 + F41.R16a, which mandate HKDF-SHA256 (Extract + Expand) with multi-block expansion for keys longer than 256 bits. See F41 for the governing key-derivation contract.*

R4. For deterministic encryption, the field name encoded as UTF-8 must be used as associated data in every encrypt and decrypt call. This binds ciphertext to a specific field: the same plaintext in different fields must produce different ciphertext.

R5. For opaque encryption, the dispatch must use AES-GCM with a 256-bit key. If the key holder provides a 512-bit key, the dispatch must derive a 256-bit sub-key from the first 32 bytes. The intermediate key arrays used during derivation must be zeroed after the encryptor is constructed.

*Note: invalidated by F41.R10 + F41.R16a, which mandate HKDF-SHA256 (Extract + Expand) with domain-separated info strings per encryption variant. See F41 for the governing key-derivation contract.*

R6. For order-preserving encryption, the dispatch must validate at construction time that the field type is compatible with the OPE byte limit. Integer types wider than 16 bits (INT32, INT64, TIMESTAMP) must be rejected. Unbounded strings, booleans, floats, vectors, arrays, and objects must be rejected. Only INT8, INT16, and bounded strings with maxLength not exceeding the OPE byte limit are permitted.

R7. For distance-preserving encryption, the dispatch must not create a byte-level encryptor or decryptor. Distance-preserving encryption operates on float arrays, not byte arrays, and must be handled separately in the serialization path.

R8. The dispatch must validate field index bounds with a runtime check (not only an assertion) when returning encryptors or decryptors. An out-of-bounds field index reachable from external input must produce an ArrayIndexOutOfBoundsException or equivalent, not undefined behavior when assertions are disabled.

### Serializer integration

R9. The document serializer must accept an optional key holder parameter. When a key holder is provided, the serializer must encrypt field values during encoding and decrypt them during decoding, according to each field's encryption specification.

R10. The document serializer must apply field encryption after type-specific serialization (value to bytes) and before writing to the output segment. Decryption must occur after reading bytes from the segment and before type-specific deserialization (bytes to value).

R11. For distance-preserving encrypted vector fields, the serializer must encrypt the float array directly (not the serialized bytes) and serialize the encrypted float array. Decryption must deserialize to a float array first, then apply DCPE decryption using the stored perturbation seed.

R12. The serializer must store the DCPE perturbation seed and detached authentication tag alongside each distance-preserving encrypted vector value. The serialized format for a DCPE vector field must be an 8-byte big-endian perturbation seed followed by dimensions * 4 bytes of encrypted float values (each float stored as 4-byte big-endian IEEE-754) followed by a 16-byte HMAC-SHA256 authentication tag. Both seed and tag must be included in the serialized output so that decryption can authenticate and reconstruct the original vector. See R79 for MAC derivation and verification requirements.

R13. For fields with the none encryption specification, the serializer must not invoke any encryption or decryption operation, regardless of whether a key holder is present.

R14. The serializer constructed without a key holder must produce output byte-for-byte identical to the non-encrypted serializer for schemas where every field uses the none encryption specification. For schemas containing any field with a non-none encryption specification, serialization must reject any document that carries a non-null value for such a field unless the document is pre-encrypted (JlsmDocument.preEncrypted): the serializer must throw IllegalStateException at serialize time naming the offending field, rather than silently storing plaintext for a field declared to require encryption. A serializer constructed without a key holder remains usable for pre-encrypted documents, whose ciphertext the caller supplies directly; it must reject any non-pre-encrypted document that would require the library to perform encryption.

### Concurrency

R15. All encryption and decryption operations (AES-SIV, AES-GCM, OPE, DCPE) must be safe to invoke concurrently from multiple threads without external synchronization. The mechanism (thread-local ciphers, stateless operations, or internal synchronization) is an implementation choice.

R16. The encryption dispatch table must be safe to read concurrently from multiple serializer threads after construction. No mutation of the dispatch table is permitted after construction.

R17. The SSE encrypted index must be safe for concurrent add and search operations. The backing store must use a concurrent data structure. Per-term state counters must use atomic increments.

### Error handling

R18. Encryption failures (JCA exceptions from Cipher, Mac, or SecureRandom) must be wrapped in an unchecked exception (IllegalStateException) with the original exception as the cause. Encryption operations must not declare checked exceptions in their functional interface signatures.

R19. Decryption failures caused by tampered or corrupt ciphertext must propagate as exceptions to the caller. The SSE index is the sole exception: it may return null for a single corrupt posting without aborting the entire search, because individual postings may be corrupted independently.

R20. The SSE index's decryptDocId method must not expose the decryption exception to callers. A failed decryption of a single posting must be treated as a missing entry (return null), not as a fatal error that terminates the search loop.

### Performance observability

R21. The performance impact of field-level encryption must be measurable via JMH benchmarks comparing encrypted and unencrypted serialization round-trips for each encryption variant. The benchmark must cover at least: deterministic (AES-SIV), opaque (AES-GCM), order-preserving (OPE), and distance-preserving (DCPE).
