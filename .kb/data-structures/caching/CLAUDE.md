# caching — Category Index
*Topic: data-structures*

Cache data structures and eviction policies, with focus on concurrent
implementations suitable for high-throughput storage engines.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [concurrent-lru-caches.md](concurrent-lru-caches.md) | Concurrent LRU Cache Implementations | mature | O(1) get/put, 4 strategies | Choosing a concurrent cache strategy |
| [int-backed-long-api.md](int-backed-long-api.md) | Int-backed long API capacity overflow | active | adversarial-finding | Validating long capacity against int-backed stores |
| [capacity-truncation-on-sharding.md](capacity-truncation-on-sharding.md) | Capacity truncation on sharding | active | adversarial-finding | Sharded capacity reporting accuracy |
| [getorload-non-atomic.md](getorload-non-atomic.md) | Non-atomic getOrLoad default method | active | adversarial-finding | Concurrent getOrLoad atomicity |
| [deferred-builder-validation.md](deferred-builder-validation.md) | Deferred builder validation | active | adversarial-finding | Builder setter validation consistency |
| [missing-close-guard.md](missing-close-guard.md) | Missing close guard on cache operations | active | adversarial-finding | Use-after-close detection |
| [striped-block-cache.md](striped-block-cache.md) | striped-block-cache feature footprint | stable | feature-footprint | Audit context for StripedBlockCache |

## Comparison Summary
<!-- Write once 2+ subjects exist -->

## Recommended Reading Order
1. Start: [concurrent-lru-caches.md](concurrent-lru-caches.md) — covers all four strategies from simple to complex

## Research Gaps
- Clock/CLOCK-Pro eviction as alternative to LRU
- Adaptive replacement cache (ARC) algorithm
- Off-heap cache implementations (e.g., OHC, Chronicle Map)
