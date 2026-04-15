---
problem: "join-ordering-optimization"
date: "2026-04-14"
version: 1
status: "deferred"
---

# Join Ordering Optimization — Deferred

## Problem
Cost-based join ordering for multi-way joins across distributed tables — selecting the
optimal join sequence to minimize intermediate result sizes and total data movement when
three or more tables are joined in a single query.

## Why Deferred
Requires query planner infrastructure (`query-planner-integration`) and cardinality
estimation that are not yet built. Without statistics collection and a cost model,
cost-based ordering cannot make correct decisions. Heuristic ordering (smallest table
first) is sufficient for initial join support.

## Resume When
Multi-way joins enter scope — specifically when the query planner integration is complete
and there is evidence that heuristic ordering is producing suboptimal plans in practice.

## What Is Known So Far
See `.decisions/distributed-join-execution/adr.md` for the two-table join foundation this
extends. See `.decisions/query-planner-integration/adr.md` for the prerequisite SQL-layer
integration.
