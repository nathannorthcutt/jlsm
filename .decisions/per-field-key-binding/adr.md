---
problem: "per-field-key-binding"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Per-Field Key Binding — Deferred

## Problem
Per-field key binding — initial implementation uses one key per table; per-field keys add key routing to EncryptionSpec.

## Why Deferred
Scoped out during `field-encryption-api-design` decision. Initial implementation uses one key per table.

## Resume When
When `field-encryption-api-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/field-encryption-api-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "per-field-key-binding"` when ready to evaluate.
