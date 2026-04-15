---
problem: "piggybacked-state-exchange"
evaluated: "2026-04-13"
candidates:
  - path: ".kb/distributed-systems/cluster-membership/cluster-membership-protocols.md"
    name: "Fixed-Field Heartbeat Metadata"
    section: "#piggybacked-state-efficiency"
  - path: ".kb/distributed-systems/cluster-membership/cluster-membership-protocols.md"
    name: "Extensible Key-Value Metadata"
    section: "#piggybacked-state-efficiency"
  - path: ".kb/distributed-systems/cluster-membership/cluster-membership-protocols.md"
    name: "Delta-State CRDT Metadata"
    section: "#piggybacked-state-efficiency"
  - path: ".kb/distributed-systems/cluster-membership/cluster-membership-protocols.md"
    name: "Separate Metadata Channel"
    section: "#piggybacked-state-efficiency"
constraint_weights:
  scale: 2
  resources: 2
  complexity: 1
  accuracy: 3
  operational: 3
  fit: 3
---

# Evaluation — piggybacked-state-exchange

## References
- Constraints: [constraints.md](constraints.md)
- KB source: [`.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md)

## Constraint Summary
Metadata must travel on existing heartbeats without inflating size beyond budget,
be parseable in O(1), and converge across all nodes. Accuracy, operational impact,
and fit are the binding constraints — metadata must not degrade failure detection.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | Per-heartbeat cost; 1000 nodes × high frequency |
| Resources | 2 | O(1) parse; no heap allocation in hot path |
| Complexity | 1 | Not a concern |
| Accuracy | 3 | Metadata must converge; staleness acceptable |
| Operational | 3 | Must not affect heartbeat timing or failure detection |
| Fit | 3 | Must extend existing heartbeats without protocol changes |

---

## Candidate: Fixed-Field Heartbeat Metadata

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 5 | 10 | Fixed 9 bytes per heartbeat; independent of cluster size |
|       |   |   |    | **Would be a 2 if:** field count grew beyond ~64 bytes, inflating heartbeat significantly |
| Resources | 2 | 5 | 10 | O(1) read — fixed offsets, no parsing. MemorySegment with ValueLayout (#piggybacked-state-efficiency) |
|           |   |   |    | **Would be a 2 if:** fields required variable-length encoding |
| Complexity | 1 | 5 | 5 | Simplest — append fixed bytes after heartbeat payload |
| Accuracy | 3 | 4 | 12 | Each heartbeat carries latest local values; convergence within K heartbeat rounds |
|          |   |   |    | **Would be a 2 if:** metadata needed merge semantics (conflicting values from different observers) |
| Operational | 3 | 5 | 15 | 9 bytes on a heartbeat that's typically ~100 bytes = 9% overhead. Well within UDP MTU |
|             |   |   |    | **Would be a 2 if:** metadata exceeded 1 KB, pushing heartbeats near UDP MTU |
| Fit | 3 | 5 | 15 | Append-only extension to heartbeat payload; version byte for forward compatibility |
|     |   |   |    | **Would be a 2 if:** fixed schema prevented adding new fields without protocol version bump |
| **Total** | | | **67** | |

**Key strengths:** zero-overhead parsing, minimal heartbeat inflation, trivial implementation
**Key weaknesses:** fixed schema — adding new metadata fields requires version bump and coordination

---

## Candidate: Extensible Key-Value Metadata

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 4 | 8 | Variable size per heartbeat; key-value encoding overhead |
| Resources | 2 | 3 | 6 | Requires iteration over key-value pairs; not O(1) fixed-offset access |
| Complexity | 1 | 3 | 3 | Key-value encoding/decoding, schema registry for known keys |
| Accuracy | 3 | 4 | 12 | Same convergence as fixed-field; slightly more flexible for multi-source metadata |
|          |   |   |    | **Would be a 2 if:** key collisions caused metadata corruption |
| Operational | 3 | 4 | 12 | Variable size means heartbeat size is unpredictable; needs size budget enforcement |
|             |   |   |    | **Would be a 2 if:** a buggy metadata producer exceeded size budget, inflating heartbeats |
| Fit | 3 | 4 | 12 | Extends heartbeats but requires length-prefix framing within the heartbeat payload |
|     |   |   |    | **Would be a 2 if:** length-prefix framing conflicted with existing heartbeat format |
| **Total** | | | **53** | |

**Key strengths:** extensible without version bumps; new metadata keys added at runtime
**Key weaknesses:** variable size, parsing overhead, requires budget enforcement

---

## Candidate: Delta-State CRDT Metadata

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 4 | 8 | Delta-only transmission reduces per-heartbeat size for changing state (#piggybacked-state-efficiency) |
| Resources | 2 | 2 | 4 | Merge operation per heartbeat; maintaining CRDT state per peer |
| Complexity | 1 | 2 | 2 | CRDT lattice merge, delta computation, state tracking per peer |
| Accuracy | 3 | 5 | 15 | Provable convergence — CRDTs merge without coordination. Almeida et al. (2016) (#piggybacked-state-efficiency) |
|          |   |   |    | **Would be a 2 if:** the metadata values were not monotonically increasing (CRDTs need join-semilattice structure) |
| Operational | 3 | 3 | 9 | Merge overhead per heartbeat; delta computation adds latency to heartbeat processing |
| Fit | 3 | 2 | 6 | Requires CRDT library or custom implementation; significant departure from simple heartbeat extension |
| **Total** | | | **44** | |

**Key strengths:** provable convergence without coordination
**Key weaknesses:** massive overkill for 9 bytes of last-writer-wins performance metrics

---

## Candidate: Separate Metadata Channel

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 4 | 8 | Dedicated messages; independent of heartbeat frequency |
| Resources | 2 | 3 | 6 | Additional message type; additional transport traffic |
| Complexity | 1 | 3 | 3 | New message handler registration; metadata request-response protocol |
| Accuracy | 3 | 4 | 12 | Metadata freshness decoupled from heartbeat rate — can refresh more or less often |
|          |   |   |    | **Would be a 2 if:** metadata channel was less reliable than heartbeats (different transport priority) |
| Operational | 3 | 3 | 9 | Additional traffic class; separate monitoring; metadata staleness if channel delays |
| Fit | 3 | 2 | 6 | Requires new message type registration on ClusterTransport — violates "no protocol changes" |
| **Total** | | | **44** | |

**Key strengths:** decoupled from heartbeat timing; can carry large metadata
**Key weaknesses:** violates fit constraint (requires new message type); adds transport complexity

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| Fixed-Field Heartbeat Metadata | 10 | 10 | 5 | 12 | 15 | 15 | **67** |
| Extensible Key-Value Metadata | 8 | 6 | 3 | 12 | 12 | 12 | **53** |
| Delta-State CRDT Metadata | 8 | 4 | 2 | 15 | 9 | 6 | **44** |
| Separate Metadata Channel | 8 | 6 | 3 | 12 | 9 | 6 | **44** |

## Preliminary Recommendation
Fixed-Field Heartbeat Metadata wins (67) by 14 points over Extensible Key-Value (53).
It scores 5 on Scale, Resources, Operational, and Fit — the four most important dimensions
for a heartbeat extension. The tradeoff (fixed schema requires version bump for new fields)
is acceptable because the initial consumer (slow-node-detection) needs exactly 9 bytes with
a known schema, and future metadata (capacity, load) can be added via a version byte.

## Risks and Open Questions
- Risk: fixed schema limits future extensibility — mitigated by version byte in the metadata header
- Risk: if metadata needs grow beyond ~64 bytes, fixed-field becomes unwieldy
- Open: should metadata include a TTL or freshness timestamp? (adds 8 bytes but enables staleness detection)
