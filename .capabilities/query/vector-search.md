---
title: "Vector Similarity Search"
slug: vector-search
domain: query
status: active
type: core
tags: ["vector", "similarity", "ANN", "HNSW", "float16", "nearest-neighbor"]
features:
  - slug: vector-field-type
    role: enables
    description: "VectorType addition to the field type hierarchy — prerequisite for vector index and search"
  - slug: float16-vector-support
    role: extends
    description: "Half-precision float16 vector storage and distance computation for reduced memory footprint"
composes: []
spec_refs: ["F01", "F12"]
decision_refs: ["vector-type-serialization-encoding"]
kb_refs: ["algorithms/vector-indexing", "algorithms/vector-quantization", "algorithms/vector-encoding", "systems/vector-partitioning"]
depends_on: ["data-management/schema-and-documents", "query/secondary-indices"]
enables: []
---

# Vector Similarity Search

Approximate nearest neighbor (ANN) search on vector fields. Vectors are
stored as typed fields in the document model and indexed via HNSW for
sub-linear similarity search. Supports float32 and float16 precision
with configurable distance metrics.

## What it does

VectorType fields store fixed-dimension float arrays. The vector index
type (registered as a secondary index) builds an HNSW graph for
approximate nearest neighbor queries. Queries specify a query vector and
k, returning the k most similar documents by the configured distance
metric. Float16 support halves storage and memory cost for workloads
where half-precision is sufficient.

## Features

**Enables:**
- **vector-field-type** — VectorType in the sealed FieldType hierarchy, dimension validation

**Extends:**
- **float16-vector-support** — half-precision storage, float16 distance computation

## Key behaviors

- Vectors are a first-class field type in the schema — validated at construction time
- Dimension is fixed per field and enforced on insertion
- Vector index is a secondary index type — same lifecycle as other index types
- HNSW provides sub-linear ANN search with configurable M and efConstruction
- Distance metrics: cosine similarity, euclidean distance, dot product
- Float16 uses IEEE 754 half-precision — 2 bytes per component vs. 4 for float32
- Vector queries integrate with SQL surface via `NEAREST(field, vector, k)` syntax
- Partitioned tables scatter vector queries to all partitions and merge top-k results

## Related

- **Specs:** F01 (float16 vector support), F12 (vector field type)
- **Decisions:** vector-type-serialization-encoding (flat vector encoding)
- **KB:** algorithms/vector-indexing (HNSW, filtered search, incremental maintenance), algorithms/vector-quantization (PQ, SQ, RaBitQ), algorithms/vector-encoding (serialization formats), systems/vector-partitioning (partitioned ANN)
- **Depends on:** data-management/schema-and-documents (VectorType field), query/secondary-indices (vector index type)
- **Deferred work:** sparse-vector-support, vector-storage-cost-optimization, vector-query-partition-pruning
