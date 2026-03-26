---
title: "in-process-database-engine"
type: feature-footprint
domains: ["database-engines", "handle-lifecycle", "catalog-persistence"]
constructs: ["Engine", "Table", "LocalEngine", "LocalTable", "HandleTracker", "HandleRegistration", "TableCatalog", "EngineMetrics", "TableMetadata", "HandleEvictedException", "AllocationTracking"]
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/**"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/internal/**"
research_status: stable
last_researched: "2026-03-26"
---

# in-process-database-engine

## What it built

Multi-table in-process database engine with handle lifecycle management, eviction
policies, and persistent table catalog. Each table is backed by a full LSM pipeline
(WAL + MemTable + SSTable + compaction) within its own subdirectory. The engine provides
thread-safe CRUD operations with tracked handle leases and diagnostic allocation tracing.

## Key constructs

- `Engine` — public interface for table lifecycle (create, get, drop, list, metrics)
- `Table` — public interface for CRUD + scan with handle validity checking
- `LocalEngine` — implementation wiring catalog, handle tracker, and LSM tree per table
- `LocalTable` — delegate wrapper adding handle tracking and validity guards
- `HandleTracker` — thread-safe handle registry with configurable eviction limits
- `HandleRegistration` — opaque token tracking handle validity and invalidation reason
- `TableCatalog` — persistent directory-based table registry with lazy recovery
- `EngineMetrics` — immutable snapshot of engine-wide handle counts
- `TableMetadata` — immutable record of table name, schema, creation time, and state
- `HandleEvictedException` — diagnostic exception carrying reason, source, and allocation site
- `AllocationTracking` — enum controlling diagnostic overhead (OFF, CALLER_TAG, FULL_STACK)

## Adversarial findings

- hardcoded-invalidation-reason: checkValid() hardcoded EVICTION regardless of cause → [KB entry](hardcoded-invalidation-reason.md)
- assert-only-public-validation: builder methods used assert instead of IAE → [KB entry](assert-only-public-validation.md)
- mutable-array-getter-return: allocationSite() returned internal array by reference → [KB entry](../lsm-index-patterns/mutable-array-getter-return.md)
- deferred-close-catch-scope: close() only caught IOException, not RuntimeException → [KB entry](../lsm-index-patterns/deferred-close-catch-scope.md)
- builder-resource-leak-on-failure: createJlsmTable() leaked WAL/tree on build failure → [KB entry](../lsm-index-patterns/builder-resource-leak-on-failure.md)
- shallow-nested-map-copy: EngineMetrics Map.copyOf didn't deep-copy inner maps → variant of [mutable-array-in-record](../../data-structures/mutable-array-in-record.md)

## Cross-references

- ADR: .decisions/engine-api-surface-design/adr.md
- ADR: .decisions/table-catalog-persistence/adr.md
- Related features: table-partitioning, cluster-membership
