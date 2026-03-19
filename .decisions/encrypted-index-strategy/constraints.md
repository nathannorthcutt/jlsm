---
problem: "How should secondary indices and queries adapt when field values are encrypted — which index types work with which EncryptionSpec variants, and what query operations are supported?"
slug: "encrypted-index-strategy"
captured: "2026-03-18"
status: "draft"
---

# Constraint Profile — encrypted-index-strategy

## Problem Statement
Define which query and index operations are available for each EncryptionSpec variant. Secondary indices (FieldIndex, VectorFieldIndex, full-text via LsmInvertedIndex) currently operate on plaintext values. When fields are encrypted, indices must either operate on encrypted representations (if the scheme permits) or be disabled for that field. The strategy must be explicit — no silent degradation where an index exists but returns wrong results.

## Constraints

### Scale
Billions of records, millions of QPS across partitioned tables. Index operations on encrypted fields must not become a bottleneck — if an encryption scheme supports indexing, the index operation overhead should be proportional to the encryption overhead, not multiplicative.

### Resources
CPU + RAM only. No specialized hardware. Same ArenaBufferPool memory budgets.

### Complexity Budget
Very high. Team can implement complex index adaptations.

### Accuracy / Correctness
Critical. An index on an encrypted field must return correct results for the operations the encryption scheme supports, or the index must be explicitly prohibited. Never return silently wrong results from an incompatible index/encryption combination.

### Operational Requirements
Minimize latency. Deterministic operation caps. Graceful degradation: if a query targets an encrypted field with an incompatible operation, fail with a clear error rather than silently skipping or returning empty results.

### Fit
Must compose with existing FieldIndex, VectorFieldIndex, IndexRegistry, QueryExecutor, and the EncryptionSpec sealed interface from the field-encryption-api-design ADR.

## Key Constraints (most narrowing)
1. **Correctness over capability** — better to prohibit an index than to allow one that returns wrong results
2. **EncryptionSpec determines capability** — the sealed interface already categorizes encryption families; index capability maps directly from it
3. **Fit with existing index infrastructure** — FieldIndex, VectorFieldIndex, QueryExecutor must adapt without restructuring

## Unknown / Not Specified
None — full profile captured (inherits from field-encryption-api-design).
