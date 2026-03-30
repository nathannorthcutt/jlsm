---
problem: "codec-negotiation"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Codec Negotiation — Deferred

## Problem
Codec negotiation between writer and reader — reader must be configured with all codecs the writer might use.

## Why Deferred
Scoped out during `compression-codec-api-design` decision. Reader must be configured with all codecs.

## Resume When
When `compression-codec-api-design` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/compression-codec-api-design/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "codec-negotiation"` when ready to evaluate.
