---
problem: "limit-offset-pushdown"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-14"
---

# LIMIT/OFFSET Partition Pushdown — Decision Index

**Problem:** How to optimize LIMIT/OFFSET beyond naive over-fetch at the scatter-gather proxy
**Status:** confirmed
**Current recommendation:** Top-N Pushdown with Keyset Pagination — replace OFFSET with continuation token pagination, each page O(P * LIMIT) regardless of depth
**Last activity:** 2026-04-14 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-14 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (3 candidates) | 2026-04-14 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-14 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-14 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Distributed Join Execution Strategies | LIMIT/OFFSET pushdown section | [`.kb/distributed-systems/query-execution/distributed-join-strategies.md`](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md) |
| Distributed Scan Cursor Management | Continuation token model | [`.kb/distributed-systems/query-execution/distributed-scan-cursors.md`](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-14 | active | Top-N Pushdown with Keyset Pagination |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
| Secondary-Sort Pagination | secondary-sort-pagination | deferred | ORDER BY non-key column enters scope |
| Backward Pagination | backward-pagination | deferred | Bidirectional pagination requirement |
