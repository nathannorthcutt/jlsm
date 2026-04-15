---
problem: "binary-field-type"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-13"
---

# Binary Field Type — Decision Index

**Problem:** Add binary/byte[] field type to jlsm-table's FieldType hierarchy with large object support
**Status:** confirmed
**Current recommendation:** Binary sealed permit + opaque BlobRef + BlobStore SPI
**Last activity:** 2026-04-13 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-13 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix | 2026-04-13 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-13 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-13 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Schema Type Systems | Type taxonomy, binary storage | [`.kb/systems/database-engines/schema-type-systems.md`](../../.kb/systems/database-engines/schema-type-systems.md) |
| Blob Store Patterns | LSM-backed blob store, chunking, GC | [`.kb/systems/database-engines/blob-store-patterns.md`](../../.kb/systems/database-engines/blob-store-patterns.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-13 | active | Binary sealed permit + BlobRef + BlobStore SPI |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
| Default LSM-backed BlobStore | default-lsm-blob-store | deferred | binary-field-type stable |
| Blob strategy for object storage | blob-object-storage-strategy | deferred | object storage deployment planned |
| Blob streaming API design | blob-streaming-api | deferred | streaming upload/download needed |
| Inline small blob optimization | inline-small-blob-optimization | deferred | profiling shows BlobStore round-trip cost |
