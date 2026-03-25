---
problem: "partition-to-node-ownership"
evaluated: "2026-03-20"
candidates:
  - path: ".kb/systems/vector-partitioning/consistent-hashing.md"
    name: "Rendezvous Hashing (HRW)"
  - path: ".kb/distributed-systems/data-partitioning/partitioning-strategies.md"
    name: "Consistent Hashing with Virtual Nodes"
  - path: ".kb/distributed-systems/data-partitioning/partitioning-strategies.md"
    name: "Modulo Assignment"
constraint_weights:
  scale: 2
  resources: 1
  complexity: 1
  accuracy: 3
  operational: 3
  fit: 2
---

# Evaluation — partition-to-node-ownership

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used: see candidate sections below

## Constraint Summary
The assignment algorithm must be a deterministic pure function of (membership view, table/partition set)
so all nodes compute identical assignments without coordination. Movement on membership changes must be
minimal (O(K/N)), and distribution must be balanced. The algorithm is on the query routing hot path.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | Hundreds of nodes, thousands of tables — must be efficient but not extreme |
| Resources | 1 | Assignment computation is lightweight; not constraining |
| Complexity | 1 | Team is expert-level; complexity acceptable |
| Accuracy | 3 | Determinism is non-negotiable — all nodes must agree. Balanced distribution critical |
| Operational | 3 | Minimal movement on membership change; on query hot path; non-disruptive rebalancing |
| Fit | 2 | Must compose with Rapid membership views; pure function of view + table set |

---

## Candidate: Rendezvous Hashing (HRW)

**KB source:** [`.kb/systems/vector-partitioning/consistent-hashing.md`](../../.kb/systems/vector-partitioning/consistent-hashing.md)
**Relevant sections read:** `#how-it-works > Layer 2`, `#key-parameters`, `#algorithm-steps`

Rendezvous hashing (Highest Random Weight): for each table/partition ID p, compute
weight_i = hash(p, node_i) for all nodes in the membership view. Assign p to the
node with the highest weight. When a node leaves, only its items are redistributed
(each goes to its next-highest-weight node). When a node joins, it claims only items
for which it has the highest weight.

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 4 | 8 | O(N) per lookup (compute hash for all nodes). At hundreds of nodes, ~100 hash computations per lookup — trivially fast. No ring structure needed. |
| Resources | 1 | 5 | 5 | No metadata structures — only the membership list and a hash function |
| Complexity | 1 | 5 | 5 | Simplest possible algorithm — one loop over nodes computing max hash. No virtual nodes, no ring, no metadata |
| Accuracy | 3 | 5 | 15 | Deterministic — same inputs always produce same assignment. Balanced — uniform hash distributes items evenly. Provably O(K/N) movement on node change (KB: `#how-it-works`) |
| Operational | 3 | 5 | 15 | Only affected items move — no cascading. Pure function of membership + table set — no state to synchronize. Lookup is a single pass over nodes (cache-friendly) |
| Fit | 2 | 5 | 10 | Pure function — composes perfectly with Rapid's consistent views. Trivial Java implementation. Extensible with weighted hashing for heterogeneous nodes |
| **Total** | | | **58** | |

**Hard disqualifiers:** none

**Key strengths for this problem:**
- Pure function of (table_id, membership list) — perfectly deterministic (KB: `#how-it-works`)
- O(K/N) movement guarantee — mathematically minimal (KB: `#how-it-works`)
- No metadata structures — assignment is computed on the fly, no ring to maintain
- Trivially extensible with weights: w_i = hash(p, node_i) * node_weight

**Key weaknesses for this problem:**
- O(N) per lookup vs O(log(N*V)) for consistent hashing ring — negligible at N=hundreds but grows linearly
- No built-in support for "sticky" assignments (items prefer to stay on their current node)

---

## Candidate: Consistent Hashing with Virtual Nodes

**KB source:** [`.kb/distributed-systems/data-partitioning/partitioning-strategies.md`](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md)
**Relevant sections read:** `#consistent-hashing`, `#key-parameters`, `#tradeoffs`

Hash ring with V virtual nodes per physical node. Items and nodes mapped to positions
on a circle. Each item assigned to the first node clockwise. Adding/removing a node
shifts only ~1/N of items.

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 5 | 10 | O(log(N*V)) lookup via binary search on ring. Scales to thousands. (KB: `#consistent-hashing`) |
| Resources | 1 | 4 | 4 | Ring metadata: N*V entries sorted. At 100 nodes * 150 vnodes = 15K entries — small |
| Complexity | 1 | 3 | 3 | Virtual node tuning (100-200 per node), ring maintenance on view changes, more code than HRW |
| Accuracy | 3 | 4 | 12 | Deterministic. Distribution depends on V — poor without enough virtual nodes. O(K/N) movement (KB: `#consistent-hashing`) |
| Operational | 3 | 4 | 12 | Good movement properties but ring must be reconstructed on every view change. Virtual node count affects balance quality |
| Fit | 2 | 4 | 8 | Can be deterministic from membership view if ring construction is deterministic. Java implementations exist. More state than HRW |
| **Total** | | | **49** | |

**Hard disqualifiers:** none

**Key strengths for this problem:**
- O(log(N*V)) lookup — faster than HRW at very large N (KB: `#consistent-hashing`)
- Well-understood — used by Cassandra, DynamoDB (KB: `#reference-implementations`)

**Key weaknesses for this problem:**
- Requires virtual node tuning — distribution quality depends on V (KB: `#key-parameters`)
- Ring reconstruction on every view change — more work than HRW's stateless computation
- More implementation complexity for the same O(K/N) movement guarantee

---

## Candidate: Modulo Assignment

**KB source:** [`.kb/distributed-systems/data-partitioning/partitioning-strategies.md`](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md)
**Relevant sections read:** `#hash-partitioning`

Simple modulo: assignment = hash(table_id) % N. Deterministic but catastrophic on
membership changes — nearly all items move.

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 5 | 10 | O(1) lookup — fastest possible |
| Resources | 1 | 5 | 5 | No metadata at all |
| Complexity | 1 | 5 | 5 | Trivial implementation |
| Accuracy | 3 | 2 | 6 | Deterministic but terrible movement: ~(N-1)/N items move on any membership change (KB: `#hash-partitioning`) |
| Operational | 3 | 1 | 3 | Nearly complete reshuffling on every membership change — massive rebalancing storm |
| Fit | 2 | 5 | 10 | Pure function, trivial Java |
| **Total** | | | **39** | |

**Hard disqualifiers:** O(K*(N-1)/N) movement on membership change — directly violates minimal-movement constraint

---

## Comparison Matrix

| Candidate | KB Source | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| [Rendezvous (HRW)](../../.kb/systems/vector-partitioning/consistent-hashing.md) | consistent-hashing.md | 8 | 5 | 5 | 15 | 15 | 10 | **58** |
| [Consistent Hashing](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md) | partitioning-strategies.md | 10 | 4 | 3 | 12 | 12 | 8 | **49** |
| [Modulo](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md) | partitioning-strategies.md | 10 | 5 | 5 | 6 | 3 | 10 | **39** |

## Preliminary Recommendation
Rendezvous Hashing (HRW) wins on weighted total (58 vs 49 vs 39). It is the simplest candidate that satisfies all constraints — a stateless pure function with mathematically minimal movement and even distribution. Consistent hashing offers faster lookup at very large N but adds complexity without benefit at the hundreds-of-nodes scale.

## Risks and Open Questions
- At >1000 nodes, O(N) per lookup could become a concern — consistent hashing would be better. But this is well beyond the stated scale.
- Weighted assignment (heterogeneous nodes) is not natively supported but is a simple extension: multiply hash weight by node capacity weight
- No "sticky" assignments — if a node temporarily departs and returns as new (past grace period), its former items may not return to it. This is by design (the brief specifies returned nodes are treated as new).
