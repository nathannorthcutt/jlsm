---
title: "Database Engine"
slug: database-engine
domain: distribution
status: active
type: core
tags: ["engine", "database", "catalog", "multi-table", "handles"]
features:
  - slug: in-process-database-engine
    role: core
    description: "In-process multi-table database engine with catalog persistence, handle-based lifecycle, and thread-safe concurrent access"
composes: []
spec_refs: ["F05"]
decision_refs: ["engine-api-surface-design", "table-catalog-persistence"]
kb_refs: ["systems/database-engines"]
depends_on: ["data-management/schema-and-documents"]
enables: ["distribution/engine-clustering"]
---

# Database Engine

In-process database engine managing multiple tables from a self-organized
root directory. Supports table creation, metadata introspection, insertion,
and querying via handle-based lifecycle management. Thread-safe for
concurrent callers.

## What it does

The jlsm-engine module provides a JlsmEngine that manages a table catalog
persisted as per-table metadata directories. Application code obtains table
handles (tracked lifecycle with lease eviction), performs operations through
the handle, and releases it when done. The engine is the coordination point
for multi-table workloads.

## Features

**Core:**
- **in-process-database-engine** — multi-table management, catalog persistence, handle-based API, thread-safe concurrent access

## Key behaviors

- Table catalog uses per-table metadata directories — lazy recovery, per-table failure isolation
- Handle-based API with tracked lifecycle and lease eviction prevents resource leaks
- Table names must be unique within an engine instance
- Engine manages table lifecycle: create, drop, list, metadata introspection
- Thread-safe for concurrent callers — multiple threads can hold handles to different tables
- Architecturally open to future network protocols and engine-level query syntax

## Related

- **Specs:** F05 (in-process database engine)
- **Decisions:** engine-api-surface-design (handle pattern), table-catalog-persistence (per-table dirs)
- **KB:** systems/database-engines (persistence, recovery patterns)
- **Depends on:** data-management/schema-and-documents (table schemas)
- **Enables:** distribution/engine-clustering (clustering extends the single-node engine)
- **Deferred work:** handle-priority-levels, cross-table-handle-budgets, handle-timeout-ttl, cross-table-transactions, remote-serialization-protocol, atomic-multi-table-ddl
