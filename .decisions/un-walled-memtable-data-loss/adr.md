---
problem: "un-walled-memtable-data-loss"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Un-WAL'd Memtable Data Loss Prevention — Deferred

## Problem
The grace period does not prevent data loss from un-WAL'd memtable entries — that is a replication concern, not a rebalancing concern.

## Why Deferred
Scoped out during `rebalancing-grace-period-strategy` decision. This is a replication concern, not a rebalancing concern.

## Resume When
When `rebalancing-grace-period-strategy` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/rebalancing-grace-period-strategy/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "un-walled-memtable-data-loss"` when ready to evaluate.
