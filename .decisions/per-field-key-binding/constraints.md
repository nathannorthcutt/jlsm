# Constraints — Per-Field Key Binding

## Scale
- Key resolution must be O(1) per field — no map lookups in hot path
- Schema construction resolves key binding once; serializer uses pre-built dispatch

## Resources
- Key material held off-heap in Arena-backed EncryptionKeyHolder (existing pattern)
- Per-field keys derived from master key — no additional KMS calls per field

## Complexity Budget
- Minor feature — extend EncryptionSpec to carry optional key identifier or derive per-field keys automatically
- Must not require changes to the sealed EncryptionSpec permits

## Accuracy / Correctness
- Different fields must produce different ciphertexts for the same plaintext (cross-field correlation prevention)
- Key derivation must be deterministic — same master key + field name always produces same per-field key
- Derived keys must be cryptographically independent (HKDF with field name as info)

## Operational
- Backward compatible — existing single-key-per-table usage continues to work
- Migration from single key to per-field keys must not require data rewrite (compaction-driven)

## Fit
- Must compose with EncryptionKeyHolder, FieldEncryptionDispatch, DocumentSerializer dispatch table
- Must compose with encryption-key-rotation (per-field keys rotate independently)
- Must compose with index operations (DET fields need consistent key per field)
