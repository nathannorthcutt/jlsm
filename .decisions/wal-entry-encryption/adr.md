---
problem: "wal-entry-encryption"
date: "2026-03-30"
version: 1
status: "deferred"
---

# WAL Entry Encryption — Deferred

## Problem
Encryption of WAL entries as a separate durability concern layered on top of field encryption.

## Why Deferred
Scoped out during `field-encryption-api-design` decision. Separate concern, can layer on later.

## Resume When
When `field-encryption-api-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/field-encryption-api-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "wal-entry-encryption"` when ready to evaluate.
