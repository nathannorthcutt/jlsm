---
{
  "id": "encryption.primitives-variants",
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
# encryption.primitives-variants — Primitives Variants

## Requirements

### Encryption specification model

R1. The encryption specification must be a sealed type with exactly five variants: none (plaintext), deterministic, order-preserving, distance-preserving, and opaque. No additional variants may be added without changing the sealed permits list.

R2. Each encryption specification variant must expose boolean capability methods indicating which query operations it supports: equality, range, keyword search, phrase search, SSE search, and approximate nearest-neighbor search. Capability methods must return false by default; each variant overrides only the capabilities it provides.

R3. The none variant must report all capabilities as true, reflecting that unencrypted values support all query operations without restriction.

R4. The deterministic variant must report support for equality and keyword search only. It must not report support for range, phrase search, SSE search, or approximate nearest-neighbor search.

R5. The order-preserving variant must report support for equality and range queries only. It must not report support for keyword search, phrase search, SSE search, or approximate nearest-neighbor search.

R6. The distance-preserving variant must report support for approximate nearest-neighbor search only. It must not report support for equality, range, keyword search, phrase search, or SSE search.

R7. The opaque variant must report no query capabilities. All capability methods must return false.

### Deterministic encryption (AES-SIV)

R8. The AES-SIV encryptor must require a 512-bit (64-byte) key. A key of any other length must be rejected at construction time with an IllegalArgumentException.

R9. AES-SIV encryption must be deterministic: the same plaintext and associated data under the same key must always produce the same ciphertext. This property is required for equality-based index lookups.

R10. AES-SIV ciphertext format must be a 16-byte synthetic IV followed by the ciphertext. The total output length must be exactly 16 bytes longer than the input plaintext.

R11. AES-SIV decryption with the wrong key or with tampered ciphertext must throw an exception. It must not return corrupted plaintext.

R12. AES-SIV encryptor instances must be safe to use concurrently from multiple threads. Thread-local cipher instances are an acceptable mechanism.

### Opaque encryption (AES-GCM)

R13. The AES-GCM encryptor must require a 256-bit (32-byte) key. A key of any other length must be rejected at construction time with an IllegalArgumentException.

R14. AES-GCM encryption must use a fresh random 12-byte IV for every encrypt call. The IV must be generated from a SecureRandom instance.

R15. AES-GCM ciphertext format must be a 12-byte IV followed by the ciphertext followed by a 16-byte authentication tag. The total output length must be exactly 28 bytes longer than the input plaintext.

R16. AES-GCM decryption with the wrong key must throw an exception (tag verification failure). It must not return corrupted plaintext silently.

R17. AES-GCM decryption with a tampered ciphertext (any byte flipped in IV, ciphertext, or tag) must throw an exception. The authentication tag must detect any single-byte modification.

R18. AES-GCM encryptor instances must be safe to use concurrently from multiple threads.

### Order-preserving encryption (Boldyreva OPE)

R19. The OPE encryptor must preserve ordering: for any two plaintext values a < b, the corresponding ciphertext values must satisfy encrypt(a) < encrypt(b). This property must hold for all values within the configured domain.

R20. The OPE domain size must be derived from the field type's byte width: 256 raised to the power of the byte count. The range must be a fixed multiple of the domain to provide ciphertext space. Both domain and range must be computed using long arithmetic to prevent integer overflow.

R21. OPE ciphertext format must be a 1-byte original-length prefix followed by an 8-byte big-endian encrypted long followed by a 16-byte detached HMAC-SHA256 authentication tag. The total output must be exactly 25 bytes regardless of input size. See R78 for MAC derivation and verification requirements.

R22. OPE decryption must reconstruct the original byte array at the original length (stored in the length prefix). The round-trip encrypt-then-decrypt must produce output identical to the input for all values within the field type's domain.

R23. The OPE byte limit must be 2. Field types requiring more than 2 bytes of OPE input must be rejected at schema validation time, not at encryption time.

### Distance-preserving encryption (DCPE / Scale-And-Perturb)

R24. The DCPE encryptor must accept a float array and return an encrypted float array of identical dimensionality. The output dimensionality must equal the input dimensionality exactly.

R25. The DCPE encryptor must reject input vectors whose dimensionality does not match the configured dimensions. The mismatch must produce an IllegalArgumentException stating the expected and actual dimensions.

R26. DCPE encryption must approximately preserve distance relationships: for three vectors a, b, c where distance(a, b) < distance(a, c), the encrypted vectors must satisfy distance(encrypt(a), encrypt(b)) < distance(encrypt(a), encrypt(c)) with high probability. The approximation quality degrades as the original distance ratio approaches 1.0.

R27. DCPE encryption must be non-deterministic: encrypting the same vector twice must produce different ciphertext (due to per-encryption random perturbation). Equality comparison on DCPE-encrypted vectors is not meaningful.

R28. DCPE decryption must require the perturbation seed that was generated during encryption. Without the correct seed, exact reconstruction of the plaintext vector is not possible.

R29. DCPE encrypted output must contain only finite float values. If the scaling or perturbation produces NaN or Infinity in any component, the encryptor must reject the operation rather than producing a vector with non-finite components.

### Ciphertext structural validation

R46. The ciphertext validator must check that ciphertext for deterministic encryption is at least 16 bytes (the AES-SIV synthetic IV). Shorter ciphertext must be rejected.

R47. The ciphertext validator must check that ciphertext for opaque encryption is at least 28 bytes (12-byte IV + 16-byte tag). Shorter ciphertext must be rejected.

R48. The ciphertext validator must check that ciphertext for order-preserving encryption is exactly 25 bytes (1-byte length prefix + 8-byte encrypted long + 16-byte authentication tag). Any other length must be rejected.

R49. The ciphertext validator must check that ciphertext for distance-preserving encryption is exactly 8 + dimensions * 4 + 16 bytes (8-byte seed + dimensions * 4 bytes encrypted vector + 16-byte authentication tag), where dimensions comes from the field's vector type. A field with distance-preserving encryption that is not a vector type must be rejected.

R50. The ciphertext validator must reject empty ciphertext (zero-length byte array) for all encryption variants with an IllegalArgumentException.

R51. The ciphertext validator must reject validation requests for fields with the none encryption specification. Validating ciphertext for an unencrypted field is a caller error.

### Wrong-key detection

R52. AES-GCM decryption with the wrong key must produce a GeneralSecurityException (tag verification failure) that surfaces to the caller as an exception, not as corrupted plaintext.

R53. AES-SIV decryption with the wrong key must produce an exception. The S2V verification must detect the key mismatch.

R54. The OPE ciphertext format must include a detached 128-bit HMAC-SHA256 authentication tag that binds the 8-byte OPE ciphertext long and the 1-byte length prefix to the UTF-8 field name and the key holder's identity. The MAC key must be derived from the master key via HMAC-SHA256 using the domain-separated label "ope-mac-key", and the MAC key array must be zeroed in a finally block after encryptor construction. OPE decryption must verify the tag in constant time (using MessageDigest.isEqual) before performing the OPE inverse; a tag mismatch must throw SecurityException whose message does not reveal key content, plaintext, or comparison-timing information. This authenticated wrapping closes the wrong-key and ciphertext-tampering gaps and binds each ciphertext to a specific field, preventing cross-field substitution. The order-preserving property is preserved because the comparison in range queries operates on the 8-byte OPE ciphertext portion only; MAC verification runs at decrypt time after query candidates have been identified.

R55. The DCPE ciphertext format must include a detached 128-bit HMAC-SHA256 authentication tag that binds the 8-byte perturbation seed and the encrypted float array bytes to the UTF-8 field name and the key holder's identity. The MAC key must be derived from the master key via HMAC-SHA256 using the domain-separated label "dcpe-mac-key", and the MAC key array must be zeroed in a finally block after encryptor construction. DCPE decryption must verify the tag in constant time (using MessageDigest.isEqual) before performing the DCPE inverse; a tag mismatch must throw SecurityException whose message does not reveal key content, plaintext, or comparison-timing information. This authenticated wrapping closes the wrong-key and ciphertext-tampering gaps and binds each encrypted vector to a specific field, preventing cross-field substitution. The approximate distance-preservation property is preserved because similarity comparisons operate on the encrypted float array portion only; MAC verification runs at decrypt time after query candidates have been identified.

---

## Design Narrative

### Intent

Generated during the 2026-04-20 spec migration. See `.spec/_archive/migration-2026-04-20/MIGRATION.md` for
the migration plan and `.spec/_archive/migration-2026-04-20/` for the
pre-migration source spec(s) this spec was derived from.
