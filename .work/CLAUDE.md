# Work Groups — Master Index

> Pull model. Load on demand only.
> Structure: .work/<group-slug>/

## Active Work Groups

| Group | Goal | Status | Created | WDs |
|-------|------|--------|---------|-----|
| implement-encryption-lifecycle | Implement encryption.primitives-lifecycle — key hierarchy, ciphertext format, DEK/KEK lifecycle, compaction migration, runtime concerns | active | 2026-04-21 | 5 (2 complete, 1 ready, 2 blocked) |
| implement-transport | Implement three transport.* DRAFT specs as a new jlsm-cluster module (framing, priority, scatter-gather) | active | 2026-04-21 | 3 (1 ready, 2 blocked) |
| implement-membership | Implement two membership.* DRAFT specs (rediscovery, health/recovery); blocked on implement-transport | active | 2026-04-21 | 2 (1 ready, 1 blocked) |

## Completed Work Groups

| Group | Goal | Completed | Created | WDs |
|-------|------|-----------|---------|-----|
| decisions-backlog | Resolve deferred decision backlog per roadmap | 2026-04-20 | 2026-04-13 | 13 |
| f04-obligation-resolution | Resolve 12 open F04 engine-clustering obligations | 2026-04-20 | 2026-04-19 | 5 |
| cross-module-integration | Wire stub indices through module boundaries (F10, F05) | 2026-04-20 | 2026-04-19 | 3 |
| close-coverage-gaps | Close @spec coverage gaps for 4 specs (engine.clustering, engine.in-process-database-engine, query.index-types, query.query-executor) | 2026-04-21 | 2026-04-20 | 2 |
| implement-sstable-enhancements | Implement three sstable.* DRAFT specs (byte-budget-block-cache, pool-aware-block-size, end-to-end-integrity) | 2026-04-23 | 2026-04-21 | 3 |

<!-- All three work groups closed 2026-04-20:
     - f04-obligation-resolution: shipped via PR #35.
     - cross-module-integration: shipped via PRs #36 (WD-01 full-text), #38 (WD-03 query binding, stacked on #36), and #37 (WD-02 vector, which bumped F10 v3 → v4 and emptied open_obligations).
     - decisions-backlog: all 13 WDs were spec-authoring; their ADRs were already in terminal states (confirmed/accepted/closed/deferred) — this group closed with an administrative flip via PR #39.
     F10.open_obligations is now empty. F04.open_obligations is now empty. -->

