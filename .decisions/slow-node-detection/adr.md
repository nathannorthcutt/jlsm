---
problem: "slow-node-detection"
date: "2026-04-13"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/"
---

# ADR — Slow Node Detection

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Fail-Slow Node Detection | Chosen approach — composite detection with 3 signals | [`.kb/distributed-systems/cluster-membership/fail-slow-detection.md`](../../.kb/distributed-systems/cluster-membership/fail-slow-detection.md) |

## Related ADRs
| ADR | Relationship |
|-----|-------------|
| [cluster-membership-protocol](../cluster-membership-protocol/adr.md) | Parent — phi accrual is the base failure detector this extends |
| [piggybacked-state-exchange](../piggybacked-state-exchange/adr.md) | Dependency — peer comparison scoring requires metadata in heartbeats |
| [scatter-gather-query-execution](../scatter-gather-query-execution/adr.md) | Data source — proxy already tracks per-partition response latency |

---

## Files Constrained by This Decision
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/` — failure detector, degraded state management

## Problem
Phi accrual failure detection is binary: alive or dead. A slow-but-alive node left in
the active routing path causes cascading latency degradation. The cluster needs to detect
performance degradation and route work around degraded nodes without removing them from
the membership view.

## Constraints That Drove This Decision
- **Accuracy**: must detect genuine degradation without false positives during compaction spikes
- **Coverage breadth**: no single signal detects all failure modes (heartbeat delays, slow disk, slow queries)
- **DEGRADED ≠ DEAD**: degraded nodes stay in membership, just deprioritized

## Decision
**Chosen approach: [Composite Detection](../../.kb/distributed-systems/cluster-membership/fail-slow-detection.md) — Phi Bands + Peer Comparison + Request Latency**

Three detection signals, any of which can trigger DEGRADED state:

1. **Phi Threshold Bands**: extend the existing phi accrual failure detector with an
   intermediate band (phi 4–8 = DEGRADED). Zero additional implementation — adds
   threshold comparisons to existing phi computation. Detects heartbeat timing delays.

2. **Peer Comparison Scoring**: each node reports its p99 query and replication latency
   as 9-byte metadata in heartbeat messages (via piggybacked-state-exchange). Monitoring
   nodes compute `slowdown_ratio = peer_p99 / median_p99`. A peer with ratio > 3.0 for
   5+ consecutive periods is DEGRADED. Detects any measurable performance degradation
   relative to peers.

3. **Request Latency Tracking**: EWMA of per-peer request-response latency observed by
   the scatter-gather proxy. When `current_latency > ewma × slowdown_factor` for
   `slow_count_threshold` consecutive requests, mark DEGRADED. Detects actual query
   performance impact — measures the symptom directly.

**Detection rule:** ANY signal triggers DEGRADED.
**Recovery rule:** ALL signals must be clear for `recovery_periods` consecutive periods
(hysteresis prevents flapping).

### DEGRADED State Actions
- Scatter-gather proxy reduces the node's routing weight — queries prefer replicas
  on healthy nodes when available
- Replication continues normally (data must still reach the degraded node)
- Node remains in the membership view — it is not evicted
- Routing weight adjustment requires the `weighted-node-capacity` deferred decision

## Rationale

### Why Composite Detection
- **Coverage breadth**: phi bands detect heartbeat delays; peer comparison detects relative
  degradation (slow disk, CPU, memory pressure); request latency detects actual query impact.
  No single signal covers all failure modes.
  ([KB: `#composite-approach`](../../.kb/distributed-systems/cluster-membership/fail-slow-detection.md))
- **Hysteresis**: ANY-trigger + ALL-clear prevents both missed detections and flapping
- **Proven signals**: phi bands extend existing phi accrual; peer comparison is PERSEUS-derived
  (99% precision at Alibaba); request latency is standard in RPC stacks

### Why not Request Latency alone
- **Blind spot**: nodes with no active queries from this observer are not evaluated.
  Peer comparison fills this gap via shared metadata.

### Why not Phi Bands alone
- **Blind spot**: a node with slow disk but healthy heartbeats (heartbeat thread is
  uncontended) produces normal phi values. Misses the primary fail-slow scenario.

### Why not Local Health Scoring
- **Self-reported**: a hung thread cannot report its own degradation. Deferred as a
  future 4th signal once the composite is proven.

## Implementation Guidance

Key parameters from [`fail-slow-detection.md#key-parameters`](../../.kb/distributed-systems/cluster-membership/fail-slow-detection.md#key-parameters):
- phi_warning_threshold: 4.0 (DEGRADED band lower bound)
- slowdown_ratio_threshold: 3.0 (peer comparison)
- slowdown_duration: 5 consecutive periods
- ewma_alpha: 0.3 (request latency smoothing)
- slow_count_threshold: 5 consecutive slow requests
- recovery_periods: 10 consecutive healthy periods

Heartbeat metadata (via piggybacked-state-exchange):
- p99_query_ms: 4 bytes (float)
- p99_replication_ms: 4 bytes (float)
- lhm: 1 byte (reserved for future Local Health Multiplier)
- Total: 9 bytes per heartbeat

Edge cases from [`fail-slow-detection.md#edge-cases-and-gotchas`](../../.kb/distributed-systems/cluster-membership/fail-slow-detection.md#edge-cases-and-gotchas):
- Compaction spikes: duration threshold (5 periods) avoids flagging brief spikes
- Cluster-wide slowdown: peer comparison produces no signal (all nodes slow); phi bands
  and request latency still detect from the observer's perspective, but no healthy nodes
  to route to — cluster degrades gracefully
- Flapping: hysteresis (10 recovery periods) prevents oscillation

## What This Decision Does NOT Solve
- Automatic routing weight adjustment for DEGRADED nodes (requires weighted-node-capacity — deferred)
- Detection of degradation on nodes with no active queries AND no heartbeat delay (theoretical gap)

## Conditions for Revision
This ADR should be re-evaluated if:
- Piggybacked-state-exchange is revised to exclude performance metadata (peer comparison becomes impossible)
- False positive rate during compaction exceeds 5% despite duration threshold (may need compaction-aware detection)
- Local Health Scoring proves necessary as a 4th signal (add when composite is proven in production)

---
*Confirmed by: user deliberation (2 rounds — initial revision to Request Latency, re-revised to Composite after user noted piggybacked-state-exchange is confirmed in same session) | Date: 2026-04-13*
*Full scoring: [evaluation.md](evaluation.md)*
