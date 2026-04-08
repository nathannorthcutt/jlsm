---
title: "SQL Query Surface"
slug: sql-surface
domain: query
status: active
type: core
tags: ["sql", "query", "parsing", "predicate", "select", "where"]
features:
  - slug: sql-query-support
    role: core
    description: "SQL SELECT parsing, AST construction, and translation to fluent query API / predicate tree"
composes: []
spec_refs: ["F07"]
decision_refs: []
kb_refs: ["algorithms/sql-extensions", "systems/query-processing"]
depends_on: ["query/secondary-indices"]
enables: ["distribution/database-engine"]
---

# SQL Query Surface

Optional SQL query surface for JlsmTable. Parses a read-only SQL subset
(SELECT with WHERE, ORDER BY, LIMIT) into an AST, then translates it to
the fluent query API and predicate tree provided by the secondary indices
capability. Custom syntax extensions support vector similarity search and
full-text match.

## What it does

The jlsm-sql module takes a SQL string, parses it into a typed AST, and
translates the AST into fluent API calls against JlsmTable. No joins,
subqueries, CTEs, window functions, or write operations. The SQL surface
is an alternative to the fluent API, not a replacement — both resolve to
the same predicate tree and index execution paths.

## Features

**Core:**
- **sql-query-support** — SQL parser, AST, translation layer, custom syntax for vector/full-text

## Key behaviors

- Read-only: SELECT queries only — no INSERT, UPDATE, DELETE, DDL
- Separate module (jlsm-sql) — optional dependency, keeps jlsm-table lean
- Architecture: SQL string → SQL AST → fluent API / predicate tree
- Supports: SELECT (projection, aliasing), FROM (single table), WHERE (comparison, BETWEEN, IN, LIKE, IS NULL, boolean combinators), ORDER BY, LIMIT/OFFSET
- Vector similarity via custom syntax: `NEAREST(field, vector, k)`
- Full-text via custom syntax: `MATCHES(field, query)`
- No joins, subqueries, CTEs, aggregations, or window functions

## Related

- **Specs:** F07 (SQL query support)
- **Decisions:** none specific to SQL (translates to existing predicate infrastructure)
- **KB:** algorithms/sql-extensions (custom syntax design), systems/query-processing (join algorithms, snapshot consistency)
- **Depends on:** query/secondary-indices (query predicates resolve against index structures)
- **Enables:** distribution/database-engine (engine-level query routing uses SQL surface)
- **Deferred work:** distributed-join-execution, aggregation-query-merge, limit-offset-pushdown
