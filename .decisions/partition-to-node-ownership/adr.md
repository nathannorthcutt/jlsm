---
problem: "partition-to-node-ownership"
date: "2026-03-20"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/"
---

# ADR — Partition-to-Node Ownership Assignment

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Consistent Hashing & LSH-Based Vector Partitioning | Chosen approach (rendezvous hashing layer) | [`.kb/systems/vector-partitioning/consistent-hashing.md`](../../.kb/systems/vector-partitioning/consistent-hashing.md) |
| Data Partitioning Strategies | Rejected candidates (consistent hashing ring, modulo) | [`.kb/distributed-systems/data-partitioning/partitioning-strategies.md`](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md) |

---

## Files Constrained by This Decision
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/` — ownership assignment implementation

## Problem
How should tables and partitions be mapped to cluster members, given that all nodes share a strongly consistent membership view (Rapid) and assignment must be deterministic without coordination?

## Constraints That Drove This Decision
- **Deterministic from membership view**: all nodes must compute identical assignment from the same inputs — rules out stateful placement services
- **Minimal movement on membership change**: O(K/N) items should move when a node joins/leaves — rules out modulo assignment
- **Balanced distribution**: even spread across nodes without manual tuning

## Decision
**Chosen approach: Rendezvous Hashing (Highest Random Weight)**

Use rendezvous hashing to assign tables and partitions to cluster nodes. For each table or partition ID, compute hash(id, node_id) for every node in the current membership view and assign to the node with the highest weight. This is a stateless pure function — given the same membership view and table/partition set, all nodes independently compute identical assignments with zero coordination. Movement on membership changes is mathematically minimal at O(K/N).

## Rationale

### Why Rendezvous Hashing (HRW)
- **Determinism**: pure function of (table_id, membership list) — composes perfectly with Rapid's consistent membership views ([KB: `#how-it-works`](../../.kb/systems/vector-partitioning/consistent-hashing.md))
- **Minimal movement**: O(K/N) items move on membership change — provably optimal. Only the departing node's items redistribute, each to its next-highest-weight node ([KB: `#how-it-works`](../../.kb/systems/vector-partitioning/consistent-hashing.md))
- **Balanced distribution**: uniform hash function produces even distribution without virtual nodes or tuning
- **Simplicity**: one loop over nodes computing max hash — no ring, no metadata, no state to synchronize

### Why not Consistent Hashing with Virtual Nodes
- **Unnecessary complexity**: same O(K/N) movement guarantee but requires ring construction, virtual node tuning (100-200 per physical node), and ring rebuilds on every view change ([KB: `#consistent-hashing`](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md))
- O(log(N*V)) lookup is faster than HRW's O(N) but unnecessary at hundreds of nodes

### Why not Modulo Assignment
- **Catastrophic movement**: ~(N-1)/N items move on any membership change — directly violates minimal-movement constraint ([KB: `#hash-partitioning`](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md))

## Implementation Guidance
Key parameters:
- Hash function: Murmur3 or SipHash on concatenation of table/partition ID and node ID
- Lookup: O(N) per table/partition — ~100 hash computations at 100 nodes (~microseconds)
- Caching: cache assignment results keyed on membership view epoch; invalidate on view change
- Weighted extension: multiply hash(id, node_id) by node capacity weight for heterogeneous nodes

Algorithm:
```java
NodeId assignOwner(TableId tableId, Set<Member> members) {
    long maxWeight = Long.MIN_VALUE;
    NodeId owner = null;
    for (Member m : members) {
        long weight = hash(tableId, m.id());
        if (weight > maxWeight) {
            maxWeight = weight;
            owner = m.id();
        }
    }
    return owner;
}
```

Edge cases:
- Empty membership view: no assignment possible — table is unavailable
- Single node: all tables assigned to it (trivially correct)
- Node returns after grace period: treated as new node with new ID — its former items may not return to it (by design)

## What This Decision Does NOT Solve
- Weighted/heterogeneous node capacity (simple extension: multiply hash by weight, but not built-in initially)
- Partition affinity (co-locating related partitions on the same node for locality)
- Hot-path lookup optimization beyond simple epoch-keyed caching
- The decision of when to trigger rebalancing — that is handled by the membership protocol's grace period

## Conditions for Revision
This ADR should be re-evaluated if:
- Cluster scale exceeds 1000 nodes and O(N) per-lookup cost becomes measurable — switch to consistent hashing ring
- Weighted assignment becomes a requirement for heterogeneous node capacities
- Partition affinity (co-location) proves necessary for query performance
- Lookup caching proves insufficient and a persistent assignment map is needed

---
*Confirmed by: user deliberation | Date: 2026-03-20*
*Full scoring: [evaluation.md](evaluation.md)*
