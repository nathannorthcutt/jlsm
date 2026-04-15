---
problem: "in-flight-write-protection"
date: "2026-04-15"
version: 2
status: "accepted"
decision_refs: ["rebalancing-grace-period-strategy"]
spec_refs: ["F27"]
---

# In-Flight Write Protection During Takeover

## Problem

In-flight writes during partition takeover — writes to the old owner's MemTable
that are in the pipeline when ownership changes must be handled safely without
silent data loss or corruption.

## Decision

**Drain-and-reject with in-flight completion guarantee** — the departing owner
enters a drain phase that flushes MemTable, syncs WAL, and rejects new writes
with structured metadata. In-flight operations that were dispatched before the
transition complete against the pre-transition state.

Key design choices resolved by F27:
- Drain phase: flush MemTable to SSTable, sync WAL to durable storage (R6-R7)
- New writes rejected with OWNERSHIP_CHANGED during drain (R9)
- Configurable drain timeout, default 30s (R8, R42)
- In-flight operations complete against pre-transition state (R39)
- State transitions are atomic with respect to concurrent dispatch (R38)
- Structured rejection metadata: reason, partition ID, epoch, new owner (R24-R25)
- Client can distinguish retryable (ownership change, catch-up) from permanent errors (R26)
- Epoch check prevents stale-epoch writes (R3, R27)

## Context

Originally deferred during `rebalancing-grace-period-strategy` decision
(2026-03-30) noting that existing WAL durability guarantees apply and this
is partially a replication concern. Resolved by F27 (Rebalancing Safety) which
specified the full drain/catch-up protocol with write rejection semantics and
in-flight operation completion guarantees. The specification was adversarially
hardened and is in APPROVED state.

## Alternatives Considered

- **Write forwarding during drain**: Departing owner forwards writes to new
  owner. Rejected — for involuntary departures (crash), forwarding is
  impossible. Adds a forwarding channel dependency (F27 design narrative).
- **Dual-write during transition**: Both owners accept writes and reconcile.
  Rejected — requires conflict resolution, incompatible with strong-consistency
  model (F27 design narrative).
- **Immediate handoff (no drain)**: New owner takes over immediately. Rejected
  — discards entire MemTable, which could represent minutes of writes.

## Consequences

- In-flight operations are guaranteed to complete — no aborted mid-flight writes
- Data loss window is bounded by WAL sync interval (not MemTable size)
- With replication (F32), committed writes survive node failure entirely
- Structured rejections enable intelligent client retry (different node vs same node)
