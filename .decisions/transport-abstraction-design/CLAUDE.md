---
problem: "transport-abstraction-design"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-03-20"
---

# Transport Abstraction Design — Decision Index

**Problem:** What should the pluggable transport interface look like for inter-node messaging?
**Status:** confirmed
**Current recommendation:** Message-Oriented Transport — send() + request() with type-based handler dispatch, threading model constraint (virtual threads for I/O, platform threads for encryption/ThreadLocal)
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
| v1 | [adr.md](adr.md) | 2026-03-20 | active | Message-Oriented Transport with threading model constraint |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
