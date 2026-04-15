---
problem: "sparse-vector-support"
date: "2026-04-13"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-table/src/main/java/jlsm/table/FieldType.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/IndexType.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/Predicate.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/JlsmDocument.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/IndexRegistry.java"
  - "modules/jlsm-indexing/src/main/java/jlsm/indexing/LsmInvertedIndex.java"
---

# ADR — Sparse Vector Support

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Sparse Vector Representations | Storage patterns, SPLADE, hybrid search | [`.kb/algorithms/vector-encoding/sparse-vector-representations.md`](../../.kb/algorithms/vector-encoding/sparse-vector-representations.md) |

---

## Files Constrained by This Decision

- `FieldType.java` — new `SparseVectorType()` as 8th sealed permit
- `IndexType.java` — new `SPARSE_VECTOR` enum value
- `Predicate.java` — new `SparseNearest(Map<Integer, Float> query, int topK)` predicate
- `JlsmDocument.java` — sparse vector field validation (Map<Integer, Float>)
- `IndexRegistry.java` — SPARSE_VECTOR validation (requires SparseVectorType field)
- `LsmInvertedIndex.java` — reused for sparse storage (dimension-id as term, weight as payload)

## Problem
VectorType is designed for dense fixed-dimension vectors. Sparse representations
(SPLADE, learned sparse retrieval) have variable dimensions and non-zero entries
per document. They need a different encoding and storage path.

## Constraints That Drove This Decision
- **Fit (weight 3)**: Sparse vectors are structurally closer to inverted index
  (FULL_TEXT) than to dense ANN (VECTOR). Storage must reuse LsmInvertedIndex.
- **Accuracy (weight 3)**: Query semantics differ from text — dot product on
  learned weights, not TF-IDF. Need distinct type identity.
- **Scale (weight 2)**: SPLADE produces ~100-300 non-zero dimensions per
  document out of 30K+ vocabulary. Storage is O(nnz), not O(dimensions).

## Decision
**Chosen approach: SparseVectorType sealed permit + inverted index storage**

Add `record SparseVectorType() implements FieldType` as the 8th sealed permit.
Sparse vector values are `Map<Integer, Float>` (dimension → weight). Storage
reuses LsmInvertedIndex with integer dimension IDs as terms and quantized weights
as payloads. New `IndexType.SPARSE_VECTOR` with `SparseNearest` predicate using
dot-product scoring. Hybrid search (dense + sparse) via RRF at query time.

## Rationale

### Why SparseVectorType + inverted index
- **Natural fit**: Each non-zero dimension is an inverted index posting. This is
  how every production sparse retrieval system works (Vespa, Elasticsearch, Qdrant).
- **Reuses existing infrastructure**: LsmInvertedIndex already stores term→doc-id
  pairs. Sparse vectors use dimension-id→doc-id with weight payload.
- **Type safety**: SparseVectorType is compile-time distinct from VectorType,
  FULL_TEXT STRING, and other types. Switch exhaustiveness catches missing arms.
- **Variable dimensions**: No fixed dimension count — each document can have
  different active dimensions. Natural for learned sparse models.

### Why not reuse VectorType
- Dense VectorType has fixed dimensions and ANN index structures (HNSW, IVF).
  Sparse vectors are variable-dimension and use inverted index retrieval.

### Why not FULL_TEXT with weighted terms
- FULL_TEXT uses string tokenization and TF-IDF scoring. Sparse vectors use
  integer dimension IDs and learned float weights. Different scoring models,
  different validation rules.

## Implementation Guidance

### FieldType
```
record SparseVectorType() implements FieldType {}
static FieldType sparseVector() { return new SparseVectorType(); }
```

### IndexType
```
enum IndexType { EQUALITY, RANGE, UNIQUE, FULL_TEXT, VECTOR, SPARSE_VECTOR }
```

### Storage mapping
```
// Sparse vector {dim_42: 0.8, dim_1337: 0.3} becomes:
// InvertedIndex entries:
//   key: [4-byte dim_42][doc-id]   value: quantized(0.8)
//   key: [4-byte dim_1337][doc-id] value: quantized(0.3)
```

### Query
```
// SparseNearest: dot product of query weights with stored weights
record SparseNearest(Map<Integer, Float> query, int topK) implements Predicate {}

// Scoring: for each query dimension, look up posting list, accumulate
// weight * stored_weight per document. Return top-K by score.
```

### Hybrid search (query-time fusion)
```
// RRF: score = sum(1 / (k + rank_dense)) + sum(1 / (k + rank_sparse))
// Linear: score = alpha * dense_score + (1 - alpha) * sparse_score
// Configured per query, not per index
```

## What This Decision Does NOT Solve
- **Learned sparse model integration** — SPLADE training/inference is
  application-layer. jlsm stores the output vectors, not the model.
- **Sparse-dense joint index optimization** — co-optimizing inverted index
  and HNSW for hybrid queries. Each index is independent today.

## Conditions for Revision
This ADR should be re-evaluated if:
- Sparse vector retrieval proves too slow with inverted index scan (may need
  impact-ordered posting lists or block-max WAND optimization)
- A native sparse ANN structure (sparse HNSW) proves superior to inverted index
- Hybrid search fusion proves to need index-level coordination rather than
  query-time score combination

---
*Confirmed by: user deliberation | Date: 2026-04-13*
