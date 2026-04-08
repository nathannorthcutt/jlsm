---
title: "Schema and Document Model"
slug: schema-and-documents
domain: data-management
status: active
type: core
tags: ["schema", "documents", "types", "serialization", "field-types"]
features:
  - slug: optimize-document-serializer
    role: quality
    description: "Zero-copy deserialization, precomputed schema constants, dispatch table optimization"
  - slug: vector-field-type
    role: extends
    description: "VectorType addition to the field type hierarchy with dimension validation"
  - slug: float16-vector-support
    role: extends
    description: "Half-precision float16 vector storage and distance computation"
composes: []
spec_refs: ["F13", "F14"]
decision_refs: ["bounded-string-field-type", "index-definition-api-simplification", "vector-type-serialization-encoding"]
kb_refs: ["algorithms/vector-encoding"]
depends_on: []
enables: ["security/field-encryption", "query/secondary-indices", "data-management/compressed-blocks", "query/vector-search"]
---

# Schema and Document Model

Typed schema definition and document storage model. Schemas define fields
with explicit types (integers, strings, bounded strings, vectors, nested
documents), and documents carry values conforming to the schema. The
serializer handles binary encoding with version tracking.

## What it does

JlsmSchema defines the field structure (name, type, constraints) for a
table. JlsmDocument stores typed values conforming to the schema. The
DocumentSerializer handles binary serialization/deserialization with
schema versioning, defensive copies for mutable types, and support for
encrypted field values alongside plaintext.

## Features

**Extends:**
- **vector-field-type** — VectorType addition to the sealed FieldType hierarchy
- **float16-vector-support** — half-precision storage extending VectorType

**Quality:**
- **optimize-document-serializer** — zero-copy deserialization, precomputed schema constants

## Key behaviors

- Schemas are immutable after construction — thread-safe by design
- Field types are a sealed hierarchy: Int8, Int16, Int32, Int64, Float32, Float64, BoundedString, VectorType, and nested types
- Documents carry values as Object[] indexed by field position, not by name
- Serialization is binary with schema version headers for forward compatibility
- Encrypted fields store byte[] ciphertext in place of their plaintext type
- Defensive copies protect mutable field values (vectors, arrays) from external mutation
- BoundedString enforces maximum byte length at construction time

## Related

- **Specs:** F13 (jlsm-schema), F14 (jlsm-document)
- **Decisions:** bounded-string-field-type (constrained strings), index-definition-api-simplification (schema-driven index derivation), vector-type-serialization-encoding (flat vector bytes)
- **KB:** algorithms/vector-encoding (serialization formats)
- **Depends on:** nothing (foundational)
- **Enables:** security/field-encryption, query/secondary-indices, data-management/compressed-blocks, query/vector-search
- **Deferred work:** binary-field-type, parameterized-field-bounds, string-to-bounded-string-migration, sparse-vector-support, vector-storage-cost-optimization
