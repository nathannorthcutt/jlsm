---
problem: "remote-serialization-protocol"
date: "2026-03-30"
version: 1
status: "deferred"
depends_on: ["message-serialization-format", "connection-pooling"]
---

# Remote Serialization Protocol — Deferred

## Problem
Network serialization protocol for remote engine mode.

## Why Deferred
Scoped out during `engine-api-surface-design` decision. Separate concern from engine API surface.

## Resume When
When `engine-api-surface-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/engine-api-surface-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "remote-serialization-protocol"` when ready to evaluate.
