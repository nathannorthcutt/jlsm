---
problem: "index-access-pattern-leakage"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Index Access Pattern Leakage — Deferred

## Problem
Access-pattern leakage in T1/T2 tiers — T3 provides stronger protection but T1/T2 leak query patterns.

## Why Deferred
Scoped out during `encrypted-index-strategy` decision. T3 provides stronger protection; T1/T2 leakage is a known trade-off.

## Resume When
When `encrypted-index-strategy` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/encrypted-index-strategy/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "index-access-pattern-leakage"` when ready to evaluate.
