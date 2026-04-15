---
problem: "concurrent-wal-replay-throttling"
date: "2026-04-15"
version: 2
status: "accepted"
decision_refs: ["rebalancing-grace-period-strategy"]
spec_refs: ["F29"]
---

# Concurrent WAL Replay Throttling

## Problem

Throttling concurrent WAL replays to avoid overloading the new owner during
rebalancing — unbounded concurrent replays can saturate I/O bandwidth, exhaust
memory, and trigger compaction storms.

## Decision

**Three-gate resource bounding** — concurrency limit, I/O throughput limit,
and memory budget independently constrain WAL replay impact on foreground
traffic. Compaction backpressure provides a fourth safety valve.

Key design choices resolved by F29 R10-R45:
- Maximum concurrent replays: 2 (R11), configurable
- Shared I/O throughput limiter: 64 MiB/s (R17), with burst of one WAL segment (R19)
- Memory budget for concurrent replays: 256 MiB (R22)
- Compaction backpressure threshold: 8 pending tasks (R30)
- Pending replays queued by TakeoverPrioritizer order (R10)
- Queue re-sorted on membership view changes (R15)
- Failed replays do not block other partitions (R33)
- Structured observability events for throttle start/resolve (R36-R39)

## Context

Originally deferred during `rebalancing-grace-period-strategy` decision
(2026-03-30) as not needed for initial implementation. Resolved by F29
(Rebalancing Operations) which specified comprehensive throttling with
three independent resource constraints. The specification was adversarially
hardened and is in APPROVED state.

## Alternatives Considered

- **Per-replay I/O sub-budgets**: Dividing throughput evenly among replays.
  Rejected — underutilizes bandwidth when one replay is idle. Shared bucket
  naturally reallocates (F29 design narrative).
- **Adaptive concurrency**: Auto-adjusting replay count based on I/O utilization.
  Rejected — adds feedback-loop complexity and oscillation risk. Three static
  knobs are simpler to reason about.
- **Weighted fair queuing for I/O tokens**: More tokens to higher-priority
  replays. Rejected — priority already determines start order; equal treatment
  is simpler once active.

## Consequences

- Three independent knobs (concurrency, throughput, memory) give operators
  fine-grained control over catch-up resource usage
- Compaction backpressure prevents write stalls from bulk WAL ingestion
- Memory budget adapts to actual partition sizes without operator prediction
