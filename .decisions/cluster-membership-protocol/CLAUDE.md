---
problem: "cluster-membership-protocol"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-03-20"
---

# Cluster Membership Protocol — Decision Index

**Problem:** What protocol should engine nodes use to form a peer-to-peer cluster, track membership, and detect split-brain scenarios without a leader?
**Status:** confirmed
**Current recommendation:** Rapid + Phi Accrual Composite — expander-graph monitoring, multi-process cut detection, leaderless 75% consensus, adaptive phi accrual edge failure detection
**Last activity:** 2026-03-20 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-03-20 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (5 candidates) | 2026-03-20 |
| [constraints.md](constraints.md) | Constraint profile | 2026-03-20 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-03-20 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Cluster Membership Protocols | Chosen (Rapid + Phi Accrual composite) | [`.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md) |
| SWIM + Quorum | Rejected — weaker consistency, no batched failure detection | [`.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md) |
| Scuttlebutt + Phi Accrual | Rejected — weak consistency, no Java impl | [`.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-03-20 | active | Rapid + Phi Accrual Composite |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
