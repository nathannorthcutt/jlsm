---
problem: "similarity-function-placement"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Similarity Function Placement — Deferred

## Problem
SimilarityFunction remains on IndexDefinition as an index-level concern — may need revisiting if it proves awkward.

## Why Deferred
Scoped out during `index-definition-api-simplification` decision. It is an index-level concern, distinct from field-level type.

## Resume When
When `index-definition-api-simplification` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/index-definition-api-simplification/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "similarity-function-placement"` when ready to evaluate.
