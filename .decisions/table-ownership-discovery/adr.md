---
problem: "table-ownership-discovery"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Table Ownership Discovery — Deferred

## Problem
Discovery of which tables a node owns — handled by HRW ownership assignment, not the discovery SPI.

## Why Deferred
Scoped out during `discovery-spi-design` decision. Handled by HRW ownership assignment.

## Resume When
When `discovery-spi-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/discovery-spi-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "table-ownership-discovery"` when ready to evaluate.
