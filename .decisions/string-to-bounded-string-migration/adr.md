---
problem: "string-to-bounded-string-migration"
date: "2026-03-30"
version: 1
status: "deferred"
---

# String to BoundedString Migration — Deferred

## Problem
Schema migration from Primitive.STRING to BoundedString for existing data.

## Why Deferred
Scoped out during `bounded-string-field-type` decision. Requires data rewrite.

## Resume When
When `bounded-string-field-type` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/bounded-string-field-type/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "string-to-bounded-string-migration"` when ready to evaluate.
