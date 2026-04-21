---
{
  "id": "sstable.byte-budget-block-cache",
  "version": 2,
  "status": "ACTIVE",
  "state": "DRAFT",
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
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "block-cache-block-size-interaction",
    "cross-stripe-eviction"
  ],
  "kb_refs": [
    "data-structures/caching/concurrent-cache-eviction-strategies"
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

R2. LruBlockCache.Builder.byteBudget must throw IllegalArgumentException for values that are not positive.

R3. LruBlockCache.Builder.build must throw IllegalArgumentException if byteBudget was never set.

R4. The `capacity(long)` method on LruBlockCache.Builder must be removed. (Supersedes F09.R43, F09.R44.)

### Per-entry byte tracking — internal insertion primitive

R5. LruBlockCache must maintain a `long currentBytes` field tracking total cached bytes, protected by the existing ReentrantLock.

R6. Every map insertion — whether from `put()`, `getOrLoad()`, or any other path — must add the inserted entry's `MemorySegment.byteSize()` to `currentBytes`.

R7. Every map removal — whether from the eviction loop, `evict(sstableId)`, `close()`, or any other path — must subtract the removed entry's `MemorySegment.byteSize()` from `currentBytes`.

R8. When `put()` or `getOrLoad()` replaces an existing entry for the same key, the replaced entry's `byteSize()` must be subtracted from `currentBytes` before the new entry's `byteSize()` is added.

R9. `put()` and `getOrLoad()` must throw IllegalArgumentException when `block.byteSize() == 0`.

### Eviction loop

R10. After every map insertion (from `put()` or `getOrLoad()`), LruBlockCache must evict eldest entries (LinkedHashMap iteration order) in a loop until `currentBytes <= byteBudget` OR the map contains only the just-inserted entry.

R11. If a single entry's byteSize exceeds the byte budget, LruBlockCache must evict all other entries, insert the new entry, and allow it to remain cached alone. This is the sole permitted exception to the `currentBytes <= byteBudget` invariant.

### Byte-budget invariant

R12. After any `put()` or `getOrLoad()` insertion completes within the lock scope, `currentBytes` must not exceed `byteBudget`, except as permitted by R11.

R13. The eviction loop must execute under the same lock as the insertion, for both `put()` and `getOrLoad()` insertion paths.

### LruBlockCache accessors

R14. LruBlockCache.capacity() must return the configured byteBudget value.

R15. LruBlockCache.size() must continue to return the number of entries, not the byte count.

R16. LruBlockCache.close() must set currentBytes to zero.

### Byte-budget builder API — StripedBlockCache

R17. StripedBlockCache.Builder must expose a `byteBudget(long)` method that returns Builder for fluent chaining.

R18. StripedBlockCache.Builder.byteBudget must throw IllegalArgumentException for values that are not positive.

R19. StripedBlockCache.Builder.build must throw IllegalArgumentException if byteBudget was never set.

R20. StripedBlockCache.Builder.build must throw IllegalArgumentException if byteBudget is less than the effective stripe count (after power-of-2 rounding).

R21. The `capacity(long)` method on StripedBlockCache.Builder must be removed.

### StripedBlockCache integration

R22. StripedBlockCache must divide the total byte budget equally across stripes: each stripe receives `totalByteBudget / stripeCount`.

R23. StripedBlockCache.capacity() must return the effective total byte budget, computed as `(totalByteBudget / stripeCount) * stripeCount`, accounting for integer division truncation.

R24. A single entry whose byteSize exceeds the per-stripe budget but is within the total budget triggers R11 behavior within its stripe. This is expected and acceptable.

### Superseded F09 requirements

R25. F09.R8 ("size must never exceed capacity") is superseded. The byte-budget invariant (R12) replaces it: `currentBytes` must not exceed `byteBudget` after insertion completes, except per R11.

R26. F09.R9 (entry-count capacity truncation) is superseded by R23 (byte-budget truncation).

R27. F09.R15 (per-stripe entry-count eviction) is superseded by R10 (per-stripe byte-budget eviction).

R28. The Integer.MAX_VALUE guard from F09.R43 does not apply to byteBudget. The LinkedHashMap.size() int-width limitation constrains entry count, not byte budget. byteBudget values exceeding Integer.MAX_VALUE are valid.

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
and eviction.

See `.decisions/block-cache-block-size-interaction/adr.md` for the full rationale.

## Adversarial Review Notes (v2)

v1 had 14 requirements with 8 failures. Key fixes in v2:
- getOrLoad path explicitly covered in R6, R7, R8, R9, R10, R13 (was missing from all)
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
