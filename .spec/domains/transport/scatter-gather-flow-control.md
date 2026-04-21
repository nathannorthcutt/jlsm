---
{
  "id": "transport.scatter-gather-flow-control",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [
    "transport"
  ],
  "requires": [
    "transport.multiplexed-framing"
  ],
  "invalidates": [],
  "decision_refs": [
    "scatter-backpressure",
    "scatter-gather-query-execution"
  ],
  "kb_refs": [
    "distributed-systems/networking/scatter-gather-backpressure",
    "distributed-systems/query-execution/distributed-scan-cursors"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F21"
  ]
}
---
# transport.scatter-gather-flow-control — Scatter-Gather Flow Control

## Requirements

### Credit Model

R1. The scatter-gather coordinator must use credit-based flow control to bound
memory consumption during distributed query fan-out. Each credit represents
one page demand token. A partition may send one page response per credit held.

R2. Credits are logical demand tokens. The coordinator tracks a byte budget
ceiling equal to ArenaBufferPool capacity (configurable). Each outstanding
credit represents `page_buffer_size` bytes of budget. When the budget is
exhausted, no more demand signals are issued until pages are consumed and
credits returned. The budget bounds the number of in-flight pages, not the
exact heap footprint — deserialized Java objects may be larger than their wire
representation (typically 2-8x). The JVM's `-Xmx` must be sized with headroom
for deserialization expansion.

R3. Initial credits per partition:
`credits_per_partition = floor(pool_capacity / (page_buffer_size x partition_count x max_concurrent_queries))`.
Floor division — some capacity may be wasted (at most `denominator - 1`
units). Minimum 1. If the formula yields 0 (pool too small for the current
partition count and concurrency), the query must be rejected with `IOException`
indicating insufficient memory budget.

R3a. If `partition_count` is 0 (empty table or all partitions pruned), the
coordinator must return an empty result immediately. No credits are needed.

R4. On consuming a page (merge operator dequeues and processes from the merge
queue): decrement budget usage, re-issue demand via `Subscription.request(1)`.
If sending the demand signal (PageRequest via transport) fails, the publisher
must call `onError()` on the subscriber. The publisher must track terminated
state and suppress ALL subsequent signals (`onNext`, `onError`, `onComplete`)
after the first terminal signal. Late transport responses received after
termination must be discarded without enqueueing.

R5. `max_concurrent_queries` is a static configuration (default 8) set at
startup. If actual concurrent queries exceed this value, excess queries must
block with a configurable timeout (default 30 seconds) waiting for a query
slot. The blocking must occur on a query-submission thread — NOT on a handler
dispatch thread (F19 R34b). If the timeout expires, the query must be rejected
with `IOException` indicating query slot exhaustion.

R5a. The query coordinator must run on its own virtual thread, outside the F19
R34b handler dispatch thread pool. The handler for `QUERY_REQUEST` must
enqueue the query for the coordinator — it must not execute the query inline
on the handler thread. This prevents query slot blocking from consuming
handler dispatch threads and causing deadlock.

R5b. The coordinator virtual thread must wrap its entire execution in a
try-finally that: (a) cancels all outstanding partition subscriptions, (b)
releases all logical credits (decrement byte budget), (c) returns the query
slot, (d) completes the query result (with error if the coordinator failed).
Uncaught exceptions on the coordinator thread must not leak slots or credits.

### Flow API Integration

R6. Each partition in a scatter-gather query must be modeled as a
`Flow.Publisher<PageResponse>`. The coordinator's merge operator subscribes as
a `Flow.Subscriber<PageResponse>`.

R7. `Subscription.request(N)` at subscription time issues the initial N
credits (computed per R3). Subsequent demand is `request(1)` per consumed
page.

R8. `onNext(PageResponse)` must not block. Responses are deserialized from
transport frame bytes into Java objects (entries) on the handler dispatch
thread (F19 R34). `onNext()` enqueues the deserialized `PageResponse` into a
bounded merge queue. The credit is NOT returned at this point — it is returned
when the merge operator dequeues and processes the page from the merge queue.

R9. `onError(Throwable)` must propagate partition failure to the coordinator's
error handling. The coordinator must release all logical credits held for that
partition.

R10. `onComplete()` signals the partition's scan is complete. The coordinator
must release any remaining logical credits for that partition.

### Continuation Token Protocol

R11. Each demand signal (page request) must be a self-contained `PageRequest`:
- `fromKey`: exclusive lower bound (from continuation token, or scan start key
  for the first request)
- `toKey`: scan upper bound (from the original query)
- `sequenceNumber`: snapshot binding (from continuation token, or the
  coordinator's scan start sequence number for the first request)
- `pageSize`: maximum entries to return

R12. Each page response must be a `PageResponse`:
- `entries`: scanned entries for this page — must contain only resolved (live)
  data. Partition-side merge resolves tombstones before page assembly.
- `continuationToken`: opaque byte array encoding scan position, or null if
  scan is complete
- `snapshotDegraded`: true if the partition fell back to a later snapshot

R13. Continuation token encoding: 8-byte big-endian sequence number, 4-byte
big-endian last-key length, N-byte last key, 1-byte flags. Total: 13 +
key_length bytes. The token is stateless — the partition holds no state
between page requests.

R13a. The coordinator must validate that `lastKey` in the continuation token
falls within the partition's assigned key range before sending the next
`PageRequest`. Out-of-range due to partition split is a topology change — the
coordinator must refresh the partition map from the table catalog, then
re-route the remaining key range to the new partition owner. The coordinator
must block the affected partition stream during the refresh. If the refresh
fails, the range must be marked unavailable in `PartialResultMetadata`.
Out-of-range for other reasons (corruption, bug) must be treated as a
partition error (`onError` with descriptive `IOException`).

R14. When `continuationToken` is null in a `PageResponse`, the partition's
scan is complete. The publisher must call `onComplete()`.

R14a. `snapshotDegraded` must be propagated to the caller via
`PartialResultMetadata`. Once ANY partition reports `snapshotDegraded` on ANY
page, the entire query result is degraded. `PartialResultMetadata` must
indicate query-level degradation, not per-page.

R14b. If a partition cannot serve the requested `sequenceNumber` (GC watermark
has advanced past it), the partition must fall back to the latest available
snapshot and set `snapshotDegraded = true` in the response. If even the latest
snapshot cannot serve the key range, the partition must return an error.

### Transport Mapping

R15. Page requests must be sent as `ClusterTransport.request()` calls with
`MessageType.QUERY_REQUEST`.

R16. Page responses must be sent as handler responses (F19 R13) with
`MessageType.QUERY_RESPONSE`.

R17. At most one outstanding page request per partition per demand signal.
Transport timeout clears the outstanding slot. The coordinator may retry with
the same continuation token — partition-side statelessness makes this safe.
The merge operator must deduplicate per partition: track the last-emitted key
per partition stream, skip entries with keys less than or equal to the
last-emitted key for that partition. Dedup operates on live entries only (R12).

### Memory Budgeting

R19. Total memory bounded by the byte budget ceiling (equal to ArenaBufferPool
capacity). Budget shared across all concurrent queries (up to
`max_concurrent_queries`). The floor-division credit formula (R3) prevents
over-commitment: each query gets at most `pool_capacity /
max_concurrent_queries` worth of credits regardless of partition count.

R20. If `credits_per_partition` computes to less than 1, the query must be
rejected with `IOException` indicating insufficient memory budget.

### Slow Partition Handling

R22. Each partition must have a per-partition timeout (configurable). The
timeout timer starts when the `PageRequest` is dispatched to the transport
layer — it measures partition response latency, not coordinator processing
latency. The timeout must account for deserialization and merge queue latency
in its configured value, but the timer itself starts at dispatch. On timeout:
cancel the subscription for that partition, release held credits, mark the
partition as timed out.

R23. When one or more partitions time out, the coordinator must return partial
results to the caller. The response must include `PartialResultMetadata`
indicating which partitions were incomplete and whether any responses had
`snapshotDegraded`.

R24. The coordinator must not wait for all partitions before returning results.
The k-way merge must stream results as partitions respond. A timed-out
partition's key range is absent from the merged output — the result may have
key-space discontinuities. Callers must check `PartialResultMetadata` to
detect gaps. K-way merge ordering assumes non-overlapping, ordered partition
key ranges (range partitioning). Hash-partitioned tables would require a
client-side sort-merge step.

### Concurrency

R25. The scatter-gather coordinator must be safe for concurrent use by
multiple queries. Each query has its own set of partition subscriptions,
credit counters, and continuation tokens. Only the byte budget ceiling is
shared.

R26. `onNext()` is called from the transport handler dispatch thread (F19
R34). The merge operator must serialize partition responses through a bounded
merge queue. One merge queue per query. Queue capacity equals
`credits_per_partition x partition_count` (total credits for that query).
All outstanding credits can have corresponding queued pages without overflow.

### Resource Lifecycle

R27. On query cancellation or error: cancel all outstanding transport requests
(complete futures exceptionally), release all logical credits (decrement byte
budget), clean up all subscription state. Each credit must use a single-
release guard (AtomicBoolean, created on demand issuance, discarded on return)
to prevent double-release on concurrent error/cancel paths.

R28. The coordinator should use try-finally to ensure credits are released on
all paths (normal completion, error, cancellation, timeout). See also R5b for
coordinator thread-level resource cleanup.

### Observability

R30. The coordinator must expose queryable metrics: (a) active query count,
(b) credits issued per query, (c) credits in-flight (not yet returned), (d)
credit utilization ratio (in-use / issued), (e) per-partition timeout rates
(not just global counter), (f) partial result query count, (g) byte budget
utilization (used / capacity), (h) query slot utilization (active /
max_concurrent_queries), (i) snapshotDegraded response count, (j) token
validation failure count, (k) merge queue depth, (l) demand signal round-trip
latency, (m) demand-blocked count (credits available but all partitions have
outstanding requests).

---

## Design Narrative

### Intent

Provide memory-bounded backpressure for the scatter-gather proxy's distributed
query fan-out. Without flow control, fast partitions can overwhelm the
coordinator with buffered responses, causing OOM. Credits tied to a byte
budget ceiling prevent unbounded fan-out — overflow is limited by the static
`max_concurrent_queries` and the floor-division credit formula.

### Why Credit-Based + Flow API

Credit-based provides the memory bound (credits x page_buffer_size ≤ budget).
Flow API provides non-blocking demand signaling compatible with NIO
(`Subscription.request(N)` doesn't block). The combination: credits enforce
the bound, Flow API provides async coordination. Pull-based alone is
RTT-limited. Push-based with TCP backpressure stalls all streams on a
multiplexed connection (violates per-stream isolation from F19).

### Why continuation tokens (not stateful cursors)

Stateful cursors pin SSTables on partition nodes, blocking compaction during
long scans. With 100 partitions x 10 concurrent scans = 1000 open iterators
cluster-wide, space amplification becomes severe. Continuation tokens are
stateless — no pinning, no server-side resources between pages, crash-safe.
The O(log N) seek-per-page cost is amortized by the block cache.

### Why logical credits (not physical slabs)

Round 1 adversarial review revealed that coupling credits to physical
ArenaBufferPool slab acquisition creates contradictions: eager acquisition
wastes pool capacity on slow partitions, lazy acquisition blocks in onNext()
(violating the non-blocking requirement). Logical credits resolve this —
responses are deserialized to Java heap objects, and the credit count bounds
the number of in-flight pages. The byte budget is an approximation (heap
objects are larger than wire size), but the combination of
`max_concurrent_queries`, page size, and `-Xmx` headroom provides the
practical OOM guard.

### What was ruled out

- **Flow API alone**: no inherent memory bound — a subscriber that issues
  unbounded demand can OOM
- **Pull-based streaming**: RTT-limited throughput (one outstanding request
  per partition)
- **Credit-based with Semaphore.acquire()**: blocks the calling thread —
  incompatible with NIO event loop
- **Push-based with bounded buffers**: TCP backpressure stalls ALL streams
  on a multiplexed connection
- **Dynamic max_concurrent_queries**: incompatible with Flow API demand model
  (cannot revoke already-issued `request(N)`)

### Hardening summary

This spec was refined through three adversarial falsification rounds:
- Round 1: 20 findings — credit-slab ambiguity (resolved: logical credits),
  dynamic credit recomputation (resolved: static max), merge queue semantics,
  handler response path, CONTROL bypass interaction, token validation,
  k-way merge ordering assumptions, pool acquire timing, zero partitions
- Round 2: 14 findings — budget approximation (honest about heap expansion),
  ceiling division over-commit (resolved: floor division), merge queue
  budgeting, retry duplicate pages (resolved: per-partition dedup), GC
  watermark handling, deserialization threading, query slot deadlock
  (resolved: separate coordinator thread), double-release guards
- Round 3: 6 findings — coordinator uncaught exception resource leak,
  partition split re-routing, tombstone resolution, merge queue cardinality,
  publisher late-signal suppression, timeout start semantics
