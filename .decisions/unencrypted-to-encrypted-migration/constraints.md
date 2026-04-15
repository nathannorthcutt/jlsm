# Constraints — Unencrypted-to-Encrypted Migration

## Scale
- Migration must not require stop-the-world — online migration via compaction
- Data volume may be large — must be I/O-efficient

## Resources
- No 2x storage overhead — in-place migration via compaction rewrites
- Migration tracking metadata is O(1) per SSTable

## Complexity Budget
- Full feature — but the mechanism (compaction-driven encryption) is already designed for key rotation
- Must handle mixed unencrypted/encrypted reads during migration window

## Accuracy / Correctness
- Unencrypted SSTables must remain readable during migration
- Encrypted SSTables use new schema's encryption config
- Indexes on DET/OPE fields must be rebuilt after migration completes
- Write path switches to encrypted immediately on schema update

## Operational
- Migration is triggered by schema update (field gains EncryptionSpec)
- Convergence via compaction — same timeline as key rotation
- Rollback: schema can be reverted to unencrypted before migration completes

## Fit
- Must compose with encryption-key-rotation (same compaction-driven mechanism)
- Must compose with per-field-key-binding (HKDF derivation)
- Must compose with string-to-bounded-string-migration pattern (compaction-time migration)
