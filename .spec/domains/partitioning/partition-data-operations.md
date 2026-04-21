---
{
  "id": "partitioning.partition-data-operations",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "partitioning"
  ],
  "requires": [
    "partitioning.table-partitioning"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "table-partitioning"
  ],
  "kb_refs": [
    "distributed-systems/data-partitioning/partitioning-strategies",
    "distributed-systems/data-partitioning/vector-search-partitioning"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F30"
  ]
}
---
# partitioning.partition-data-operations — Partition Data Operations

## Requirements

### Sequential insert hotspot mitigation

R1. The system must define a `WriteDistributor` interface with a single method `distribute(MemorySegment logicalKey)` that returns a physical key (`MemorySegment`) suitable for partition routing.

R2. The `WriteDistributor.distribute` method must be deterministic: the same logical key must always produce the same physical key.

R3. The `WriteDistributor.distribute` method must reject a null `logicalKey` with a `NullPointerException`.

R4. The system must provide an `IdentityDistributor` implementation that returns the logical key unchanged. This must be the default when no distributor is explicitly configured.

R5. The system must provide a `PrefixHashDistributor` implementation that prepends a fixed-length hash prefix to the logical key. The hash prefix must be derived from a configurable salt field within the logical key, so that keys with the same salt value are co-located while monotonic keys within a salt group are scattered across the range space.

R6. The `PrefixHashDistributor` must accept a `saltExtractor` function (`Function<MemorySegment, MemorySegment>`) at construction that selects the portion of the logical key to use as the hash input. The distributor must reject a null `saltExtractor` with a `NullPointerException`.

R7. The `saltExtractor` function must not return null. If the extractor returns null for a given key, `PrefixHashDistributor.distribute` must throw a `NullPointerException`.

R8. The `saltExtractor` function may return a zero-length `MemorySegment`. The `PrefixHashDistributor` must hash the zero-length segment deterministically (producing a valid prefix).

R9. The `PrefixHashDistributor` hash prefix must be a configurable fixed byte length (default: 2 bytes). The prefix length must reject values less than 1 or greater than 8 with an `IllegalArgumentException`.

R10. The `PrefixHashDistributor` must use a deterministic hash function to compute the prefix from the salt bytes. The hash output must be truncated to the configured prefix length by taking the most-significant bytes.

R11. The `PrefixHashDistributor` must provide a reverse method `logicalKey(MemorySegment physicalKey)` that strips the hash prefix and returns the original logical key.

R12. The `PrefixHashDistributor.logicalKey` method must reject a null `physicalKey` with a `NullPointerException`.

R13. The `PrefixHashDistributor.logicalKey` method must reject a `physicalKey` shorter than the configured prefix length with an `IllegalArgumentException`.

R14. When a `WriteDistributor` other than `IdentityDistributor` is configured, all read paths (get, getRange, query) must apply the same distributor to transform lookup keys before routing.

R15. When `PrefixHashDistributor` is active, `PartitionedTable.getRange(fromKey, toKey)` must scatter the range query to all partitions because the hash prefix destroys logical key adjacency.

R16. When `PrefixHashDistributor` is active, the coordinator must strip hash prefixes from result keys before returning entries to the caller.

R17. When `PrefixHashDistributor` is active, the result ordering of range queries follows physical key order, which differs from logical key order. This must be documented in the `PartitionedTable.getRange` Javadoc.

R18. The `WriteDistributor` must be configurable on the `PartitionedTable.Builder`. The builder must reject a null distributor with a `NullPointerException`.

R19. The `WriteDistributor` must be safe to invoke concurrently from multiple threads without external synchronization.

### Vector query partition pruning

R20. The system must define a `PartitionPruner` interface with a method `prune` that accepts a query context (vector query parameters and optional metadata predicate) and a list of `PartitionDescriptor` entries, and returns the subset of descriptors that must not be pruned.

R21. The `PartitionPruner.prune` method must be a pure function: given the same query context and descriptors, it must return the same result set.

R22. The `PartitionPruner.prune` method must reject a null query context with a `NullPointerException`.

R23. The `PartitionPruner.prune` method must reject a null descriptor list with a `NullPointerException`.

R24. When the `PartitionPruner.prune` method receives an empty descriptor list, it must return an empty list (not throw).

R25. The system must provide an `AllPartitionsPruner` implementation that returns all descriptors unchanged (no pruning). This must be the default when no pruner is configured.

R26. The system must provide a `MetadataPartitionPruner` implementation that uses per-partition metadata summaries to eliminate partitions that cannot contain matching documents. The pruner must accept a `PartitionMetadataProvider` at construction.

R27. The `MetadataPartitionPruner` must reject a null `PartitionMetadataProvider` at construction with a `NullPointerException`.

R28. The `PartitionMetadataProvider` must be an interface with a method that accepts a partition ID (`long`) and returns an `Optional<PartitionMetadata>`.

R29. The `PartitionMetadata` must include minimum and maximum values for each indexed field (field range bounds) and the total document count.

R30. The `MetadataPartitionPruner` must prune a partition when every filter predicate in the query can be determined unsatisfiable from the partition's field range bounds. An equality predicate is unsatisfiable when the value falls outside the [min, max] range for that field. A range predicate is unsatisfiable when the predicate's range does not overlap the field's [min, max] range.

R31. The `MetadataPartitionPruner` must not prune a partition when the `PartitionMetadataProvider` returns empty for that partition's ID. Missing metadata must be treated as "partition may contain matches."

R32. The system must provide a `CentroidPartitionPruner` implementation for vector queries. The pruner must accept per-partition centroid summaries (a representative vector per partition) and a configurable absolute distance threshold at construction.

R33. The `CentroidPartitionPruner` must prune a partition when the distance between the query vector and the partition's centroid exceeds the distance threshold multiplied by the configured expansion factor.

R34. The `CentroidPartitionPruner` expansion factor must default to 2.0. The constructor must reject values less than or equal to zero with an `IllegalArgumentException`.

R35. The `CentroidPartitionPruner` distance threshold must reject values less than or equal to zero with an `IllegalArgumentException`.

R36. The `CentroidPartitionPruner` must not prune a partition that has no centroid (new or empty partitions). Missing centroids must be treated as "partition may contain matches."

R37. The system must provide a `CompositePartitionPruner` that combines multiple `PartitionPruner` instances. A partition is pruned if at least one delegate prunes it (union of pruned sets).

R38. The `CompositePartitionPruner` must reject a null or empty delegate list at construction with an `IllegalArgumentException`.

R39. `PartitionedTable.query(predicate, limit)` must invoke the configured `PartitionPruner` before scatter-gather. Only the non-pruned partitions must receive the query. If all partitions are pruned, the method must return an empty result list (not throw).

R40. The `PartitionPruner` must be configurable on the `PartitionedTable.Builder`. The builder must reject a null pruner with a `NullPointerException`.

R41. The `PartitionPruner` must be safe to invoke concurrently from multiple threads without external synchronization.

### Partition-aware compaction scheduling

R42. The system must define a `CompactionScheduler` interface with a method `schedule` that accepts a set of partition IDs requesting compaction and returns an ordered list of partition IDs indicating the order in which compaction should proceed.

R43. The `CompactionScheduler.schedule` method must return an empty list when the input set is empty.

R44. The maximum number of concurrent compactions across all co-located partitions on a single node must be configurable on the `CompactionScheduler`. The default must be 2.

R45. The concurrency limit must reject values less than 1 with an `IllegalArgumentException`.

R46. The `CompactionScheduler` must not schedule a compaction for a partition that is in the DRAINING or CATCHING_UP state (as defined in F27 R18–R23). Only partitions in the SERVING state (F27 R19) are eligible for compaction.

R47. The system must provide a `RoundRobinCompactionScheduler` implementation that cycles through eligible partitions in a fixed order (by partition ID ascending), ensuring that no single partition monopolizes the compaction budget when multiple partitions have pending compaction work.

R48. When a new partition is registered with the `RoundRobinCompactionScheduler`, it must be inserted into the cycle at the position determined by its partition ID (maintaining ascending order).

R49. The system must provide a `SizeWeightedCompactionScheduler` implementation that prioritizes partitions with the highest ratio of pending compaction bytes to total partition bytes.

R50. The `SizeWeightedCompactionScheduler` must treat a partition with zero total bytes (empty partition) as having the lowest priority, not as a division-by-zero error.

R51. The `RoundRobinCompactionScheduler` must be the default when no scheduler is explicitly configured.

R52. When a compaction completes for one partition, the scheduler must re-evaluate the pending set and schedule the next eligible partition without requiring a new external trigger.

R53. The `CompactionScheduler` must emit a `CompactionThrottleEvent` when a partition's compaction request is deferred because the concurrency limit is reached. The event must include the partition ID, the current number of active compactions, and the configured limit.

R54. The `CompactionScheduler` must provide an `activeCompactionCount()` method that returns the number of compactions currently in progress. This value must be readable without blocking from any thread.

R55. The `CompactionScheduler` must provide a `pendingCompactionCount()` method that returns the number of partitions waiting for a compaction slot. This value must be readable without blocking from any thread.

R56. The `CompactionScheduler` must be configurable on the partition manager or `PartitionedTable.Builder`. The builder must reject a null scheduler with a `NullPointerException`.

R57. When a node acquires new partitions during rebalancing (transition to SERVING per F27 R23), those partitions must be registered with the `CompactionScheduler`.

R58. When a node loses partitions (transition to DRAINING per F27 R23), those partitions must be deregistered from the scheduler and any pending compaction for them must be cancelled.

R59. The `CompactionScheduler` must be safe for concurrent invocation from multiple threads (compaction completion callbacks and new compaction requests may arrive concurrently).

R60. The `CompactionScheduler` must implement `Closeable`. The `close()` method must cancel all pending compaction requests and must be idempotent.

### Interaction between subsystems

R61. When `PrefixHashDistributor` is active, the `CompactionScheduler` must schedule compaction using the physical key space (with hash prefixes). Compaction operates below the logical key translation layer.

R62. The `PartitionPruner` must operate on partition descriptors whose key ranges are in physical key space. When a `WriteDistributor` transforms keys, the pruner's metadata bounds must reflect physical key ranges.

R63. The `CentroidPartitionPruner` must not be affected by the `WriteDistributor` because vector distance is computed in embedding space, not key space. The pruner operates on vector embeddings independent of key routing.

---

## Design Narrative

### Intent

Resolve three deferred decisions from the table-partitioning ADR that address per-partition data management: (1) mitigating write hotspots from monotonic keys in range partitions, (2) skipping partitions during vector/filtered queries when metadata or centroid distance can eliminate them, and (3) coordinating compaction scheduling across co-located partitions to prevent thundering-herd I/O spikes. These three concerns are grouped into a single spec because they share the same partition abstraction layer and interact with each other in the key translation and scheduling dimensions.

### Sequential insert hotspot mitigation

**PrefixHash over automatic split-on-hotspot:** The parent ADR identifies sequential insert hotspots as a known edge case for range partitioning. The KB (partitioning-strategies.md) documents CockroachDB's approach: "prefix keys with a hash of a secondary attribute, or use hash-sharded indexes for sequential workloads." The `PrefixHashDistributor` follows this approach directly. The alternative -- detecting hotspots at runtime and auto-splitting -- requires load-based trigger policy infrastructure and creates small partitions that may be difficult to merge later. A prefix hash is deterministic, requires no runtime detection, and scatters monotonic keys across the range space immediately.

**Salt-based extraction over whole-key hashing:** Hashing the entire key would destroy all key locality, making every range query a full scatter. By extracting a salt field (e.g., a tenant ID prefix), keys within the same tenant remain ordered relative to each other while different tenants are scattered. This preserves intra-salt range scan efficiency while distributing inter-salt writes evenly.

**Range query scatter when hash is active (R15):** This is an intentional tradeoff documented explicitly. When `PrefixHashDistributor` is active, the physical key order no longer matches logical key order, so a logical range query must scatter to all partitions. Consumers who need both range scan efficiency and hotspot mitigation should use the salt-based approach to limit scatter to salt-group-level queries. Consumers who never use range queries can hash the entire key with no penalty.

### Vector query partition pruning

**Metadata-based pruning over always-scatter:** The parent ADR's "What This Decision Does NOT Solve" section identifies "Vector query optimization at 1000+ partitions (needs partition-level metadata for skip logic)" as an open concern. Even at moderate partition counts (10-100), metadata pruning reduces I/O for queries with selective filters. A query like "price < 50 AND vector_similar(embedding, query, 10)" can skip partitions where all documents have price >= 50, as determined by per-partition field range bounds.

**Centroid-based pruning for vector queries:** The KB (vector-search-partitioning.md) notes that per-partition local vector indices mean "no global IVF routing optimisation (can't skip irrelevant partitions for vector queries without additional metadata)." The `CentroidPartitionPruner` provides this metadata: a representative centroid vector per partition enables coarse distance-based pruning. If the query vector is very far from a partition's centroid (beyond threshold * expansion factor), that partition is unlikely to contain top-k results. The expansion factor (default 2.0) provides a safety margin against centroid staleness.

**Composite pruning (R37) over single-strategy:** Real queries combine vector similarity with metadata filters. The `CompositePartitionPruner` applies both centroid-based and metadata-based pruning, taking the union of pruned partitions. This means a partition is skipped only if it fails at least one pruning criterion, which is safe because each criterion independently determines unsatisfiability.

**Missing metadata treated as non-prunable (R31, R36):** New partitions, partitions mid-split, or partitions whose metadata provider is unavailable must never be pruned. This prevents false negatives (missing valid results) at the cost of occasional unnecessary queries to metadata-sparse partitions.

### Partition-aware compaction scheduling

**Coordinated scheduling over independent compaction:** The parent ADR identifies "Partition-aware compaction strategy (interaction between range splits and SpookyCompactor)" as an unsolved concern. Without coordination, all co-located partitions on a node may trigger compaction simultaneously after a bulk import, creating a thundering-herd I/O spike that degrades read latency and may exhaust disk I/O bandwidth. The `CompactionScheduler` bounds concurrent compactions to a configurable limit (default: 2), spreading I/O load over time.

**Round-robin default over priority-based:** The default `RoundRobinCompactionScheduler` ensures fairness: no single partition's compaction starves others. This prevents pathological scenarios where a hot partition continuously generates L0 files and monopolizes the compaction budget while cold partitions accumulate unbounded L0 depth (increasing read amplification). `SizeWeightedCompactionScheduler` is provided for workloads where reducing the largest compaction debt first minimizes overall read amplification.

**Concurrency limit over I/O bandwidth estimation:** Estimating actual I/O bandwidth and scheduling to a bandwidth target would require ongoing disk throughput measurement, which varies by storage backend (local NVMe vs S3 multipart upload). A simple concurrency limit is backend-agnostic and achievable without runtime I/O profiling. Operators can tune the limit based on their storage characteristics.

**Deregistration on ownership loss (R58):** When a partition transitions to DRAINING, any pending compaction for it is wasted work -- the departing owner will not serve reads from the compacted output. Immediate deregistration avoids consuming compaction slots for partitions this node is about to lose.

### What was ruled out

- **Automatic hotspot detection with dynamic splitting:** Requires load-based metrics collection, configurable detection thresholds, and interaction with the split/merge pipeline (F28). Adds runtime complexity with marginal benefit over deterministic prefix hashing for known-monotonic workloads.
- **Global IVF centroid routing (Milvus-style):** Would require a centralized centroid index decoupled from range partitioning. Incompatible with the co-located index topology chosen in the parent ADR. The `CentroidPartitionPruner` achieves similar skip behavior within the existing architecture.
- **Per-partition compaction thread pools:** Would allow true parallel compaction per partition but doubles or triples thread count. The shared scheduler with a concurrency limit achieves the same I/O bound with fewer resources.
- **Compaction-aware range splitting:** Splitting a range during compaction requires compaction to produce SSTables aligned to partition boundaries. This is a compaction-layer concern (interaction with SpookyCompactor) deferred to the compaction subsystem spec.
