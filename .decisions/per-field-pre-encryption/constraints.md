# Constraints — Per-Field Pre-Encryption

## Scale
- Document ingestion rates up to millions/sec; per-field encryption check must be O(1) per field
- Schema may have dozens of encrypted fields — the mechanism must not scale with field count at runtime

## Resources
- No additional allocations per document beyond existing dispatch table
- Must compose with MemorySegment-based serialization path

## Complexity Budget
- Minor feature — should be a small extension of the existing all-or-nothing boolean
- Must not require changes to the binary serialized format

## Accuracy / Correctness
- Mixed pre-encrypted and library-encrypted fields in the same document must produce correct ciphertext
- Type validation must be relaxed only for fields marked as pre-encrypted
- Unencrypted fields (EncryptionSpec.NONE) must never be marked as pre-encrypted

## Operational
- Backward compatible — existing `JlsmDocument.of()` and `JlsmDocument.preEncrypted()` APIs unchanged
- `JlsmDocument.preEncrypted()` continues to work (all encrypted fields pre-encrypted)

## Fit
- Must compose with FieldDefinition, EncryptionSpec, DocumentSerializer dispatch table
- Must compose with the per-field-key-binding decision (different keys per field)
- Must not break IndexRegistry, QueryExecutor, or any downstream consumer
