---
problem: "block-cache-block-size-interaction"
date: "2026-04-11"
version: 1
status: "deferred"
---

# ADR: Block Cache Sizing Relative to Block Size

**Status:** deferred
**Source:** out-of-scope from `backend-optimal-block-size`

## Problem

Block cache capacity is configured independently of block size. When block size
changes (e.g., 4 KiB local vs 256 KiB S3), the effective number of cached
blocks changes dramatically, which may cause unexpected memory usage or poor
cache hit rates.

## Why Deferred

Scoped out during `backend-optimal-block-size` decision. Block cache sizing is
a separate concern — the current `StripedBlockCache` has its own capacity
configuration that works independently.

## Resume When

When block size variability causes observable cache performance issues, or when
adding adaptive cache sizing.

## What Is Known So Far

See `.decisions/backend-optimal-block-size/adr.md` for the block size decision.
See `.decisions/cross-stripe-eviction/adr.md` and
`.decisions/power-of-two-stripe-optimization/adr.md` for the cache architecture.

## Next Step

Run `/architect "block-cache-block-size-interaction"` when ready to evaluate.
