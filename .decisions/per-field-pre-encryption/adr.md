---
problem: "per-field-pre-encryption"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Per-Field Pre-Encryption — Deferred

## Problem
Per-field pre-encryption (partial documents) — current design is all-or-nothing only.

## Why Deferred
Scoped out during `pre-encrypted-document-signaling` decision. Explicitly out of scope; all-or-nothing only.

## Resume When
When `pre-encrypted-document-signaling` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/pre-encrypted-document-signaling/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "per-field-pre-encryption"` when ready to evaluate.
