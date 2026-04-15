# Constraints — Index Access Pattern Leakage

## Scale
- Mitigations must not add O(n) overhead per query — only constant-factor additions
- Response padding overhead must be bounded (at most 2x bandwidth)

## Resources
- Per-field key derivation: one HKDF call per field at schema construction — negligible
- Response padding: at most 2x result set size (power-of-2 bucketing)

## Complexity Budget
- Full feature but scope is documentation + low-cost mitigations, not ORAM
- ORAM is explicitly out of scope for v1 (10-100x throughput reduction)

## Accuracy / Correctness
- Per-field keys: must produce cryptographically independent keys (HKDF)
- Response padding: must not break iterator semantics — consumers must strip padding
- Leakage documentation: must accurately describe L1-L4 leakage per EncryptionSpec

## Operational
- Mitigations are transparent — no caller configuration needed for per-field keys
- Response padding is opt-in at query level
- Leakage profiles documented per EncryptionSpec variant

## Fit
- Per-field keys: already decided in per-field-key-binding (HKDF derivation)
- Response padding: composes with query result iterators
- Documentation: extends EncryptionSpec or a companion enum
