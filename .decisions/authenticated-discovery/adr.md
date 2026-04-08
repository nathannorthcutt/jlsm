---
problem: "authenticated-discovery"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Authenticated Discovery — Deferred

## Problem
Authenticated discovery (TLS, tokens) — implementation concern, not SPI concern.

## Why Deferred
Scoped out during `discovery-spi-design` decision. Implementation concern, not SPI concern.

## Resume When
When `discovery-spi-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/discovery-spi-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "authenticated-discovery"` when ready to evaluate.
