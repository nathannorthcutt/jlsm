---
problem: "discovery-spi-design"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-03-20"
---

# Discovery SPI Design — Decision Index

**Problem:** What should the pluggable discovery SPI look like?
**Status:** confirmed
**Current recommendation:** Minimal Seed Provider with Optional Registration — one required method (discoverSeeds), two default no-op methods (register/deregister)
**Last activity:** 2026-03-20 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-03-20 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (3 candidates) | 2026-03-20 |
| [constraints.md](constraints.md) | Constraint profile | 2026-03-20 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-03-20 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| (general knowledge) | No direct KB entry | — |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-03-20 | active | Minimal Seed Provider with Optional Registration |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
