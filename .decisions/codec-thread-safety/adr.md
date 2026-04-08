---
problem: "codec-thread-safety"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Codec Thread Safety — Deferred

## Problem
Thread safety of codec instances — currently left to each implementation with no contract.

## Why Deferred
Scoped out during `compression-codec-api-design` decision. Left to each implementation.

## Resume When
When `compression-codec-api-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/compression-codec-api-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "codec-thread-safety"` when ready to evaluate.
