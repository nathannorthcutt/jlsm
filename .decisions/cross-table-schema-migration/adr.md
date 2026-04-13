---
problem: "cross-table-schema-migration"
date: "2026-04-13"
version: 1
status: "deferred"
depends_on: ["string-to-bounded-string-migration"]
---

# Cross-Table Schema Migration Coordination — Deferred

## Problem
Migrating multiple tables atomically as a single schema version bump — coordinated migration across tables that share a schema or have referential constraints.

## Why Deferred
Scoped out during `string-to-bounded-string-migration` decision. Single-table migration is the foundation; cross-table coordination is a separate concern.

## Resume When
When single-table schema migration is implemented and multi-table atomic schema changes are needed.

## What Is Known So Far
Single-table migration uses compaction-time + on-demand scan. Cross-table coordination would need either a shared schema version counter or a two-phase schema change protocol. See catalog-replication-strategies KB article for online schema change patterns.

## Next Step
Run `/architect "cross-table-schema-migration"` when ready to evaluate.
