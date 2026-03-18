---
problem: "cross-stripe-eviction"
date: "2026-03-17"
version: 1
status: "confirmed"
supersedes: null
---

# ADR — Cross-Stripe Eviction

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Sequential loop | Chosen approach | Domain knowledge (no KB entry) |
| Parallel stream | Rejected candidate | Domain knowledge (no KB entry) |
| All-locks-then-evict | Rejected candidate | Domain knowledge (no KB entry) |

---

## Problem
Choose how `evict(sstableId)` traverses all stripes in `StripedBlockCache` to remove cached blocks belonging to a compacted SSTable. Each stripe is an independent `LruBlockCache` with its own lock.

## Constraints That Drove This Decision
- **No thread pool available**: Library code cannot assume an executor exists — eliminates parallel/async approaches
- **Must not block other stripes**: Holding all locks simultaneously defeats the purpose of striping
- **All entries must be removed**: No stale blocks may survive after `evict()` returns

## Decision
**Chosen approach: Sequential loop**

Iterate over all stripes in order, calling `stripe.evict(sstableId)` on each. Each stripe acquires and releases its own lock independently, so `get()`/`put()` on other stripes are never blocked during eviction.

```java
@Override
public void evict(long sstableId) {
    for (LruBlockCache stripe : stripes) {
        stripe.evict(sstableId);
    }
}
```

## Rationale

### Why sequential loop
- **Simplicity**: Two lines of code; delegates entirely to existing `LruBlockCache.evict()` — no new locking logic, no encapsulation breaks
- **Non-blocking**: Only one stripe lock held at a time; concurrent `get()`/`put()` on other stripes proceed unimpeded
- **Correctness**: Visits every stripe — guaranteed to remove all entries for the given `sstableId`
- **No infrastructure**: No thread pool, no executor, no parallel stream — plain `for` loop

### Why not parallel stream
- **Common pool dependency**: `Arrays.stream().parallel()` uses `ForkJoinPool.commonPool()` — a library must not contend on the application's shared pool. No control over pool sizing or saturation.

### Why not all-locks-then-evict
- **Blocks all stripes**: Acquiring all stripe locks simultaneously blocks every `get()`/`put()` across the entire cache during eviction — defeats the purpose of striping. Also requires breaking `LruBlockCache` encapsulation to expose `lock()`/`unlock()`.

## Implementation Guidance
- Call `stripe.evict(sstableId)` directly — the existing method handles its own locking
- No need to expose internal lock/unlock methods on `LruBlockCache`
- Momentary inconsistency during the sweep is acceptable (stripe 0 evicted while stripe 1 is not yet)

## What This Decision Does NOT Solve
- Does not provide atomic eviction across all stripes — a concurrent `get()` might briefly see an entry in an un-evicted stripe
- Does not parallelize for very large caches — not needed at current scale (2–16 stripes)

## Conditions for Revision
This ADR should be re-evaluated if:
- Stripe counts grow beyond 64 where sequential eviction time becomes noticeable
- A caller-provided executor becomes available in the API, enabling optional parallelism
- Atomic cross-stripe eviction becomes a correctness requirement (currently it is not)

---
*Confirmed by: user deliberation | Date: 2026-03-17*
*Full scoring: [evaluation.md](evaluation.md)*
