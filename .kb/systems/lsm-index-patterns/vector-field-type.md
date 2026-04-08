---
title: "vector-field-type"
type: feature-footprint
domains: ["vector-encoding", "document-model", "secondary-indices"]
constructs: ["FieldType.VectorType", "IndexDefinition", "DocumentSerializer", "IndexRegistry"]
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/FieldType.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/IndexDefinition.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/DocumentSerializer.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/IndexRegistry.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/JlsmDocument.java"
research_status: stable
last_researched: "2026-03-25"
---

# vector-field-type

## What it built
Added `FieldType.VectorType(Primitive elementType, int dimensions)` record to the sealed `FieldType` hierarchy, constrained to FLOAT16/FLOAT32 element types and positive dimensions. Updated `IndexRegistry` validation to require `VectorType` for VECTOR indices, `IndexDefinition` to derive dimensions from schema instead of carrying them, and `DocumentSerializer` / JSON / YAML serializers to handle fixed-length vector encoding.

## Key constructs
- `FieldType.VectorType` — inner record implementing `FieldType`, holds element precision and dimension count
- `FieldType.vector()` — static factory method
- `JlsmSchema.Builder.vectorField()` — convenience builder method
- `IndexDefinition` — simplified record (removed `vectorDimensions`, dimensions derived from VectorType at IndexRegistry)
- `DocumentSerializer.encodeVector/decodeVector` — fixed-length binary encode/decode (no length prefix)
- `JsonParser.parseVector/JsonWriter.writeVector` — JSON array serialization
- `YamlParser.parseVectorSequence/YamlWriter.writeVector` — YAML block sequence serialization

## Adversarial findings
- vector-mutable-array-input: float[]/short[] stored by reference in JlsmDocument.of() → [KB entry](../lsm-index-patterns/vector-mutable-array-input.md)
- non-finite-vector-element: NaN/Infinity accepted in vector elements at construction → [KB entry](../../algorithms/vector-encoding/non-finite-vector-element.md)
- spurious-similarity-function: IndexDefinition accepted non-null similarityFunction for non-VECTOR index types (fixed inline, no standalone KB entry — low recurrence risk)

## Cross-references
- ADR: .decisions/vector-type-serialization-encoding/adr.md
- ADR: .decisions/index-definition-api-simplification/adr.md
- Related features: float16-vector-support, table-indices-and-queries
