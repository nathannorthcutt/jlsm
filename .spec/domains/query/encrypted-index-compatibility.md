---
{
  "id": "query.encrypted-index-compatibility",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "query",
    "encryption"
  ],
  "requires": [
    "encryption.primitives-variants"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [],
  "kb_refs": [],
  "open_obligations": [],
  "_extracted_from": "encryption.primitives-variants (R30,R31,R32,R33,R34,R35)"
}
---
# query.encrypted-index-compatibility — Encrypted Index Compatibility

## Requirements

R1. The index registry must validate encryption-to-index compatibility at construction time using the encryption specification's capability methods. An index type that requires a capability the field's encryption specification does not provide must be rejected with an IllegalArgumentException.

R2. An equality or unique index on a field with opaque or distance-preserving encryption must be rejected. Only none, deterministic, and order-preserving encryption specifications report equality support.

R3. A range index on a field with deterministic, opaque, or distance-preserving encryption must be rejected. Only none and order-preserving encryption specifications report range support.

R4. A full-text index on a field with opaque, order-preserving, or distance-preserving encryption must be rejected. Only none and deterministic encryption specifications report keyword search support.

R5. A vector index on a field with deterministic, order-preserving, or opaque encryption must be rejected. Only none and distance-preserving encryption specifications report approximate nearest-neighbor support.

R6. Validation of encryption compatibility must use the capability methods on the encryption specification, not pattern matching on variant types. This ensures that if capability methods are overridden in future variants, the validation remains correct.

---

## Design Narrative

### Intent

Extracted from F03 application-layer requirements during the F03 follow-up
split (2026-04-20). Behavior is the F03 originals — see git history of
`.spec/_archive/migration-2026-04-20/encryption/F03-encrypt-memory-data.md`
for the original phrasing and design rationale.
