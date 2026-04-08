---
problem: "atomic-multi-table-ddl"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Atomic Multi-Table DDL — Deferred

## Problem
Atomic multi-table DDL operations (create table A and drop table B atomically).

## Why Deferred
Scoped out during `table-catalog-persistence` decision. Not needed for initial single-table DDL operations.

## Resume When
When `table-catalog-persistence` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/table-catalog-persistence/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "atomic-multi-table-ddl"` when ready to evaluate.
