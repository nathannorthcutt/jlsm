# caching — Category Index
*Topic: data-structures*

Cache data structures and eviction policies, with focus on concurrent
implementations suitable for high-throughput storage engines.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [concurrent-lru-caches.md](concurrent-lru-caches.md) | Concurrent LRU Cache Implementations | mature | O(1) get/put, 4 strategies | Choosing a concurrent cache strategy |

## Comparison Summary
<!-- Write once 2+ subjects exist -->

## Recommended Reading Order
1. Start: [concurrent-lru-caches.md](concurrent-lru-caches.md) — covers all four strategies from simple to complex

## Research Gaps
- Clock/CLOCK-Pro eviction as alternative to LRU
- Adaptive replacement cache (ARC) algorithm
- Off-heap cache implementations (e.g., OHC, Chronicle Map)
