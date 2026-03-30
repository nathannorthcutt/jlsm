---
problem: "atomic-cross-stripe-eviction"
date: "2026-03-30"
version: 1
status: "deferred"
---

# Atomic Cross-Stripe Eviction — Deferred

## Problem
Atomic eviction across all stripes — currently a concurrent get() might briefly see an entry in an un-evicted stripe.

## Why Deferred
Scoped out during `cross-stripe-eviction` decision. Brief inconsistency window is acceptable at current scale.

## Resume When
When `cross-stripe-eviction` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/cross-stripe-eviction/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "atomic-cross-stripe-eviction"` when ready to evaluate.
