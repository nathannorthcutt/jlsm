---
problem: "encrypted-fuzzy-matching"
date: "2026-03-30"
version: 1
status: "deferred"
depends_on: ["encryption-key-rotation"]
---

# Encrypted Fuzzy Matching — Deferred

## Problem
Fuzzy matching on encrypted text — edit distance doesn't work on ciphertexts.

## Why Deferred
Scoped out during `encrypted-index-strategy` decision. Edit distance doesn't work on ciphertexts.

## Resume When
When `encrypted-index-strategy` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/encrypted-index-strategy/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "encrypted-fuzzy-matching"` when ready to evaluate.
