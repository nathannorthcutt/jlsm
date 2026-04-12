# Data Management — Capability Domain

> Pull model. Read capability files for details.

How data is stored, structured, compressed, and cached. Covers the typed
document model, binary serialization, block-level compression, and
read-path caching.

## Capabilities

| Capability | Type | Status | Tags | Features |
|-----------|------|--------|------|----------|
| [schema-and-documents](schema-and-documents.md) | core | active | schema, documents, types, serialization | 3 |
| [compressed-blocks](compressed-blocks.md) | core | active | compression, storage, sstable, blocks, checksums, wal, zero-copy | 4 |
| [block-cache](block-cache.md) | refinement | active | cache, lru, striped, concurrency | 1 |
| [json-processing](json-processing.md) | core | active | json, jsonl, parsing, simd, streaming, serialization | 1 |

## Cross-references

- **KB topics:** algorithms/compression, data-structures/caching, algorithms/vector-encoding, algorithms/serialization
- **Spec domains:** serialization, storage, engine
