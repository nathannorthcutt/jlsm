---
problem: "discovery-environment-config"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Discovery Environment Configuration — Deferred

## Problem
Environment-specific discovery configuration — each implementation handles its own config.

## Why Deferred
Scoped out during `discovery-spi-design` decision. Each implementation handles its own config.

## Resume When
When `discovery-spi-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/discovery-spi-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "discovery-environment-config"` when ready to evaluate.
