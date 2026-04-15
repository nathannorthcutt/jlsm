---
problem: "query-planner-integration"
date: "2026-04-14"
version: 1
status: "deferred"
---

# Query Planner Integration — Deferred

## Problem
How jlsm-sql's query translator selects and invokes distributed join strategies — the
interface between the SQL layer's logical plan and the physical execution layer's join
strategy selector.

## Why Deferred
Scoped out during the `distributed-join-execution` decision. The strategy selector
(`JoinStrategySelector`) exists and is exercised by the engine layer, but wiring it into
the SQL translator is a distinct concern that requires jlsm-sql to support multi-table
SELECT — which it does not yet do.

## Resume When
jlsm-sql is extended beyond single-table SELECT to support joins, at which point the SQL
translator will need to emit logical join nodes and delegate strategy selection to the
engine.

## What Is Known So Far
See `.decisions/distributed-join-execution/adr.md` for the engine-layer strategy selector
design. The SQL translator's current scope is documented in `jlsm-sql` module docs.
