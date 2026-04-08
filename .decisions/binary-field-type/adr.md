---
problem: "binary-field-type"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Binary Field Type — Deferred

## Problem
Binary field type for raw byte data — separate feature, explicitly deferred.

## Why Deferred
Scoped out during `bounded-string-field-type` decision. Separate feature, explicitly deferred.

## Resume When
When `bounded-string-field-type` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/bounded-string-field-type/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "binary-field-type"` when ready to evaluate.
