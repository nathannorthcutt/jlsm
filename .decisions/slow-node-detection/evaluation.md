---
problem: "slow-node-detection"
evaluated: "2026-04-13"
candidates:
  - path: ".kb/distributed-systems/cluster-membership/fail-slow-detection.md"
    name: "Phi Threshold Bands"
    section: "#approach-1-phi-threshold-bands"
  - path: ".kb/distributed-systems/cluster-membership/fail-slow-detection.md"
    name: "Peer Comparison Scoring"
    section: "#approach-2-peer-comparison-scoring"
  - path: ".kb/distributed-systems/cluster-membership/fail-slow-detection.md"
    name: "Local Health Scoring"
    section: "#approach-3-local-health-scoring"
  - path: ".kb/distributed-systems/cluster-membership/fail-slow-detection.md"
    name: "Request Latency Tracking"
    section: "#approach-4-request-latency-tracking"
  - paths:
      - ".kb/distributed-systems/cluster-membership/fail-slow-detection.md"
    name: "Composite (Phi Bands + Peer Scoring + Request Latency)"
    boundary: "ANY signal triggers DEGRADED; ALL must clear for recovery"
constraint_weights:
  scale: 2
  resources: 1
  complexity: 1
  accuracy: 3
  operational: 3
  fit: 3
---

# Evaluation — slow-node-detection

## References
- Constraints: [constraints.md](constraints.md)
- KB source: [`.kb/distributed-systems/cluster-membership/fail-slow-detection.md`](../../.kb/distributed-systems/cluster-membership/fail-slow-detection.md)

## Constraint Summary
Must detect slow-but-alive nodes without false positives during compaction, route around
them without removing from membership, and integrate with existing phi accrual and
scatter-gather infrastructure. Accuracy, operational speed, and fit are the binding constraints.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | Per-peer scoring; overhead scales linearly with peers monitored |
| Resources | 1 | Negligible overhead for any candidate |
| Complexity | 1 | Not a concern |
| Accuracy | 3 | False positives during compaction would cause unnecessary routing changes |
| Operational | 3 | Detection speed determines how long a slow node degrades cluster latency |
| Fit | 3 | Must compose with phi accrual, piggybacked state, scatter-gather proxy |

---

## Candidate: Phi Threshold Bands

**KB source:** [`.kb/distributed-systems/cluster-membership/fail-slow-detection.md#approach-1`](../../.kb/distributed-systems/cluster-membership/fail-slow-detection.md)

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 5 | 10 | Reuses existing phi computation — zero additional per-peer state (#approach-1) |
| Resources | 1 | 5 | 5 | No additional state — extends existing phi with threshold comparison |
| Complexity | 1 | 5 | 5 | Add threshold comparison to existing phi check |
| Accuracy | 3 | 3 | 9 | Detects heartbeat delays but misses slow disk/CPU with healthy heartbeats (#approach-1 weaknesses) |
| Operational | 3 | 4 | 12 | Instant detection — phi is computed on every heartbeat (#approach-1) |
|             |   |   |    | **Would be a 2 if:** heartbeat timing was decoupled from actual query performance |
| Fit | 3 | 5 | 15 | Phi accrual already implemented — adding bands is a config change (#approach-1) |
|     |   |   |    | **Would be a 2 if:** phi bands interfered with the failure detection threshold (they don't — separate ranges) |
| **Total** | | | **56** | |

**Key strengths:** zero-cost extension of existing infrastructure
**Key weaknesses:** blind to slow disk with healthy heartbeats

---

## Candidate: Peer Comparison Scoring

**KB source:** [`.kb/distributed-systems/cluster-membership/fail-slow-detection.md#approach-2`](../../.kb/distributed-systems/cluster-membership/fail-slow-detection.md)

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 4 | 8 | Requires metadata from all peers — O(n) comparison per evaluation (#approach-2) |
|       |   |   |   | **Would be a 2 if:** n > 10,000 peers made median computation expensive |
| Resources | 1 | 4 | 4 | 9 bytes metadata per heartbeat; scoring state per peer |
| Complexity | 1 | 3 | 3 | Requires slowdown_ratio computation, duration tracking |
| Accuracy | 3 | 5 | 15 | Detects ANY performance degradation relative to peers — disk, CPU, memory, network. Adapts to cluster-wide load (#approach-2) |
|          |   |   |    | **Would be a 2 if:** all nodes degraded simultaneously (no healthy peers to compare against) |
| Operational | 3 | 4 | 12 | Detection within slowdown_duration periods (default 5 × protocol period) |
|             |   |   |    | **Would be a 2 if:** duration threshold was too high, delaying detection |
| Fit | 3 | 3 | 9 | Depends on piggybacked-state-exchange (sibling decision, not yet confirmed) for metadata transport (#approach-2) |
| **Total** | | | **51** | |

**Key strengths:** detects any measurable degradation vs peers
**Key weaknesses:** depends on unconfirmed sibling decision; blind to cluster-wide slowdown

---

## Candidate: Local Health Scoring

**KB source:** [`.kb/distributed-systems/cluster-membership/fail-slow-detection.md#approach-3`](../../.kb/distributed-systems/cluster-membership/fail-slow-detection.md)

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 5 | 10 | Self-reported — 1 byte per heartbeat |
| Resources | 1 | 5 | 5 | One saturating counter per node |
| Complexity | 1 | 4 | 4 | Simple counter with increment/decrement rules |
| Accuracy | 3 | 3 | 9 | Detects local problems (GC, disk) but self-reported — hung thread can't report (#approach-3) |
| Operational | 3 | 4 | 12 | Peers adjust expectations based on LHM — reduces false suspicion |
|             |   |   |    | **Would be a 2 if:** LHM counter saturated permanently, hiding recovery |
| Fit | 3 | 3 | 9 | Also depends on piggybacked-state-exchange for metadata transport; proven in Consul/Serf |
| **Total** | | | **49** | |

**Key strengths:** detects local problems invisible to peers; proven at scale (Consul)
**Key weaknesses:** self-reported — can't detect its own total hangs

---

## Candidate: Request Latency Tracking

**KB source:** [`.kb/distributed-systems/cluster-membership/fail-slow-detection.md#approach-4`](../../.kb/distributed-systems/cluster-membership/fail-slow-detection.md)

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 5 | 10 | Per-peer EWMA — O(1) per request |
| Resources | 1 | 5 | 5 | One EWMA + slow_count per peer |
| Complexity | 1 | 4 | 4 | EWMA update + threshold check per request |
| Accuracy | 3 | 4 | 12 | Measures actual request latency — detects exactly what matters for query performance (#approach-4) |
|          |   |   |    | **Would be a 2 if:** node had no active queries (detection depends on request rate) |
| Operational | 3 | 4 | 12 | Detection within slow_count_threshold requests (default 5) |
|             |   |   |    | **Would be a 2 if:** request rate was too low for timely detection |
| Fit | 3 | 5 | 15 | Scatter-gather proxy already observes per-partition response times — zero new infrastructure (#approach-4) |
|     |   |   |    | **Would be a 2 if:** request latency tracking required new transport-level hooks |
| **Total** | | | **58** | |

**Key strengths:** measures what actually matters — user-visible request latency
**Key weaknesses:** only evaluates peers that receive requests

---

## Composite Candidate: Phi Bands + Peer Scoring + Request Latency

**Components:** Phi Threshold Bands + Peer Comparison Scoring + Request Latency Tracking
**Boundary rule:** ANY signal triggers DEGRADED; ALL signals must clear for sustained period to recover (hysteresis)

| Constraint | Weight | Component | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-----------|-------------|----------|-----------------|
| Scale | 2 | All | 4 | 8 | Peer scoring requires O(n) comparison; phi and latency are O(1) |
|       |   |     |   |   | **Would be a 2 if:** peer scoring overhead dominated at extreme scale |
| Resources | 1 | All | 4 | 4 | 9 bytes/heartbeat + EWMA/peer + phi (already exists) |
| Complexity | 1 | All | 3 | 3 | Three detection systems + hysteresis recovery logic |
| Accuracy | 3 | All | 5 | 15 | Covers all failure modes: heartbeat delays (phi), relative degradation (peer), actual query impact (latency). No single blind spot. (#composite-approach) |
|          |   |     |   |    | **Would be a 2 if:** all three signals contradicted each other (by design they OR for detection) |
| Operational | 3 | All | 5 | 15 | Fastest detection from any signal; hysteresis prevents flapping (#key-parameters recovery_periods) |
|             |   |     |   |    | **Would be a 2 if:** hysteresis was too aggressive, keeping nodes in DEGRADED after recovery |
| Fit | 3 | All | 4 | 12 | Phi: already implemented. Latency: scatter-gather already tracks. Peer scoring: depends on piggybacked-state-exchange |
|     |   |     |   |    | **Would be a 2 if:** piggybacked-state-exchange decision rejected metadata in heartbeats |
| **Total** | | | | **57** | |

**Integration cost:** Three detection paths feeding a single `isDegraded()` predicate. Hysteresis
counter for recovery (require N consecutive healthy periods across ALL signals).
**When composite is better:** no single approach covers all failure modes. Phi misses slow disk;
peer scoring misses cluster-wide slowdown; request latency misses idle peers.

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| Request Latency Tracking | 10 | 5 | 4 | 12 | 12 | 15 | **58** |
| Composite (Phi+Peer+Latency) | 8 | 4 | 3 | 15 | 15 | 12 | **57** |
| Phi Threshold Bands | 10 | 5 | 5 | 9 | 12 | 15 | **56** |
| Peer Comparison Scoring | 8 | 4 | 3 | 15 | 12 | 9 | **51** |
| Local Health Scoring | 10 | 5 | 4 | 9 | 12 | 9 | **49** |

## Preliminary Recommendation
Request Latency Tracking (58) and the Composite (57) are effectively tied. Request Latency
wins by 1 point due to higher Fit (no dependency on piggybacked-state-exchange). However,
the Composite scores 5 on both Accuracy and Operational (the two highest-weighted constraints)
while Request Latency scores 4 on both. The Composite's lower total comes from Scale and
Resources (weight 1-2 constraints).

Given that Accuracy and Operational are weight 3, the Composite is the better choice by
constraint priority. The 1-point gap favoring Request Latency is driven by lower-priority
dimensions. **Recommend Composite** with the note that Request Latency alone is a valid
v1 if piggybacked-state-exchange is delayed.

## Risks and Open Questions
- Risk: composite depends on piggybacked-state-exchange decision for peer comparison metadata
- Risk: if weighted-node-capacity (deferred) is never implemented, DEGRADED state has no routing effect
- Open: should Local Health Scoring be added as a 4th signal in a future iteration?
