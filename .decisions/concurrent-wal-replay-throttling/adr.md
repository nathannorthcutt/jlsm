---
problem: "concurrent-wal-replay-throttling"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Concurrent WAL Replay Throttling — Deferred

## Problem
Throttling concurrent WAL replays to avoid overloading the new owner during rebalancing.

## Why Deferred
Scoped out during `rebalancing-grace-period-strategy` decision. Not needed for initial implementation.

## Resume When
When `rebalancing-grace-period-strategy` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/rebalancing-grace-period-strategy/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "concurrent-wal-replay-throttling"` when ready to evaluate.
