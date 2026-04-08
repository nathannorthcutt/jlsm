---
title: "optimize-document-serializer"
type: feature-footprint
domains: ["serialization", "document-model"]
constructs: ["DocumentSerializer.SchemaSerializer", "JlsmDocument"]
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/DocumentSerializer.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/JlsmDocument.java"
research_status: stable
last_researched: "2026-03-25"
---

# optimize-document-serializer

## What it built

Optimized `DocumentSerializer.SchemaSerializer.deserialize()` to reduce scan CPU cost: (1)
zero-copy heap fast path via `heapBase()` avoiding `toArray()`, (2) precomputed schema
constants (`prefixBoolCount`, `isBoolField`, mask sizes) replacing per-document iteration,
(3) `FieldDecoder[]` dispatch table replacing per-field `switch` pattern matching.

## Key constructs

- `ByteArrayView` — private record for zero-copy heap segment access
- `extractBytes()` — heap fast path with off-heap fallback
- `FieldDecoder` — `@FunctionalInterface` for per-field decode dispatch
- `prefixBoolCount[]` — O(1) boolean count lookup replacing O(n) iteration

## Adversarial findings

- mutable-array-getter-return: `getArray()` exposed internal `Object[]` → [KB entry](mutable-array-getter-return.md)
- inconsistent-null-getter-contract: `getArray()`/`getObject()` silent null vs NPE → [KB entry](inconsistent-null-getter-contract.md)

## Cross-references

- Related features: table-indices-and-queries, vector-field-type, float16-vector-support
