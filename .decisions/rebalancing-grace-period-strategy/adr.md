---
problem: "rebalancing-grace-period-strategy"
date: "2026-03-20"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/"
---

# ADR — Rebalancing & Grace Period Strategy

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| (general knowledge) | No direct KB entry — implementation design layering on existing ADRs | — |

## Related ADRs
| ADR | Relationship |
|-----|-------------|
| [cluster-membership-protocol](../cluster-membership-protocol/adr.md) | Rapid provides consistent views that trigger rebalancing |
| [partition-to-node-ownership](../partition-to-node-ownership/adr.md) | HRW computes assignment; top-R ranking enables replication |

---

## Files Constrained by This Decision
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/` — rebalancing and grace period logic

## Problem
How and when should partition ownership be redistributed when cluster membership changes? Must compose with Rapid (consistent views) and HRW (deterministic assignment) while tolerating rolling restarts and avoiding unnecessary unavailability.

## Constraints That Drove This Decision
- **No unavailability window**: partitions must always be assigned to a live node — marking partitions UNAVAILABLE for the grace duration is unacceptable
- **Object storage eliminates data migration**: new owners already have access to persisted data; takeover is WAL replay, not data transfer
- **Rolling restart tolerance**: brief node departures must not trigger permanent data movement that would just be reversed

## Decision
**Chosen approach: Eager Reassignment with Deferred Cleanup**

When Rapid produces a new membership view removing a node, HRW immediately recomputes on current members. The departed node's partitions are instantly assigned to their next-highest-weight nodes. Because data lives on object storage, the new owner already has access — takeover is WAL replay to rebuild the memtable, not a data transfer.

The grace period controls cleanup timing, not assignment:
- **During grace**: partition data from the departed node is not cleaned up. If the node returns within the grace window, HRW gives it back the same partitions (deterministic from membership) and it resumes with its hot memtable intact — zero-cost recovery.
- **After grace**: the departure is permanent. The new owner is authoritative. A returning node is treated as new (per feature brief).

**Replication readiness**: HRW's weight ranking naturally extends to top-R. When replication is added, replicas already hold the data. Primary-to-replica failover is instant. The grace period then controls when to promote a new node to fill the lost replica slot — no architectural change needed.

## Rationale

### Why Eager Reassignment with Deferred Cleanup
- **Zero unavailability**: partitions are always assigned to a live node — no UNAVAILABLE state during grace period
- **Object storage composability**: new owners have immediate data access; takeover cost is bounded WAL replay (seconds)
- **Rolling restart tolerance**: if a node returns during grace, it reclaims its partitions at zero cost (hot memtable preserved, no data movement occurred)
- **Replication-ready**: HRW top-R ranking means adding replication is a parameter change, not an architecture change
- **Clean separation of concerns**: Rapid handles failure detection, HRW handles assignment, grace period handles cleanup timing — three independent knobs

### Why not View-Epoch Grace Period (original proposal)
- Marks departed node's partitions as UNAVAILABLE for the entire grace duration — creates an unavailability window proportional to the grace period configuration

### Why not Delayed View Application
- Holds back Rapid's consistent view from HRW, fighting the design. Delays new node capacity. Routes queries to departed nodes during grace

### Why not Immediate Rebalance (No Grace)
- Conflates Rapid's failure detection timeout with operational rebalancing tolerance — cannot tune independently. Rolling restarts trigger unnecessary WAL replays that are reversed seconds later

## Implementation Guidance

**Grace period mechanics:**
- Grace list: set of (node_id, departure_view_epoch, departure_timestamp)
- All nodes maintain the same grace list (derived from Rapid view history)
- During grace: departed node's data directories are not cleaned up
- After grace: cleanup is safe — new owner is permanent
- Grace duration: configurable, default suggestion 2–5 minutes (covers k8s rolling restarts)

**Partition takeover sequence:**
1. Rapid produces new view without departed node
2. All nodes recompute HRW on current_members
3. New owner of each affected partition initiates WAL replay from object storage
4. Once replay completes, partition is fully serving on the new owner
5. Queries during replay: return partial results or wait (configurable)

**Returning node (within grace):**
1. Node rejoins → Rapid produces new view including it
2. HRW recomputes → node gets its original partitions back (deterministic)
3. Node still has hot memtable → resumes immediately, no replay needed

**Returning node (after grace):**
1. Node rejoins → treated as new member
2. HRW assigns it a fresh set of partitions (may differ from original)
3. Node must load these partitions from object storage (fresh start)

## What This Decision Does NOT Solve
- In-flight writes during takeover — writes to the old owner's memtable that haven't been WAL'd are lost (existing WAL durability guarantees apply)
- Priority ordering of partition takeover (which partitions replay first on the new owner)
- Throttling concurrent WAL replays to avoid overloading the new owner
- The grace period does not prevent data loss from un-WAL'd memtable entries — that is a replication concern

## Conditions for Revision
This ADR should be re-evaluated if:
- WAL replay proves too slow for acceptable takeover time (may need eager replication)
- Object storage latency makes remote WAL replay impractical (may need local data caching)
- Replication is added and the grace period semantics need to change for replica promotion
- Clock skew between nodes proves problematic for grace period expiration agreement

---
*Confirmed by: user deliberation | Date: 2026-03-20*
*Full scoring: [evaluation.md](evaluation.md)*
