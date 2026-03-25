---
problem: "scatter-gather-query-execution"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-03-20"
---

# Scatter-Gather Query Execution — Decision Index

**Problem:** How should queries on partitioned tables fan out to partition owners and merge results?
**Status:** confirmed
**Current recommendation:** Partition-Aware Proxy Table — implements Table interface transparently, streaming k-way merge, partition pruning, PartialResultMetadata
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
| v1 | [adr.md](adr.md) | 2026-03-20 | active | Partition-Aware Proxy Table |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
| Distributed join execution | distributed-join-execution | deferred | Joins enter scope |
