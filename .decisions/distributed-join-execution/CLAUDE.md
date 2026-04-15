---
problem: "distributed-join-execution"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-14"
---

# Distributed Join Execution — Decision Index

**Problem:** How to execute joins across distributed partitioned tables
**Status:** confirmed
**Current recommendation:** Co-Partitioned + Broadcast two-tier strategy — co-partitioned for same-key tables (zero cost), broadcast for small-side joins, reject otherwise (shuffle deferred)
**Last activity:** 2026-04-14 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-14 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (4 candidates incl. composite) | 2026-04-14 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-14 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-14 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Distributed Join Execution Strategies | Primary — strategy taxonomy | [`.kb/distributed-systems/query-execution/distributed-join-strategies.md`](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md) |
| Join Algorithm Selection & Cost Models | Local algorithm selection | [`.kb/systems/query-processing/lsm-join-algorithms.md`](../../.kb/systems/query-processing/lsm-join-algorithms.md) |
| Join Anti-Patterns & Optimizations | Optimization checklist | [`.kb/systems/query-processing/lsm-join-anti-patterns.md`](../../.kb/systems/query-processing/lsm-join-anti-patterns.md) |
| Snapshot Consistency for Joins | Cross-table consistency model | [`.kb/systems/query-processing/lsm-join-snapshot-consistency.md`](../../.kb/systems/query-processing/lsm-join-snapshot-consistency.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-14 | active | Co-Partitioned + Broadcast two-tier |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
| Shuffle/Repartition Joins | shuffle-repartition-joins | deferred | Large non-co-partitioned joins needed |
| Semi-Join Reduction | semi-join-reduction | deferred | Low-selectivity join optimization needed |
| Query Planner Integration | query-planner-integration | deferred | jlsm-sql needs strategy invocation |
| Join Ordering Optimization | join-ordering-optimization | deferred | Multi-way joins enter scope |
