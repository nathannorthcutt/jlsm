---
problem: "engine-api-surface-design"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-03-19"
---

# Engine API Surface Design — Decision Index

**Problem:** What should the jlsm-engine public API look like for dual-mode (embedded + remote) with handle leak protection?
**Status:** confirmed
**Current recommendation:** Interface-Based Handle Pattern with Tracked Lifecycle and Lease Eviction — Engine + Table interfaces, source-attributed handle tracking, greedy-source-first eviction
**Last activity:** 2026-03-19 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-03-19 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix | 2026-03-19 |
| [constraints.md](constraints.md) | Constraint profile | 2026-03-19 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-03-19 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| (none) | No direct KB coverage | — |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-03-19 | active | Interface-Based Handle Pattern with Tracked Lifecycle and Lease Eviction |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
