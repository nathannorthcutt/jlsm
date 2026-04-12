---
problem: "table-migration-protocol"
date: "2026-03-30"
version: 1
status: "deferred"
depends_on: ["catalog-replication", "partition-replication-protocol"]
---

# Table Migration Protocol — Deferred

## Problem
Table migration protocol between cluster nodes for rebalancing and scaling.

## Why Deferred
Scoped out during `table-catalog-persistence` decision. Future cluster work.

## Resume When
When `table-catalog-persistence` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/table-catalog-persistence/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "table-migration-protocol"` when ready to evaluate.
