---
problem: "rebalancing-grace-period-strategy"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-03-20"
---

# Rebalancing & Grace Period Strategy — Decision Index

**Problem:** How and when should partition ownership be redistributed when nodes join/leave?
**Status:** confirmed
**Current recommendation:** Eager Reassignment with Deferred Cleanup — immediate HRW recompute on departure, grace period controls cleanup not assignment, WAL replay for takeover
**Last activity:** 2026-03-20 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-03-20 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (4 candidates) | 2026-03-20 |
| [constraints.md](constraints.md) | Constraint profile | 2026-03-20 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-03-20 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| (general knowledge) | No direct KB entry | — |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-03-20 | active | Eager Reassignment with Deferred Cleanup |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
