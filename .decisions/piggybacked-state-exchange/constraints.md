---
problem: "How should node metadata (performance metrics, health scores) be distributed across the cluster?"
slug: "piggybacked-state-exchange"
captured: "2026-04-13"
status: "draft"
---

# Constraint Profile — piggybacked-state-exchange

## Problem Statement
The Rapid membership protocol exchanges heartbeat messages between peers. These messages
currently carry only protocol data (pings, acks, view-change proposals). Node metadata —
performance metrics for slow-node detection, capacity for weighted routing, health scores —
needs to travel across the cluster without requiring a separate channel. The design must
define what metadata is piggybacked, how it's encoded, and how it converges.

## Constraints

### Scale
Up to 1000 nodes. Each node monitors K peers (3-5 in the expander graph). Metadata per
heartbeat must be small — target <64 bytes to avoid inflating heartbeat size significantly.

### Resources
Heartbeats fire every protocol period (200ms-2s). Metadata serialization/deserialization
must be O(1) per heartbeat. No heap allocation for metadata parsing in the hot path.

### Complexity Budget
Not a constraint.

### Accuracy / Correctness
Metadata must eventually converge across all nodes. Brief staleness (1-2 protocol periods)
is acceptable — performance metrics are statistical, not transactional. Must not interfere
with membership protocol correctness (heartbeat timing, failure detection).

### Operational Requirements
Must not increase heartbeat size enough to affect phi accrual timing or UDP MTU compliance.
Observable: per-node metadata freshness, metadata size budget utilization.

### Fit
Extends existing Rapid heartbeat messages. Must not require protocol-level changes
(new message types, new handshake phases). The metadata section is an optional extension
of the existing heartbeat payload — nodes that don't understand it ignore it.

## Key Constraints (most narrowing)
1. **Heartbeat size budget** — metadata must fit within existing heartbeat without exceeding UDP MTU or affecting timing
2. **O(1) per heartbeat** — no per-metadata-field iteration or merge at heartbeat frequency
3. **Backward compatible** — nodes that don't understand metadata must still process heartbeats correctly

## Unknown / Not Specified
None — full profile captured.
