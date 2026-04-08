---
title: "Int-backed long API capacity overflow"
type: adversarial-finding
domain: "memory-safety"
severity: "tendency"
applies_to:
  - "modules/jlsm-*/src/main/**"
research_status: active
last_researched: "2026-03-25"
---

# Int-backed long API capacity overflow

## What happens
When a public API declares a `long` parameter for size or capacity but the backing data structure uses `int` internally (e.g., `LinkedHashMap.size()`, `HashMap` array indexing), values exceeding `Integer.MAX_VALUE` silently disable bounding logic. Eviction policies that compare `size() > capacity` never trigger because the `int` size can never reach the `long` capacity, causing unbounded growth and eventual OOM.

## Why implementations default to this
Using `long` for capacity is a forward-looking design choice that avoids API breaks if the backing store is later replaced. Developers don't always trace through to the backing store's size return type to realize the mismatch. The issue is invisible in tests that use small capacity values.

## Test guidance
- For any class where a `long` capacity parameter controls eviction/bounding: pass `Integer.MAX_VALUE + 1L` and verify `IllegalArgumentException`
- Also verify that `Integer.MAX_VALUE` itself is accepted (boundary value)
- Check both the direct class and any wrapper/delegating class (e.g., striped/sharded caches that divide capacity across shards)

## Found in
- striped-block-cache (audit round 1, 2026-03-25): `LruBlockCache.Builder` accepted `long` capacity but `LinkedHashMap.size()` returns `int`; eviction never triggered for capacity > `Integer.MAX_VALUE`
