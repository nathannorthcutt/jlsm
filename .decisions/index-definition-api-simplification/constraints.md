---
problem: "IndexDefinition API simplification — remove vectorDimensions and derive from schema VectorType"
slug: "index-definition-api-simplification"
captured: "2026-03-17"
status: "draft"
---

# Constraint Profile — index-definition-api-simplification

## Problem Statement
Decide whether `IndexDefinition` should continue carrying `vectorDimensions` as an explicit
parameter, or whether it should be removed and derived from the schema's `VectorType` field
at `IndexRegistry` construction time. The new `VectorType` encodes dimensions as part of the
type definition, creating a potential redundancy.

## Constraints

### Scale
Not applicable — this is a compile-time API design decision with no runtime scaling impact.

### Resources
Not applicable — no memory or I/O difference between approaches.

### Complexity Budget
Simpler API is strongly preferred. `IndexDefinition` is a public record in the table API;
fewer required parameters reduce caller burden and eliminate a class of misconfiguration
(dimension mismatch between schema and index definition).

### Accuracy / Correctness
The schema's `VectorType.dimensions()` must be the single source of truth. A column cannot
have different dimension counts for different indices — the dimension is a property of the
data, not the index.

### Operational Requirements
Not applicable — no runtime operational impact.

### Fit
- `IndexDefinition` is a public record — changes affect the public API surface
- `IndexRegistry` already reads the schema during validation — it can extract dimensions there
- `VectorFieldIndex` needs dimensions at construction — must receive them from somewhere
- Existing callers (tests, table builder) must be updated

## Key Constraints (most narrowing)
1. **Single source of truth** — dimensions are a property of the data type, not the index;
   duplicating them creates a consistency risk
2. **API simplicity** — fewer parameters on a public record means less caller burden and
   fewer misconfiguration paths
3. **Backward compatibility** — this is pre-1.0 with no external consumers, so breaking
   changes are acceptable

## Unknown / Not Specified
None — full profile captured.
