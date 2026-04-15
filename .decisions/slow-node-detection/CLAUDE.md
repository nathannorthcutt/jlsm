---
problem: "slow-node-detection"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-13"
---

# Slow Node Detection — Decision Index

**Problem:** How to detect and respond to slow-but-alive nodes
**Status:** confirmed
**Current recommendation:** Composite — Phi Bands + Peer Comparison + Request Latency; ANY triggers DEGRADED, ALL clear for recovery
**Last activity:** 2026-04-13 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-13 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (5 candidates) | 2026-04-13 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-13 |
| [log.md](log.md) | Full decision history | 2026-04-13 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Fail-Slow Node Detection | Chosen (composite) | [`.kb/distributed-systems/cluster-membership/fail-slow-detection.md`](../../.kb/distributed-systems/cluster-membership/fail-slow-detection.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-13 | active | Composite Detection |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
