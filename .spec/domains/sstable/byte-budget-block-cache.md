---
{
  "id": "sstable.byte-budget-block-cache",
  "version": 4,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "sstable"
  ],
  "requires": [
    "sstable.striped-block-cache"
  ],
  "invalidates": [
    "sstable.striped-block-cache.R8",
    "sstable.striped-block-cache.R9",
    "sstable.striped-block-cache.R15",
    "sstable.striped-block-cache.R43",
    "sstable.striped-block-cache.R44"
  ],
  "preserves": [
    "sstable.striped-block-cache.R28",
    "sstable.striped-block-cache.R29",
    "sstable.striped-block-cache.R30",
    "sstable.striped-block-cache.R40",
    "sstable.striped-block-cache.R46"
  ],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "block-cache-block-size-interaction",
    "cross-stripe-eviction"
  ],
  "kb_refs": [
    "data-structures/caching/concurrent-cache-eviction-strategies",
    "data-structures/caching/byte-budget-cache-variable-size-entries",
    "data-structures/caching/capacity-truncation-on-sharding",
    "data-structures/caching/getorload-non-atomic",
    "data-structures/caching/deferred-builder-validation",
    "data-structures/caching/missing-close-guard",
    "data-structures/caching/int-backed-long-api"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F25"
  ]
}
---
# sstable.byte-budget-block-cache — Byte-Budget Block Cache

## Requirements

### Byte-budget builder API — LruBlockCache

R1. LruBlockCache.Builder must expose a `byteBudget(long)` method that returns Builder for fluent chaining.

R2. LruBlockCache.Builder.byteBudget must throw IllegalArgumentException for values that are not positive, and must not mutate any builder state when the exception is thrown. A subsequent call to byteBudget with a valid value must succeed as if the rejected call had not occurred.

R3. LruBlockCache.Builder.build must throw IllegalArgumentException if byteBudget was never set.

R3a. LruBlockCache.<init>(Builder) and StripedBlockCache.<init>(Builder) must detect the "byteBudget not set" sentinel (implementation-specific, commonly `-1L`) and emit the same "byteBudget not set" diagnostic as Builder.build(). Reflective-bypass callers that skip Builder.build()'s R3/R19 check must receive an identical error message, never a message containing the sentinel value. This prevents information leakage about the validation internals.

R4. The `capacity(long)` method on LruBlockCache.Builder must be removed. (Supersedes F09.R43, F09.R44.)

### Per-entry byte tracking — internal insertion primitive

R5. LruBlockCache must maintain a `long currentBytes` field tracking total cached bytes, protected by the existing ReentrantLock.

R6. LruBlockCache must provide exactly one private method through which all map insertions funnel. The input validation guards (R9 / R9a zero-length rejection, R28a entry-count cap, R29 overflow check), the byte-tracking add (R8), and the eviction loop (R10) must ALL reside in that method and must be the only code path that mutates the backing map via insertion. Every insertion-originating public method (put, getOrLoad, any future path) must delegate to this single method before performing any map or state mutation. Furthermore, LruBlockCache must override every insertion-originating method declared or defaulted on the `BlockCache` interface — including any default `getOrLoad` implementation that might split an insertion across multiple public calls — so that every insertion path terminates at this single chokepoint within a single critical section.

R7. LruBlockCache must provide exactly one private method through which all map removals funnel. The byte-tracking subtract (subtracting the removed entry's `MemorySegment.byteSize()` from `currentBytes`) must reside in that method and must be the only code path that mutates the backing map via removal. Every removal-originating path (eviction loop, evict(sstableId), close(), any future path) must delegate to this single method.

R7a. LruBlockCache is NOT required to deduplicate `MemorySegment` instances across keys. In the default implementation, a segment referenced by two distinct keys is counted twice in `currentBytes`, and removing either key decrements by the segment's `byteSize()`. Implementations that choose to deduplicate (for example, using identity tracking to avoid double-counting aliased segments) must still satisfy R12 (byte-budget invariant) and R16 (close zeroes `currentBytes`); dedup is permitted as an optimization but MUST NOT weaken those invariants. Callers that intentionally cache aliased segments must account for the default double-counting behavior unless they know their implementation deduplicates.

R8. When `put()` or `getOrLoad()` replaces an existing entry for the same key, within the *same critical section* (same uninterrupted `lock()` / `unlock()` pair) the implementation must, in order: (a) compute the prospective post-operation value `currentBytes' = Math.addExact(Math.subtractExact(currentBytes, replacedBytes), newBytes)` — if either exact operation throws, R29 is triggered and NO state mutation may occur; (b) assign `currentBytes = currentBytes'`; (c) commit the new entry to the map; (d) run the R10 eviction loop. Steps a–d must execute atomically under the lock with no intervening lock release; no concurrent observer may see an intermediate state. The ordering (compute-then-assign) ensures R29's "no state mutation on overflow detection" contract holds on the put-replace path.

R9. `put(sstableId, blockOffset, block)` must throw IllegalArgumentException when `block.byteSize() == 0`, checked before any state mutation.

R9a. `getOrLoad` must throw IllegalArgumentException when the `MemorySegment` returned by `loader.get()` has `byteSize() == 0`. The check must occur after the loader returns and before lock reacquisition; the loader-returned segment must be discarded without being committed to the map. The loader retains ownership of any external resources (FileChannel, Arena, socket, etc.) backing the returned segment — the cache does NOT assume ownership of a discarded zero-length segment, so the loader must either avoid allocating such resources for a zero-length payload or release them before returning. This ownership contract must be documented on the public `getOrLoad` Javadoc.

### Overflow protection

R29. LruBlockCache must reject any insertion whose completion would cause `currentBytes` to overflow `Long.MAX_VALUE`. The overflow check must be computed on the PROSPECTIVE post-operation value of `currentBytes` and must use exact arithmetic at every step:
- **New-key insertion:** compute `Math.addExact(currentBytes, block.byteSize())` — if this throws, R29 fires.
- **Put-replace insertion (same key already present):** compute `Math.addExact(Math.subtractExact(currentBytes, replacedBytes), block.byteSize())` — if either exact operation throws, R29 fires.

The overflow check must occur BEFORE any mutation of `currentBytes` or `map`, so that R29's "no state mutation on overflow detection" contract holds identically on both insertion paths. On overflow detection, the insertion must fail with `IllegalStateException` and no state mutation (no map change, no currentBytes change) may occur. The two insertion paths must be symmetric with respect to the value of `byteBudget` and the caller-provided `block.byteSize()` that triggers the overflow.

### Eviction loop

R10. After every map insertion (from `put()` or `getOrLoad()`), LruBlockCache must evict eldest entries (LinkedHashMap iteration order) in a loop until `currentBytes <= byteBudget`, except as permitted by R11.

R11. When `put()` or `getOrLoad()` inserts a single entry whose `block.byteSize() > byteBudget`, LruBlockCache must: (a) evict all OTHER entries before (or during) committing the new entry, (b) commit the oversized entry, (c) allow `currentBytes > byteBudget` while the oversized entry is the sole cached entry. This is the SOLE permitted exception to R12. R10's eviction loop must terminate in this case when only the oversized just-inserted entry remains, rather than by virtue of the OR clause alone. **Partial-failure rollback:** the implementation must arrange steps (a) and (b) so that no recoverable exception can be thrown between completion of the evictions and commit of the new entry — for example, by committing the new entry first and then evicting others, or by pre-reserving `currentBytes` headroom before any eviction. If an exception does occur after step (a) has begun evictions, the cache is left in an emptied state (evictions are NOT rolled back); the caller must treat the exception as indicating both a failed insertion and a drained cache.

### Byte-budget invariant

R12. After the final lock release of any call to `put()` or `getOrLoad()` that performed a map insertion, `currentBytes` must not exceed `byteBudget`, except as permitted by R11. The eviction loop must run within the same lock critical section that performed the insertion, so the invariant is re-established before the lock is released.

R13. The eviction loop must execute while holding the same `ReentrantLock` instance that protects `map` and `currentBytes`, and within the *same critical section* (same uninterrupted `lock()` / `unlock()` pair) as the `map.put()` it follows. The eviction loop must NOT cross a lock release. For `getOrLoad`, the eviction loop must run in the post-loader critical section, in the same `lock()` / `unlock()` pair that performs the `map.put()`. Satisfying R13 by acquiring "the same `ReentrantLock` object" in a separate critical section is NOT sufficient — the map insertion and its eviction loop must be atomic with respect to any concurrent reader or writer.

### LruBlockCache accessors

R14. LruBlockCache must expose `byteBudget()` returning the configured byte budget as a `long`. The `capacity()` method required by the `BlockCache` interface must return the `byteBudget` value, and its Javadoc must document that the unit semantics have changed from entries (F09) to bytes in this spec. Additionally, the `BlockCache` interface's own `capacity()` Javadoc must be updated to specify that it returns a byte count (byte budget), not an entry count — making the unit change authoritative across every current and future implementation of the interface.

R15. LruBlockCache.size() must continue to return the number of entries, not the byte count. `size()` (entries) and `capacity()` / `byteBudget()` (bytes) use different units and are not directly comparable; under R11, `size()` may be 1 while `currentBytes > byteBudget`.

R15a. `MemorySegment` references returned by `get()` and `getOrLoad()` may be invalidated by any *subsequent or concurrent* cache operation that can evict the entry (`put`, a cache-miss `getOrLoad`, `evict`, `close`). The cache provides NO happens-before relationship between the return of a segment reference and a concurrent eviction by another thread — once a reference is returned, a different thread's `put` can evict the backing entry before the first thread dereferences the segment. `get()` itself never evicts and never invalidates previously-returned references; a cache-hit `getOrLoad` (loader not invoked) behaves like `get()` and also never invalidates. Callers requiring stable segment lifetimes across concurrent cache use must either: (a) retain an external Arena whose lifetime exceeds any cache operation, or (b) copy the segment contents before releasing their own synchronization or re-entering the cache. Arenas backing cached segments must not be closed while the cache holds references to them. The cache stores references only and does not refcount or extend the lifetime of cached segments.

R16. `close()` must acquire the ReentrantLock and, in a single critical section, (a) set `closed = true`, (b) clear the map (via the R7 removal chokepoint so `currentBytes` decrements accordingly), (c) assert `currentBytes == 0` — a non-zero value at this point indicates an R7 byte-tracking bug and must surface as an assertion error in development builds per the project's assertion conventions, (d) set `currentBytes = 0` as a final defensive guard so production builds (with assertions disabled) still leave the cache in a consistent empty state. A reader blocked on the lock during close must observe a consistent empty state. Double-close must be idempotent.

### Use-after-close

R31. After `close()`, all of LruBlockCache's `put`, `getOrLoad`, `get`, `evict`, `size`, `capacity`, and `byteBudget` accessors must throw `IllegalStateException`. This requirement explicitly preserves `sstable.striped-block-cache.R28`, `R29`, `R30`, `R40`, and `R46` under the new byte-budget semantics — those requirements are NOT on the invalidation list of this spec and continue to govern closed-cache behavior.

### MemorySegment contract

R30. LruBlockCache's byte accounting uses `MemorySegment.byteSize()` and does not account for shared backing allocations. Callers that pass slices of a larger backing segment are responsible for understanding that the cache bounds the sum of slice `byteSize()` values, not the sum of distinct backing allocations. This contract must be documented on the public `put` and `getOrLoad` method Javadoc.

### Loader exception

R32. If `loader.get()` in `getOrLoad` throws any exception, no state mutation (no map insertion, no currentBytes change, no eviction) may have occurred by the time the exception leaves the method. The exception must propagate to the caller unchanged. If the implementation uses reservation-style pre-accounting, a compensating release must execute in a `finally` block before the exception leaves the method.

### Byte-budget builder API — StripedBlockCache

R17. StripedBlockCache.Builder must expose a `byteBudget(long)` method that returns Builder for fluent chaining.

R18. StripedBlockCache.Builder.byteBudget must throw IllegalArgumentException for values that are not positive, and must not mutate any builder state when the exception is thrown. A subsequent call to byteBudget with a valid value must succeed as if the rejected call had not occurred.

R19. StripedBlockCache.Builder.build must throw IllegalArgumentException if byteBudget was never set.

R20. StripedBlockCache.Builder.build must throw IllegalArgumentException if byteBudget is less than the effective stripe count (after power-of-2 rounding).

R20a. StripedBlockCache.Builder.build must throw IllegalArgumentException when `totalByteBudget / effectiveStripeCount < expectedMinimumBlockSize`, where `expectedMinimumBlockSize` is the smallest block size the cache expects to serve (defaulting to 4096 bytes — one local SSTable block — when unspecified). The builder must accept a hint via `expectedMinimumBlockSize(long)` for deployments with larger block sizes (e.g., 8 MiB for remote backends) so the per-stripe floor scales with the workload. Per-stripe budgets below the expected minimum block size make every insertion trigger R11 oversized-entry behavior and defeat the purpose of caching.

R20b. StripedBlockCache.Builder.expectedMinimumBlockSize must throw IllegalArgumentException for values that are not positive, and must not mutate any builder state when the exception is thrown. A subsequent call to expectedMinimumBlockSize with a valid value must succeed as if the rejected call had not occurred. This parallels the transactional-setter semantics of R2 (LruBlockCache.Builder.byteBudget) and R18 (StripedBlockCache.Builder.byteBudget).

R21. The `capacity(long)` method on StripedBlockCache.Builder must be removed.

### StripedBlockCache integration

R22. StripedBlockCache must divide the total byte budget equally across stripes: each stripe receives `totalByteBudget / effectiveStripeCount`, where `effectiveStripeCount` is the configured stripeCount rounded up to the next power of 2 per ADR `power-of-two-stripe-optimization`.

R23. StripedBlockCache.capacity() must return the effective total byte budget, computed as `(totalByteBudget / effectiveStripeCount) * effectiveStripeCount`, accounting for integer division truncation.

R24. A single entry whose byteSize exceeds the per-stripe budget but is within the total budget triggers R11 behavior within its stripe. This is expected and acceptable.

### Superseded F09 requirements

R25. F09.R8 ("size must never exceed capacity") is superseded. The byte-budget invariant (R12) replaces it: `currentBytes` must not exceed `byteBudget` after insertion completes, except per R11.

R26. F09.R9 (entry-count capacity truncation) is superseded by R23 (byte-budget truncation).

R27. F09.R15 (per-stripe entry-count eviction) is superseded by R10 (per-stripe byte-budget eviction).

R28. The Integer.MAX_VALUE guard from F09.R43 does not apply to byteBudget. The LinkedHashMap.size() int-width limitation constrains entry count, not byte budget. byteBudget values exceeding Integer.MAX_VALUE are valid, subject to R29 overflow protection.

R28a. LruBlockCache must reject any new-key insertion (an insertion for a key not already present in the map) when `map.size() >= Integer.MAX_VALUE - 1`, with `IllegalStateException`. Put-replace insertions (same-key update per R8) do not change `size()` and are not subject to this check. The check must be evaluated against the *projected post-eviction size*: if R10/R11 eviction would reduce `map.size()` below the cap during the insertion's critical section, the insertion is permitted. This precedence rule ensures that R11-qualifying oversized insertions (which evict all other entries before committing) are not unnecessarily rejected when the cache approaches the entry-count cap. This cap is enforced independently of byteBudget because LinkedHashMap's internal structure uses int-width `size()` and reaching `Integer.MAX_VALUE` corrupts the map's invariants.

---

## Design Narrative

Block cache capacity was entry-count-based. With variable block sizes (4 KiB
local to 8 MiB remote), a fixed entry count leads to unpredictable memory usage.
Per-entry byte tracking via MemorySegment.byteSize() gives exact budget
enforcement regardless of block size variation. The existing LinkedHashMap
access-order structure is preserved; only the eviction trigger changes.

All map insertions — whether via `put()` or `getOrLoad()` — funnel through the
same byte-tracking logic. This is critical: the existing `getOrLoad` performs
`map.put()` directly inside the lock, and must participate in byte tracking
and eviction. R6 mandates this structurally (single private insertion method)
rather than behaviorally, so future maintainers adding new insertion paths
cannot silently bypass byte tracking.

### MemorySegment slice caveat

`MemorySegment.byteSize()` reflects the size of the segment view, not the
size of the backing allocation. A 4 KiB slice of a 1 GiB mmap'd segment
reports `byteSize() == 4096`, even though releasing that slice does not free
the 1 GiB backing region. The cache bounds the sum of slice sizes and does
not attempt to reason about shared backing. Callers wishing to bound actual
committed off-heap memory must ensure the segments they insert are backed
by distinct allocations, or accept that the budget reflects the logical view
rather than the physical footprint. (See ADR
`block-cache-block-size-interaction` revision conditions.)

### Overflow protection

`currentBytes` is a `long`. Addition overflow is theoretically possible when
`byteBudget` approaches `Long.MAX_VALUE` and a single segment's `byteSize()`
pushes the running total past the 64-bit ceiling. R29 mandates `Math.addExact`
to detect and reject such inserts before they corrupt the running total.

See `.decisions/block-cache-block-size-interaction/adr.md` for the full rationale.

## Adversarial Review Notes (v4, audit round-001 reconciliation — 2026-04-21)

v4 adds one reconciliation update from audit round-001 against the shipped
LruBlockCache / StripedBlockCache implementation:

- **R3a (NEW)** — constructor-side byteBudget sentinel detection. Both
  LruBlockCache and StripedBlockCache constructors must recognise the
  implementation-specific "byteBudget not set" sentinel (commonly `-1L`) and
  emit the same "byteBudget not set — call .byteBudget(n) before .build()"
  diagnostic that Builder.build() emits. Reflective-bypass callers that set
  builder fields directly must receive an identical error message, never a
  message containing the raw sentinel value. Resolves the information-leakage
  finding F-R1.contract_boundaries.4.2.

## Adversarial Review Notes (v3)

v2 had 28 requirements. v3 applied 19 findings from a two-round adversarial
pipeline (Pass 2 falsification + Pass 3 depth pass). Pass 2 surfaced 20
findings (12 critical/high applied, 7 medium, 1 low); Pass 3 surfaced 15
fix-consequence findings (7 critical/high applied, 4 medium, 2 low tightened
further on top of Pass 2's applied changes).

**Pass 2 key additions (applied to create v3 baseline):**

- **R6/R7 structural enforcement** — chokepoint requirement; future
  insertion/removal paths must delegate to single private methods (trust-boundary)
- **R9a** — getOrLoad zero-length check explicit (loader return, discarded
  post-load before lock reacquisition)
- **R29** — Math.addExact overflow protection on `currentBytes + byteSize()`
  at `Long.MAX_VALUE` (degenerate value)
- **R30** — MemorySegment slice/backing accounting contract (resource lifecycle;
  ADR revision condition)
- **R31** — close-guard requirement explicit; preserves
  striped-block-cache.R28/R29/R30/R40/R46 (KB pattern: missing-close-guard)
- **R32** — loader exception bookkeeping (KB pattern: getorload-non-atomic)
- **R14** — `byteBudget()` accessor; `capacity()` unit change documented
- **R20a** — minimum per-stripe budget (KB: capacity-truncation-on-sharding)
- **R2/R18** — transactional setter semantics (KB: deferred-builder-validation)
- **R10/R11 decoupling** — R11 explicit oversized-entry precondition
- **R12** — "final lock release" clarification for getOrLoad two-acquisition pattern
- **R16** — close() ordering: set closed → clear via R7 → zero currentBytes,
  atomic under lock

**Pass 3 fix-consequence tightenings:**

- **R6** — chokepoint scope expanded: R9/R9a/R28a/R29 input validation must
  reside INSIDE the chokepoint; LruBlockCache must override `BlockCache` default
  methods to prevent split-acquisition delegation
- **R8** — put-replace reordered: compute `Math.addExact(Math.subtractExact(...))`
  BEFORE any `currentBytes` mutation; prevents R29 "no state mutation" contract
  violation on put-replace overflow (Pass 2's step-ordering was the bug)
- **R11** — partial-failure rollback clause added: implementations must arrange
  steps so no recoverable exception can occur between eviction and commit
- **R15a** — rewritten for concurrent-thread semantics: no happens-before
  between return-of-reference and another thread's eviction; `get()` and
  cache-hit `getOrLoad` explicitly never invalidate
- **R20a** — magic-number 4096 replaced by configurable
  `expectedMinimumBlockSize(long)` to scale with remote backends (8 MiB blocks)
- **R28a** — rewritten with projected-post-eviction size; precedence vs R11
  resolved (R11-qualifying inserts not unnecessarily rejected at cap)
- **R29** — explicit symmetry statement: put-new and put-replace must use
  matching exact arithmetic so the overflow window is identical
- **`preserves` front-matter field** — machine-readable alternative to R31's
  prose anchors; lets spec-verify tooling detect stale references
- **R14** — BlockCache interface Javadoc must be updated too (not just impl)
- **R9a** — loader retains ownership of external resources on zero-length discard
- **R7a** — softened: dedup NOT required but permitted; R12/R16 invariants hold
- **R16** — `assert currentBytes == 0` before defensive zero-reset to catch
  R7 bugs in development builds

## v2 Adversarial Review Notes (retained for history)

v1 had 14 requirements with 8 failures. Key fixes in v2:
- getOrLoad path explicitly covered in R6, R7, R8, R9, R10, R13
- put-replace scenario: R8 requires subtracting replaced entry's bytes
- R3 ambiguity resolved: capacity(long) removed (not deprecated) — R4, R21
- F09 invalidation list expanded: R8, R9, R15, R43, R44 all superseded
- R7/R13 contradiction resolved: R12 cross-references R11's exception
- R9 truncation: R23 specifies effective truncated value
- LruBlockCache.capacity() specified: R14 returns byteBudget
- Integer.MAX_VALUE guard: R28 explicitly removes it for byteBudget
- close() resets currentBytes: R16
- StripedBlockCache validation: R20 (byteBudget >= stripeCount)
- Zero-size segments: R9 throws IllegalArgumentException
