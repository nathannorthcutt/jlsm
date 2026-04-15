---
problem: "scan-lease-gc-watermark"
date: "2026-04-13"
version: 1
status: "deferred"
---

# Scan Lease GC Watermark — Deferred

## Problem
How should active scans prevent the GC watermark from advancing past their snapshot
sequence number, avoiding stale-token errors without holding SSTable references?

## Why Deferred
Scoped out during `scatter-backpressure` decision. Depends on scan-snapshot-binding
decision (sequence-number binding requires a lease mechanism; best-effort does not).

## Resume When
When `scan-snapshot-binding` is resolved with sequence-number binding.

## What Is Known So Far
KB research on distributed scan cursors describes a lightweight "scan lease" that registers
active scan sequence numbers to prevent version trimming. See
`.kb/distributed-systems/query-execution/distributed-scan-cursors.md#compaction-interaction`.

## Next Step
Run `/architect "scan-lease-gc-watermark"` when ready to evaluate.
