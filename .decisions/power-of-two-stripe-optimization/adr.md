---
problem: "power-of-two-stripe-optimization"
date: "2026-04-10"
version: 1
status: "accepted"
depends_on: []
---

# Power-of-Two Stripe Optimization

## Problem
`stripeIndex()` uses `(h & 0x7FFFFFFFFFFFFFFFL) % stripeCount` for all stripe
counts. For power-of-2 counts, `h & (stripeCount - 1)` is a single bitwise AND
— no integer division. The current valid range (2-16) is already all powers of 2.

## Decision
**Use power-of-2 stripe counts with bitmask exclusively. Non-power-of-2
inputs are rounded up to the next power of 2.**

### Changes
1. In `StripedBlockCache` constructor, round non-power-of-2 stripe counts up
   to the next power of 2 via `Integer.highestOneBit()` shift
2. Replace `% stripeCount` with `& stripeMask` using a pre-computed
   `stripeMask = stripeCount - 1` field
3. Add `stripeFor()` fast-path instance method that skips validation
4. Static `stripeIndex()` retains power-of-2 validation for direct callers

## Rationale
- Rounding up is friendlier than rejecting — callers don't need to know about
  the optimization to benefit from it.
- Bitmask is strictly faster than division and has zero risk of modulo bias.
- Pre-computed `stripeMask` field eliminates the power-of-2 check on every
  hot-path call.
- Powers of 2 are the natural stripe count choice in all real-world cache designs.

## Key Assumptions
- Rounding up a small number of extra stripes is acceptable (e.g., 5 → 8 adds 3
  unused stripes with minimal memory overhead).

## Conditions for Revision
- None anticipated — round-up is strictly better than rejection or modulo.

## Implementation Guidance
1. Round up non-power-of-2 stripe counts in `StripedBlockCache` constructor
2. Store `int stripeMask = stripeCount - 1` as a field
3. Add `stripeFor()` fast-path using `& stripeMask`
4. Update `stripeIndex()` static method to use bitmask with validation
5. Update tests to verify round-up behavior

## What This Decision Does NOT Solve
- Hash quality or uniformity — addressed (and closed) by `hash-distribution-uniformity`
