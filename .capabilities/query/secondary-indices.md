---
title: "Secondary Indices"
slug: secondary-indices
domain: query
status: active
type: core
tags: ["indices", "indexing", "secondary", "full-text", "unique-constraint"]
features:
  - slug: table-indices-and-queries
    role: core
    description: "Secondary index support (equality, range, unique, full-text, vector) and fluent query API for JlsmTable"
composes: []
spec_refs: ["F10"]
decision_refs: ["index-definition-api-simplification"]
kb_refs: ["systems/lsm-index-patterns"]
depends_on: ["data-management/schema-and-documents"]
enables: ["query/sql-surface", "security/searchable-encryption", "distribution/table-partitioning"]
---

# Secondary Indices

Single-field secondary index support for JlsmTable. Indices are defined at
table construction time and maintained synchronously on writes. Five index
types cover equality, range, unique constraints, full-text search (via
jlsm-indexing), and vector similarity search (via jlsm-vector).

## What it does

Each index is owned by the table, not the schema. On every write, the table
updates all registered indices inline. Queries use a fluent builder API with
predicates and boolean combinators that resolve against index structures when
available, falling back to full scans when no index covers the predicate.

## Features

**Core:**
- **table-indices-and-queries** — secondary index lifecycle, five index types, fluent query API with index-backed execution

## Key behaviors

- Indices are defined at table construction, not schema definition — the table owns its indices
- Index types: equality (hash), range (sorted), unique (hash + constraint), full-text (inverted), vector (ANN)
- Writes maintain all indices synchronously — no eventual consistency
- The fluent query API is designed to be translatable to SQL (query/sql-surface)
- Query results are Iterator<TableEntry<K>>, consistent with existing scan patterns
- Full-text and vector index types delegate to jlsm-indexing and jlsm-vector modules
- Index state is persisted on disk alongside the table's LSM tree

## Related

- **Specs:** F10 (table indices and queries)
- **Decisions:** index-definition-api-simplification (schema-driven index derivation)
- **KB:** systems/lsm-index-patterns (LSM scan paths, index integration)
- **Depends on:** data-management/schema-and-documents (field types drive index type compatibility)
- **Enables:** query/sql-surface (SQL translates to fluent API), security/searchable-encryption (indices on encrypted fields), distribution/table-partitioning (per-partition co-located indices)
- **Deferred work:** similarity-function-placement, non-vector-index-type-review
