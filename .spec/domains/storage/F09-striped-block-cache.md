---
{
  "id": "F09",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": ["storage"],
  "requires": [],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": ["stripe-hash-function", "cross-stripe-eviction"],
  "kb_refs": [],
  "open_obligations": []
}
---

# F09 — Striped Block Cache

## Requirements

### BlockCache contract conformance

R1. StripedBlockCache must implement the BlockCache interface.

R2. StripedBlockCache.get must return an Optional containing the cached MemorySegment when the entry exists in the appropriate stripe for the given (sstableId, blockOffset) pair.

R3. StripedBlockCache.get must return Optional.empty() when no entry exists in the appropriate stripe for the given (sstableId, blockOffset) pair.

R4. StripedBlockCache.put must insert or replace the block in the stripe determined by (sstableId, blockOffset), such that a subsequent get with the same key returns the new block.

R5. StripedBlockCache.evict must remove all cached blocks for the given sstableId from every stripe by iterating all stripes sequentially.

R6. StripedBlockCache.size must return the sum of entries across all stripes.

R7. StripedBlockCache.size must never return a negative value.

R8. StripedBlockCache.size must never exceed StripedBlockCache.capacity.

R9. StripedBlockCache.capacity must return the effective total capacity, computed as (configuredCapacity / stripeCount) * stripeCount, not the raw configured value.

### Stripe selection

R10. The stripeIndex function must accept (sstableId, blockOffset, stripeCount) and return an integer in [0, stripeCount).

R11. The stripeIndex function must use the Splitmix64 finalizer (Stafford variant 13) with golden-ratio combining of sstableId and blockOffset as input.

R12. The stripeIndex function must distribute sequential 4096-aligned block offsets for a single sstableId across at least half the available stripes when stripeCount is 8 and 64 sequential offsets are tested.

R13. The stripeIndex function must be deterministic: identical (sstableId, blockOffset, stripeCount) inputs must always produce the same output.

R14. The stripeIndex function must be package-private static.

### Per-stripe LRU eviction

R15. Each stripe must independently enforce LRU eviction when its per-stripe capacity (configuredCapacity / stripeCount) is exceeded.

R16. LRU eviction in one stripe must not affect entries in other stripes.

### Builder validation

R17. StripedBlockCache.Builder.stripeCount must reject values less than or equal to zero with an IllegalArgumentException.

R18. StripedBlockCache.Builder.stripeCount must reject values exceeding MAX_STRIPE_COUNT (1024) with an IllegalArgumentException.

R19. StripedBlockCache.Builder.stripeCount at exactly MAX_STRIPE_COUNT must be accepted.

R20. StripedBlockCache.Builder.capacity must reject values less than or equal to zero eagerly (at the setter call, not deferred to build) with an IllegalArgumentException.

R21. StripedBlockCache.Builder.build must reject configurations where capacity is less than stripeCount with an IllegalArgumentException.

R22. StripedBlockCache.Builder.build must reject configurations where per-stripe capacity (capacity / stripeCount) exceeds Integer.MAX_VALUE with an IllegalArgumentException.

R23. StripedBlockCache.Builder must default stripeCount to min(Runtime.getRuntime().availableProcessors(), 16) when not explicitly set.

R24. StripedBlockCache.Builder.capacity must be set explicitly before build; calling build without setting capacity must throw an IllegalArgumentException.

### Operation input validation

R25. StripedBlockCache.get must throw IllegalArgumentException when blockOffset is negative.

R26. StripedBlockCache.put must throw IllegalArgumentException when blockOffset is negative.

R27. StripedBlockCache.put must throw NullPointerException when block is null.

### Use-after-close

R28. StripedBlockCache.get must throw IllegalStateException when called after close.

R29. StripedBlockCache.put must throw IllegalStateException when called after close.

R30. StripedBlockCache.evict must throw IllegalStateException when called after close.

### Close behavior

R31. StripedBlockCache.close must close all stripes, accumulating exceptions via the deferred exception pattern: if multiple stripes throw on close, the first exception is thrown with subsequent exceptions added as suppressed.

R32. StripedBlockCache.close must clear all entries from all stripes, such that size returns zero after close completes.

R33. StripedBlockCache.close must be safe to call on a newly constructed cache with no entries.

### Concurrency

R34. StripedBlockCache must be safe for concurrent use by multiple threads without external synchronization.

R35. Concurrent put and get operations from multiple threads must not lose entries that fall within the effective capacity, assuming no LRU eviction pressure.

R36. Each stripe must hold its own independent lock so that operations on different stripes do not contend on the same lock.

### getOrLoad atomicity

R37. StripedBlockCache.getOrLoad must invoke the loader at most once for a given (sstableId, blockOffset) pair when multiple threads call getOrLoad concurrently for the same key.

R38. StripedBlockCache.getOrLoad must throw IllegalArgumentException when blockOffset is negative.

R39. StripedBlockCache.getOrLoad must throw NullPointerException when the loader parameter is null.

R40. StripedBlockCache.getOrLoad must throw IllegalStateException when called after close.

### Factory methods

R41. LruBlockCache.getMultiThreaded must return a StripedBlockCache.Builder instance.

R42. LruBlockCache.getSingleThreaded must return a LruBlockCache.Builder instance.

### LruBlockCache capacity guard

R43. LruBlockCache.Builder.build must reject capacity values exceeding Integer.MAX_VALUE with an IllegalArgumentException, because the backing LinkedHashMap uses int-width size().

R44. LruBlockCache.Builder.capacity must reject values less than or equal to zero eagerly (at the setter call) with an IllegalArgumentException.

---

## Design Narrative

### Intent

Eliminate single-lock contention in LruBlockCache under concurrent read workloads by partitioning the key space across N independent LruBlockCache stripes. Each stripe has its own ReentrantLock, so threads accessing different stripes never contend. The feature provides explicit entry points via static factory methods on LruBlockCache -- getMultiThreaded() for the striped variant, getSingleThreaded() for the original single-lock variant -- so callers make an intentional choice based on their concurrency profile.

### Why this approach

**Lock striping over concurrent data structures:** A ConcurrentHashMap would eliminate locking but cannot enforce LRU eviction order without additional tracking structures. Striping N independent LRU caches preserves per-stripe LRU semantics with a simple, well-understood implementation.

**Splitmix64 hash (Stafford variant 13):** Chosen for its excellent avalanche properties (every input bit affects every output bit) and simplicity (three multiply-XOR-shift stages, no table lookups). Golden-ratio combining of sstableId and blockOffset produces good distribution even for sequential block offsets, which are the common access pattern for SSTable reads.

**Sequential cross-stripe eviction:** Evict(sstableId) iterates all stripes sequentially, acquiring and releasing each stripe's lock independently. This avoids holding multiple locks simultaneously (no deadlock risk) at the cost of momentary inconsistency during the sweep -- a concurrent get might see a block from a not-yet-swept stripe that will be evicted shortly. This is acceptable because eviction is called after compaction, and stale reads during the brief sweep are harmless.

**Capacity truncation:** When capacity is not evenly divisible by stripeCount, each stripe gets floor(capacity / stripeCount) entries. The effective capacity may be less than the configured value. capacity() reports the effective value to prevent callers from relying on phantom capacity that no stripe can honor.

### What was ruled out

- **ReadWriteLock per stripe:** LinkedHashMap.get with accessOrder=true mutates internal ordering, making it a write-equivalent operation. ReadWriteLock offers no benefit because every access is effectively a write.
- **ConcurrentHashMap + separate LRU tracking:** More complex, harder to reason about eviction correctness, and the LRU tracking structure itself becomes a contention point.
- **Caffeine-style window-TinyLFU:** Out of scope for this feature; the simple LRU-per-stripe approach is sufficient for the current workload and avoids a heavyweight dependency.
- **Byte-based / weighted capacity:** Would require per-block size tracking and more complex eviction logic. Block-count capacity is simpler and matches the current BlockCache interface contract.

### Audit provenance

This spec incorporates fixes from adversarial audit findings:

| Issue | Requirement |
|-------|-------------|
| CAPACITY-TRUNCATION | R9 |
| LONG-CAPACITY-UNBOUNDED | R22, R43 |
| BUILDER-EAGER-VALIDATION | R20, R44 |
| USE-AFTER-CLOSE | R28, R29, R30, R40 |
| GETORLOAD-ATOMICITY | R37 |
| EXCESSIVE-STRIPE-COUNT | R18, R19 |
| SIZE-INVARIANTS | R7, R8 |
