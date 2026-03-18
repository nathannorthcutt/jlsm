---
problem: "Stripe hash function for StripedBlockCache"
slug: "stripe-hash-function"
captured: "2026-03-17"
status: "draft"
---

# Constraint Profile — stripe-hash-function

## Problem Statement
Choose a mixing function to map `(sstableId, blockOffset)` pairs to stripe indices in the new `StripedBlockCache`. The function determines which of N independent `LruBlockCache` shards handles a given cache operation.

## Constraints

### Scale
Cache capacities of 1K–10K entries, stripe counts of 2–16. The hash function is called on every `get()` and `put()` — tens of millions of operations per second under concurrent load.

### Resources
Pure Java 25. No external dependencies. Zero allocation per call (no object creation, no boxing). The function must operate entirely on primitive `long` inputs.

### Complexity Budget
Minimal. This is an internal implementation detail, not a public API. The function should be understandable in a single glance — no clever bit tricks that require comments to explain.

### Accuracy / Correctness
Even distribution across stripes to avoid hot-stripe imbalance. Perfect uniformity is not required — the goal is to avoid pathological clustering (e.g., sequential `blockOffset` values all landing in the same stripe). Small distribution skew is acceptable.

### Operational Requirements
Must be faster than the lock acquisition it replaces. Sub-nanosecond overhead target — the hash must not become a new bottleneck. No branching or memory access beyond the two input longs.

### Fit
Java 25. Inputs are two `long` values (`sstableId`, `blockOffset`). Output is `int` index in `[0, stripeCount)`. Must work with both power-of-2 and non-power-of-2 stripe counts, though power-of-2 is the expected common case.

## Key Constraints (most narrowing)
1. **Zero allocation, sub-nanosecond** — eliminates any approach that allocates (e.g., creating a byte[] to feed to a hash function)
2. **Two long inputs → int index** — eliminates string-based or byte-oriented hash functions
3. **Even distribution for sequential blockOffsets** — eliminates naive modulo (`blockOffset % N`) since sequential offsets would stripe sequentially

## Unknown / Not Specified
None — full profile captured.
