---
problem: "transport-traffic-priority"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Transport Traffic Priority — Deferred

## Problem
Traffic priority/isolation between membership and query traffic at the handler level.

## Why Deferred
Scoped out during `transport-abstraction-design` decision. Handler-level concern, not transport abstraction concern.

## Resume When
When `transport-abstraction-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/transport-abstraction-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "transport-traffic-priority"` when ready to evaluate.
