---
problem: "wal-group-commit"
date: "2026-04-14"
version: 2
status: "deferred"
depends_on: ["wal-compression"]
---

# WAL Group Commit — Re-Deferred

## Problem
Batching multiple WAL writes into a single fsync to reduce write-path latency.
Currently every append triggers an individual force/fsync.

## Why Deferred
Performance-gated. WAL compression is specified but not yet implemented. Group
commit is an orthogonal optimization that can layer on top of compressed or
uncompressed records. No benchmarks exist to show fsync is the dominant bottleneck.

## Resume When
When WAL write throughput benchmarks show fsync is the dominant bottleneck and
batching would provide measurable improvement.

## What Is Known So Far
- KB: `.kb/systems/database-engines/wal-group-commit.md` — batch fsync
  amortization patterns, latency/throughput tradeoffs
- KB: `.kb/distributed-systems/data-partitioning/multi-writer-wal.md` — group
  commit in per-partition WAL context
- wal-compression ADR: per-record format compatible with group commit

## Next Step
Run `/architect "wal-group-commit"` when benchmarks demonstrate the need.
