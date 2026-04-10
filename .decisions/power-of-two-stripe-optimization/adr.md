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
**Enforce power-of-2 stripe counts and use bitmask exclusively.**

### Changes
1. Validate in `StripedBlockCache` builder that stripe count is a power of 2:
   `Integer.bitCount(stripeCount) == 1`
2. Replace `% stripeCount` with `& (stripeCount - 1)` in `stripeIndex()`
3. Store `stripeCount - 1` as a `stripeMask` field to avoid recomputing

### Validation
```java
if (stripeCount <= 0 || Integer.bitCount(stripeCount) != 1) {
    throw new IllegalArgumentException(
        "stripeCount must be a positive power of 2, got: " + stripeCount);
}
```

## Rationale
- The current valid range (2-16) is entirely powers of 2 — this constraint
  matches existing usage with zero user impact.
- Bitmask is strictly faster than division and has zero risk of modulo bias.
- Eliminates a conditional branch that would be needed for "branch on power-of-2
  check" alternative.
- Powers of 2 are the natural stripe count choice in all real-world cache designs.

## Key Assumptions
- No use case requires non-power-of-2 stripe counts (e.g., 3, 5, 6).

## Conditions for Revision
- If a use case requires non-power-of-2 stripe counts, fall back to the branch
  approach (check at construction, store a boolean flag).

## Implementation Guidance
1. Add power-of-2 validation to `StripedBlockCache` builder
2. Store `int stripeMask = stripeCount - 1` as a field
3. Replace `% stripeCount` with `& stripeMask` in `stripeIndex()`
4. Also apply to `RendezvousOwnership` if it uses the same pattern
5. Update tests to verify rejection of non-power-of-2 counts

## What This Decision Does NOT Solve
- Hash quality or uniformity — addressed (and closed) by `hash-distribution-uniformity`
