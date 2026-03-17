---
problem: "index-definition-api-simplification"
evaluated: "2026-03-17"
candidates:
  - path: "(codebase-derived)"
    name: "Derive from schema"
  - path: "(codebase-derived)"
    name: "Keep redundant"
constraint_weights:
  scale: 1
  resources: 1
  complexity: 3
  accuracy: 3
  operational: 1
  fit: 3
---

# Evaluation — index-definition-api-simplification

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used: none — API design decision grounded in codebase

## Constraint Summary
The API must enforce that vector dimensions are a single-source-of-truth property of the data
type (VectorType), not duplicated across the schema and index definition. Simpler public API
with fewer parameters is strongly preferred. Pre-1.0 library, so breaking changes are acceptable.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 1 | No runtime impact |
| Resources | 1 | No runtime impact |
| Complexity | 3 | Public API simplicity directly affects usability |
| Accuracy | 3 | Single source of truth is a correctness invariant |
| Operational | 1 | No runtime impact |
| Fit | 3 | Must integrate cleanly with IndexRegistry, VectorFieldIndex, existing tests |

---

## Candidate: Derive from Schema

**Source:** Codebase analysis — `IndexDefinition.java`, `IndexRegistry.java`, `VectorType` (proposed)

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | No runtime impact |
| Resources | 1 | 5 | 5 | No runtime impact |
| Complexity | 3 | 5 | 15 | Removes one parameter from public record; VECTOR constructor: `new IndexDefinition(field, VECTOR, similarityFn)` |
| Accuracy | 3 | 5 | 15 | Schema is sole authority; no possibility of dimension mismatch |
| Operational | 1 | 5 | 5 | No runtime impact |
| Fit | 3 | 4 | 12 | IndexRegistry already reads schema; extract dimensions in validate(). VectorFieldIndex receives dimensions from registry. Minor: callers lose explicit dimension visibility in the definition. |
| **Total** | | | **57** | |

**Hard disqualifiers:** None

**Key strengths:**
- Eliminates an entire class of bugs (dimension mismatch between schema and index)
- Simpler public API — callers only provide what they actually control (field, type, similarity)
- `IndexRegistry.validate()` already has the schema — zero additional plumbing to extract dims

**Key weaknesses:**
- Dimensions are no longer visible when inspecting an `IndexDefinition` in isolation (must consult schema)

---

## Candidate: Keep Redundant

**Source:** Current `IndexDefinition.java` implementation

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | No runtime impact |
| Resources | 1 | 5 | 5 | No runtime impact |
| Complexity | 3 | 2 | 6 | Retains a parameter that duplicates schema data; callers must keep them in sync |
| Accuracy | 3 | 2 | 6 | Two sources of truth for dimensions — risk of mismatch even with validation |
| Operational | 1 | 5 | 5 | No runtime impact |
| Fit | 3 | 3 | 9 | Current code works, but VectorType makes vectorDimensions conceptually redundant |
| **Total** | | | **36** | |

**Hard disqualifiers:** None, but the redundancy is a design smell

**Key strengths:**
- Dimensions visible on the definition without consulting the schema
- No code changes to existing constructor

**Key weaknesses:**
- Callers must pass dimensions that already exist in the schema — error-prone
- Cross-validation between IndexDefinition and schema adds complexity to IndexRegistry

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| Derive from schema | 5 | 5 | 15 | 15 | 5 | 12 | **57** |
| Keep redundant | 5 | 5 | 6 | 6 | 5 | 9 | **36** |

## Preliminary Recommendation
Derive from schema wins decisively (57 vs 36). The gap is entirely in the Complexity and
Accuracy dimensions — the two highest-weighted constraints.

## Risks and Open Questions
- **Risk**: If a future use case requires creating an IndexDefinition before a schema exists
  (e.g., migration tooling), the dimensions would need to come from somewhere. Currently no
  such use case exists.
- **Risk**: `VectorFieldIndex` constructor currently receives dimensions from `IndexDefinition`.
  After this change, `IndexRegistry` must pass dimensions from the schema instead. This is a
  minor plumbing change.
