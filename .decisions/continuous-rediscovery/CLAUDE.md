---
problem: "continuous-rediscovery"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-13"
---

# Continuous Re-Discovery — Decision Index

**Problem:** Should the engine periodically re-invoke discoverSeeds() to find new nodes in dynamic environments?
**Status:** confirmed
**Current recommendation:** Periodic Rediscovery + Optional Reactive Watch — periodic loop as universal fallback, optional watchSeeds() for sub-second push-based discovery
**Last activity:** 2026-04-13 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-13 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (4 candidates) | 2026-04-13 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-13 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-13 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Service Discovery Patterns | Chosen (composite) | [`.kb/distributed-systems/cluster-membership/service-discovery-patterns.md`](../../.kb/distributed-systems/cluster-membership/service-discovery-patterns.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-13 | active | Periodic + Optional Watch (revised from periodic-only during deliberation) |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
