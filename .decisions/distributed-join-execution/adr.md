---
problem: "distributed-join-execution"
date: "2026-03-20"
version: 1
status: "deferred"
depends_on: ["aggregation-query-merge", "limit-offset-pushdown"]
---

# Distributed Join Execution — Deferred

## Problem
How should joins across distributed (partitioned) tables be executed? Involves choosing between co-partitioned joins (zero cross-node traffic when tables share a partition key), broadcast joins (small table broadcast to all nodes), and repartition/shuffle joins (fallback when partition keys are incompatible).

## Why Deferred
Joins are not in scope for the current engine-clustering feature. The scatter-gather proxy handles single-table queries. A query planner for multi-table operations is a separate layer.

## Resume When
Joins enter scope — likely when jlsm-sql is extended beyond single-table SELECT or when a feature brief includes cross-table distributed queries.

## What Is Known So Far
- The Proxy Table pattern (ADR: scatter-gather-query-execution) composes well — a planner would sit above proxies and use them as building blocks
- Three candidate strategies identified: co-partitioned join, broadcast join, repartition/shuffle join
- Partition pruning applies to joins — key-bounded join predicates prune both sides before fan-out
- Co-partitioned join is the optimal case and should be the primary design target (encourage compatible partition keys)
- jlsm-sql already has a SQL parser — the planner would sit between the SQL translator and proxy tables

## Next Step
Run `/architect "distributed-join-execution"` when ready to evaluate.
