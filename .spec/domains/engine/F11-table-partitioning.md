---
{
  "id": "F11",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": ["engine"],
  "requires": ["F10"],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": ["table-partitioning"],
  "kb_refs": ["distributed-systems/data-partitioning/partitioning-strategies", "distributed-systems/data-partitioning/vector-search-partitioning"],
  "open_obligations": []
}
---

# F11 — Table Partitioning

## Requirements

### PartitionDescriptor record

R1. `PartitionDescriptor` must be a public record in `jlsm.table` with components `id` (long), `lowKey` (MemorySegment), `highKey` (MemorySegment), `nodeId` (String), and `epoch` (long).

R2. `PartitionDescriptor` must reject a null `lowKey` with a `NullPointerException` whose message identifies the parameter.

R3. `PartitionDescriptor` must reject a null `highKey` with a `NullPointerException` whose message identifies the parameter.

R4. `PartitionDescriptor` must reject a null `nodeId` with a `NullPointerException` whose message identifies the parameter.

R5. `PartitionDescriptor` must reject a negative `epoch` with an `IllegalArgumentException`.

R6. `PartitionDescriptor` must defensively copy `lowKey` and `highKey` at construction so that mutations to the original `MemorySegment` backing array do not affect the descriptor's state.

R7. `PartitionDescriptor` must make the copied `lowKey` and `highKey` segments read-only so that callers cannot mutate the descriptor's internal state via the accessor methods.

R8. `PartitionDescriptor` must reject a range where `lowKey` is greater than or equal to `highKey` in unsigned byte-lexicographic order with an `IllegalArgumentException`. The range is half-open: [lowKey, highKey).

R9. `PartitionDescriptor` must implement content-based `equals` by comparing `MemorySegment` fields by byte content, not by reference identity.

R10. `PartitionDescriptor` must implement content-based `hashCode` consistent with its `equals`.

### Key comparison semantics

R11. All key comparisons in the partitioning subsystem (PartitionDescriptor, RangeMap, PartitionConfig) must use unsigned byte-lexicographic ordering: at the first mismatched byte position, the byte with the smaller unsigned value is considered smaller. A segment that is a proper prefix of another is considered smaller.

R12. The `MemorySegment.mismatch` method must be used as the basis for key comparison. A return value of -1L means the segments are byte-for-byte identical.

R13. When `mismatch` returns a value equal to the byte size of segment A but less than the byte size of segment B, A must be treated as less than B (prefix rule).

### PartitionConfig validation

R14. `PartitionConfig` must be a public final class in `jlsm.table` with a static factory method `of(List<PartitionDescriptor>)` that validates and returns an immutable configuration.

R15. `PartitionConfig.of` must reject a null descriptor list with a `NullPointerException`.

R16. `PartitionConfig.of` must reject an empty descriptor list with an `IllegalArgumentException`.

R17. `PartitionConfig.of` must reject null elements in the descriptor list with a `NullPointerException` whose message includes the element index.

R18. `PartitionConfig.of` must reject duplicate partition IDs with an `IllegalArgumentException` whose message includes the duplicate ID and its index.

R19. `PartitionConfig.of` must validate contiguity: for consecutive descriptors at indices i and i+1, the `highKey` of descriptor i must be byte-for-byte equal to the `lowKey` of descriptor i+1. A mismatch must produce an `IllegalArgumentException` identifying the gap or overlap by index.

R20. `PartitionConfig.descriptors()` must return an unmodifiable list. Attempts to mutate the returned list must throw `UnsupportedOperationException`.

R21. `PartitionConfig.partitionCount()` must return the number of descriptors.

### ScoredEntry record

R22. `ScoredEntry<K>` must be a public record in `jlsm.table` with components `key` (K), `document` (JlsmDocument), and `score` (double).

R23. `ScoredEntry` must reject a null `key` with a `NullPointerException`.

R24. `ScoredEntry` must reject a null `document` with a `NullPointerException`.

R25. `ScoredEntry` must reject a NaN `score` with an `IllegalArgumentException`. NaN values have undefined ordering and would corrupt merge results.

### RangeMap routing

R26. `RangeMap` must be a final class in `jlsm.table.internal` that accepts a `PartitionConfig` at construction.

R27. `RangeMap` must reject a null `PartitionConfig` with a `NullPointerException`.

R28. `RangeMap.routeKey(MemorySegment)` must return the `PartitionDescriptor` whose half-open range [lowKey, highKey) contains the given key, using O(log P) binary search where P is the partition count.

R29. `RangeMap.routeKey` must reject a null key with a `NullPointerException`.

R30. `RangeMap.routeKey` must throw `IllegalArgumentException` when the key is strictly less than the lowKey of the first partition (below all ranges).

R31. `RangeMap.routeKey` must throw `IllegalArgumentException` when the key is greater than or equal to the highKey of the last partition (above all ranges).

R32. For a key exactly equal to a partition boundary (where partition N's highKey equals partition N+1's lowKey), `RangeMap.routeKey` must route to partition N+1, not partition N, because the range is half-open [lowKey, highKey) and the key equals partition N+1's lowKey.

R33. `RangeMap.overlapping(fromKey, toKey)` must return all partition descriptors whose ranges overlap the half-open query range [fromKey, toKey), in key order.

R34. `RangeMap.overlapping` must return an empty list when `fromKey` is greater than or equal to `toKey` (empty or inverted range).

R35. `RangeMap.overlapping` must return an empty list when the query range does not intersect any partition range.

R36. `RangeMap.overlapping` must reject a null `fromKey` or `toKey` with a `NullPointerException`.

R37. `RangeMap.all()` must return all partition descriptors in key order.

R38. The overlap test for a partition [pLow, pHigh) against query [from, to) must be: pLow < to AND pHigh > from. Both conditions must hold for the partition to be included.

### PartitionClient SPI

R39. `PartitionClient` must be a public interface in `jlsm.table` extending `Closeable`.

R40. `PartitionClient.descriptor()` must return the `PartitionDescriptor` for this partition.

R41. `PartitionClient.create(String key, JlsmDocument doc)` must create a document in the partition. It must throw `IOException` on write failure and `DuplicateKeyException` if the key already exists.

R42. `PartitionClient.get(String key)` must return `Optional<JlsmDocument>` -- present if found, empty if not found. It must throw `IOException` on read failure.

R43. `PartitionClient.update(String key, JlsmDocument doc, UpdateMode mode)` must update a document. It must throw `IOException` on write failure and `KeyNotFoundException` if the key does not exist.

R44. `PartitionClient.delete(String key)` must delete a document. It must throw `IOException` on write failure.

R45. `PartitionClient.getRange(String fromKey, String toKey)` must return an iterator over entries in the half-open key range [fromKey, toKey). It must throw `IOException` on read failure.

R46. `PartitionClient.query(Predicate predicate, int limit)` must return a `List<ScoredEntry<String>>` of at most `limit` matching entries. It must throw `IOException` on query failure.

R47. All `PartitionClient` method parameter types and return types must be serializable-friendly (no MemorySegment, no raw byte arrays, no function references). This enables future remote implementations without changing the interface.

### InProcessPartitionClient

R48. `InProcessPartitionClient` must be a final class in `jlsm.table.internal` implementing `PartitionClient`.

R49. `InProcessPartitionClient` must accept a `PartitionDescriptor` and a `JlsmTable.StringKeyed` at construction, rejecting null for either with a `NullPointerException`.

R50. `InProcessPartitionClient` must delegate all CRUD operations (create, get, update, delete) to the wrapped `JlsmTable.StringKeyed` instance.

R51. `InProcessPartitionClient.getRange` must delegate to the wrapped table's `getAllInRange` method.

R52. `InProcessPartitionClient.close()` must close the wrapped table.

### ResultMerger -- top-k merge

R53. `ResultMerger` must be a final class in `jlsm.table.internal` with a private constructor (static utility).

R54. `ResultMerger.mergeTopK(List<List<ScoredEntry<K>>> partitionResults, int k)` must return the top-k entries across all partitions sorted by score descending (highest score first).

R55. `ResultMerger.mergeTopK` must reject a null `partitionResults` list with a `NullPointerException`.

R56. `ResultMerger.mergeTopK` must reject a non-positive `k` with an `IllegalArgumentException`.

R57. `ResultMerger.mergeTopK` must reject null elements within the `partitionResults` list with a `NullPointerException` whose message includes the element index.

R58. When the total number of entries across all partitions is less than k, `mergeTopK` must return all available entries (not pad to k).

R59. `ResultMerger.mergeTopK` must use a priority queue (max-heap by score) to produce the global top-k without materializing the full merged set.

R60. When two entries have identical scores, `mergeTopK` must include both in the result set (ties are not broken by arbitrary elimination). The relative ordering of tied entries is unspecified.

### ResultMerger -- ordered merge

R61. `ResultMerger.mergeOrdered(List<Iterator<TableEntry<String>>> partitionIterators)` must return a single iterator that produces entries in global key order by performing an N-way merge.

R62. `ResultMerger.mergeOrdered` must reject a null `partitionIterators` list with a `NullPointerException`.

R63. The N-way merge must use a min-heap keyed by entry key so that each `next()` call performs O(log N) work where N is the number of active partition iterators.

R64. When a partition iterator is exhausted, it must be removed from the heap. The merge must not poll from exhausted iterators.

R65. When all partition iterators are exhausted, the merged iterator's `hasNext()` must return false and `next()` must throw `NoSuchElementException`.

R66. If multiple partition iterators yield entries with the same key (which should not occur with correct routing but could occur during range queries that span partition boundaries), the merge must emit all such entries without deduplication.

### PartitionedTable coordinator

R67. `PartitionedTable` must be a public final class in `jlsm.table` implementing `Closeable`.

R68. `PartitionedTable` must be constructed via a builder pattern accessed through a static `builder()` method.

R69. The builder must accept a `PartitionConfig` via `partitionConfig(config)`, rejecting null with a `NullPointerException`.

R70. The builder must accept a client factory `Function<PartitionDescriptor, PartitionClient>` via `partitionClientFactory(factory)`, rejecting null with a `NullPointerException`.

R71. The builder must reject `build()` when `partitionConfig` has not been set, throwing `IllegalStateException`.

R72. The builder must reject `build()` when `partitionClientFactory` has not been set, throwing `IllegalStateException`.

R73. During `build()`, the builder must invoke the client factory once per descriptor in configuration order. The factory must not return null; a null return must be rejected with a `NullPointerException` identifying the descriptor ID.

R74. If the client factory throws for partition N, the builder must close all previously created clients (indices 0 through N-1) using the deferred close pattern before propagating the exception. Close failures must be added as suppressed exceptions.

### PartitionedTable CRUD routing

R75. `PartitionedTable.create(key, doc)` must route to the partition whose range contains the UTF-8 encoded key, using `RangeMap.routeKey`. It must reject null key or doc with a `NullPointerException`.

R76. `PartitionedTable.get(key)` must route to the correct partition by key. It must reject a null key with a `NullPointerException`.

R77. `PartitionedTable.update(key, doc, mode)` must route to the correct partition by key. It must reject null key, doc, or mode with a `NullPointerException`.

R78. `PartitionedTable.delete(key)` must route to the correct partition by key. It must reject a null key with a `NullPointerException`.

R79. String keys must be converted to `MemorySegment` using UTF-8 encoding for range map lookups. The encoding must use `StandardCharsets.UTF_8`.

### PartitionedTable range queries

R80. `PartitionedTable.getRange(fromKey, toKey)` must identify the minimal set of overlapping partitions via `RangeMap.overlapping`, query each, and merge the results in key order via `ResultMerger.mergeOrdered`.

R81. `PartitionedTable.getRange` must reject null `fromKey` or `toKey` with a `NullPointerException`.

R82. When the query range does not overlap any partition, `getRange` must return an empty iterator (not throw).

### PartitionedTable scatter-gather queries

R83. `PartitionedTable.query(predicate, limit)` must reject a null predicate with a `NullPointerException`.

R84. `PartitionedTable.query(predicate, limit)` must reject a non-positive limit with an `IllegalArgumentException`.

R85. For vector, full-text, and combined predicates, `PartitionedTable.query` must scatter the query to all partitions, collect per-partition results, and merge via `ResultMerger.mergeTopK` with the requested limit.

R86. Each partition must receive the full `limit` value (not limit/P) to ensure global correctness -- the coordinator performs the final top-k selection across partition results.

### PartitionedTable close

R87. `PartitionedTable.close()` must close all partition clients using the deferred close pattern: every client is closed even if one or more throw, and exceptions are accumulated. The first exception is thrown with remaining exceptions added as suppressed.

R88. If the first accumulated exception is an `IOException`, it must be thrown directly. If it is a different exception type, it must be wrapped in an `IOException`.

### Partial partition failure

R89. If a single partition fails during a scatter-gather query (throws `IOException`), the coordinator must propagate the failure to the caller. Partial results from successful partitions must not be silently returned as if they were complete.

R90. If a CRUD operation fails on the target partition (throws `IOException`), the coordinator must propagate the failure to the caller without modifying other partitions.

### JPMS module boundaries

R91. `PartitionDescriptor`, `PartitionConfig`, `PartitionClient`, `ScoredEntry`, and `PartitionedTable` must reside in `jlsm.table`, which is exported in the `jlsm-table` module descriptor.

R92. `RangeMap`, `ResultMerger`, and `InProcessPartitionClient` must reside in `jlsm.table.internal`, which must not be exported in the `jlsm-table` module descriptor.

### Input validation summary

R93. All public API methods on `PartitionedTable`, `PartitionClient`, `PartitionConfig`, and `PartitionDescriptor` must reject null arguments with a `NullPointerException` whose message identifies the null parameter.

R94. All internal classes (`RangeMap`, `ResultMerger`, `InProcessPartitionClient`) must use `assert` statements to verify preconditions on internal-only call paths where the caller is trusted code within the module.

### Thread safety

R95. `PartitionDescriptor`, `PartitionConfig`, and `ScoredEntry` are immutable records/classes. They must be safe to share across threads without synchronization.

R96. `RangeMap` is immutable after construction. It must be safe to use from multiple threads without synchronization.

R97. `PartitionedTable` must be safe for concurrent CRUD operations from multiple threads, provided the underlying `PartitionClient` implementations are themselves thread-safe. The coordinator must not introduce additional synchronization requirements beyond what the clients require.

---

## Design Narrative

### Intent

Add range-based table partitioning to jlsm-table so that documents can be distributed across multiple self-contained partitions, each owning a contiguous key range and its own set of co-located indices (secondary, vector, inverted). The PartitionedTable coordinator routes key-based CRUD to the correct partition in O(log P) time and executes scatter-gather for queries that cannot be routed to a single partition (vector similarity, full-text search, combined predicates). The PartitionClient SPI abstracts the communication channel so that future remote implementations can be plugged in without changing the coordinator.

### Why this approach

**Range partitioning over hash partitioning:** LSM-trees store data in sorted order. Range partitioning preserves this ordering across partitions, so range scans stay within a single partition or a small number of adjacent partitions instead of touching every node. Hash partitioning would destroy key ordering and degrade every range query to O(P) fan-out. This aligns with the approach used by CockroachDB, TiKV, and FoundationDB.

**Per-partition co-located indices over global IVF sharding:** Each partition maintains its own vector index, inverted index, and secondary indices alongside its documents. This means filtered vector search ("find similar images where category='outdoor'") executes entirely within a single partition in one pass -- no cross-partition join required. Global IVF sharding (Milvus-style) would separate vectors from documents, turning every combined query into a cross-system join.

**Static partition assignment over dynamic split/merge:** The initial implementation uses fixed partition boundaries defined at creation time. Dynamic splitting and merging require a coordination protocol (epoch-based versioning, split-during-compaction handling, vector index rebuild) that adds significant complexity. Static assignment is sufficient for the initial use case and provides a clean foundation for dynamic partitioning in a future spec.

**SPI-based PartitionClient over direct JlsmTable coupling:** The PartitionClient interface uses only serializable-friendly types (String keys, JlsmDocument, Predicate, ScoredEntry). This means the coordinator does not need to change when a remote implementation is added -- only a new PartitionClient implementation is needed. The in-process implementation simply delegates to the wrapped JlsmTable.

**Scatter-gather with full limit per partition:** Each partition receives the full top-k limit rather than limit/P. This ensures that the global top-k is correct even when results are concentrated in a few partitions. The coordinator performs the final selection via priority queue merge.

### What was ruled out

- **Dynamic split/merge:** Requires partition-aware compaction, epoch-based routing, vector index rebuild on split, and merge policies for empty ranges. Deferred to a separate spec (see ADR out-of-scope items).
- **Remote communication:** The PartitionClient SPI is designed for remote implementations, but no network transport, serialization protocol, or failure retry logic is included in this spec. Deferred to the clustering/transport layer (F04).
- **Replication:** Per-partition Raft/Paxos replication is a separate concern requiring consensus protocol selection. Deferred per ADR.
- **Cross-partition transactions:** 2PC, Calvin, or Percolator-style coordination is out of scope. Each partition operates independently.
- **Vector query partition pruning:** At moderate partition counts (<100), scatter-gather to all partitions is acceptable. Pruning based on partition-level vector metadata (centroid summaries) is deferred until partition count makes fan-out expensive.
- **Rebalancing:** Moving partitions between nodes requires transport, state transfer, and grace period handling. Deferred to the rebalancing spec.
