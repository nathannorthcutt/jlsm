---
problem: "client-side-encryption-sdk"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Client-Side Encryption SDK — Deferred

## Problem
A client-side encryption SDK that wraps the encryption primitives into a higher-level API for external consumers.

## Why Deferred
Scoped out during `pre-encrypted-document-signaling` decision. Clients use the encryption primitives directly.

## Resume When
When `pre-encrypted-document-signaling` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/pre-encrypted-document-signaling/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "client-side-encryption-sdk"` when ready to evaluate.
