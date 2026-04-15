---
problem: "block-cache-block-size-interaction"
date: "2026-04-14"
version: 2
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-core/src/main/java/jlsm/cache/LruBlockCache.java"
  - "modules/jlsm-core/src/main/java/jlsm/cache/StripedBlockCache.java"
---

# ADR — Block Cache / Block Size Interaction

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Concurrent Cache Eviction Strategies | Cross-stripe eviction models, RocksDB byte-budget pattern | [`.kb/data-structures/caching/concurrent-cache-eviction-strategies.md`](../../.kb/data-structures/caching/concurrent-cache-eviction-strategies.md) |

---

## Files Constrained by This Decision
- `LruBlockCache.java` — replace `removeEldestEntry` with byte-budget eviction loop;
  add `currentBytes` tracking; builder API changes from `capacity(long)` to `byteBudget(long)`
- `StripedBlockCache.java` — per-stripe byte budgets derived from total byte budget;
  `capacity()` returns byte budget, not entry count

## Problem
Block cache capacity is entry-count-based. When block size varies across SSTables
(4 KiB local vs 8 MiB remote, or mixed after compaction/transfer), a fixed entry
count leads to unpredictable memory usage. Capacity 1000 could mean 4 MiB or 8 GB.

## Constraints That Drove This Decision
- **Mixed block sizes**: A single cache instance holds blocks from SSTables written
  at different block sizes — entry-count derivation from a single assumed blockSize
  is wrong in this scenario
- **Byte-budget API**: Operators must reason about cache memory in bytes, not entries
- **Minimal internal change**: Preserve the LinkedHashMap structure; change the
  eviction trigger, not the data structure

## Decision
**Per-entry byte-budget eviction — track total cached bytes via `MemorySegment.byteSize()`
and evict eldest entries until within budget.**

Replace the `removeEldestEntry` override with a post-put eviction loop. Each
`put()` adds the new entry's `MemorySegment.byteSize()` to a running `currentBytes`
counter. After insertion, a loop removes eldest entries (via LinkedHashMap
iteration order) until `currentBytes <= byteBudget`. On `remove()` and `evict()`,
subtract the removed entry's byte size.

### API change
```java
// Before
LruBlockCache.builder().capacity(1000).build();

// After
LruBlockCache.builder().byteBudget(256 * 1024 * 1024).build(); // 256 MiB
```

`StripedBlockCache` divides the total byte budget equally across stripes, same
as it currently divides entry-count capacity.

### Why per-entry tracking instead of derivation
Entry-count derivation (`byteBudget / blockSize`) assumes all cached blocks are
the same size. This assumption fails when:
- SSTables in the same tree were written with different block sizes (local vs remote)
- Cross-node transfer introduces SSTables from a differently-configured node
- Compaction merges SSTables from different levels with different block sizes

Per-entry tracking via `MemorySegment.byteSize()` is exact regardless of block
size variation.

## Rationale

### Why per-entry byte accounting
- **Accuracy**: Exact byte tracking handles mixed block sizes without assumptions
- **Simplicity**: Small change — replace one eviction condition with a loop; add
  a `long currentBytes` field. The LinkedHashMap access-order structure is preserved.
- **O(1) cost**: `MemorySegment.byteSize()` is a field read, not a computation
- **Thread safety**: The existing `ReentrantLock` serializes all access; the
  eviction loop runs under the lock with no concurrent access concerns

### Why not entry-count derivation
- Breaks on mixed block sizes — the `BlockCache` interface accepts blocks from
  any SSTable; nothing enforces uniform block sizes within a cache instance
- Pool buffer size (write-path) ≠ cache entry size (read-path) — deriving from
  the pool conflates unrelated concerns

### Why not full byte-budget rewrite
- Unnecessarily invasive — the LinkedHashMap structure and access-order LRU
  work correctly. Only the eviction trigger needs to change.

### Why not document-only
- Pushes byte-to-entry-count math to every operator; they face the same
  mixed-block-size problem

## Implementation Guidance

### LruBlockCache changes
1. Replace `capacity(long)` builder method with `byteBudget(long)`
2. Remove `removeEldestEntry` override from the LinkedHashMap
3. Add `private long currentBytes` field (not atomic — lock serializes access)
4. On `put()`: add `block.byteSize()` to `currentBytes`, then loop:
   ```java
   while (currentBytes > byteBudget && !map.isEmpty()) {
       var eldest = map.entrySet().iterator().next();
       currentBytes -= eldest.getValue().byteSize();
       map.remove(eldest.getKey());
   }
   ```
5. On `remove()` / `evict()`: subtract removed entry's `byteSize()`

### StripedBlockCache changes
1. Builder accepts `byteBudget(long)` instead of `capacity(long)`
2. Per-stripe budget = `totalByteBudget / stripeCount`
3. `capacity()` returns total byte budget

### Edge cases
- Empty block (byteSize == 0): should not happen; `MemorySegment` from SSTable
  blocks always has positive size. Guard with assertion.
- Single entry exceeds budget: evict all existing entries, then insert. The entry
  is cached alone until the next put evicts it. This is correct behavior —
  the cache holds the most recently accessed block.

## What This Decision Does NOT Solve
- Off-heap memory tracking — the cache stores MemorySegment references but does
  not manage their allocation lifecycle
- Cache admission policy (TinyLFU, CLOCK-Pro) — separate concern from capacity

## Conditions for Revision
This ADR should be re-evaluated if:
- `MemorySegment.byteSize()` becomes unreliable (e.g., segments are sliced with
  shared backing and byteSize reflects the slice, not the allocation)
- The eviction loop under lock becomes a contention bottleneck (e.g., frequent
  large-to-small block size transitions causing many evictions per put)
- Cache needs to track off-heap memory directly (allocate via Arena in the cache)

---
*Confirmed by: user deliberation | Date: 2026-04-14*
*Full scoring: [evaluation.md](evaluation.md)*
