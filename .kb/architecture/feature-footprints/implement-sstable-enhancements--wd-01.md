---
title: "Byte-budget block cache (implement-sstable-enhancements WD-01)"
type: feature-footprint
tags: [sstable, block-cache, byte-budget, lru, adversarial-hardening]
feature_slug: implement-sstable-enhancements--wd-01
work_group: implement-sstable-enhancements
shipped: 2026-04-21
pr: https://github.com/nathannorthcutt/jlsm/pull/44
domains: [sstable, caching]
---

# Byte-budget block cache

## Shipped outcome

Replaced the entry-count-based block cache eviction with byte-budget LRU,
driven by `MemorySegment.byteSize()`. Before: `LruBlockCache.Builder.capacity(N)`
cached N entries regardless of size — 1000 capacity meant 4 MiB with local
blocks (4 KiB) or 8 GiB with remote blocks (8 MiB). After: `byteBudget(N)`
bounds total off-heap bytes exactly, producing predictable memory usage
across mixed block sizes.

Two specs involved:
- `sstable.byte-budget-block-cache` — authored v2 DRAFT → v4 APPROVED
- `sstable.striped-block-cache` — amended v2 APPROVED → v4 APPROVED (5 Rs
  invalidated in-place; R-number gaps preserved so existing `@spec`
  annotations still match)

## Key constructs

Three files were modified (no new files among production sources):

- `modules/jlsm-core/src/main/java/jlsm/core/cache/BlockCache.java` —
  Javadoc-only: `capacity()` unit is bytes, `close()` mandates ISE on
  use-after-close, default `getOrLoad` documents monitor-collision risk
- `modules/jlsm-core/src/main/java/jlsm/cache/LruBlockCache.java` —
  full rewrite; single private insertion chokepoint (R6) and removal
  chokepoint (R7); `Math.addExact` overflow protection (R29); R11
  oversized-entry admission; R28a entry-count cap; R16 ordered close
- `modules/jlsm-core/src/main/java/jlsm/cache/StripedBlockCache.java` —
  full rewrite; `expectedMinimumBlockSize(long)` hint (R20a/R20b);
  deferred-exception pattern extended to `evict()` (R5) and `size()`
  (R46); constructor-side reflective-bypass defenses (R3a, R18a); partial
  construction rollback (R48); non-linear splitmix64 pre-avalanche (R11)

Three test-only files were added:
- `ByteBudgetBlockCacheTest.java` — 45 tests across 7 `@Nested` classes
- `DataTransformationAdversarialTest.java` — splitmix64 pre-image tests
- `DispatchRoutingAdversarialTest.java` — stripe fan-out tests

## API change the caller sees

Old:
```java
LruBlockCache.builder().capacity(1000).build();              // 1000 entries
StripedBlockCache.builder().capacity(1000).stripeCount(8).build();
```

New:
```java
LruBlockCache.builder().byteBudget(256 * 1024 * 1024).build();   // 256 MiB
StripedBlockCache.builder()
    .byteBudget(256 * 1024 * 1024)
    .stripeCount(8)
    .expectedMinimumBlockSize(4096)   // optional hint
    .build();
```

`capacity(long)` is REMOVED (not deprecated). `capacity()` (getter) still
exists, returns the same value as `byteBudget()` — its unit semantics
changed and the interface Javadoc documents the change.

## Cross-references

**ADRs consulted:**
- [`block-cache-block-size-interaction`](../../../.decisions/block-cache-block-size-interaction/adr.md) — confirmed 2026-04-14, established per-entry byte tracking via `MemorySegment.byteSize()` as the design
- [`cross-stripe-eviction`](../../../.decisions/cross-stripe-eviction/adr.md) — confirmed, permitted per-stripe R11 oversized-entry behavior
- [`atomic-cross-stripe-eviction`](../../../.decisions/atomic-cross-stripe-eviction/adr.md) — closed as non-issue, informed R24

**KB entries used / created:**
- Used during authoring: `.kb/data-structures/caching/` — byte-budget-cache-variable-size-entries, concurrent-cache-eviction-strategies, capacity-truncation-on-sharding, getorload-non-atomic, deferred-builder-validation, missing-close-guard, int-backed-long-api
- Created during audit: `.kb/patterns/validation/reflective-bypass-of-builder-validation.md`, `.kb/patterns/validation/interface-contract-missing-from-javadoc.md`, `.kb/patterns/resource-management/fan-out-dispatch-deferred-exception-pattern.md`
- Created during retro: `.kb/patterns/adversarial-review/hash-function-algebraic-probes.md`

## Adversarial pipeline summary

The spec went through an aggressive adversarial review pipeline:

| Phase | Findings | Applied |
|-------|----------|---------|
| `/spec-author` Pass 2 (falsification) | 20 | 19 |
| `/spec-author` Pass 3 (fix-consequence) | 15 | 15 |
| `/feature-harden` (domain lenses) | 8 | 8 |
| `/audit` round 1 | 32 | 10 (10 IMPOSSIBLE, 1 FIX_IMPOSSIBLE → resolved via R11 relaxation, 11 were already resolved) |

The audit caught a weakness that survived two prior falsification rounds:
`splitmix64Hash` used a linear combine `sstableId * C + blockOffset`
admitting algebraic pre-image collisions. R11 was relaxed to permit
non-linear pre-avalanche, the fix landed in the same PR, and the
`hash-function-algebraic-probes.md` KB entry records the gap in the
spec-authoring checklist that let it slip through.

## Noteworthy constraints and pitfalls

- **MemorySegment slice caveat (R30):** `byteSize()` returns the view size,
  not the backing allocation. A 4 KiB slice of a 1 GiB mmap backing region
  reports 4096 — the cache bounds the sum of slice views, not physical
  footprint. Callers wishing to bound actual committed memory must pass
  segments backed by distinct allocations.
- **Reference lifetime (R15a):** `MemorySegment` refs returned by `get()`
  or `getOrLoad()` may be invalidated by concurrent operations from other
  threads. The cache provides NO happens-before relationship between
  returning a reference and another thread's eviction. Callers requiring
  stable refs must copy contents or retain an external Arena.
- **Single private chokepoint (R6):** all insertion paths (`put`,
  `getOrLoad`, and any future method) must funnel through one private
  `insertEntry` method that contains R9/R9a/R28a/R29 validation + R8 put-
  replace sequence + R10 eviction loop. Do NOT duplicate this logic at a
  caller — new methods must delegate.
- **Overflow protection is symmetric (R29):** both put-new-key
  (`Math.addExact(currentBytes, newBytes)`) and put-replace
  (`Math.addExact(Math.subtractExact(currentBytes, replacedBytes), newBytes)`)
  use exact arithmetic before mutating state. The "no state mutation on
  overflow" contract holds identically on both paths.
- **`maxHeapSize = '6g'` in test task:** the R29 overflow test needs to
  allocate two ~2 GiB `MemorySegment`s to exercise the `Long.MAX_VALUE`
  boundary. CI workers may need this bump or the test should be
  `@Disabled` like `entryCountCap_R28a_rejectsNearIntegerMaxValue`.
- **Integer.MAX_VALUE entry count (R28a):** enforced in the implementation
  but not reachable in unit tests; the test is `@Disabled` with a pointer
  to the spec invariant.

## Prior art displaced (from this feature group)

The displacement chain targeted `sstable.striped-block-cache.R{8,9,15,43,44}`:
- R8 (`size <= capacity` invariant) — superseded by byte-budget R12
- R9 (entry-count capacity truncation formula) — superseded by byte-budget R23
- R15 (per-stripe entry-count LRU eviction) — superseded by byte-budget R10/R22
- R43 (LruBlockCache.Builder.build reject capacity > Integer.MAX_VALUE) — superseded by byte-budget R28
- R44 (LruBlockCache.Builder.capacity eager non-positive rejection) — superseded by byte-budget R2 (byteBudget has transactional rejection instead)

These were marked `[INVALIDATED v3]` in-place rather than splitting into a new
"striped-core" spec, to preserve the 28 existing `@spec sstable.striped-block-cache.RN`
annotations in the implementation.

## Related work definitions (same work group)

- WD-02 `sstable.pool-aware-block-size` — READY, not yet implemented. May
  take an `expectedMinimumBlockSize(long)` hint wired in here; integration
  is future work, no dependency declared here.
- WD-03 `sstable.end-to-end-integrity` — READY, not yet implemented. No
  interaction with byte-budget semantics expected.
