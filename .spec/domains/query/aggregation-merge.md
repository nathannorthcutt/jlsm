---
{
  "id": "query.aggregation-merge",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "query"
  ],
  "requires": [
    "engine.clustering",
    "transport.scatter-gather-flow-control"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "aggregation-query-merge",
    "scatter-backpressure",
    "scatter-gather-query-execution"
  ],
  "kb_refs": [
    "distributed-systems/query-execution/distributed-join-strategies",
    "distributed-systems/query-execution/distributed-scan-cursors"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F38"
  ]
}
---
# query.aggregation-merge — Aggregation Query Merge

## Requirements

### Scope

R1. The aggregation merge must support exactly five aggregate function types: COUNT, SUM, MIN, MAX, and AVG. Any aggregate function type not in this set must be rejected with an `UnsupportedOperationException` whose message identifies the unsupported type.

R2. The aggregation merge must support two execution modes: scalar aggregation (no GROUP BY clause) and grouped aggregation (one or more GROUP BY columns). The execution mode must be determined from the query before dispatch.

R3. The aggregation merge must reject a query requesting DISTINCT aggregates (e.g., COUNT(DISTINCT), SUM(DISTINCT)) with an `UnsupportedOperationException` whose message states that DISTINCT aggregates are not supported and identifies the offending aggregate expression.

R4. The aggregation merge must reject a query requesting holistic aggregates (MEDIAN, PERCENTILE, MODE) with an `UnsupportedOperationException` whose message states that holistic aggregates are not supported and identifies the offending aggregate expression.

### Partial computation (partition side)

R5. Each partition must compute partial aggregates locally from its own data before sending results to the coordinator. The partition must not send raw rows to the coordinator for aggregation queries.

R6. For COUNT, each partition must compute the count of matching rows as a long value. The partial count must never be negative.

R7. For SUM, each partition must compute the sum of the target column's values. The partial sum must preserve the numeric type of the column: long for integer-typed columns, double for floating-point-typed columns.

R7a. For long-typed SUM, each partition must detect arithmetic overflow during partial sum computation. If the partial sum overflows, the partition must promote its accumulator to double and send the partial sum as a double value. The coordinator must handle mixed long/double partial sums by promoting to double for the merge.

R8. For MIN, each partition must compute the minimum value of the target column among matching rows. If the partition contains no matching rows, the partial MIN must be absent (null), not a sentinel value.

R9. For MAX, each partition must compute the maximum value of the target column among matching rows. If the partition contains no matching rows, the partial MAX must be absent (null), not a sentinel value.

R10. For AVG, each partition must compute and send a (sum, count) pair -- the sum of the target column and the count of non-null values. The partition must not compute the average itself.

R10a. The partition-side sum in an AVG (sum, count) pair must apply the same overflow promotion rules as SUM (R7a). If the sum overflows during partial computation, the partition must promote to double and send the partial sum as a double value.

R11. For scalar aggregation (no GROUP BY), each partition must produce exactly one partial result tuple containing one partial value per aggregate function in the query.

R12. For grouped aggregation, each partition must produce one partial result tuple per distinct group key observed in that partition's data. Each tuple must contain the group key columns and one partial value per aggregate function.

R13. A partition that contains no matching rows for a scalar aggregation must return a partial result with COUNT = 0, SUM = 0 (or 0.0 for floating-point), and absent (null) for MIN and MAX. For AVG, the partial must be (sum=0, count=0).

R14. A partition that contains no matching rows for a grouped aggregation must return an empty partial result set (zero tuples).

### Merge protocol (coordinator side)

R15. The coordinator must merge COUNT partials by summing all partial counts. The merged COUNT must equal the total number of matching rows across all responding partitions.

R15a. If the merged COUNT overflows Long.MAX_VALUE, the coordinator must throw an ArithmeticException. The system has a maximum addressable row count of Long.MAX_VALUE across all partitions for a single query.

R16. The coordinator must merge SUM partials by summing all partial sums. For long-typed sums, the merge must use long addition. For double-typed sums, the merge must use double addition.

R17. The coordinator must merge MIN partials by taking the minimum across all non-null partial MIN values. If all partials are null (no partition had matching rows), the merged MIN must be null. For double-typed MIN, NaN values in partials must be treated as absent (skipped). If all non-null partial values are NaN, the merged MIN must be null. The coordinator must use `Double.compare()` ordering, which distinguishes -0.0 < +0.0.

R18. The coordinator must merge MAX partials by taking the maximum across all non-null partial MAX values. If all partials are null (no partition had matching rows), the merged MAX must be null. For double-typed MAX, NaN values in partials must be treated as absent (skipped). If all non-null partial values are NaN, the merged MAX must be null. The coordinator must use `Double.compare()` ordering.

R19. The coordinator must compute AVG by dividing the total sum (sum of all partial sums) by the total count (sum of all partial counts). If the total count is zero, the merged AVG must be null, not a division-by-zero error.

R19a. AVG computation must propagate IEEE 754 semantics during sum accumulation (same rules as R55). If the total sum is NaN or the division produces NaN, the merged AVG must be NaN, not null. If the total sum is positive or negative infinity, standard IEEE 754 division rules apply.

R20. For long-typed AVG, the coordinator must compute the result as a double (floating-point division of total sum by total count). The result type of AVG must always be double, regardless of the column's storage type.

R21. For scalar aggregation, the coordinator must produce exactly one final result tuple.

R22. For grouped aggregation, the coordinator must merge partials by group key. For each distinct group key across all partitions, the coordinator must apply the merge functions (R15-R20) to the partials from that group key. The final result must contain exactly one tuple per distinct group key.

R23. The coordinator must use value equality (not reference identity) to match group keys across partitions. Two group keys from different partitions that have identical column values must be merged into the same group.

R23a. For floating-point group key columns, the coordinator must use `Double.compare()` equality (where `Double.compare(a, b) == 0` means same group). This implies -0.0 and +0.0 are the same group, and NaN equals NaN for grouping purposes (consistent with SQL GROUP BY semantics where NaN values are grouped together). The hash map key representation must be consistent with this equality definition.

### Cardinality guard

R24. The coordinator must compute a cardinality threshold before accumulating GROUP BY partials. The threshold must be: `credit_budget / (partition_count * per_group_bytes)`, where `credit_budget` is the byte budget available to the current query from the credit-based flow control system (F21 R2-R3), `partition_count` is the number of partitions being queried, and `per_group_bytes` is the estimated memory cost per group entry. The division must use long arithmetic, and the guard factor multiplication (R26) must use double arithmetic to avoid intermediate overflow. The threshold computation is only performed for grouped aggregation queries dispatched to one or more partitions. When partition_count is zero (R42 early return), the threshold is not computed.

R25. The `per_group_bytes` estimate must account for the group key size and one partial value per aggregate function. The estimate must be configurable via the query builder with a default value.

R26. A configurable guard threshold factor (default: 0.5, range: (0.0, 1.0]) must scale the cardinality threshold. The effective threshold is `floor(threshold * guard_threshold_factor)`. The guard threshold factor must reject values that are NaN, infinite, or outside the range (0.0, 1.0] with an `IllegalArgumentException`.

R27. If the estimated group count (from the first arriving partition's partial result count, extrapolated by partition count) exceeds the effective cardinality threshold, the coordinator must abandon partial-aggregate accumulation and fall back to sorted-run merge aggregation.

R27a. The cardinality guard must be re-evaluated on each arriving partition's partial results, not only the first. If any individual partition's partial result count (without extrapolation) exceeds the effective cardinality threshold, the coordinator must trigger the sorted-run merge fallback. This prevents a low-cardinality first partition from masking a later high-cardinality partition.

R28. The sorted-run merge fallback must request partitions to return sorted partial streams (one entry per group, sorted by group key). The coordinator must perform a streaming k-way merge across partition streams, aggregating groups as they pass through the merge. The streaming merge must hold at most O(partition_count) entries in memory at any time.

R28a. The coordinator's k-way merge must validate that each partition stream is sorted by group key in ascending order. If an out-of-order entry is detected in a partition's stream, the coordinator must treat that partition as an error (R38) and exclude its remaining data from the merge.

R29. The fallback decision must be made before the coordinator has accumulated more than one partition's partials in the hash map. If the first partition's result triggers the guard, the coordinator must switch to streaming without discarding work -- the already-received partial must be fed into the streaming merge.

R30. When the cardinality guard is triggered, the coordinator must log a message at DEBUG level identifying the query, the estimated cardinality, and the effective threshold.

### GROUP BY ordering

R31. The final result of a grouped aggregation must be ordered by group key columns in ascending order. This ordering must hold for both the hash-map merge path and the sorted-run merge fallback path.

R31a. For multi-column group keys, the ascending order must be lexicographic: compare by the first GROUP BY column, then by the second column for ties, and so on. Each column must be compared using its natural ordering (Comparable for typed columns, with floating-point columns using `Double.compare()` semantics per R23a).

R32. For the hash-map merge path, the coordinator must sort the final result by group key after merging all partials. For the sorted-run merge fallback, the k-way merge naturally produces sorted output.

### Error handling

R33. If one or more partitions are unavailable (timeout or transport failure), the coordinator must return a partial aggregation result covering only the responding partitions. The result must include `PartialResultMetadata` (F04 R64, R73) indicating which partitions were unavailable.

R34. The partial aggregation result must be clearly distinguishable from a complete result. The `PartialResultMetadata.isComplete()` must return false when any partition did not contribute its partial.

R35. A partial COUNT result must reflect only the rows from responding partitions. The coordinator must not extrapolate or estimate the missing partitions' contributions.

R36. A partial MIN or MAX result may be incorrect (the true minimum or maximum may reside on an unavailable partition). The `PartialResultMetadata` must expose sufficient information for the caller to determine which partitions are missing so the caller can decide whether to trust the result.

R37. If all partitions are unavailable, the coordinator must return an empty result with `PartialResultMetadata` listing all partitions as unavailable. The coordinator must not throw an exception for total unavailability of partitions -- it must return metadata that the caller can inspect.

R38. If a partition returns an error (not a timeout, but an explicit error response), the coordinator must treat that partition as unavailable and include it in `PartialResultMetadata`. The coordinator must not fail the entire query due to a single partition error.

R38a. If a partition sends partial data before returning an error, the coordinator must discard that partition's already-accumulated partial data from the merge state. The final result must not include partial contributions from error partitions. The `PartialResultMetadata` entry for that partition must indicate it was excluded due to an error.

### Input validation

R39. The aggregation merge must reject a null query predicate with a `NullPointerException`.

R40. The aggregation merge must reject an empty aggregate function list (a query that specifies GROUP BY but no aggregate functions, or no aggregate functions at all) with an `IllegalArgumentException`.

R41. The aggregation merge must reject a null aggregate function entry within the function list with a `NullPointerException`.

R42. The aggregation merge must reject a null partition list with a `NullPointerException`. An empty partition list (all partitions pruned) must return an empty result immediately without dispatching any requests.

R43. The aggregation merge must reject a SUM or AVG applied to a non-numeric column type with an `IllegalArgumentException` identifying the column name and its type.

R44. The aggregation merge must reject a MIN or MAX applied to a column type that does not implement `Comparable` ordering with an `IllegalArgumentException` identifying the column name and its type.

R44a. The aggregation merge must capture column type information at validation time. Subsequent mutations to schema or column metadata objects must not affect the validated query's type checks.

### Concurrency

R45. The aggregation merge must execute coordinator-side merging on a single thread per query. Partial results from different partitions arrive concurrently via the Flow API, but the merge accumulation must be single-threaded (no concurrent writes to the merge hash map or running accumulators).

R45a. The coordinator must serialize all partition `onNext` deliveries through a single-threaded executor or queue before merge accumulation. Concurrent Flow API callbacks from multiple partition publishers must not directly invoke merge logic or cardinality guard evaluation. The serialization mechanism must guarantee that at most one partition's partial results are being accumulated at any time.

R46. Multiple concurrent aggregation queries must not interfere with each other. Each query must have its own merge state (accumulators, group map, cardinality guard state). No merge state may be shared across queries.

R47. The aggregation merge must not hold any engine-wide or table-wide locks during merge accumulation. The merge is coordinator-local computation, not a shared-state mutation.

### Integration with credit-based backpressure

R48. Partial aggregate results from each partition must count against the query's credit budget (F21 R2). Each partial result page consumes one credit. The coordinator must issue demand signals for partial results through the standard `Subscription.request()` mechanism.

R49. For scalar aggregation, each partition's partial fits in a single page (one tuple). The coordinator must issue one credit per partition for scalar aggregation queries.

R50. For grouped aggregation where the cardinality guard does NOT trigger (hash-map merge path), the coordinator must buffer all partials in the merge hash map. The total memory consumed by the hash map must not exceed the query's credit budget. The cardinality guard (R24-R29) enforces this bound.

R51. For grouped aggregation where the cardinality guard triggers (sorted-run merge fallback), the coordinator must process partial streams page-by-page using the standard credit-based paging mechanism (F21 R4, R7). The coordinator holds at most one page per partition at any time.

### Resource lifecycle

R52. The coordinator must release all merge state (group hash map, running accumulators, intermediate buffers) and cancel all outstanding partition subscriptions when the query completes, whether by normal completion, error, or cancellation. Release and cancellation must occur in a finally block. Outstanding partition subscriptions that are not cancelled will continue consuming credits from the query's budget.

R53. If the coordinator switches from hash-map merge to sorted-run merge fallback (R29), the hash map accumulated before the switch must be released after the already-received entries are fed into the streaming merge, or immediately if the feeding fails. The release must occur in a finally block wrapping the feed operation.

### SUM overflow

R54. For long-typed SUM, the coordinator merge must detect arithmetic overflow. If the sum of two long partials overflows, the coordinator must promote the accumulator to double and continue the merge. The final SUM result type must reflect the promotion (double instead of long).

R55. For double-typed SUM, the coordinator merge must propagate IEEE 754 semantics. If any partial sum is NaN, the merged sum must be NaN. If any partial sum is positive or negative infinity, standard IEEE 754 addition rules apply.

R54a. The coordinator must apply overflow detection when summing AVG partial sums, using the same promotion rule as SUM (R54). If the sum of AVG partial sums overflows long, the coordinator must promote to double and continue. The AVG count accumulation must use `Math.addExact()` — count overflow must throw ArithmeticException (same bound as R15a).

### Partition response validation

R6a. The coordinator must validate that partial COUNT values received from partitions are non-negative. A partition that sends a negative COUNT must be treated as an error (R38). The coordinator must validate that AVG partials contain both sum and count components; a malformed AVG partial must be treated as an error (R38).

## Cross-References

- ADR: .decisions/aggregation-query-merge/adr.md
- ADR: .decisions/scatter-backpressure/adr.md
- ADR: .decisions/scatter-gather-query-execution/adr.md
- Spec: F04 R62-R67 (scatter-gather execution, ordering preservation)
- Spec: F21 R1-R10 (credit-based flow control, Flow API integration)
- Spec: F30 R39 (query with partition pruning)

---

## Design Narrative

### Intent

Define the coordinator-side merge protocol for aggregation queries (COUNT, SUM, MIN, MAX, AVG, GROUP BY) in the scatter-gather proxy. Partitions compute partial aggregates locally, the coordinator merges them -- reducing network traffic from O(total_rows) to O(groups). A cardinality guard prevents high-cardinality GROUP BY from exceeding the memory budget by falling back to streaming k-way merge.

### Why two-phase partial aggregation

The ADR (aggregation-query-merge) evaluated three approaches: full materialization at coordinator, pure streaming merge, and two-phase partial aggregation. Two-phase wins on all three constraints:

1. **Bounded memory.** For scalar aggregates and low-cardinality GROUP BY, the coordinator holds O(groups) state, not O(rows). At 1000 partitions with 1000 groups, the coordinator holds 1000 merge entries -- trivially within credit budget.

2. **Minimal network traffic.** Partials (one tuple per group per partition) instead of full result sets. Reduces coordinator input from O(rows) to O(groups * partitions).

3. **Table interface transparency.** The aggregation merge is internal to the proxy. Callers receive a standard result set -- they cannot distinguish a local aggregation from a distributed one.

Full materialization is disqualified: it requires buffering all rows at the coordinator, violating ArenaBufferPool credit budget for any non-trivial dataset. Pure streaming is viable but sends O(total_rows) through the coordinator even when the result is O(groups) -- wasteful by a factor of rows_per_group.

### Why the cardinality guard

Two-phase partial aggregation degrades when GROUP BY cardinality approaches the total row count. At the extreme (each row is its own group), the coordinator hash map holds as many entries as there are rows -- equivalent to full materialization. The cardinality guard detects this before it happens.

The guard estimates cardinality from the first partition's result, extrapolated by partition count. This is a heuristic -- partitions may have skewed group distributions -- but it triggers conservatively (default threshold factor 0.5 means the guard activates at half the memory limit). The downside of a false positive is the sorted-run merge path, which is correct but slower than hash-map merge for moderate cardinality. The downside of a false negative is OOM. Conservative is correct.

The sorted-run merge fallback exploits the fact that GROUP BY results can be sorted by group key at each partition, enabling a streaming k-way merge at the coordinator with O(partition_count) memory. This is the same merge mechanism used for non-aggregation scatter-gather queries (F04 R67), reused for aggregation.

### Why AVG is decomposed into SUM/COUNT

AVG is not an algebraic aggregate -- `avg(A union B)` does not equal `avg(avg(A), avg(B))` in general. The standard decomposition is: each partition computes (sum, count), the coordinator computes total_sum / total_count. This produces an exact result, unlike averaging averages.

This decomposition is well-known (CockroachDB, TiDB, YugabyteDB all use it) and the ADR explicitly mandates it.

### Why SUM overflow promotes to double

Long overflow during merge is a real possibility: if each partition holds rows with values near Long.MAX_VALUE, summing their partials can overflow. Throwing an exception would fail the query. Promoting to double loses precision for large integers but preserves a usable result. This follows the principle of least surprise -- SQL databases typically promote integer overflow to a wider type or decimal, not to an error.

The alternative -- using BigInteger or BigDecimal -- would introduce heap allocation in the merge hot path and is not justified until precision requirements are explicitly specified. The spec documents the promotion so callers know the result type may differ from the column type.

### What was ruled out

- **Approximate aggregates (HyperLogLog, t-digest):** Out of scope per ADR. DISTINCT and holistic aggregates are deferred.
- **Parallel merge accumulation:** The merge is coordinator-local and processes O(groups) entries, not O(rows). Single-threaded merge is simpler and fast enough. The bottleneck is network I/O, not merge computation.
- **Adaptive cardinality estimation:** The guard uses a simple first-partition extrapolation. Dynamic estimation (sampling, histograms) is deferred per ADR.
- **HAVING clause evaluation:** HAVING filters groups after aggregation. It is a post-processing step that applies to the merged result and does not affect the merge protocol. It is handled by the query layer, not the aggregation merge.

### Adversarial falsification (Pass 2 — 2026-04-15)

22 findings from structured adversarial review (all mandatory probes). All promoted.
Critical: partition-side SUM/AVG overflow (R7a, R10a, R54a). High: NaN in MIN/MAX
(R17/R18 amended), NaN/infinity in guard factor (R26 amended), subscription leak on
cancel (R52 amended), onNext serialization (R45a), per-partition guard re-eval (R27a),
error partition data discard (R38a), float group key equality (R23a). Medium/Low:
COUNT overflow (R15a), IEEE 754 in AVG (R19a), threshold div-by-zero (R24 amended),
feed-fail leak (R53 amended), multi-column sort (R31a), partition response validation
(R6a), sort-order verification (R28a), schema capture (R44a).

The following degenerate cases were also considered during initial spec authoring:

1. **Zero matching rows across all partitions (scalar).** R13 ensures each partition returns COUNT=0, SUM=0, MIN=null, MAX=null, AVG=(0,0). R19 ensures AVG=null when total count is zero (no division by zero).

2. **Zero matching rows across all partitions (grouped).** R14 ensures empty partial sets. R22 produces zero groups in the final result. The coordinator must not produce a "null group."

3. **Single partition.** The merge is trivially correct -- one partial is the final result. The cardinality guard still applies (partition_count=1, so the threshold is higher, making the guard less likely to trigger). No special-casing needed.

4. **All partitions unavailable.** R37 ensures an empty result with full `PartialResultMetadata`, not an exception. The caller can inspect and retry.

5. **High-cardinality GROUP BY exceeding memory.** R24-R29 trigger the sorted-run merge fallback. R29 ensures the transition happens before the hash map grows unbounded.

6. **SUM overflow.** R54 promotes to double rather than throwing or wrapping.

7. **NaN in floating-point SUM.** R55 propagates NaN per IEEE 754.

8. **Mixed null and non-null MIN/MAX.** R17-R18 take the extremum of non-null values. All-null produces null.

9. **Concurrent queries.** R45-R47 ensure per-query isolation with no shared mutable state.

10. **Cardinality guard false positive (skewed distribution).** The first partition may have far more groups than average. The guard activates conservatively -- the sorted-run fallback is correct, just potentially slower. No data loss or incorrect results.

11. **GROUP BY with composite key (multiple columns).** R23 requires value equality, not reference identity. Composite keys work as long as equality is defined on the column values.

12. **DISTINCT and holistic aggregate rejection.** R3-R4 explicitly reject with `UnsupportedOperationException` before any dispatch occurs.
