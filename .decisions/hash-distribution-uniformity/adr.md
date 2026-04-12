---
problem: "hash-distribution-uniformity"
date: "2026-04-10"
version: 1
status: "closed"
---

# Hash Distribution Uniformity — Closed (Won't Pursue)

## Problem
Concern that splitmix64 might produce non-uniform stripe distribution at extreme scale.

## Decision
**Will not pursue.** Explicitly ruled out — should not be raised again.

## Reason
Splitmix64 (Stafford variant 13) is one of the best-studied integer hash functions:
- Passes all SMHasher uniformity tests
- Full avalanche — every input bit affects every output bit
- Same finalizer used by `java.util.SplittableRandom` in the JDK

The only source of non-uniformity is modulo bias from `% stripeCount`. For stripe
counts ≤ 64 (the practical range for block caches), the bias is ~2^-58 — smaller
than hardware error rates. If extreme uniformity were ever needed, rejection
sampling on the modulo would eliminate bias entirely.

## Context
Raised during `stripe-hash-function` evaluation as a theoretical concern at
extreme scale. Analysis confirms it is a non-issue for any practical stripe count.

## Conditions for Reopening
None — treat as permanently closed.
