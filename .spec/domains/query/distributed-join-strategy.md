---
{
  "id": "query.distributed-join-strategy",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "query"
  ],
  "requires": [
    "F04",
    "F11",
    "F21"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "distributed-join-execution",
    "scatter-backpressure",
    "scatter-gather-query-execution",
    "table-partitioning"
  ],
  "kb_refs": [
    "distributed-systems/query-execution/distributed-join-strategies",
    "systems/query-processing/lsm-join-algorithms",
    "systems/query-processing/lsm-join-snapshot-consistency"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F40"
  ]
}
---
# query.distributed-join-strategy — Distributed Join Strategy

## Requirements

### Scope

R1. The distributed join system must support exactly two join strategies: co-partitioned join and broadcast join. The strategy selector must model these as variants of a sealed strategy type with no other permitted subtypes.

R2. The strategy selector must reject any join that qualifies for neither co-partitioned nor broadcast execution by throwing an `UnsupportedJoinException` whose message identifies which checks failed and why (partition key mismatch, boundary misalignment, both sides exceeding broadcast budget). `UnsupportedJoinException` must extend `IllegalArgumentException` (unchecked). When the broadcast budget is zero or negative due to pool exhaustion, the message must state that the buffer pool has insufficient available capacity rather than that the broadcast candidate exceeds the budget.

R3. The distributed join system must support only equi-joins (equality predicates on join columns). A join request with a non-equality predicate must be rejected with an `UnsupportedJoinException` whose message states that only equi-joins are supported and identifies the offending predicate.

R4. The distributed join system must support only two-table joins. A join request specifying more than two tables must be rejected with an `UnsupportedJoinException` whose message states that multi-way joins are not supported.

### Strategy selection decision tree

R5. The strategy selector must evaluate co-partitioned eligibility before broadcast eligibility. The selector must never evaluate broadcast when co-partitioned conditions are met.

R6. The strategy selector must choose the co-partitioned strategy when all three conditions hold: (a) the join is an equi-join on the partition key column(s) of both tables, (b) both tables have the same partition count, and (c) every partition boundary pair aligns -- each partition's low key (inclusive) and high key (exclusive) on the left table equals the corresponding partition's low key and high key on the right table.

R7. The strategy selector must choose the broadcast strategy when co-partitioned conditions are not met and the estimated byte size of the smaller side is less than or equal to the broadcast budget (R18-R21).

R8. The strategy selector must reject the join (R2) when neither co-partitioned conditions are met nor the smaller side fits within the broadcast budget.

### Co-partitioned detection

R9. The co-partitioned check must compare partition key columns by schema-level column identity (name and type), not by reference equality. Two tables whose partition key columns have the same names and types in the same order must be considered to have matching partition keys. Column names must be non-null; the join handler must reject a partition key column with a null name with a NullPointerException. The partition key column order is defined by the table's partition key declaration order as reported by the table's PartitionMetadata. Column type comparison must include the full type descriptor (base type and all type parameters such as length, precision, and scale). VARCHAR(255) and VARCHAR(100) must be considered different types for co-partitioned eligibility.

R10. The co-partitioned check must compare partition boundaries using byte-level equality of the boundary key segments. Two boundaries must be considered aligned only when every corresponding pair of low key and high key segments are byte-for-byte identical. A zero-length key segment is a valid boundary value, representing the logical minimum (for low keys) or maximum (for high keys) of the key space. Byte-level comparison of two zero-length segments must evaluate as equal.

R11. The co-partitioned check must verify that both tables report identical partition counts. If one table reports a different partition count than the other, the co-partitioned check must fail.

### Fail-safe co-partitioned detection

R12. The co-partitioned check must treat unavailable or incomplete partition metadata as a detection failure. If the partition metadata for either table cannot be fully read (e.g., a table is mid-rebalance and metadata is in transition), the co-partitioned check must fail and the selector must fall through to the broadcast check.

R13. The co-partitioned check must never produce a false positive. If any individual boundary check is uncertain or inconsistent, the entire co-partitioned check must fail. A false negative (falling through to broadcast when co-partitioned would have been correct) is safe; a false positive (assuming co-partitioned when boundaries differ) would produce incorrect join results. The false-positive guarantee applies to the correctness of the comparison algorithm given the supplied metadata. The coordinator assumes partition metadata is truthful; if nodes are untrusted, the caller must validate partition metadata independently before invoking the join.

### Co-partitioned execution

R14. For a co-partitioned join, the coordinator must fan out one join request per matched partition pair. Each request must target the node holding that partition pair and must carry both table identifiers, the join key columns, and the snapshot sequence number (R28).

R15. Each partition-local join for a co-partitioned strategy must execute a sort-merge join exploiting the LSM sort order of the partition key. The local join must not require an additional sort pass when the join key is a prefix of the SSTable sort key.

R16. The coordinator must k-way merge the partition-local join results in ascending join key order and stream the merged result to the caller. The merge must preserve the global sort order across partitions. The coordinator must validate that each row returned from a partition-level join falls within the key range of the corresponding partition. Rows outside the expected range must be discarded and logged as a partition integrity violation.

R16a. The merge queue must have a bounded capacity equal to the number of outstanding credits. Each partition result occupies one slot; credit release (R48) frees a slot. If a partition result arrives when the queue is full, the transport layer must apply backpressure by not acknowledging the result until a slot is available.

### Broadcast budget

R17. The strategy selector must determine which of the two join sides is smaller by comparing estimated byte sizes. The smaller side must be selected as the broadcast candidate. When both sides have equal estimated byte sizes, the left table must be selected as the broadcast candidate.

R18. The broadcast budget must be computed as: `pool.available() / (active_queries * safety_factor)`, where `pool.available()` is the current available byte capacity of the coordinator's buffer pool, `active_queries` is the count of concurrently executing queries (including the current one), and `safety_factor` is a configurable positive integer. The coordinator must increment active_queries before computing the broadcast budget. The budget computation must observe active_queries >= 1 as an invariant; if the observed value is zero, the coordinator must throw an IllegalStateException. The active_queries counter must be decremented only after all pool memory for the query has been released, ensuring concurrent budget computations see a consistent view.

R19. The default safety factor must be 4. The safety factor must be configurable via the join builder.

R20. The join builder must reject a safety factor of zero with an `IllegalArgumentException` whose message states that the safety factor must be positive.

R21. The join builder must reject a negative safety factor with an `IllegalArgumentException` whose message states that the safety factor must be positive and identifies the invalid value.

R21a. The safety factor must be at least 2. The join builder must reject a safety factor of 1 with an `IllegalArgumentException` whose message states that the safety factor must be at least 2 to reserve capacity for join execution overhead (hash table construction, result materialization, merge heap).

### Broadcast size estimation

R22. The strategy selector must obtain an estimated byte size for the broadcast candidate before initiating a full scan. The estimate must be derived from partition metadata (SSTable-level size statistics), not from a full table scan.

R23. The strategy selector must compare the estimated byte size of the broadcast candidate against the broadcast budget (R18). The comparison must account for serialization overhead by subtracting a configurable overhead margin (default: 10% of the budget) from the budget before comparison. If the estimate exceeds the adjusted budget, the strategy selector must not attempt a broadcast and must proceed to rejection (R8). The broadcast budget check is advisory — it prevents obviously oversized broadcasts but does not guarantee allocation success. The actual broadcast payload allocation (R25-R26) must acquire capacity from the buffer pool with a hard check; if the pool cannot satisfy the allocation, the coordinator must abort and proceed to rejection (R2) rather than blocking.

R24. If the actual byte size of the broadcast payload exceeds the broadcast budget during serialization (the estimate was an undercount), the coordinator must abort the broadcast, release any partially serialized payload, and throw an `UnsupportedJoinException` whose message states that the broadcast payload exceeded the budget and reports the budget and actual size. The serialization process must check cumulative payload size against the broadcast budget after each row is serialized; if the cumulative size exceeds the budget, serialization must abort immediately without processing remaining rows.

R24a. During the broadcast scan (R25), the coordinator must track cumulative bytes read. If the cumulative bytes read exceed twice the estimated byte size (indicating a significant undercount), the coordinator must abort the scan early and proceed to rejection. This limits the I/O cost of a bad size estimate.

### Broadcast execution

R25. For a broadcast join, the coordinator must scan the smaller side completely via the scatter-gather proxy, reading at the join snapshot (R28).

R26. The coordinator must serialize the scanned smaller-side rows into a compact payload and distribute the payload to every node holding a partition of the larger side. The serialized broadcast payload must be treated as immutable after serialization completes. If the transport layer requires per-destination buffer state (e.g., position tracking), the coordinator must provide each destination with an independent read-only slice or duplicate of the payload buffer. The payload must not be mutated during distribution.

R26a. If broadcast payload distribution fails for some nodes (transport error or timeout), the coordinator must treat those nodes' partitions as unavailable (R31-R32). Nodes that successfully received the payload must proceed with their local probe, and their results must be included in the partial result. The coordinator must not re-send the payload to failed nodes.

R27. Each receiving node must deserialize the broadcast payload, build a local hash table keyed on the join columns, and probe the hash table against its local partition of the larger side. The probe must scan the local partition at the join snapshot (R28). Each receiving node must validate the broadcast payload size against the broadcast budget before deserialization. If the payload exceeds the budget, the node must reject it and report failure to the coordinator. During deserialization, the node must enforce a configurable maximum row count; if the deserialized row count exceeds the limit, deserialization must abort. The hash table must be allocated from the node's ArenaBufferPool.

### Cross-table snapshot consistency

R28. The coordinator must acquire a global sequence number before the join starts and before strategy selection (R5-R8). The partition metadata used for co-partitioned eligibility checking must be read at or after the snapshot sequence number. If partition metadata is read before the snapshot, a rebalance between the metadata read and the snapshot could invalidate the co-partitioned strategy. Both sides of the join -- whether scanned locally (co-partitioned) or remotely (broadcast) -- must read at this single sequence number. The global snapshot must prevent compaction from deleting any SSTables that contain data visible at the snapshot's sequence number; this is an invariant of the snapshot mechanism that F40 depends on.

R29. The global sequence number must be acquired exactly once per join execution. The coordinator must not acquire separate sequence numbers for the left and right sides.

R30. The coordinator must release the snapshot (allow GC watermark advancement past the acquired sequence number) after the join completes, whether by normal completion, error, or cancellation. The release must occur in a finally block.

### Partial results and partition unavailability

R31. If one or more partitions are unavailable (timeout or transport failure) during join execution, the coordinator must return a partial join result covering only the responding partitions. The result must include `PartialResultMetadata` (F04 R64, R73) indicating which partitions were unavailable.

R32. The coordinator must not fail the entire join due to a single partition's unavailability. The unavailable partition must be recorded in `PartialResultMetadata`, and the coordinator must continue processing results from the remaining partitions.

R33. If all partitions are unavailable, the coordinator must return an empty result with `PartialResultMetadata` listing all partitions as unavailable. The coordinator must not throw an exception for total partition unavailability.

### Input validation

R34. The join handler must reject a null left table reference with a `NullPointerException`.

R35. The join handler must reject a null right table reference with a `NullPointerException`.

R36. The join handler must reject a null join predicate with a `NullPointerException`.

R37. The join handler must reject a join predicate that references a column not present in the left table's schema with an `IllegalArgumentException` whose message identifies the missing column name and the table. The join predicate must specify a left column and a right column explicitly. The join handler must not attempt to resolve unqualified column names by searching both schemas. Each column reference in the predicate must be bound to exactly one table side.

R38. The join handler must reject a join predicate that references a column not present in the right table's schema with an `IllegalArgumentException` whose message identifies the missing column name and the table.

R39. The join handler must reject a null partition list for either table with a `NullPointerException`. An empty partition list (all partitions pruned) for either table must return an empty result immediately without dispatching any join requests. The empty-partition-list check must execute before strategy selection; if either side has an empty partition list, the join returns an empty result without invoking the strategy selector. A table with zero partitions must report an estimated byte size of zero for R22.

R39a. The coordinator must enforce a maximum partition count per join. If the total partition count across both sides exceeds a configurable limit (default: 10,000), the join must be rejected with an `UnsupportedJoinException` whose message identifies the partition count and the limit.

### Concurrency

R40. Strategy selection must be coordinator-local and single-threaded per query. The selector must not acquire any engine-wide or table-wide locks during selection.

R41. Multiple concurrent join queries must not interfere with each other. Each query must maintain its own strategy selection state, snapshot reference, and merge state. No join state may be shared across queries.

R42. The broadcast budget computation (R18) must read `pool.available()` and `active_queries` atomically with respect to each other. A budget computed from a stale `active_queries` count that is lower than the true count could over-allocate memory.

### Resource lifecycle

R43. The coordinator must release all join state (merge heap, per-partition tracking, broadcast payload reference) when the join completes, whether by normal completion, error, or cancellation. Release must occur in a finally block. The coordinator must use a deferred-exception pattern for cleanup: snapshot release (R30), payload release (R45), and join state release must each execute in their own try block within the finally, accumulating suppressed exceptions. The snapshot release must execute last (outermost finally) to ensure it is never skipped by a cleanup failure. Join state structures must be released regardless of whether any partition-level results were received; the release logic must be safe to call on empty or uninitialized structures (null-safe or idempotent). Thread interruption during join execution must be treated as cancellation — the coordinator must restore the interrupt flag, trigger the cancellation cleanup path (R46), and throw an IOException wrapping the InterruptedException.

R44. For a broadcast join, each receiving node must release the deserialized hash table memory after the local probe completes. The hash table must not outlive the join execution on that node. Release must occur in a finally block.

R45. For a broadcast join, the coordinator must release the serialized broadcast payload after distribution to all receiving nodes completes. If distribution fails partway through, the coordinator must release the payload in the error handler.

R46. The coordinator must cancel all outstanding partition-level join transport futures when the join query is cancelled or errors. Outstanding futures must be completed exceptionally, and held credits must be released (F21 R27). On join completion (normal, error, or cancellation), the coordinator must also release credits for all partition-level results that have been received but not yet consumed from the merge queue, in addition to credits released via R48 for consumed results.

### Credit budget integration

R47. Each partition-level join request (co-partitioned: one per partition pair; broadcast: one per large-side partition) must consume one credit from the query's credit budget (F21 R2-R3). The coordinator must not dispatch a partition-level join request unless a credit is available.

R48. The coordinator must release consumed credits as partition-level join results are consumed from the merge queue and must re-issue demand via `Subscription.request(1)` (F21 R4) to receive the next result page from that partition.

### Out of scope

R49. The distributed join system must not support shuffle or repartition joins. A request that would require shuffle must be rejected via the standard rejection path (R2).

R50. The distributed join system must not support semi-join reduction. Join selectivity estimation is not performed during strategy selection.

R51. The distributed join system must not integrate with a query planner. Strategy selection is invoked directly by the caller, not by an optimizer.

## Cross-References

- ADR: .decisions/distributed-join-execution/adr.md
- ADR: .decisions/scatter-backpressure/adr.md
- ADR: .decisions/scatter-gather-query-execution/adr.md
- ADR: .decisions/table-partitioning/adr.md
- KB: .kb/distributed-systems/query-execution/distributed-join-strategies.md
- KB: .kb/systems/query-processing/lsm-join-algorithms.md
- KB: .kb/systems/query-processing/lsm-join-snapshot-consistency.md
- Spec: F04 R62-R67 (scatter-gather execution, ordering preservation, PartialResultMetadata)
- Spec: F11 (table partitioning -- partition key, boundaries, metadata)
- Spec: F21 R1-R10 (credit-based flow control, Flow API integration)

---

## Design Narrative

### Intent

Define the distributed join execution protocol for the scatter-gather proxy. The strategy selector inspects partition metadata at query time and chooses between co-partitioned join (zero network cost when both tables share identical partitioning on the join key) and broadcast join (serialize the smaller side, distribute to all nodes). Joins that qualify for neither strategy are rejected with a diagnostic message. Shuffle and semi-join are deferred per the ADR.

### Why two strategies, not one

Co-partitioned-only would reject all non-partition-key joins, including the common reference-table lookup pattern (small configuration or dimension tables joined to large fact tables). Broadcast-only would miss the zero-cost opportunity when tables are already co-partitioned. The two-tier selector covers the vast majority of real-world workloads: co-partitioned for co-designed analytics tables, broadcast for lookup enrichment.

The ADR evaluated four candidates: co-partitioned only, broadcast only, full strategy framework (including shuffle and semi-join), and the two-tier composite. The composite scored highest on the constraints (ArenaBufferPool budget, cross-table snapshot consistency, composability with the scatter-gather proxy) because it delivers value without introducing shuffle's temporary SSTables, spill-to-disk, and crash-recovery complexity.

### Why co-partitioned is checked first

Co-partitioned join has zero network cost and zero extra memory cost -- it is strictly superior when applicable. Checking it first avoids needlessly computing a broadcast budget or scanning a small side when the join can execute locally. The ADR's decision tree mandates this ordering.

### Why fail-safe detection

A false positive in co-partitioned detection -- assuming boundaries align when they do not -- produces incorrect join results (rows matched against the wrong partition). A false negative -- falling through to broadcast when co-partitioned would have worked -- produces correct results at higher cost. The asymmetry demands conservatism: if metadata is unavailable (mid-rebalance, stale cache), the detector must fail the co-partitioned check rather than guess.

This is not hypothetical. During a rebalance, partition boundaries are in transition. A stale metadata cache could report old boundaries that no longer match. The fail-safe design ensures correctness at the cost of occasionally choosing broadcast when co-partitioned would have been valid.

### Why dynamic broadcast budget

A static broadcast threshold (e.g., "10 MB") does not account for concurrent load. If 10 queries each broadcast 10 MB simultaneously, the coordinator uses 100 MB of pool memory for broadcast payloads alone. The dynamic formula `pool.available() / (active_queries * safety_factor)` ensures the broadcast budget shrinks as contention increases and grows when the system is idle. The safety factor (default 4) provides headroom for concurrent WAL appends, compaction reads, and SSTable block cache allocations that also consume pool memory.

The ADR specifies this formula and the default safety factor. The spec makes the safety factor configurable via the join builder so operators can tune it for their workload profile.

### Why estimate-then-scan for broadcast

Scanning the smaller side before checking whether it fits would be wasteful when it does not fit -- the scan cost is paid and then discarded. The spec requires an estimate check (R22-R23) before scan. The estimate comes from SSTable-level size statistics available in partition metadata, which are cheap to read.

The estimate may undercount (tombstones inflate apparent size; recent writes not yet flushed are not reflected in SSTable stats). R24 provides a safety net: if the actual serialized size exceeds the budget during scan, the coordinator aborts and throws `UnsupportedJoinException`. This prevents a slow memory overrun from an inaccurate estimate.

### Why sort-merge for co-partitioned and hash join for broadcast

Co-partitioned joins exploit the LSM sort order. Both tables are partitioned on the join key, and LSM SSTables are sorted by key. Sort-merge join on a pre-sorted input is O(N+M) with no additional sort pass -- the cheapest possible local algorithm. The KB (`lsm-join-algorithms.md`) confirms sort-merge is optimal when the join key is a prefix of the SSTable sort key.

Broadcast joins do not have a guaranteed sort order on the broadcast side (it was scanned and serialized from potentially many partitions). Building a hash table from the broadcast payload and probing from the large side is O(N+M) with O(S) memory for the hash table, where S is the broadcast side. The KB confirms hash join is optimal when one side is small and in-memory.

### Why a single global snapshot

The KB (`lsm-join-snapshot-consistency.md`) establishes that per-table sequence numbers are insufficient for cross-table joins. Two tables with independent sequence spaces can produce inconsistent reads -- the join sees a row from table A that references a row in table B that has been deleted or modified at the other table's sequence number.

A single global sequence number acquired before the join starts ensures both sides read at the same logical point in time. Tombstones, deletes, and concurrent writes with higher sequence numbers are invisible to the join. This is the standard approach used by CockroachDB, TiDB, and YugabyteDB.

### Why snapshot release in a finally block

If the snapshot is not released, the GC watermark cannot advance past it, and old versions accumulate. A long-lived pinned snapshot degrades compaction effectiveness and inflates space amplification (the feedback loop described in the snapshot consistency KB). Releasing in a finally block guarantees release even on exception or cancellation.

### Why broadcast payload cleanup is specified separately

The broadcast payload is a large in-memory allocation (up to the broadcast budget). If the coordinator crashes or the join is cancelled after serialization but before distribution, this memory is leaked until GC reclaims it. The spec explicitly requires cleanup in the error handler (R45) and on each receiving node after probe completion (R44) to ensure deterministic resource release.

### What was ruled out

- **Shuffle/repartition joins:** Require temporary SSTables, disk space management, and crash-recovery cleanup. Deferred per ADR. The sealed strategy type supports future extension.
- **Semi-join reduction:** Requires selectivity estimation (cardinality statistics, histograms). Deferred per ADR.
- **Query planner integration:** The strategy selector is invoked directly. A planner layer above the selector is a separate concern, deferred per ADR.
- **Multi-way joins:** Require join ordering optimization and a cost-based planner. The spec restricts to two-table joins (R4).
- **Non-equi joins on co-partitioned path:** Range or theta joins on co-partitioned tables cannot exploit the partition alignment guarantee -- different rows would match across partition boundaries. Only equi-joins qualify for co-partitioned execution.
- **Spill-to-disk broadcast:** If the broadcast payload exceeds memory, the spec rejects rather than spilling. Spill-to-disk broadcast is a complexity bridge to shuffle and is deferred with it.

### Adversarial falsification (Pass 2 — 2026-04-15)

30 findings from structured adversarial review (all mandatory probes). All promoted.
Critical: snapshot-vs-strategy-selection race (R28 amended). High: active_queries=0
div-by-zero (R18 amended), safety_factor=1 (R21a), serialization overhead (R23 amended),
cleanup failure suppression (R43 amended), credit leak for unconsumed results (R46
amended), pool.available stale read (R23 amended), partial broadcast distribution (R26a),
snapshot-compaction invariant (R28 amended), broadcast buffer sharing (R26 amended),
payload deserialization DoS (R27 amended), bad-estimate early abort (R24a). Medium:
zero-partition ordering (R39 amended), equal-size tiebreaker (R17 amended), serialization
granularity (R24 amended), partition count bound (R39a), pool-exhaustion error message
(R2 amended), hash table ArenaBufferPool (R27 amended), counter decrement ordering (R18
amended), exception hierarchy (R2 amended), unqualified column refs (R37 amended),
InterruptedException (R43 amended), type comparison granularity (R9 amended), merge
queue backpressure (R16a), metadata trust (R13 amended), partition key range validation
(R16 amended). Low: null column name (R9 amended), zero-length boundary (R10 amended),
eager/lazy merge heap (R43 amended), column order source (R9 amended).

The following degenerate cases were also considered during initial spec authoring:

1. **Both tables empty (zero rows).** Co-partitioned detection depends on metadata (partition key, count, boundaries), not row count. If metadata matches, co-partitioned is selected. Each partition-local sort-merge produces zero results. The coordinator emits an empty result. Broadcast path: estimated size is zero, which is within budget. Scan produces zero rows. Hash table is empty. Probe produces zero results. Both paths are correct.

2. **One table empty, one non-empty.** Co-partitioned: correct -- sort-merge of an empty side against a non-empty side produces zero results. Broadcast: the empty table is the smaller side (0 bytes, within budget). Hash table is empty. Probe finds no matches. Correct.

3. **Identical tables (self-join).** Both sides have identical partition metadata. Co-partitioned check passes. Each partition joins against itself. Sort-merge produces the expected self-join output. No special-casing needed.

4. **Single partition per table.** Partition count = 1, one boundary pair to check. Co-partitioned check is trivially correct. Broadcast check operates normally. No degenerate merge (single partition, no k-way merge needed).

5. **Safety factor of 1.** Minimum valid value. Broadcast budget = pool.available() / active_queries. No safety margin for concurrent WAL, compaction, or cache allocations. Legal but aggressive -- the operator accepts the risk. The spec permits this.

6. **active_queries = 0 at budget computation.** If the current query is the only query and active_queries includes itself, active_queries = 1 (minimum). If active_queries is read before the current query increments it, the denominator is 0, causing division by zero. R42 requires atomic read of both values. The implementation must ensure active_queries is at least 1 (includes the current query) at computation time. The requirement is implicit in the formula: "active_queries" is defined as "including the current one" in R18.

7. **Broadcast estimate undercounts by a large factor.** R24 aborts during serialization if actual size exceeds budget. The abort releases partial payload memory. The caller receives an `UnsupportedJoinException`, not an OOM.

8. **Broadcast estimate overcounts (false rejection).** The estimated size exceeds the budget, but the actual data would have fit. The join is rejected (R8). This is a false negative -- safe but suboptimal. The caller can retry with a larger safety factor or restructure the query. No incorrect results.

9. **Mid-rebalance metadata for one table.** R12 treats incomplete metadata as a co-partitioned detection failure. The selector falls through to broadcast. If the table being rebalanced is the smaller side and fits in budget, broadcast proceeds. If not, the join is rejected. No incorrect results from stale metadata.

10. **Concurrent rebalance completes between co-partitioned check and execution.** The co-partitioned check saw aligned boundaries, but by the time fan-out occurs, a rebalance has changed boundaries. The partition-local join executes against the new partition layout. If rows have migrated, the local join misses them. The snapshot binding (R28) prevents seeing writes from the rebalance itself, but row migration during rebalance changes which rows are on which node. This is a known limitation of optimistic metadata reads. The scatter-gather proxy's partition map refresh (F04) handles stale routing. Partition-unavailability (R31) covers cases where the old partition no longer exists.

11. **Both sides exactly at broadcast budget.** R7 requires estimated size "less than or equal to" budget. The smaller side fits. The other side is not broadcast. Correct.

12. **Both sides identical size, both above broadcast budget.** Neither qualifies for broadcast. Co-partitioned check was already attempted and failed (otherwise broadcast would not be evaluated). Join is rejected (R8). Correct.

13. **Join on non-partition-key column, both tables co-partitioned on a different key.** Co-partitioned check fails at R6(a) -- the join columns do not match the partition key columns. Falls through to broadcast. If one side fits, broadcast proceeds. The hash table is keyed on the join column, not the partition key. Correct.

14. **Broadcast payload distribution fails to some nodes.** Nodes that received the payload execute the probe. Nodes that did not are treated as unavailable partitions (R31-R32). PartialResultMetadata reports the gap. Coordinator does not fail.

15. **Snapshot GC watermark advances past join snapshot during execution.** The snapshot was acquired (R28) and is held until completion (R30). While held, the GC watermark must not advance past it (this is a property of the snapshot manager, specified in the snapshot consistency KB). If a bug allows advancement, partition-local reads may encounter missing versions. The spec delegates correctness of snapshot pinning to the snapshot manager contract.

16. **Null join columns in data (null key values).** Equi-join semantics: NULL = NULL is false in SQL. Rows with null join keys produce no matches. The local join algorithm (sort-merge or hash join) must respect this. The spec does not override SQL null semantics.

17. **Extremely large number of partitions (e.g., 10,000).** Co-partitioned: 10,000 boundary pairs to check. Linear scan is O(N) and fast for metadata comparison. 10,000 fan-out requests consume 10,000 credits. Credit budget (F21) bounds concurrency. Broadcast: payload distributed to 10,000 nodes. Network cost is O(S * 10,000). If S is within budget, this is feasible but expensive. No correctness issue, but operators should be aware of the amplification.

18. **Double cleanup of broadcast payload.** R45 releases payload after distribution. If a receiving node's probe fails and triggers coordinator error handling, the coordinator must not release the payload twice. Idempotent cleanup (check-before-release or use-once reference) is an implementation concern; the spec requires release "after distribution completes" and "in the error handler," which are mutually exclusive paths through a finally block.
