---
problem: "scan-snapshot-binding"
date: "2026-04-15"
version: 2
status: "accepted"
decision_refs: ["scatter-backpressure"]
spec_refs: ["F39"]
---

# Scan Snapshot Binding

## Problem

Should continuation tokens for paged scans bind to a specific sequence number
(point-in-time consistency) or use best-effort latest-snapshot semantics?

## Decision

**Sequence-number binding with degraded fallback** — continuation tokens
carry the snapshot's sequence number. Each page request resumes from that
snapshot for point-in-time consistency. If the GC watermark advances past
the token's sequence number, the partition falls back to the latest available
snapshot with a `SNAPSHOT_DEGRADED` flag. If even the latest snapshot cannot
serve, the partition returns `STALE_TOKEN`.

Key design choices resolved by F39 R25-R28:
- Token `sequenceNumber` field provides point-in-time consistency (R9, R16)
- Degraded fallback to latest snapshot with flag propagation (R25, R27)
- STALE_TOKEN error when even fallback is impossible (R26)
- Degradation flag is sticky — once set, remains true for the rest of the
  pagination sequence (R27)
- Partition page handler is stateless between requests (R28)

## Context

Originally deferred during `scatter-backpressure` decision (2026-04-13)
as orthogonal to flow control. Resolved by F39 (Distributed Pagination)
which specified the full snapshot binding protocol. The specification was
adversarially hardened and is in APPROVED state.

## Alternatives Considered

- **Best-effort latest-snapshot:** Simpler but permits phantom reads and
  non-repeatable reads across pages. Rejected — inconsistent pagination
  results are confusing to callers.
- **Stateful server-side cursors:** Pin iterators and SSTables on partition
  nodes. Rejected — blocks compaction, scales poorly (100 partitions x 10
  concurrent scans = 1000 pinned iterators).

## Consequences

- Point-in-time consistency for paginated scans without server-side state
- Long-running scans risk snapshot expiry (addressed by scan-lease-gc-watermark)
- Callers can detect degradation via `SNAPSHOT_DEGRADED` flag and decide
  whether to trust the result
