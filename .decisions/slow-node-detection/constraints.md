---
problem: "How should the cluster detect and respond to slow-but-alive nodes without removing them from membership?"
slug: "slow-node-detection"
captured: "2026-04-13"
status: "draft"
---

# Constraint Profile — slow-node-detection

## Problem Statement
Phi accrual failure detection is binary: a node is alive or suspected/dead. It cannot
distinguish a slow-but-alive node from a healthy one. A slow node left in the active
routing path causes cascading latency degradation across the cluster. The cluster needs
a mechanism to detect performance degradation and route work around degraded nodes
without removing them from the membership view.

## Constraints

### Scale
Up to 1000 nodes. Per-peer performance scoring — each node tracks metrics for its
monitored peers. Metadata in heartbeats must be small (target <10 bytes per peer).

### Resources
Minimal — EWMA per peer, scoring state per peer. Pure Java.

### Complexity Budget
Not a constraint.

### Accuracy / Correctness
Must distinguish genuine degradation from transient spikes (compaction, GC). Must not
false-positive during brief compaction bursts. Must not remove degraded nodes from
membership — they remain valid members, just deprioritized for latency-sensitive work.
The DEGRADED state must have hysteresis (require sustained recovery before clearing).

### Operational Requirements
Detection within seconds of sustained degradation (5+ consecutive slow periods).
Routing weight reduction must be automatic. Observable: per-node degradation state,
current scoring metrics.

### Fit
Builds on phi accrual (already part of cluster-membership-protocol ADR). Peer comparison
requires performance metadata in heartbeats (depends on piggybacked-state-exchange,
sibling decision). Request latency tracking uses scatter-gather proxy observations
(already in place). Routing around degraded nodes requires weighted-node-capacity
(deferred decision in partition-to-node-ownership).

## Key Constraints (most narrowing)
1. **No false positives during compaction** — duration threshold prevents flagging transient spikes
2. **DEGRADED ≠ DEAD** — degraded nodes stay in membership, just deprioritized
3. **Depends on piggybacked-state-exchange** — peer comparison needs metadata in heartbeats

## Unknown / Not Specified
None — full profile captured.
