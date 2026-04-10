---
problem: "catalog-replication"
date: "2026-03-30"
version: 1
status: "deferred"
depends_on: ["connection-pooling", "message-serialization-format"]
---

# Catalog Replication — Deferred

## Problem
Catalog replication to other cluster nodes for distributed catalog consistency.

## Why Deferred
Scoped out during `table-catalog-persistence` decision. Future cluster work.

## Resume When
When `table-catalog-persistence` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/table-catalog-persistence/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "catalog-replication"` when ready to evaluate.
