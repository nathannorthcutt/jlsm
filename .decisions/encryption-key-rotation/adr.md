---
problem: "encryption-key-rotation"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Encryption Key Rotation — Deferred

## Problem
Key rotation for encrypted fields — requires rebuilding the table with new keys.

## Why Deferred
Scoped out during `field-encryption-api-design` decision. Requires rebuilding the table with new keys (documented limitation).

## Resume When
When `field-encryption-api-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/field-encryption-api-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "encryption-key-rotation"` when ready to evaluate.
