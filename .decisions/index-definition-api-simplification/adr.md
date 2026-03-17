---
problem: "index-definition-api-simplification"
date: "2026-03-17"
version: 1
status: "confirmed"
supersedes: null
---

# ADR-002 — Index Definition API Simplification

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| (none) | API design decision grounded in codebase, not research | — |

---

## Problem
Should `IndexDefinition` carry `vectorDimensions` as an explicit parameter, or should it be
removed and derived from the schema's `VectorType` field at `IndexRegistry` construction time?

## Constraints That Drove This Decision
- **Single source of truth**: Dimensions are a property of the data type (`VectorType`), not
  the index. Duplicating them creates a consistency risk.
- **API simplicity**: Fewer parameters on a public record means less caller burden and fewer
  misconfiguration paths.
- **Fit**: `IndexRegistry` already has the schema during validation — extracting dimensions
  there requires no new plumbing.

## Decision
**Chosen approach: Derive from Schema**

Remove `vectorDimensions` from the `IndexDefinition` record. When `IndexRegistry` validates a
VECTOR index, it reads dimensions from the schema field's `VectorType.dimensions()`. The VECTOR
index constructor simplifies to `new IndexDefinition(fieldName, VECTOR, similarityFunction)`.
`VectorFieldIndex` receives dimensions from the registry, not the definition.

## Rationale

### Why Derive from Schema
- **Single source of truth**: The schema's `VectorType` is the sole authority on a column's
  dimensions. No possibility of mismatch between definition and schema.
- **API simplicity**: Removes one parameter from the public record. Callers only provide what
  they actually control: field name, index type, and similarity function.
- **Fit**: `IndexRegistry.validate()` already reads the schema to check field existence and
  type compatibility. Extracting `VectorType.dimensions()` is a one-line addition.

### Why not Keep Redundant
- **Duplication**: Two sources of truth for the same value. Even with cross-validation, callers
  must keep them in sync — an unnecessary burden and bug surface.

## Implementation Guidance
- Remove `vectorDimensions` field from `IndexDefinition` record
- Add a new constructor: `IndexDefinition(String fieldName, IndexType indexType, SimilarityFunction similarityFunction)`
- Keep the two-arg constructor `IndexDefinition(String fieldName, IndexType indexType)` for non-vector types
- In `IndexRegistry.validate()`: when `indexType == VECTOR`, cast the field type to `VectorType`
  and extract `dimensions()`. Pass to `VectorFieldIndex` constructor.
- Update `IndexDefinition` compact constructor: remove `vectorDimensions > 0` validation for
  VECTOR type (dimensions no longer on the record)
- Update all test callsites that pass `vectorDimensions`

## What This Decision Does NOT Solve
- `SimilarityFunction` remains on `IndexDefinition` — it is an index-level concern (different
  indices on the same field could use different similarity functions)
- Other index types (EQUALITY, RANGE, UNIQUE, FULL_TEXT) are unaffected

## Conditions for Revision
This ADR should be re-evaluated if:
- A use case emerges where `IndexDefinition` is constructed without a corresponding schema
  (e.g., standalone migration tooling)
- Multiple vector indices on the same field need different dimension handling (unlikely given
  the fixed-dimension invariant of `VectorType`)

---
*Confirmed by: user deliberation | Date: 2026-03-17*
*Full scoring: [evaluation.md](evaluation.md)*
