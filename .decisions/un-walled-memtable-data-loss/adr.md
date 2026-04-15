---
problem: "un-walled-memtable-data-loss"
date: "2026-04-15"
version: 2
status: "accepted"
decision_refs: ["rebalancing-grace-period-strategy"]
spec_refs: ["F27", "F32"]
---

# Un-WAL'd Memtable Data Loss Prevention

## Problem

MemTable entries not yet synced to the WAL are lost during unclean ownership
transfer. The grace period does not prevent this data loss — it is inherently
a replication concern.

## Decision

**Documented data loss window (single-node) eliminated by Raft replication
(replicated mode)** — two complementary specifications address this concern
depending on the replication configuration.

**Unreplicated mode (F27 R28-R29):**
- Data loss window equals the WAL sync interval
- Acknowledged writes not yet WAL-synced are lost during unclean transfer
- The data loss window is documented in public API Javadoc

**Replicated mode (F32 R64-R66, invalidates F27 R28-R29):**
- Data loss window is eliminated for committed writes
- A write is acknowledged only after WAL append on a majority of replicas
- Loss of a minority of replicas does not lose committed writes
- Uncommitted writes (in transit, not yet acknowledged) may be lost on
  leader failure — inherent in any majority-quorum protocol
- Public API documents: "With replication factor N, up to floor(N/2)
  simultaneous failures tolerated without data loss for committed writes"

## Context

Originally deferred during `rebalancing-grace-period-strategy` decision
(2026-03-30) noting that "the grace period does not prevent data loss from
un-WAL'd memtable entries — that is a replication concern." This was correct:
the concern is now fully addressed by the replication protocol (F32) which
provides majority-quorum write durability.

F27 (Rebalancing Safety) established the drain phase that reduces the data loss
window from "entire MemTable" to "only entries not yet WAL-synced." F32
(Partition Replication) eliminates the window entirely for committed writes.
Both specs are adversarially hardened and in APPROVED state.

## Alternatives Considered

- **Synchronous local WAL flush before ack**: Every write flushes WAL to durable
  storage before acknowledgment. Eliminates the window without replication but
  at severe throughput cost (one fsync per write). Group commit (WAL group
  commit ADR) amortizes this but adds latency.
- **Battery-backed write cache assumption**: Assume durable write cache on storage
  hardware. Not portable — the library cannot assume hardware configuration.

## Consequences

- Unreplicated deployments have a documented, understood data loss window
  bounded by WAL sync interval (typically milliseconds to seconds)
- Replicated deployments have zero data loss for committed (acknowledged) writes
- The explicit documentation (R29, R66) ensures operators understand the tradeoff
  and can configure replication factor accordingly
