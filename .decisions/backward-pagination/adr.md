---
problem: "backward-pagination"
date: "2026-04-14"
version: 1
status: "deferred"
---

# Backward Pagination — Deferred

## Problem
Continuation tokens are forward-only. Bidirectional pagination requires dual-direction keyset with descending index support or offset-based approaches.

## Why Deferred
Scoped out during `limit-offset-pushdown` decision. Current use cases are forward-only scan pagination.

## Resume When
When bidirectional pagination becomes a requirement.

## What Is Known So Far
Identified during architecture evaluation of `limit-offset-pushdown`. See `.decisions/limit-offset-pushdown/adr.md`. Options include dual-direction token encoding or maintaining a descending index alongside the ascending one.

## Next Step
Run `/architect "backward-pagination"` when ready to evaluate.
