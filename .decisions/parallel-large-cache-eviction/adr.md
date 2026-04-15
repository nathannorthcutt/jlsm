---
problem: "parallel-large-cache-eviction"
date: "2026-04-13"
version: 1
status: "closed"
---

# Parallel Large Cache Eviction — Closed (Non-Issue at Current Scale)

## Problem
Parallelizing eviction for very large caches beyond the current 2-16 stripe range.

## Decision
**Will not pursue.** Sequential eviction of 16 stripes is negligible latency. The problem does not exist at current scale.

## Reason
The sequential eviction loop does O(stripes) lock-acquire-evict operations. At 16 stripes, this is 16 independent lock acquisitions — microsecond-scale total latency. Even at 64 stripes, sequential eviction would complete in single-digit milliseconds.

KB research at `.kb/data-structures/caching/concurrent-cache-eviction-strategies.md` covers cooperative batch eviction and stripe-affine eviction threads for when scale demands it. This research is banked for the future but not needed today.

## Context
Deferred during `cross-stripe-eviction` (2026-03-17). The sequential loop handles current stripe counts (2-16) with negligible overhead.

## Conditions for Reopening
- Stripe count exceeds 64 and eviction latency becomes measurable
- Profiling shows eviction contention affecting query latency at the p99 level
