---
{
  "id": "serialization.encrypted-field-serialization",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "serialization",
    "encryption"
  ],
  "requires": [
    "encryption.primitives-variants",
    "encryption.primitives-dispatch",
    "encryption.ciphertext-envelope"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [],
  "kb_refs": [],
  "open_obligations": [],
  "_extracted_from": "encryption.primitives-dispatch (R9,R10,R11,R12,R13,R14)"
}
---
# serialization.encrypted-field-serialization — Encrypted Field Serialization

## Requirements

R1. The document serializer must accept an optional key holder parameter. When a key holder is provided, the serializer must encrypt field values during encoding and decrypt them during decoding, according to each field's encryption specification.

R2. The document serializer must apply field encryption after type-specific serialization (value to bytes) and before writing to the output segment. Decryption must occur after reading bytes from the segment and before type-specific deserialization (bytes to value).

R3. For distance-preserving encrypted vector fields, the serializer must encrypt the float array directly (not the serialized bytes) and serialize the encrypted float array. Decryption must deserialize to a float array first, then apply DCPE decryption using the stored perturbation seed.

R4. The serializer must store the DCPE perturbation seed and detached authentication tag alongside each distance-preserving encrypted vector value. The DCPE on-disk layout is canonically specified in `encryption.ciphertext-envelope` R1 (the DistancePreserving variant): `[4B DEK version | 8B perturbation seed | N*4B encrypted floats | 16B detached HMAC-SHA256 tag]`, total `8 + N*4 + 20` bytes. Both seed and tag must be included in the serialized output so that decryption can authenticate and reconstruct the original vector. MAC derivation and verification requirements are defined by `encryption.primitives-variants` R79. This requirement preserves `@spec serialization.encrypted-field-serialization.R4` annotations; the canonical byte layout now lives in the envelope spec.

R5. For fields with the none encryption specification, the serializer must not invoke any encryption or decryption operation, regardless of whether a key holder is present.

R6. The serializer constructed without a key holder must produce output byte-for-byte identical to the non-encrypted serializer for schemas where every field uses the none encryption specification. For schemas containing any field with a non-none encryption specification, serialization must reject any document that carries a non-null value for such a field unless the document is pre-encrypted (JlsmDocument.preEncrypted): the serializer must throw IllegalStateException at serialize time naming the offending field, rather than silently storing plaintext for a field declared to require encryption. A serializer constructed without a key holder remains usable for pre-encrypted documents, whose ciphertext the caller supplies directly; it must reject any non-pre-encrypted document that would require the library to perform encryption.

---

## Design Narrative

### Intent

Extracted from F03 application-layer requirements during the F03 follow-up
split (2026-04-20). Behavior is the F03 originals — see git history of
`.spec/_archive/migration-2026-04-20/encryption/F03-encrypt-memory-data.md`
for the original phrasing and design rationale.

### Amended: v2 — 2026-04-23 — depend on encryption.ciphertext-envelope

- **Frontmatter `requires` extended** to include `encryption.ciphertext-envelope`. Declares the previously-implicit dependency explicit at the manifest level.
- **R4 rewritten** to reference `encryption.ciphertext-envelope.R1` (DistancePreserving variant) for the canonical DCPE byte layout. The inline duplication of the layout bytes is removed; `@spec serialization.encrypted-field-serialization.R4` annotations remain valid (the ID still exists and still asserts the same contract via normative pointer). No behavioural change.

Rationale: before v2, R4 duplicated the DCPE on-disk layout inline. The extraction of `encryption.ciphertext-envelope` (encryption.primitives-lifecycle v8) made it possible — and safer — to cite a single canonical source. This eliminates the drift risk between R4's inline bytes and F41.R22's inline bytes that existed pre-split.
