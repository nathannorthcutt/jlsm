---
title: "Fail-Slow Node Detection"
aliases: ["slow node", "degraded node", "gray failure", "fail-slow", "performance degradation detection"]
topic: "distributed-systems"
category: "cluster-membership"
tags: ["fail-slow", "gray-failure", "degraded", "performance", "detection", "scoring", "adaptive-threshold"]
complexity:
  time_build: "N/A"
  time_query: "O(1) per heartbeat evaluation"
  space: "O(window_size) per monitored peer"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/"
related:
  - "distributed-systems/cluster-membership/cluster-membership-protocols.md"
  - "distributed-systems/cluster-membership/view-stall-recovery.md"
decision_refs: ["slow-node-detection"]
sources:
  - url: "https://www.usenix.org/conference/fast23/presentation/lu"
    title: "Perseus: A Fail-Slow Detection Framework for Cloud Storage Systems (FAST 2023)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://www.micahlerner.com/2023/04/16/perseus-a-fail-slow-detection-framework-for-cloud-storage-systems.html"
    title: "Perseus Framework Summary (Micah Lerner)"
    accessed: "2026-04-13"
    type: "blog"
  - url: "https://ucare.cs.uchicago.edu/pdf/atc19-iaso.pdf"
    title: "IASO: A Fail-Slow Detection and Mitigation Framework (ATC 2019)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://dl.acm.org/doi/10.1145/3617690"
    title: "From Missteps to Milestones: A Journey to Practical Fail-Slow Detection (ACM TOS 2023)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://essoz.github.io/assets/pdf/xinda-nsdi25-preprint.pdf"
    title: "Understanding and Enhancing Slow-Fault Tolerance in Distributed Systems (NSDI 2025)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://github.com/tikv/tikv/issues/10539"
    title: "TiKV: Introduce slow node detecting mechanism"
    accessed: "2026-04-13"
    type: "repo"
  - url: "https://www.usenix.org/system/files/atc25-dong.pdf"
    title: "Understanding and Detecting Fail-Slow Hardware Failure (ATC 2025)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://dl.acm.org/doi/pdf/10.1145/3658617.3697567"
    title: "A Fail-Slow Detection Framework for HBM Devices (2024)"
    accessed: "2026-04-13"
    type: "paper"
---

# Fail-Slow Node Detection

## summary

A fail-slow (or "gray failure") node is functioning but with degraded
performance — heartbeats arrive late, queries respond slowly, replication
lags. Traditional failure detectors (phi accrual, SWIM ping/ack) are binary:
alive or dead. They cannot distinguish "slow but alive" from "temporarily
unreachable." Fail-slow detection adds a third state — **degraded** — enabling
the cluster to route work away from slow nodes without removing them from
membership. This is critical because a slow node left in the active path can
cause cascading latency degradation across the entire cluster.

## how-it-works

### the-fail-slow-spectrum

```
HEALTHY ──────── DEGRADED ──────── SUSPECTED ──────── DEAD
  │                 │                  │                │
  │  phi < 4        │  phi 4-8         │  phi > 8       │  timeout
  │  p99 < 2x avg   │  p99 2-10x avg   │  no response   │  confirmed
  │                 │                  │                │
  route normally    route around       prepare removal   remove from view
```

Binary failure detection collapses DEGRADED and HEALTHY into one state.
Fail-slow detection expands the model to make DEGRADED actionable.

### detection-approaches

#### approach-1-phi-threshold-bands

Extend the phi accrual failure detector with intermediate thresholds:

| Band | Phi Range | State | Action |
|------|-----------|-------|--------|
| Normal | phi < 4 | HEALTHY | Route normally |
| Warning | 4 ≤ phi < 8 | DEGRADED | Reduce routing weight; log warning |
| Suspicious | phi ≥ 8 | SUSPECTED | Stop routing; begin failure confirmation |
| Failed | timeout | DEAD | Remove from view |

**Strengths:** minimal implementation — phi accrual already maintains the
heartbeat distribution. Adding bands requires only threshold comparisons.
**Weaknesses:** phi measures heartbeat arrival time, which correlates with
but does not directly measure query/replication performance. A node with
healthy heartbeats but slow disk I/O would not be detected.

#### approach-2-peer-comparison-scoring

PERSEUS-style scoring: compare each node's performance metrics against its
peers to derive a relative slowdown score.

**Algorithm:**
1. Each node reports its p99 latency for key operations (query, replication,
   compaction) as metadata in heartbeat messages
2. The monitoring node builds a distribution of p99 values across all peers
3. For each peer, compute: `slowdown_ratio = peer_p99 / median_p99`
4. A peer with `slowdown_ratio > threshold` (default 3.0) for more than
   `duration_threshold` consecutive periods is marked DEGRADED

**Strengths:** measures actual performance, not just heartbeat timing. Detects
slow disk, slow CPU, memory pressure. Adapts to cluster-wide load changes
(if all nodes slow down, median rises, no false positives).
**Weaknesses:** requires performance metadata in heartbeat messages. Scoreboard
mechanism adds state per peer. Cannot detect degradation when ALL nodes are
slow (no healthy peers to compare against).

#### approach-3-local-health-scoring

Lifeguard-style Local Health Multiplier (LHM): each node scores its own
health based on its success rate for outgoing operations.

**Algorithm:**
1. Maintain a saturating counter LHM (0 to S, default S=8)
2. Increment on: failed outgoing probe, missed NACK, self-suspicion refutation
3. Decrement on: successful outgoing probe
4. Expose LHM in heartbeat metadata
5. Peers use LHM to adjust their expectations: a node with high LHM is
   experiencing local problems — widen its phi threshold to prevent false
   suspicion, but reduce its routing weight

**Strengths:** detects local problems (GC pressure, disk contention) that
affect this node specifically, even when heartbeat timing looks normal.
Proven in Hashicorp Consul/Serf (>50x false positive reduction).
**Weaknesses:** self-reported — a node with a hung thread may not be able to
report its own degradation. Does not detect problems visible only to peers
(network path degradation between specific node pairs).

#### approach-4-request-latency-tracking

Track actual request-response latency per peer at the transport layer.
The scatter-gather proxy already observes per-partition response times.

**Algorithm:**
1. For each peer, maintain an exponentially weighted moving average (EWMA)
   of request-response latency: `ewma = alpha * current + (1-alpha) * ewma`
2. Maintain a slow_count: number of consecutive requests where
   `current > ewma * slowdown_factor`
3. If `slow_count > threshold` (default 5), mark peer as DEGRADED
4. Reset slow_count on any fast response

**Strengths:** measures what matters — actual request latency from this
node's perspective. Detects asymmetric network degradation (path between
two specific nodes). No metadata in heartbeats needed.
**Weaknesses:** only detects slow nodes for peers that send requests. A
node with no active queries may not be evaluated. Detection latency
depends on request rate.

### composite-approach

For comprehensive coverage, combine approaches:

```
                     ┌─────────────────────┐
                     │  DEGRADED if ANY of: │
                     │                     │
  Phi bands ─────────┤  phi in [4, 8)      │
                     │                     │
  Peer scoring ──────┤  slowdown_ratio > 3 │──── Route around
                     │                     │     Reduce weight
  Local health ──────┤  LHM > S/2          │     Log alert
                     │                     │
  Request latency ───┤  slow_count > 5     │
                     │                     │
                     └─────────────────────┘
```

Any single signal can trigger DEGRADED state. Recovery from DEGRADED requires
ALL signals to return to normal for a sustained period (hysteresis prevents
flapping).

### key-parameters

| Parameter | Description | Default | Range |
|-----------|-------------|---------|-------|
| phi_warning_threshold | Phi value for DEGRADED band | 4.0 | 2.0–6.0 |
| slowdown_ratio_threshold | Peer comparison ratio for DEGRADED | 3.0 | 2.0–10.0 |
| slowdown_duration | Consecutive periods above ratio before marking | 5 | 3–20 |
| lhm_max | Local Health Multiplier saturation | 8 | 4–16 |
| ewma_alpha | EWMA smoothing factor for request latency | 0.3 | 0.1–0.5 |
| slow_count_threshold | Consecutive slow requests before DEGRADED | 5 | 3–10 |
| recovery_periods | Consecutive healthy periods to clear DEGRADED | 10 | 5–30 |

## implementation-notes

### metadata-in-heartbeats

For approaches 2 and 3, performance metadata must travel with heartbeats.
The piggybacked-state-exchange decision (sibling in this WD) determines the
format. Minimum metadata per heartbeat:
- `p99_query_ms` (4 bytes, float)
- `p99_replication_ms` (4 bytes, float)
- `lhm` (1 byte, unsigned)
- Total: 9 bytes per heartbeat — negligible overhead

### routing-around-degraded-nodes

When a node is marked DEGRADED:
- Scatter-gather proxy reduces the node's partition weight — queries prefer
  replicas on healthy nodes when available
- Replication continues to the degraded node (data must still reach it)
- The degraded node is NOT removed from the membership view — it remains
  a valid member, just deprioritized for latency-sensitive work
- This requires the ownership lookup (HRW) to support weighted routing,
  which is the `weighted-node-capacity` deferred decision

### edge-cases-and-gotchas

- **Cluster-wide slowdown:** if all nodes degrade simultaneously (e.g.,
  shared storage issue), peer comparison produces no signal. Phi bands
  and local health still detect the problem, but there are no healthy nodes
  to route to. The cluster degrades gracefully rather than flagging everyone.
- **Flapping:** a node oscillating between healthy and degraded causes
  routing weight oscillation. Hysteresis (require N consecutive healthy
  periods before clearing DEGRADED) prevents this.
- **False positives during compaction:** heavy compaction temporarily
  increases p99 latency. The duration threshold (5 consecutive periods)
  avoids flagging brief compaction spikes. Tune higher for write-heavy
  workloads.

## trade-offs

| Approach | Detects | Misses | Overhead |
|----------|---------|--------|----------|
| Phi bands | Heartbeat delays | Slow disk with healthy heartbeats | None (reuses existing phi) |
| Peer scoring | Any perf degradation vs peers | Cluster-wide slowdown | 9 bytes/heartbeat |
| Local health | Local problems (GC, disk) | Problems only visible to peers | 1 byte/heartbeat |
| Request latency | Actual query slowdown | Nodes with no active queries | Per-request EWMA update |

**Recommendation for jlsm:** composite approach using phi bands (already
implemented) + peer comparison scoring (requires piggybacked metadata) +
request latency tracking (scatter-gather proxy already observes this).
Local health multiplier adds value but can be deferred to a follow-up.

## current-research

### key-papers

- PERSEUS (FAST 2023) — polynomial regression on latency-throughput for
  adaptive fail-slow thresholds; 99% precision, 100% recall on 248K drives
- IASO (ATC 2019) — timeout-based fail-slow detection at application level
- Themis (EuroSys 2025) — imbalance failure detection in distributed file
  systems; 82% of imbalance failures affect all/majority of nodes
- NSDI 2025 preprint — slow-fault tolerance gaps; many production systems
  lack explicit fail-slow handling, causing cascading failures
- ATC 2025 — fail-slow detection for HBM devices; extends PERSEUS to
  high-bandwidth memory with workload-specific regression models

### active-research-directions

- Hardware-specific fail-slow models (HBM, NVMe, CXL-attached memory)
- AI-assisted threshold auto-tuning for heterogeneous hardware
- Integration of fail-slow detection with capacity-aware scheduling

## practical-usage

### when-to-use
- Any cluster where a single slow node can cause cascading latency impact
- Systems with heterogeneous hardware where some nodes may degrade
- Production clusters running on shared infrastructure (noisy neighbors)

### when-not-to-use
- Homogeneous clusters with tight SLAs where any degradation = eviction
- Systems where removing a slow node is preferred over routing around it

## code-skeleton

```java
class FailSlowDetector {
    record PeerHealth(double phi, double slowdownRatio, int lhm, int slowCount) {
        boolean isDegraded(FailSlowConfig config) {
            return phi >= config.phiWarningThreshold()
                || slowdownRatio > config.slowdownRatioThreshold()
                || lhm > config.lhmMax() / 2
                || slowCount > config.slowCountThreshold();
        }
    }

    void onHeartbeat(NodeAddress peer, HeartbeatMetadata meta) {
        updatePhiBands(peer, meta.arrivalTime());
        updatePeerScoring(peer, meta.p99QueryMs(), meta.p99ReplicationMs());
        updateLocalHealth(peer, meta.lhm());
    }

    void onRequestComplete(NodeAddress peer, long latencyNanos) {
        var ewma = peerEwma.get(peer);
        double currentMs = latencyNanos / 1_000_000.0;
        ewma.update(currentMs);
        if (currentMs > ewma.value() * slowdownFactor) {
            peerSlowCount.merge(peer, 1, Integer::sum);
        } else {
            peerSlowCount.put(peer, 0);
        }
    }

    MemberState assessPeer(NodeAddress peer) {
        var health = buildPeerHealth(peer);
        if (health.isDegraded(config)) return MemberState.DEGRADED;
        return MemberState.HEALTHY;
    }
}
```

---
*Researched: 2026-04-13 | Next review: 2026-07-13*
