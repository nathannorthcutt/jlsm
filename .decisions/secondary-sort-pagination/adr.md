---
problem: "secondary-sort-pagination"
date: "2026-04-14"
version: 1
status: "deferred"
---

# Secondary-Sort Pagination — Deferred

## Problem
Pagination for queries with ORDER BY on a non-key column (e.g., ORDER BY score DESC). Current continuation tokens encode primary key position only — secondary-sort resume requires richer token encoding.

## Why Deferred
Scoped out during `limit-offset-pushdown` decision. jlsm's current query model is key-ordered; secondary-sort pagination is not supported at the distributed level.

## Resume When
When jlsm-sql adds ORDER BY on non-key columns and distributed secondary-sort pagination becomes a requirement.

## What Is Known So Far
Identified during architecture evaluation of `limit-offset-pushdown`. See `.decisions/limit-offset-pushdown/adr.md`. The continuation token format would need to encode position in a secondary index order rather than primary key order.

## Next Step
Run `/architect "secondary-sort-pagination"` when ready to evaluate.
