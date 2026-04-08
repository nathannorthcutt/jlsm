---
problem: "encrypted-prefix-wildcard-queries"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Encrypted Prefix/Wildcard Queries — Deferred

## Problem
Prefix/wildcard queries on encrypted text — would need character-level encryption scheme.

## Why Deferred
Scoped out during `encrypted-index-strategy` decision. Would need character-level encryption.

## Resume When
When `encrypted-index-strategy` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/encrypted-index-strategy/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "encrypted-prefix-wildcard-queries"` when ready to evaluate.
