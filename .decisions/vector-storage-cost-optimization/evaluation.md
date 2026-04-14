---
problem: "vector-storage-cost-optimization"
evaluated: "2026-04-13"
candidates:
  - path: "design-approach"
    name: "QuantizedVectorType sealed permit"
  - path: "design-approach"
    name: "Quantization on IndexDefinition"
  - path: "design-approach"
    name: "Quantization as index builder policy"
constraint_weights:
  scale: 3
  resources: 2
  complexity: 1
  accuracy: 3
  operational: 2
  fit: 3
---

# Evaluation — vector-storage-cost-optimization

## References
- Constraints: [constraints.md](constraints.md)
- KB sources: [vector-quantization/CLAUDE.md](../../.kb/algorithms/vector-quantization/CLAUDE.md),
  [scalar-quantization.md](../../.kb/algorithms/vector-quantization/scalar-quantization.md),
  [rabitq.md](../../.kb/algorithms/vector-quantization/rabitq.md)

## Constraint Summary
Quantization must be configurable (user chooses accuracy/storage tradeoff),
streaming-compatible by default, and fit the existing index architecture where
VectorType is the logical type and IvfFlat/HNSW are the storage implementations.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 3 | Billion-vector is the motivation |
| Resources | 2 | SIMD matters but not the deciding factor |
| Complexity | 1 | Expert team |
| Accuracy | 3 | User-configurable tradeoff is critical |
| Operational | 2 | Streaming required but not contentious |
| Fit | 3 | Architecture integration is the core design question |

---

## Candidate: QuantizedVectorType sealed permit

**Design:** New sealed permit `record QuantizedVectorType(Primitive source, int dimensions, QuantizationScheme scheme)`. Compile-time distinction between flat and quantized vectors.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 3 | 4 | 12 | Supports any quantization scheme via QuantizationScheme |
| Resources | 2 | 3 | 6 | Same SIMD paths, but switch-site overhead |
| Complexity | 1 | 2 | 2 | New sealed permit → ~10 switch arms across jlsm-table/sql |
| Accuracy | 3 | 4 | 12 | QuantizationScheme makes accuracy tradeoff explicit in type |
| Operational | 2 | 3 | 6 | Streaming vs batch is in the scheme, not enforced by type |
| Fit | 3 | 2 | 6 | Quantization is NOT a logical type — it's a storage concern. Putting it in FieldType mixes abstraction levels. A 768-dim float32 vector is the same type whether stored flat or SQ8. |
| **Total** | | | **44** | |

**Hard disqualifiers:** Fit score of 2 — mixes type system with storage concern.

---

## Candidate: Quantization on IndexDefinition

**Design:** Add `QuantizationConfig quantization` to IndexDefinition record alongside `similarityFunction`. `IndexDefinition(field, VECTOR, similarity, quantization)`. The index decides how to store vectors, not the type.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 3 | 4 | 12 | Any quantization scheme supported via config |
| Resources | 2 | 4 | 8 | Zero switch-site changes in FieldType handling |
|  |  |  |  | **Would be a 2 if:** IndexDefinition record grew too many optional fields |
| Complexity | 1 | 4 | 4 | Config on existing record — minimal new code |
|  |  |  |  | **Would be a 2 if:** IndexDefinition becomes a builder instead of a record |
| Accuracy | 3 | 4 | 12 | QuantizationConfig makes tradeoff explicit at index creation |
|  |  |  |  | **Would be a 2 if:** quantization choice couldn't be changed after index creation |
| Operational | 2 | 4 | 8 | Streaming constraint expressible in config validation |
|  |  |  |  | **Would be a 2 if:** config options were confusing to users |
| Fit | 3 | 4 | 12 | IndexDefinition already has VECTOR-specific fields (similarityFunction). Adding quantization follows the same pattern. |
|  |  |  |  | **Would be a 2 if:** non-VECTOR indices start needing their own optional configs |
| **Total** | | | **56** | |

---

## Candidate: Quantization as index builder policy

**Design:** Quantization configured on the vector index builder, not on IndexDefinition. `LsmVectorIndex.builder().quantization(SQ8).build(...)`. Separate from schema — pure implementation concern.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 3 | 4 | 12 | Any quantization scheme supported |
| Resources | 2 | 5 | 10 | Zero changes to schema or definition layer |
|  |  |  |  | **Would be a 2 if:** the builder pattern made quantization invisible to schema introspection |
| Complexity | 1 | 4 | 4 | Config at builder level is clean |
| Accuracy | 3 | 3 | 9 | Tradeoff is configured at build time but NOT visible in schema. If you inspect the schema, you can't tell if vectors are quantized. |
| Operational | 2 | 4 | 8 | Same as IndexDefinition approach |
| Fit | 3 | 3 | 9 | Cleanest separation of concerns but hides quantization from schema metadata. Schema tools, catalog, and monitoring can't introspect the quantization strategy. |
| **Total** | | | **52** | |

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Total |
|-----------|-------|-----------|------------|----------|-------------|-----|-------|
| QuantizedVectorType | 12 | 6 | 2 | 12 | 6 | 6 | **44** |
| IndexDefinition config | 12 | 8 | 4 | 12 | 8 | 12 | **56** |
| Builder policy | 12 | 10 | 4 | 9 | 8 | 9 | **52** |

## Preliminary Recommendation
IndexDefinition config wins (56 vs 52 vs 44). Quantization is an index-level
concern that belongs alongside similarityFunction on IndexDefinition — visible
in schema metadata, configurable per index, and zero FieldType changes.
