---
problem: "codec-dictionary-support"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Codec Dictionary Support — Deferred

## Problem
Codec-specific configuration beyond the constructor, such as dictionary support for zstd.

## Why Deferred
Scoped out during `compression-codec-api-design` decision. Beyond current API surface.

## Resume When
When `compression-codec-api-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/compression-codec-api-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "codec-dictionary-support"` when ready to evaluate.
