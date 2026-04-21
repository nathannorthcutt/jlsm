# caching — Category Index
*Topic: data-structures*
*Tags: cache, lru, eviction, tinylfu, w-tinylfu, clock, clock-pro, weigher, byte-budget, variable-size, admission, pinning, refcount, stripe, shard, block-cache*

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
| [concurrent-cache-eviction-strategies.md](concurrent-cache-eviction-strategies.md) | Concurrent Cache Eviction Strategies | active | O(1) amortized, TinyLFU admission | Cross-stripe eviction, parallel eviction, admission policies |
| [byte-budget-cache-variable-size-entries.md](byte-budget-cache-variable-size-entries.md) | Byte-budget cache for variable-size entries | active | byte-weighted admission + pin refcounts | Block caches with mixed block sizes; byte ceiling vs entry count |

## Comparison Summary

The three main subject files form a layered view of cache design:
`concurrent-lru-caches.md` covers concurrency strategies (single-lock, striped,
lock-free, buffered) using entry-count capacity; `concurrent-cache-eviction-strategies.md`
covers eviction-policy choice (LRU vs CLOCK vs TinyLFU) and cross-stripe
coordination; `byte-budget-cache-variable-size-entries.md` covers the
byte-weighted variant — admission control, single-entry caps, pin/refcount
hand-off for in-flight readers. Choose by the dimension you need to constrain:
concurrency model, eviction policy, or capacity unit.

## Recommended Reading Order
1. Start: [concurrent-lru-caches.md](concurrent-lru-caches.md) — covers all four concurrency strategies from simple to complex
2. Then: [concurrent-cache-eviction-strategies.md](concurrent-cache-eviction-strategies.md) — eviction policies (LRU/CLOCK/TinyLFU) and cross-stripe coordination
3. Then: [byte-budget-cache-variable-size-entries.md](byte-budget-cache-variable-size-entries.md) — byte-weighted variant for variable-size block caches

## Research Gaps
- Clock/CLOCK-Pro eviction as alternative to LRU
- Adaptive replacement cache (ARC) algorithm
- Off-heap cache implementations (e.g., OHC, Chronicle Map)
- Weight-aware TinyLFU variants (cost-per-hit admission)
