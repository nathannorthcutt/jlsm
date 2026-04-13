# cluster-membership — Category Index
*Topic: distributed-systems*

Protocols and algorithms for maintaining consistent cluster membership views
in distributed systems. Covers failure detection (SWIM, phi accrual),
membership dissemination (gossip/piggybacking), and split-brain detection
(quorum-based, adaptive).

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [cluster-membership-protocols.md](cluster-membership-protocols.md) | SWIM, phi accrual, split-brain detection | mature | O(1) per-member per-period | Peer-to-peer cluster membership at scale |
| [nan-at-phi-threshold.md](nan-at-phi-threshold.md) | NaN bypasses phi threshold validation | active | adversarial-finding | Failure detector parameter validation |
| [ismember-ignores-state.md](ismember-ignores-state.md) | isMember() returns true for DEAD members | active | adversarial-finding | Membership state transition handling |
| [engine-clustering.md](engine-clustering.md) | engine-clustering feature footprint | stable | feature-footprint | Cluster membership + ownership audit |
| [service-discovery-patterns.md](service-discovery-patterns.md) | Service Discovery Patterns for Cluster Bootstrap | active | O(1) cached lookup | Bootstrap, rediscovery, authenticated discovery |

## Comparison Summary
<!-- Narrative comparison — write once 2+ subjects exist -->

## Recommended Reading Order
1. Start: [cluster-membership-protocols.md](cluster-membership-protocols.md) — SWIM protocol, failure detection, split-brain
2. Then: [service-discovery-patterns.md](service-discovery-patterns.md) — bootstrap, environment config, auth'd discovery

## Research Gaps
- Raft-based membership (strong consistency alternative to SWIM)
- Virtual synchrony / JGroups approach (ordered view changes)
- Phi accrual failure detector detailed implementation and tuning
- CRDT-based membership state (conflict-free replicated membership)

## Shared References Used
@../../_refs/complexity-notation.md
