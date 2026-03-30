---
problem: "message-serialization-format"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Message Serialization Format — Deferred

## Problem
Message serialization format — caller's responsibility; in-JVM mode skips it entirely.

## Why Deferred
Scoped out during `transport-abstraction-design` decision. Caller's responsibility; in-JVM skips it.

## Resume When
When `transport-abstraction-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/transport-abstraction-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "message-serialization-format"` when ready to evaluate.
