---
title: "striped-block-cache"
type: feature-footprint
domains: ["concurrency", "data-integrity", "memory-safety"]
constructs: ["StripedBlockCache", "LruBlockCache"]
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/cache/*"
research_status: stable
last_researched: "2026-03-26"
---

# striped-block-cache

## What it built
A `StripedBlockCache` implementation of `BlockCache` that partitions the key space across N independent `LruBlockCache` stripes, each with its own lock, to eliminate single-lock contention under concurrent access. Added `getMultiThreaded()` and `getSingleThreaded()` factory methods to `LruBlockCache`.

## Key constructs
- `StripedBlockCache` — sharded BlockCache using splitmix64 hash for stripe selection
- `LruBlockCache` — single-lock LRU cache backed by LinkedHashMap (existing, extended with factory methods)

## Adversarial findings
- capacity-truncation-on-sharding: capacity() reported configured value instead of effective `(perStripeCapacity * stripeCount)` → [KB entry](capacity-truncation-on-sharding.md)
- int-backed-long-api: LruBlockCache accepted long capacity > Integer.MAX_VALUE but LinkedHashMap.size() returns int → [KB entry](int-backed-long-api.md)
- getorload-non-atomic: default getOrLoad was non-atomic, allowing duplicate loader calls under concurrency → [KB entry](getorload-non-atomic.md)
- deferred-builder-validation: capacity() setters accepted invalid values silently while stripeCount() validated eagerly → [KB entry](deferred-builder-validation.md)
- missing-close-guard: operations silently succeeded after close() → [KB entry](missing-close-guard.md)

## Cross-references
- ADR: .decisions/stripe-hash-function/adr.md
- ADR: .decisions/cross-stripe-eviction/adr.md
- KB: .kb/data-structures/caching/concurrent-lru-caches.md
