---
problem: "aggregation-query-merge"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-14"
---

# Aggregation Query Merge — Decision Index

**Problem:** How to merge aggregation results (COUNT, SUM, AVG, MIN, MAX, GROUP BY) from per-partition execution at the scatter-gather proxy
**Status:** confirmed
**Current recommendation:** Two-Phase Partial Aggregation with Cardinality Guard — partitions compute partials, coordinator merges; falls back to sorted-run merge for high-cardinality GROUP BY
**Last activity:** 2026-04-14 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-14 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (3 candidates, revised after falsification) | 2026-04-14 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-14 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-14 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Distributed Join Execution Strategies | Chosen approach (aggregation pushdown section) | [`.kb/distributed-systems/query-execution/distributed-join-strategies.md`](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md) |
| Distributed Scan Cursor Management | Context (backpressure alignment) | [`.kb/distributed-systems/query-execution/distributed-scan-cursors.md`](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-14 | active | Two-Phase Partial Aggregation with Cardinality Guard |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
| DISTINCT Aggregate Decomposition | distinct-aggregate-decomposition | deferred | DISTINCT aggregates become primary use case |
| Holistic Aggregate Support | holistic-aggregate-support | deferred | MEDIAN/PERCENTILE enter scope |
