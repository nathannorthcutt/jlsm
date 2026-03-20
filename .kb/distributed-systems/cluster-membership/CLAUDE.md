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

## Comparison Summary
<!-- Narrative comparison — write once 2+ subjects exist -->

## Recommended Reading Order
1. Start: [cluster-membership-protocols.md](cluster-membership-protocols.md) — SWIM protocol, failure detection, split-brain

## Research Gaps
- Raft-based membership (strong consistency alternative to SWIM)
- Virtual synchrony / JGroups approach (ordered view changes)
- Phi accrual failure detector detailed implementation and tuning
- CRDT-based membership state (conflict-free replicated membership)

## Shared References Used
@../../_refs/complexity-notation.md
