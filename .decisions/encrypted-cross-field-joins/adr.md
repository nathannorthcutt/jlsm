---
problem: "encrypted-cross-field-joins"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Encrypted Cross-Field Joins — Deferred

## Problem
Cross-field joins on encrypted values from different tables.

## Why Deferred
Scoped out during `encrypted-index-strategy` decision. Separate concern from index strategy.

## Resume When
When `encrypted-index-strategy` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/encrypted-index-strategy/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "encrypted-cross-field-joins"` when ready to evaluate.
