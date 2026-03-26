# Spec Analysis — striped-block-cache (Phase 1)

**Date:** 2026-03-26
**Scope:** LruBlockCache.java, StripedBlockCache.java
**Prior KB findings loaded:** capacity-truncation-on-sharding (RESOLVED), int-backed-long-api (RESOLVED)

## Construct Inventory

| # | Construct | File | Lines |
|---|-----------|------|-------|
| 1 | `LruBlockCache` | LruBlockCache.java | 24–108 |
| 2 | `LruBlockCache.CacheKey` | LruBlockCache.java | 26–27 |
| 3 | `LruBlockCache.Builder` | LruBlockCache.java | 135–157 |
| 4 | `StripedBlockCache` | StripedBlockCache.java | 26–173 |
| 5 | `StripedBlockCache.Builder` | StripedBlockCache.java | 192–254 |

Total: 5 constructs — no clustering needed.

---

## Findings

### F1 — CONTRACT-GAP: `getOrLoad` default not overridden — non-atomic under concurrency

**Construct:** LruBlockCache + StripedBlockCache (inherited from BlockCache default method)
**Lines:** BlockCache.java:61–68 (default method); neither impl overrides it
**Lens B Level:** Level 1 (multi-step mutation not atomic)

**What:** The default `getOrLoad` performs a non-atomic check-then-act:
1. `get()` acquires lock, checks map, releases lock → miss
2. `loader.get()` is called outside any lock
3. `put()` acquires lock, inserts, releases lock

Under concurrent access, two threads can both miss for the same key and both invoke
the loader. The interface Javadoc says the loader is "called exactly once on a cache
miss" — ambiguous about concurrent semantics, but the natural reading implies per-key
atomicity that isn't provided.

For `LruBlockCache`: the single ReentrantLock is released between `get()` and `put()`.
For `StripedBlockCache`: the stripe lock is released between `get()` and `put()`.

**Impact:** Duplicate loader invocations for the same key. If the loader performs disk
I/O (the primary use case — loading an SSTable block from disk), this doubles I/O
under contention.

**Suggested test:** Two threads race `getOrLoad` for the same (sstableId, blockOffset)
with an `AtomicInteger` counting loader invocations. Assert the count is 1. Expected
result: fails — count is 2.

---

### F2 — IMPL-RISK: Builder `capacity()` setters don't validate eagerly

**Construct:** LruBlockCache.Builder + StripedBlockCache.Builder
**Lines:** LruBlockCache.java:141–144, StripedBlockCache.java:223–226
**Lens B Level:** Level 2 (trust boundary — silent acceptance of invalid input)
**project-rule:** coding-guidelines.md ("Validate all inputs at public API boundaries eagerly")

**What:** `LruBlockCache.Builder.capacity(long)` and `StripedBlockCache.Builder.capacity(long)`
accept any value including negatives, zero, or `Long.MAX_VALUE` without validation. Errors
are deferred to `build()`. This is inconsistent with `StripedBlockCache.Builder.stripeCount(int)`
(line 208) which validates eagerly with `if (stripeCount <= 0) throw`.

**Impact:** A caller setting an invalid capacity gets no feedback until `build()`. In a
builder chain like `builder.capacity(-5).stripeCount(4)`, the stripeCount setter validates
immediately but the capacity setter doesn't — inconsistent developer experience.

**Suggested test:** Call `builder.capacity(-1)` and assert `IllegalArgumentException` is
thrown immediately (before `build()`). Expected result: fails — currently no exception
until `build()`.

---

### F3 — CONTRACT-GAP: Silent capacity truncation on non-divisible stripe counts

**Construct:** StripedBlockCache (constructor)
**Lines:** StripedBlockCache.java:39–40
**Lens B Level:** Level 2 (semantically wrong but technically valid values)

**What:** `perStripeCapacity = builder.capacity / stripeCount` uses integer division,
silently discarding the remainder. The effective capacity is
`perStripeCapacity * stripeCount`, which may be significantly less than requested.

Example: `capacity=7, stripeCount=4` → `perStripeCapacity=1`, effective=4. The user
requested 7 but gets 4 (43% reduction). No warning, no exception.

The prior audit (capacity-truncation-on-sharding) fixed `capacity()` to return the
effective value. But the silent truncation at construction was not addressed — the
user still silently loses capacity without any indication.

**Impact:** Callers who compute capacity based on expected workload may unknowingly
under-provision. All existing tests use evenly divisible values (e.g., 100/4) so this
path is untested.

**Suggested test:** Build with `capacity=7, stripeCount=4`. Assert `capacity()` returns
4 (not 7). Insert 5 entries mapping to the same stripe and verify one was evicted.
Also: build with `capacity=5, stripeCount=3` and verify effective capacity is 3.

---

### F4 — IMPL-RISK: `LruBlockCache` capacity boundary at `Integer.MAX_VALUE`

**Construct:** LruBlockCache (constructor + removeEldestEntry callback)
**Lines:** LruBlockCache.java:38–43, LruBlockCache.java:150
**Lens B Level:** Level 1 (silent truncation / overflow)

**What:** The builder accepts `capacity == Integer.MAX_VALUE` (line 150: only rejects
`capacity > Integer.MAX_VALUE`). The `removeEldestEntry` callback compares
`size() > cap` where `size()` is `LinkedHashMap.size()` returning `int`.

At capacity `Integer.MAX_VALUE`, when the (MAX_VALUE+1)th entry is inserted:
- `HashMap` increments `++size`, overflowing `int` to `Integer.MIN_VALUE`
- `removeEldestEntry` compares `(long) Integer.MIN_VALUE > (long) Integer.MAX_VALUE` → false
- Eviction never triggers; the map grows unbounded

**Impact:** Theoretically breaks eviction contract. Practically requires ~160 GB heap
to reach. The fix is simple: change the boundary to `capacity >= Integer.MAX_VALUE` or
document this as a known limitation.

**Suggested test:** Construct `LruBlockCache` with `capacity = Integer.MAX_VALUE`.
Assert `IllegalArgumentException`. Expected result: fails — currently accepted.

---

### F5 — CONTRACT-GAP: No use-after-close detection

**Construct:** LruBlockCache + StripedBlockCache
**Lines:** LruBlockCache.java:101–108, StripedBlockCache.java:157–173
**Lens B Level:** Level 1 (resource lifecycle — use-after-close)

**What:** Neither `LruBlockCache.close()` nor `StripedBlockCache.close()` sets a
"closed" flag. After `close()`:
- `LruBlockCache`: `get()` returns empty, `put()` re-populates the cleared map
- `StripedBlockCache`: delegates to closed stripes, same behavior

The `BlockCache` contract says "behavior of all other methods is undefined" after close.
But silently succeeding (with degraded results) masks consumer bugs — a caller that
forgot to update its cache reference after close gets silent misses or silently
re-populates a zombie cache.

**Impact:** Hard-to-diagnose bugs in consumer code. A closed cache that silently
accepts puts wastes memory without serving its intended caching purpose.

**Suggested test:** Close a cache, then call `put()` followed by `get()`. Assert
`IllegalStateException` is thrown. Expected result: fails — currently silently succeeds.

---

### F6 — IMPL-RISK: `LruBlockCache.evict()` O(n) scan under held lock

**Construct:** LruBlockCache
**Lines:** LruBlockCache.java:76–83
**Lens B Level:** Level 1 (performance — O(n) where bounded is expected)

**What:** `evict(long sstableId)` iterates all entries via
`map.keySet().removeIf(k -> k.sstableId() == sstableId)` while holding the global lock.
For a cache with thousands of entries, this blocks all concurrent `get()` and `put()`
operations for the duration of the scan.

For `StripedBlockCache`, each stripe's eviction is O(n/s) under its own lock, which
improves parallelism but is still O(n) total.

**Impact:** Latency spikes during compaction-triggered evictions on large caches. Under
sustained load, a single eviction call could block reader threads for the full scan
duration.

**Suggested test:** Populate a single-stripe cache with many entries across multiple
sstableIds. Measure that `evict()` correctness holds (functional test). Document the
O(n) characteristic. This is primarily a performance concern, not a correctness bug.

---

### F7 — CONTRACT-GAP: `StripedBlockCache.size()` not linearizable across stripes

**Construct:** StripedBlockCache
**Lines:** StripedBlockCache.java:134–139
**Lens B Level:** Level 1 (multi-step read not atomic)

**What:** `size()` sums stripe sizes by iterating all stripes. Each stripe's `size()`
acquires/releases its own lock independently. Between reading stripe 0 and stripe N,
concurrent `put()` or `evict()` operations can change counts. The returned sum may
never have been the actual total at any single point in time.

The ADR for cross-stripe eviction states "momentary inconsistency during the sweep is
acceptable" for eviction, but does not address `size()` consistency.

**Impact:** Monitoring code that uses `size()` to track cache pressure may see
inconsistent values under concurrent load. This is conventional for striped caches
but undocumented.

**Suggested test:** Verify weak invariants: `size()` never returns negative, never
returns > `capacity()`. The non-linearizability itself is inherent and hard to test
deterministically — document as known behavior.

---

### F8 — IMPL-RISK: No assertions on `stripeIndex` result in hot paths

**Construct:** StripedBlockCache
**Lines:** StripedBlockCache.java:86, 107 (array index usages)
**Lens B Level:** Level 1 (missing defensive assertions)
**project-rule:** code-quality.md ("use assert statements throughout all code to
document and enforce assumptions")

**What:** `get()` and `put()` use `stripes[stripeIndex(...)]` without asserting the
index is in bounds:
```java
return stripes[stripeIndex(sstableId, blockOffset, stripeCount)].get(...);
```

If `stripeIndex` had a bug (e.g., returned a negative value or a value >= stripeCount),
the result would be `ArrayIndexOutOfBoundsException` — less informative than an
assertion documenting the expected invariant.

**Impact:** Reduced debuggability. A bounds assertion would fail with a clear message
showing the computed index, the inputs, and the expected range.

**Suggested test:** Not directly testable (assertions are development-time verification).
Verify by code inspection that assertions are present after fix.

---

### F9 — CONTRACT-GAP: No upper bound on `stripeCount`

**Construct:** StripedBlockCache.Builder
**Lines:** StripedBlockCache.java:207–213
**Lens B Level:** Level 2 (resource exhaustion via semantically wrong but technically
valid input)

**What:** `Builder.stripeCount(int)` validates `> 0` but has no upper bound. A caller
could pass `Integer.MAX_VALUE`, causing the constructor to allocate 2,147,483,647
`LruBlockCache` instances — each with a `LinkedHashMap` and `ReentrantLock`. This
would exhaust heap immediately.

The brief says the default is "number of available processors, capped" (at 16),
implying high stripe counts are not useful. But the API doesn't enforce any upper limit.

**Impact:** Resource exhaustion via misconfiguration or adversarial input. Even
`stripeCount=10_000` would allocate far more objects than useful.

**Suggested test:** Call `builder.stripeCount(Integer.MAX_VALUE).capacity(Integer.MAX_VALUE).build()`
and assert `IllegalArgumentException` for excessive stripeCount. Expected result:
fails — currently attempts to allocate billions of objects (OOM or extremely slow).

---

## Summary

| ID | Tag | Construct | Severity | Lens Level |
|----|-----|-----------|----------|------------|
| F1 | CONTRACT-GAP | Both (inherited) | High | L1 — atomicity |
| F2 | IMPL-RISK | Both Builders | Medium | L2 — validation |
| F3 | CONTRACT-GAP | StripedBlockCache | Medium | L2 — truncation |
| F4 | IMPL-RISK | LruBlockCache | Low | L1 — overflow |
| F5 | CONTRACT-GAP | Both | Medium | L1 — lifecycle |
| F6 | IMPL-RISK | LruBlockCache | Medium | L1 — performance |
| F7 | CONTRACT-GAP | StripedBlockCache | Low | L1 — linearizability |
| F8 | IMPL-RISK | StripedBlockCache | Low | L1 — assertions |
| F9 | CONTRACT-GAP | StripedBlockCache.Builder | Medium | L2 — resource exhaustion |

**CONTRACT-GAP:** 5 findings (F1, F3, F5, F7, F9)
**IMPL-RISK:** 4 findings (F2, F4, F6, F8)
**Project-rule violations:** F2 (coding-guidelines), F8 (code-quality)

## Breaker Priority Order

1. **F1** (getOrLoad atomicity) — highest risk: silent duplicate I/O on hot read path
2. **F9** (stripeCount unbounded) — resource exhaustion vector at construction
3. **F5** (use-after-close) — silent degradation masks consumer bugs
4. **F2** (eager validation) — project-rule violation, inconsistent API
5. **F3** (capacity truncation) — untested edge case with silent under-provisioning
6. **F6** (O(n) evict under lock) — performance risk under sustained load
7. **F4** (Integer.MAX_VALUE boundary) — theoretical overflow
8. **F7** (size() linearizability) — conventional, document-only
9. **F8** (missing assertions) — code-quality improvement, not runtime bug
