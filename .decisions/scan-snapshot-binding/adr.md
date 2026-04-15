---
problem: "scan-snapshot-binding"
date: "2026-04-13"
version: 1
status: "deferred"
---

# Scan Snapshot Binding — Deferred

## Problem
Should continuation tokens for paged scans bind to a specific sequence number (point-in-time
consistency) or use best-effort latest-snapshot semantics?

## Why Deferred
Scoped out during `scatter-backpressure` decision. Orthogonal to flow control mechanism.

## Resume When
When `scatter-backpressure` implementation reaches the continuation token encoding stage.

## What Is Known So Far
KB research on distributed scan cursors identifies three snapshot strategies: sequence-number
binding (point-in-time), best-effort (latest snapshot), and hybrid (fall back with warning).
jlsm's SequenceNumber already supports snapshot binding. See
`.kb/distributed-systems/query-execution/distributed-scan-cursors.md`.

## Next Step
Run `/architect "scan-snapshot-binding"` when ready to evaluate.
