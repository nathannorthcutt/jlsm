---
problem: "in-flight-write-protection"
date: "2026-03-30"
version: 1
status: "deferred"
---

# In-Flight Write Protection During Takeover — Deferred

## Problem
In-flight writes during partition takeover — writes to the old owner's memtable that haven't been WAL'd are lost. Existing WAL durability guarantees apply.

## Why Deferred
Scoped out during `rebalancing-grace-period-strategy` decision. Existing WAL durability guarantees apply; this is a replication concern.

## Resume When
When `rebalancing-grace-period-strategy` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/rebalancing-grace-period-strategy/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "in-flight-write-protection"` when ready to evaluate.
