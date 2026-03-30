---
problem: "unencrypted-to-encrypted-migration"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Unencrypted-to-Encrypted Schema Migration — Deferred

## Problem
Schema migration from unencrypted to encrypted fields — requires complete data rewrite.

## Why Deferred
Scoped out during `field-encryption-api-design` decision. Requires data rewrite.

## Resume When
When `field-encryption-api-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/field-encryption-api-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "unencrypted-to-encrypted-migration"` when ready to evaluate.
