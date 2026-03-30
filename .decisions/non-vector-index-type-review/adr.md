---
problem: "non-vector-index-type-review"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Non-Vector Index Type Review — Deferred

## Problem
Other index types (EQUALITY, RANGE, UNIQUE, FULL_TEXT) are unaffected by simplification — may need their own review.

## Why Deferred
Scoped out during `index-definition-api-simplification` decision. Unaffected by current simplification.

## Resume When
When `index-definition-api-simplification` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/index-definition-api-simplification/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "non-vector-index-type-review"` when ready to evaluate.
