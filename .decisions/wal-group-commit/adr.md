---
problem: "wal-group-commit"
date: "2026-04-12"
version: 1
status: "deferred"
---

# WAL Group Commit — Deferred

## Problem
Batching multiple WAL writes into a single fsync to reduce write-path latency.
Currently every append triggers an individual force/fsync. Group commit would
allow multiple concurrent writers to share a single fsync call.

## Why Deferred
Scoped out during `wal-compression` decision. Group commit is an orthogonal
optimization that can layer on top of compressed or uncompressed records.

## Resume When
When WAL write throughput benchmarks show fsync is the dominant bottleneck
and batching would provide measurable improvement.

## What Is Known So Far
See `.decisions/wal-compression/adr.md` for the WAL compression format.
See `.kb/distributed-systems/data-partitioning/multi-writer-wal.md` for
group commit discussion in the per-partition WAL context.

## Next Step
Run `/architect "wal-group-commit"` when ready to evaluate.
