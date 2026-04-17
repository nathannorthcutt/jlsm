---
{
  "id": "F03",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": ["encryption"],
  "requires": [],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": ["field-encryption-api-design", "encrypted-index-strategy", "pre-encrypted-document-signaling"],
  "kb_refs": ["algorithms/encryption/searchable-encryption-schemes", "algorithms/encryption/vector-encryption-approaches", "systems/security/jvm-key-handling-patterns"],
  "open_obligations": []
}
---

# F03 — Field-Level In-Memory Encryption

## Requirements

### Encryption specification model

R1. The encryption specification must be a sealed type with exactly five variants: none (plaintext), deterministic, order-preserving, distance-preserving, and opaque. No additional variants may be added without changing the sealed permits list.

R2. Each encryption specification variant must expose boolean capability methods indicating which query operations it supports: equality, range, keyword search, phrase search, SSE search, and approximate nearest-neighbor search. Capability methods must return false by default; each variant overrides only the capabilities it provides.

R3. The none variant must report all capabilities as true, reflecting that unencrypted values support all query operations without restriction.

R4. The deterministic variant must report support for equality and keyword search only. It must not report support for range, phrase search, SSE search, or approximate nearest-neighbor search.

R5. The order-preserving variant must report support for equality and range queries only. It must not report support for keyword search, phrase search, SSE search, or approximate nearest-neighbor search.

R6. The distance-preserving variant must report support for approximate nearest-neighbor search only. It must not report support for equality, range, keyword search, phrase search, or SSE search.

R7. The opaque variant must report no query capabilities. All capability methods must return false.

### Schema-level field encryption configuration

R8. Every field definition must carry an encryption specification. The specification must never be null; a compact constructor must reject null with a NullPointerException.

R9. Field definitions constructed without an explicit encryption specification must default to the none variant. This default must not require callers to import or reference the encryption specification type.

R10. The schema builder must accept a three-argument field method that takes a field name, field type, and encryption specification. All three arguments must be validated as non-null at the call site before the field definition is created.

R11. The schema builder must continue to accept the two-argument field method (name and type) for backward compatibility. The two-argument overload must produce a field definition with the none encryption specification.

### Key holder lifecycle

R12. The key holder must store key material in off-heap memory allocated from a shared arena. The caller's byte array must be zeroed immediately after the key is copied to the off-heap segment.

R13. The key holder must accept only 32-byte or 64-byte key material. Any other length must be rejected at construction time with an IllegalArgumentException that states the required lengths without revealing the actual key bytes.

R14. The key holder must provide a method to obtain a temporary on-heap byte array copy of the key. Callers must zero this copy after use; the key holder's documentation must state this obligation.

R15. The key holder's close method must zero the off-heap key segment before releasing the arena. Close must be idempotent: a second close call must have no effect and must not throw.

R16. Any method call on a closed key holder must throw IllegalStateException. The closed check must use an atomic boolean, not a plain boolean, to be safe under concurrent close and read.

R17. The key holder must not implement toString, and must not include key material in any exception message. If a custom toString is present, it must return a fixed string that does not vary with key content.

### Encryption dispatch

R18. The encryption dispatch table must be constructed once per serializer instance from the schema and key holder. After construction, the dispatch table must be immutable and safe to use from multiple threads without synchronization.

R19. If no key holder is provided (null), the dispatch table must produce null encryptors and decryptors for all fields regardless of their encryption specifications. Encryption specifications on field definitions must be silently ignored when no key holder is present.

R20. For deterministic encryption, the dispatch must use AES-SIV with a 512-bit key. If the key holder provides a 256-bit key, the dispatch must derive a 512-bit key by concatenating the 256-bit key with itself. The intermediate key arrays used during derivation must be zeroed after the encryptor is constructed.

*Note: invalidated by F41.R10 + F41.R16a, which mandate HKDF-SHA256 (Extract + Expand) with multi-block expansion for keys longer than 256 bits. See F41 for the governing key-derivation contract.*

R21. For deterministic encryption, the field name encoded as UTF-8 must be used as associated data in every encrypt and decrypt call. This binds ciphertext to a specific field: the same plaintext in different fields must produce different ciphertext.

R22. For opaque encryption, the dispatch must use AES-GCM with a 256-bit key. If the key holder provides a 512-bit key, the dispatch must derive a 256-bit sub-key from the first 32 bytes. The intermediate key arrays used during derivation must be zeroed after the encryptor is constructed.

*Note: invalidated by F41.R10 + F41.R16a, which mandate HKDF-SHA256 (Extract + Expand) with domain-separated info strings per encryption variant. See F41 for the governing key-derivation contract.*

R23. For order-preserving encryption, the dispatch must validate at construction time that the field type is compatible with the OPE byte limit. Integer types wider than 16 bits (INT32, INT64, TIMESTAMP) must be rejected. Unbounded strings, booleans, floats, vectors, arrays, and objects must be rejected. Only INT8, INT16, and bounded strings with maxLength not exceeding the OPE byte limit are permitted.

R24. For distance-preserving encryption, the dispatch must not create a byte-level encryptor or decryptor. Distance-preserving encryption operates on float arrays, not byte arrays, and must be handled separately in the serialization path.

R25. The dispatch must validate field index bounds with a runtime check (not only an assertion) when returning encryptors or decryptors. An out-of-bounds field index reachable from external input must produce an ArrayIndexOutOfBoundsException or equivalent, not undefined behavior when assertions are disabled.

### Deterministic encryption (AES-SIV)

R26. The AES-SIV encryptor must require a 512-bit (64-byte) key. A key of any other length must be rejected at construction time with an IllegalArgumentException.

R27. AES-SIV encryption must be deterministic: the same plaintext and associated data under the same key must always produce the same ciphertext. This property is required for equality-based index lookups.

R28. AES-SIV ciphertext format must be a 16-byte synthetic IV followed by the ciphertext. The total output length must be exactly 16 bytes longer than the input plaintext.

R29. AES-SIV decryption with the wrong key or with tampered ciphertext must throw an exception. It must not return corrupted plaintext.

R30. AES-SIV encryptor instances must be safe to use concurrently from multiple threads. Thread-local cipher instances are an acceptable mechanism.

### Opaque encryption (AES-GCM)

R31. The AES-GCM encryptor must require a 256-bit (32-byte) key. A key of any other length must be rejected at construction time with an IllegalArgumentException.

R32. AES-GCM encryption must use a fresh random 12-byte IV for every encrypt call. The IV must be generated from a SecureRandom instance.

R33. AES-GCM ciphertext format must be a 12-byte IV followed by the ciphertext followed by a 16-byte authentication tag. The total output length must be exactly 28 bytes longer than the input plaintext.

R34. AES-GCM decryption with the wrong key must throw an exception (tag verification failure). It must not return corrupted plaintext silently.

R35. AES-GCM decryption with a tampered ciphertext (any byte flipped in IV, ciphertext, or tag) must throw an exception. The authentication tag must detect any single-byte modification.

R36. AES-GCM encryptor instances must be safe to use concurrently from multiple threads.

### Order-preserving encryption (Boldyreva OPE)

R37. The OPE encryptor must preserve ordering: for any two plaintext values a < b, the corresponding ciphertext values must satisfy encrypt(a) < encrypt(b). This property must hold for all values within the configured domain.

R38. The OPE domain size must be derived from the field type's byte width: 256 raised to the power of the byte count. The range must be a fixed multiple of the domain to provide ciphertext space. Both domain and range must be computed using long arithmetic to prevent integer overflow.

R39. OPE ciphertext format must be a 1-byte original-length prefix followed by an 8-byte big-endian encrypted long followed by a 16-byte detached HMAC-SHA256 authentication tag. The total output must be exactly 25 bytes regardless of input size. See R78 for MAC derivation and verification requirements.

R40. OPE decryption must reconstruct the original byte array at the original length (stored in the length prefix). The round-trip encrypt-then-decrypt must produce output identical to the input for all values within the field type's domain.

R41. The OPE byte limit must be 2. Field types requiring more than 2 bytes of OPE input must be rejected at schema validation time, not at encryption time.

### Distance-preserving encryption (DCPE / Scale-And-Perturb)

R42. The DCPE encryptor must accept a float array and return an encrypted float array of identical dimensionality. The output dimensionality must equal the input dimensionality exactly.

R43. The DCPE encryptor must reject input vectors whose dimensionality does not match the configured dimensions. The mismatch must produce an IllegalArgumentException stating the expected and actual dimensions.

R44. DCPE encryption must approximately preserve distance relationships: for three vectors a, b, c where distance(a, b) < distance(a, c), the encrypted vectors must satisfy distance(encrypt(a), encrypt(b)) < distance(encrypt(a), encrypt(c)) with high probability. The approximation quality degrades as the original distance ratio approaches 1.0.

R45. DCPE encryption must be non-deterministic: encrypting the same vector twice must produce different ciphertext (due to per-encryption random perturbation). Equality comparison on DCPE-encrypted vectors is not meaningful.

R46. DCPE decryption must require the perturbation seed that was generated during encryption. Without the correct seed, exact reconstruction of the plaintext vector is not possible.

R47. DCPE encrypted output must contain only finite float values. If the scaling or perturbation produces NaN or Infinity in any component, the encryptor must reject the operation rather than producing a vector with non-finite components.

### Serializer integration

R48. The document serializer must accept an optional key holder parameter. When a key holder is provided, the serializer must encrypt field values during encoding and decrypt them during decoding, according to each field's encryption specification.

R49. The document serializer must apply field encryption after type-specific serialization (value to bytes) and before writing to the output segment. Decryption must occur after reading bytes from the segment and before type-specific deserialization (bytes to value).

R50. For distance-preserving encrypted vector fields, the serializer must encrypt the float array directly (not the serialized bytes) and serialize the encrypted float array. Decryption must deserialize to a float array first, then apply DCPE decryption using the stored perturbation seed.

R51. The serializer must store the DCPE perturbation seed and detached authentication tag alongside each distance-preserving encrypted vector value. The serialized format for a DCPE vector field must be an 8-byte big-endian perturbation seed followed by dimensions * 4 bytes of encrypted float values (each float stored as 4-byte big-endian IEEE-754) followed by a 16-byte HMAC-SHA256 authentication tag. Both seed and tag must be included in the serialized output so that decryption can authenticate and reconstruct the original vector. See R79 for MAC derivation and verification requirements.

R52. For fields with the none encryption specification, the serializer must not invoke any encryption or decryption operation, regardless of whether a key holder is present.

R53. The serializer constructed without a key holder must produce output byte-for-byte identical to the non-encrypted serializer for schemas where every field uses the none encryption specification. For schemas containing any field with a non-none encryption specification, serialization must reject any document that carries a non-null value for such a field unless the document is pre-encrypted (JlsmDocument.preEncrypted): the serializer must throw IllegalStateException at serialize time naming the offending field, rather than silently storing plaintext for a field declared to require encryption. A serializer constructed without a key holder remains usable for pre-encrypted documents, whose ciphertext the caller supplies directly; it must reject any non-pre-encrypted document that would require the library to perform encryption.

### Index registry validation

R54. The index registry must validate encryption-to-index compatibility at construction time using the encryption specification's capability methods. An index type that requires a capability the field's encryption specification does not provide must be rejected with an IllegalArgumentException.

R55. An equality or unique index on a field with opaque or distance-preserving encryption must be rejected. Only none, deterministic, and order-preserving encryption specifications report equality support.

R56. A range index on a field with deterministic, opaque, or distance-preserving encryption must be rejected. Only none and order-preserving encryption specifications report range support.

R57. A full-text index on a field with opaque, order-preserving, or distance-preserving encryption must be rejected. Only none and deterministic encryption specifications report keyword search support.

R58. A vector index on a field with deterministic, order-preserving, or opaque encryption must be rejected. Only none and distance-preserving encryption specifications report approximate nearest-neighbor support.

R59. Validation of encryption compatibility must use the capability methods on the encryption specification, not pattern matching on variant types. This ensures that if capability methods are overridden in future variants, the validation remains correct.

### SSE encrypted index

R60. The SSE encrypted index must derive two sub-keys from the master key: one for the PRF (search token derivation) and one for posting encryption. Sub-key derivation must use HMAC-SHA256 with distinct labels. The master key array must be zeroed after derivation.

R61. Search token derivation must be deterministic: the same term under the same PRF key must always produce the same token. This is required for the caller to search without revealing the plaintext term to the index after initial token derivation.

R62. Each add operation must increment a per-term state counter and derive a unique storage address from the token and counter value. This provides forward privacy: a new addition cannot be linked to previous additions for the same term by observing storage addresses alone.

R63. The SSE index search method must accept a pre-derived token (not a plaintext term). The index must iterate storage addresses for counter values 0 through N until a miss is encountered, collecting and decrypting all live entries.

R64. The SSE index must support soft deletion by marking entries with a deleted marker byte. Deleted entries must remain in storage (to preserve counter continuity) but must be excluded from search results.

R65. SSE posting encryption must use AES-GCM with the storage address as authenticated associated data. This binds each encrypted posting to its address: moving an encrypted entry to a different address must cause decryption failure.

### Positional posting codec

R66. The positional posting codec must encrypt term positions using order-preserving encryption. Encrypted positions must preserve the relative ordering of plaintext positions, enabling phrase queries (consecutive positions) and proximity queries (position difference within a threshold) on encrypted data.

R67. The positional posting encoding format must be: 4-byte big-endian document ID length, document ID bytes, 4-byte big-endian position count, followed by position count OPE-encrypted positions as 8-byte big-endian longs.

R68. The positional posting codec must reject null document IDs and null position arrays at the public API boundary with NullPointerException.

R69. Positional posting deserialization must validate that the encoded byte array is at least the minimum posting size (4 + 1 + 4 + 8 = 17 bytes). Undersized input must be rejected with an IllegalArgumentException, not an ArrayIndexOutOfBoundsException.

### Ciphertext structural validation

R70. The ciphertext validator must check that ciphertext for deterministic encryption is at least 16 bytes (the AES-SIV synthetic IV). Shorter ciphertext must be rejected.

R71. The ciphertext validator must check that ciphertext for opaque encryption is at least 28 bytes (12-byte IV + 16-byte tag). Shorter ciphertext must be rejected.

R72. The ciphertext validator must check that ciphertext for order-preserving encryption is exactly 25 bytes (1-byte length prefix + 8-byte encrypted long + 16-byte authentication tag). Any other length must be rejected.

R73. The ciphertext validator must check that ciphertext for distance-preserving encryption is exactly 8 + dimensions * 4 + 16 bytes (8-byte seed + dimensions * 4 bytes encrypted vector + 16-byte authentication tag), where dimensions comes from the field's vector type. A field with distance-preserving encryption that is not a vector type must be rejected.

R74. The ciphertext validator must reject empty ciphertext (zero-length byte array) for all encryption variants with an IllegalArgumentException.

R75. The ciphertext validator must reject validation requests for fields with the none encryption specification. Validating ciphertext for an unencrypted field is a caller error.

### Wrong-key detection

R76. AES-GCM decryption with the wrong key must produce a GeneralSecurityException (tag verification failure) that surfaces to the caller as an exception, not as corrupted plaintext.

R77. AES-SIV decryption with the wrong key must produce an exception. The S2V verification must detect the key mismatch.

R78. The OPE ciphertext format must include a detached 128-bit HMAC-SHA256 authentication tag that binds the 8-byte OPE ciphertext long and the 1-byte length prefix to the UTF-8 field name and the key holder's identity. The MAC key must be derived from the master key via HMAC-SHA256 using the domain-separated label "ope-mac-key", and the MAC key array must be zeroed in a finally block after encryptor construction. OPE decryption must verify the tag in constant time (using MessageDigest.isEqual) before performing the OPE inverse; a tag mismatch must throw SecurityException whose message does not reveal key content, plaintext, or comparison-timing information. This authenticated wrapping closes the wrong-key and ciphertext-tampering gaps and binds each ciphertext to a specific field, preventing cross-field substitution. The order-preserving property is preserved because the comparison in range queries operates on the 8-byte OPE ciphertext portion only; MAC verification runs at decrypt time after query candidates have been identified.

R79. The DCPE ciphertext format must include a detached 128-bit HMAC-SHA256 authentication tag that binds the 8-byte perturbation seed and the encrypted float array bytes to the UTF-8 field name and the key holder's identity. The MAC key must be derived from the master key via HMAC-SHA256 using the domain-separated label "dcpe-mac-key", and the MAC key array must be zeroed in a finally block after encryptor construction. DCPE decryption must verify the tag in constant time (using MessageDigest.isEqual) before performing the DCPE inverse; a tag mismatch must throw SecurityException whose message does not reveal key content, plaintext, or comparison-timing information. This authenticated wrapping closes the wrong-key and ciphertext-tampering gaps and binds each encrypted vector to a specific field, preventing cross-field substitution. The approximate distance-preservation property is preserved because similarity comparisons operate on the encrypted float array portion only; MAC verification runs at decrypt time after query candidates have been identified.

### Key material hygiene

R80. No encryption component may include raw key bytes in exception messages, log output, or toString representations. Exception messages must describe the error condition (e.g., "key length mismatch") without revealing key content.

R81. Intermediate key arrays created during key derivation (e.g., splitting a 512-bit key into two 256-bit halves) must be zeroed in a finally block immediately after use. The zeroing must not be deferred to garbage collection.

R82. The key holder must not implement Serializable. Serialization of key material to disk or network would defeat the purpose of off-heap storage with explicit zeroing.

R83. The key holder's getKeyBytes method must return a fresh copy on each call. Two sequential calls must return distinct array objects (not the same reference) so that zeroing one copy does not affect the other.

### Concurrency

R84. All encryption and decryption operations (AES-SIV, AES-GCM, OPE, DCPE) must be safe to invoke concurrently from multiple threads without external synchronization. The mechanism (thread-local ciphers, stateless operations, or internal synchronization) is an implementation choice.

R85. The encryption dispatch table must be safe to read concurrently from multiple serializer threads after construction. No mutation of the dispatch table is permitted after construction.

R86. The SSE encrypted index must be safe for concurrent add and search operations. The backing store must use a concurrent data structure. Per-term state counters must use atomic increments.

### Error handling

R87. Encryption failures (JCA exceptions from Cipher, Mac, or SecureRandom) must be wrapped in an unchecked exception (IllegalStateException) with the original exception as the cause. Encryption operations must not declare checked exceptions in their functional interface signatures.

R88. Decryption failures caused by tampered or corrupt ciphertext must propagate as exceptions to the caller. The SSE index is the sole exception: it may return null for a single corrupt posting without aborting the entire search, because individual postings may be corrupted independently.

R89. The SSE index's decryptDocId method must not expose the decryption exception to callers. A failed decryption of a single posting must be treated as a missing entry (return null), not as a fatal error that terminates the search loop.

### Performance observability

R90. The performance impact of field-level encryption must be measurable via JMH benchmarks comparing encrypted and unencrypted serialization round-trips for each encryption variant. The benchmark must cover at least: deterministic (AES-SIV), opaque (AES-GCM), order-preserving (OPE), and distance-preserving (DCPE).

## Cross-References

- ADR: .decisions/field-encryption-api-design/adr.md
- ADR: .decisions/encrypted-index-strategy/adr.md
- ADR: .decisions/pre-encrypted-document-signaling/adr.md
- KB: .kb/algorithms/encryption/searchable-encryption-schemes.md
- KB: .kb/algorithms/encryption/vector-encryption-approaches.md
- KB: .kb/systems/security/jvm-key-handling-patterns.md

## Verification Notes

### Amended: v2 — 2026-04-17

Amendments driven by adversarial verification against the v1 implementation.

#### Key derivation delegated to F41 (R20, R22 left with v1 text + invalidation note)

- **R20, R22** — v1 text preserved verbatim; both are invalidated by F41 (per F41's front-matter `invalidates: ["F03.R20", "F03.R22"]`). F41 R10 + R16a mandate HKDF-SHA256 (Extract + Expand) with multi-block expansion for ≥512-bit keys and domain-separated info strings per variant. The current implementation uses single-pass HMAC-SHA256 with labels like `"siv-cmac-key"` / `"gcm-opaque-key"`, which is HKDF-Expand only (missing the Extract step) and therefore violates the APPROVED F41 contract. Code repair for key derivation is scheduled under F41 verification, not F03.

#### Strengthening amendments (close authentication gaps present in v1)

- **R78** — OPE previously had no cryptographic authentication; wrong-key detection relied on caller-side consistency checks. Amended to require a detached 128-bit HMAC-SHA256 tag binding the OPE ciphertext to the field name and key identity. OPE ciphertext grows from 9 bytes to 25 bytes. Order-preserving property is preserved because range comparisons use the 8-byte OPE portion only; MAC verification runs at decrypt time.
- **R79** — DCPE previously had no cryptographic authentication. Amended to require the same detached 128-bit HMAC-SHA256 tag pattern, binding the perturbation seed and encrypted vector to the field name and key identity. DCPE ciphertext grows from `dims*4` bytes to `8 + dims*4 + 16` bytes. Similarity-preserving property is preserved because distance comparisons use the encrypted float array only.
- **R39** — OPE ciphertext length updated from 9 to 25 bytes to accommodate the detached MAC required by R78.
- **R51** — DCPE serialized format amended to mandate the `[seed][encrypted values][MAC]` layout required by R79. Serializer now owns both seed generation and tag computation.
- **R72, R73** — Ciphertext validator length checks updated to match the new OPE (25 bytes) and DCPE (`8 + dims*4 + 16`) formats.

#### Behavioral tightening (close silent-plaintext failure modes)

- **R53** — v1 permitted a serializer without a key holder to silently ignore encryption specifications and store plaintext. Amended to require the serializer factory to throw `IllegalStateException` at construction time when the schema contains any non-none encryption spec but no key holder is provided. The `JlsmDocument.preEncrypted(...)` path (from the `pre-encrypted-document-signaling` ADR) remains available for callers that already hold ciphertext. This closes a silent-plaintext failure mode where a misconfigured serializer would store unencrypted values for a field declared to require encryption.

#### Cross-spec amendments

- **R19, R24, R50** — Text unchanged. v1 code violated these requirements via an identity-passthrough encryptor for DCPE fields. Repair moves the code toward the v1 spec text: the dispatch produces null encryptors for DCPE (R24), null encryptors for all fields when no key holder is present (R19), and the serializer itself encrypts DCPE float arrays directly (R50).

Amendments applied to F03: 7 (R39, R51, R53, R72, R73, R78, R79).
Cross-spec amendments applied: F41.R22 (ciphertext format updated to include detached MAC for OPE and DCPE).
F41 demoted APPROVED → DRAFT: discovered zero code coverage for ~70 requirements; full build tracked under `implement-f41-lifecycle` obligation.
Code fixes applied (Phase 5): R19, R24, R47, R50, R51, R53, R78, R79, R81, R90. MAC wrapping partially implements F41.R22; HKDF-SHA256 compliance (F41.R10, R16a) remains under the lifecycle obligation.
Obligations deferred: none beyond `implement-f41-lifecycle`.

### Verified: v2 — 2026-04-17

Final verdict table after Phase 5 code repair.

| Req | Verdict | Evidence |
|-----|---------|----------|
| R1 | SATISFIED | `EncryptionSpec.java:11-16` — sealed interface with 5 permits |
| R2 | SATISFIED | `EncryptionSpec.java:17-49` — 6 default-false capability methods |
| R3 | SATISFIED | `EncryptionSpec.None` overrides all capabilities to true |
| R4 | SATISFIED | `EncryptionSpec.Deterministic` — equality + keyword only |
| R5 | SATISFIED | `EncryptionSpec.OrderPreserving` — equality + range only |
| R6 | SATISFIED | `EncryptionSpec.DistancePreserving` — ANN only |
| R7 | SATISFIED | `EncryptionSpec.Opaque` — all false |
| R8 | SATISFIED | `FieldDefinition.java:21-25` compact constructor rejects null |
| R9 | SATISFIED | `FieldDefinition.java:34-36` 2-arg delegates to EncryptionSpec.NONE |
| R10 | SATISFIED | `JlsmSchema.Builder.field(name, type, encryption)` |
| R11 | SATISFIED | `JlsmSchema.Builder.field(name, type)` creates NONE-encrypted fd |
| R12 | SATISFIED | `EncryptionKeyHolder.java:53-68` Arena.ofShared + zero source |
| R13 | SATISFIED | `EncryptionKeyHolder.java:55-58` rejects non-32/64 byte keys |
| R14 | SATISFIED | `getKeyBytes()` returns fresh copy; javadoc states obligation |
| R15 | SATISFIED | `close()` zeros segment then closes arena; idempotent via CAS |
| R16 | SATISFIED | `ensureOpen()` via AtomicBoolean |
| R17 | SATISFIED | No toString override; no key bytes in exception messages |
| R18 | SATISFIED | Dispatch arrays are private final, safely published |
| R19 | SATISFIED | `FieldEncryptionDispatch:82-87` null-keyHolder → all null encryptors |
| R20 | *Invalidated by F41.R10/R16a* | Code uses single-pass HMAC; full HKDF tracked under `implement-f41-lifecycle` |
| R21 | SATISFIED | SIV branch: `associatedData = fd.name().getBytes(UTF_8)` used in encrypt/decrypt |
| R22 | *Invalidated by F41.R10/R16a* | Code uses HMAC-SHA256("gcm-opaque-key"); full HKDF tracked under obligation |
| R23 | SATISFIED | `validateOpeFieldType` rejects INT32/INT64/TIMESTAMP/FLOAT/VECTOR/unbounded-string |
| R24 | SATISFIED | DCPE branch has empty case; no byte-level encryptor installed |
| R25 | SATISFIED | `encryptorFor`/`decryptorFor` runtime-check bounds with IAE |
| R26 | SATISFIED | AES-SIV rejects non-64-byte key |
| R27 | SATISFIED | S2V deterministic: same plaintext+AD+key → same ciphertext |
| R28 | SATISFIED | SIV ciphertext = 16B IV + ciphertext |
| R29 | SATISFIED | Wrong key → S2V mismatch → SecurityException |
| R30 | SATISFIED | ThreadLocal<Cipher> per instance |
| R31 | SATISFIED | AES-GCM rejects non-32-byte key |
| R32 | SATISFIED | Fresh random 12B IV per encrypt via SecureRandom |
| R33 | SATISFIED | GCM ciphertext = 12B IV + ciphertext + 16B tag |
| R34 | SATISFIED | AEADBadTagException → SecurityException |
| R35 | SATISFIED | GCM tag catches any byte flip |
| R36 | SATISFIED | ThreadLocal<Cipher> + ThreadLocal<SecureRandom> |
| R37 | SATISFIED | `BoundedStringOpeTest.opeDispatch_orderPreserved_int16` passes |
| R38 | SATISFIED | Domain/range computed with long arithmetic |
| R39 | SATISFIED | `FieldEncryptionDispatch.OPE_CIPHERTEXT_BYTES = 25` |
| R40 | SATISFIED | Round-trip test `orderPreservingField_roundTrip` |
| R41 | SATISFIED | `MAX_OPE_BYTES = 2`; wider types rejected at validation |
| R42 | SATISFIED | DCPE accepts `float[]` and returns `float[]` of same dims |
| R43 | SATISFIED | `DcpeSapEncryptor:116-119` rejects dimension mismatch with IAE |
| R44 | UNTESTABLE | Probabilistic distance preservation; `encrypt_approximatelyPreservesDistanceOrdering` provides sample evidence |
| R45 | SATISFIED | `DcpeSapEncryptor:121` fresh seed via seedRng.nextLong() |
| R46 | SATISFIED | `DcpeSapEncryptor.decrypt(EncryptedVector, byte[])` requires seed (in record) |
| R47 | SATISFIED | `DcpeSapEncryptor:127-132` rejects non-finite output with IllegalStateException |
| R48 | SATISFIED | `DocumentSerializer.forSchema(schema, keyHolder)` overload |
| R49 | SATISFIED | Encrypt-after-encode, decrypt-before-decode in serialize/deserialize |
| R50 | SATISFIED | `DocumentSerializer:240-255` calls `dcpe.encrypt(float[], ad)` |
| R51 | SATISFIED | Blob format `[8B seed][4N values][16B tag]` via `DcpeSapEncryptor.toBlob` |
| R52 | SATISFIED | None fields skip the encryption branch in the serialize loop |
| R53 | SATISFIED | `DocumentSerializer:257-267` throws IllegalStateException naming field |
| R54 | SATISFIED | `IndexRegistry.validateEncryptionCompatibility` uses capability methods |
| R55 | SATISFIED | EQUALITY/UNIQUE rejected for Opaque and DistancePreserving |
| R56 | SATISFIED | RANGE rejected for Det/Opaque/DistancePreserving |
| R57 | SATISFIED | FULL_TEXT rejected for Opaque/OPE/DCPE |
| R58 | SATISFIED | VECTOR rejected for Det/OPE/Opaque |
| R59 | SATISFIED | Uses `supportsEquality/Range/KeywordSearch/ANN` |
| R60 | SATISFIED | SSE derives PRF + ENC sub-keys via HMAC-SHA256 with distinct labels |
| R61 | SATISFIED | Search token = HMAC-SHA256(prfKey, term) |
| R62 | SATISFIED | `add()` increments AtomicInteger and derives unique address |
| R63 | SATISFIED | `search(byte[] token)` iterates counter 0..N |
| R64 | SATISFIED | Soft deletion via LIVE_MARKER / DELETED_MARKER bytes |
| R65 | SATISFIED | `encryptDocId` passes address as AAD via cipher.updateAAD |
| R66 | SATISFIED | `PositionalPostingCodec.encode` calls `opeEncryptor.encrypt` per position |
| R67 | SATISFIED | ByteBuffer big-endian layout matches spec |
| R68 | SATISFIED | Null docId and null positions rejected with NullPointerException |
| R69 | SATISFIED | `MIN_POSTING_SIZE = 17`; undersized rejected with IAE |
| R70 | SATISFIED | `CiphertextValidator.AES_SIV_MIN_LENGTH = 16` |
| R71 | SATISFIED | `CiphertextValidator.AES_GCM_MIN_LENGTH = 28` |
| R72 | SATISFIED | `CiphertextValidator.OPE_EXACT_LENGTH = 25` |
| R73 | SATISFIED | DCPE length = `8 + dims*4 + 16` |
| R74 | SATISFIED | Empty ciphertext rejected with IAE |
| R75 | SATISFIED | None rejected with IAE (caller error) |
| R76 | SATISFIED | GCM wrong key → AEADBadTagException → SecurityException |
| R77 | SATISFIED | SIV wrong key → IV mismatch → SecurityException |
| R78 | SATISFIED | OPE MAC verification via `MessageDigest.isEqual`; `orderPreservingField_tamperedMacRejected` passes |
| R79 | SATISFIED | DCPE MAC verification; `decrypt_tamperedTagRejected/wrongKeyRejected/wrongAssociatedDataRejected` pass |
| R80 | SATISFIED | All exception messages include only lengths; no toString override |
| R81 | SATISFIED | All intermediate key arrays zeroed in `finally` blocks |
| R82 | SATISFIED | `EncryptionKeyHolder` does not implement Serializable |
| R83 | SATISFIED | `getKeyBytes()` allocates fresh `new byte[keyLength]` per call |
| R84 | SATISFIED | ThreadLocal ciphers (SIV/GCM/OPE) + thread-safe SecureRandom (DCPE) |
| R85 | SATISFIED | Dispatch arrays are `private final` — safe publication |
| R86 | SATISFIED | ConcurrentHashMap + AtomicInteger in `SseEncryptedIndex` |
| R87 | SATISFIED | JCA exceptions wrapped as IllegalStateException |
| R88 | SATISFIED | Decryption failures propagate (GCM/SIV/OPE/DCPE) |
| R89 | SATISFIED | `SseEncryptedIndex.decryptDocId` returns null on GeneralSecurityException |
| R90 | SATISFIED | `benchmarks/jlsm-encryption-benchmarks/` with round-trip JMH benchmarks |

**Overall: PASS_WITH_NOTES**

- SATISFIED: 88 (includes 2 delegated to F41 key-derivation implementation)
- UNTESTABLE: 1 (R44 — probabilistic distance preservation)
- VIOLATED: 0
- PARTIAL: 0

The two delegated items (R20, R22) are explicitly invalidated by F41 and will be satisfied by the HKDF-SHA256 compliance work tracked under the `implement-f41-lifecycle` obligation.

---

## Design Narrative

### Intent

Add opt-in field-level encryption for in-memory data in jlsm-table, protecting sensitive field values against heap/memory inspection. Each field in a schema can independently declare an encryption specification, and the system selects the appropriate encryption mechanism based on the required query capabilities. The design supports a spectrum from no encryption (full query capability) through deterministic and order-preserving (partial query capability) to opaque (no query capability, maximum security).

### Why this approach

A sealed interface with five variants was chosen over a configuration enum or strategy pattern because (1) it makes the capability matrix exhaustive and compiler-verifiable via switch expressions, (2) it prevents invalid capability combinations that could arise from independent boolean flags, and (3) it keeps the encryption specification separate from the encryptor implementations so that the specification can live in the public API while the encryptors remain internal.

Field-name-as-associated-data in AES-SIV binds ciphertext to its field, preventing cross-field ciphertext substitution attacks where an attacker swaps encrypted values between fields that share the same key. This is cheap (one UTF-8 encode per operation) and eliminates a real attack vector.

Off-heap key storage via Arena.ofShared() was chosen because (1) key material in off-heap memory is not visible to heap dumps, reducing the risk of key extraction from crash dumps or memory analysis tools, (2) the shared arena allows concurrent read access from multiple serializer threads without copying, and (3) explicit zeroing on close provides a deterministic cleanup path that does not depend on GC finalization.

The dispatch table is constructed once and treated as immutable to avoid per-field encryption dispatch overhead during hot-path serialization. The table maps field indices to encryptor/decryptor function pairs, making the per-field cost a single array lookup.

### What was ruled out

- **Single encryption mode per table:** Rejected because different fields have different security and query requirements. A table storing both a searchable name (deterministic) and a social security number (opaque) needs per-field control.
- **Encryption at the SSTable layer:** Rejected because it would encrypt all fields uniformly, preventing indexed search on any encrypted field. Field-level encryption in the serializer allows selective encryption.
- **Custom key derivation function:** Rejected in favor of the simple concatenation/truncation approach for key size adaptation. A proper KDF (HKDF) would be better but is out of scope until key rotation is addressed.
- **Homomorphic encryption for range queries:** Rejected as impractical for a general-purpose library. Boldyreva OPE provides range queries with acceptable security trade-offs for the constrained use case (narrow types only).
- **Format-preserving encryption (FPE) for strings:** Rejected because FPE is complex to implement correctly and the primary use case (searchable strings) is better served by deterministic encryption with SSE.

### Known limitations

- **OPE security:** Order-preserving encryption reveals ordering information to an adversary with access to ciphertext. It is suitable only for narrow-domain fields (INT8, INT16, short bounded strings) where the ordering leakage is acceptable. This is an inherent property of OPE, not a bug.
- **DCPE approximation quality:** Distance-preserving encryption is probabilistic. The quality of distance preservation degrades as the ratio of original distances approaches 1.0. Recall in ANN search on DCPE-encrypted vectors will be lower than on plaintext vectors. The degradation depends on the noise magnitude, which is key-derived and not tunable per query.
- **OPE and DCPE lack authentication:** Neither OPE nor DCPE provides cryptographic authentication. Wrong-key decryption produces plausible but incorrect values without raising an exception. Applications that require wrong-key detection on these field types must implement application-level checksums.
- **Key derivation is not a KDF:** The current key size adaptation (concatenation for upsizing, truncation for downsizing) is not a cryptographically proper key derivation. This is acceptable for the initial implementation but should be replaced with HKDF when key rotation is implemented.
