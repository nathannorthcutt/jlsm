---
problem: "continuous-rediscovery"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Continuous Re-Discovery — Deferred

## Problem
Continuous re-discovery of cluster members — currently handled by the Rapid membership protocol, not the discovery SPI.

## Why Deferred
Scoped out during `discovery-spi-design` decision. Handled by Rapid membership protocol.

## Resume When
When `discovery-spi-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/discovery-spi-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "continuous-rediscovery"` when ready to evaluate.
