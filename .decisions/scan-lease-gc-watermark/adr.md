---
problem: "scan-lease-gc-watermark"
date: "2026-04-15"
version: 2
status: "accepted"
decision_refs: ["scatter-backpressure", "scan-snapshot-binding"]
spec_refs: ["F44"]
---

# Scan Lease GC Watermark

## Problem

How should active scans prevent the GC watermark from advancing past their
snapshot sequence number, avoiding stale-token errors without holding SSTable
references?

## Decision

**Lease-based watermark hold with bounded duration** — a `ScanLeaseManager`
SPI tracks active scan leases per partition. Each lease holds a sequence
number that prevents the GC watermark from advancing past it. Leases have
a bounded duration with renewal, and compaction must consult the lease
manager before advancing the watermark.

Key design choices resolved by F44:
- Per-partition lease tracking with configurable max lease count
- Bounded lease duration (default 5 minutes) with renewal on each page request
- Compaction consults lease manager — watermark advances only past the
  minimum held sequence number across all active leases
- Expired leases are reaped lazily (on next compaction check or explicit reap)
- Lease acquisition fails gracefully when at capacity — scan proceeds without
  lease protection (falls back to F39 R25-R26 degraded behavior)
- Coordinator-side lease lifecycle: acquire on first page, renew on
  continuation, release on scan completion or cancellation

## Context

Originally deferred during `scatter-backpressure` decision (2026-04-13)
pending scan-snapshot-binding (sequence-number binding requires a lease
mechanism; best-effort does not). Now that scan-snapshot-binding is confirmed
with sequence-number binding (F39), this decision is unblocked. Resolved by
F44 (Scan Lease GC Watermark) which specified the lease lifecycle, compaction
interaction, and failure modes.

## Alternatives Considered

- **SSTable pinning:** Hold references to SSTables needed by active scans.
  Rejected — prevents compaction from reclaiming space, causes unbounded
  space amplification under sustained scan load.
- **No lease mechanism (rely on degraded fallback):** Let GC advance freely
  and rely on F39 R25-R26 for graceful degradation. Viable but provides
  poor user experience for long-running analytics queries.
- **Global watermark hold:** Single cluster-wide watermark hold. Rejected —
  one slow scan would prevent GC across all partitions.

## Consequences

- Long-running scans maintain point-in-time consistency without degradation
  as long as the lease is renewed within the bounded duration
- Compaction is never blocked indefinitely — lease expiry guarantees
  watermark advancement resumes
- Per-partition lease tracking limits blast radius — one partition's leases
  don't affect other partitions
- Open obligation OB-F44-01: lease message types must be registered in F19
  transport framing protocol
