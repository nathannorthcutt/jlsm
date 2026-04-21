---
{
  "id": "query.distributed-pagination",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "query"
  ],
  "requires": [
    "F04",
    "F21"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "limit-offset-pushdown",
    "scatter-backpressure",
    "scatter-gather-query-execution"
  ],
  "kb_refs": [
    "distributed-systems/query-execution/distributed-scan-cursors",
    "distributed-systems/query-execution/distributed-join-strategies"
  ],
  "open_obligations": [
    "OB-F39-01: Token HMAC authentication \u2014 forged tokens enable arbitrary table scanning. Deferred pending key management infrastructure."
  ],
  "_migrated_from": [
    "F39"
  ]
}
---
# query.distributed-pagination — Distributed Pagination

## Requirements

### Scope

R1. The distributed pagination system must support keyset (cursor-based) pagination via opaque continuation tokens. Traditional positional OFFSET must not be exposed at the distributed query API.

R2. The distributed pagination system must support forward-only traversal. Backward pagination (descending key traversal from a token) must be rejected with an `UnsupportedOperationException` whose message states that backward pagination is not supported.

R3. The distributed pagination system must support pagination only on primary key order. Pagination on secondary sort orders (ORDER BY non-key columns) must be rejected with an `UnsupportedOperationException` whose message identifies the unsupported sort column.

### First-page query (no token)

R4. The coordinator must treat a query with a LIMIT and no continuation token as a first-page request. The coordinator must fan out the query to all partitions selected by partition pruning (F04 R63, F30 R39).

R5. For a first-page request, each partition must return at most LIMIT entries in ascending primary key order from the beginning of its key range (or from the query's lower key bound, if specified).

R6. The coordinator must k-way merge the partition responses in ascending primary key order and emit exactly the first LIMIT entries from the merged stream. If fewer than LIMIT entries exist across all partitions, the coordinator must emit all available entries. Primary key ordering must use unsigned lexicographic byte comparison (comparing byte values as unsigned integers from the first byte to the last, with shorter keys sorting before longer keys that share the same prefix).

R6a. If the k-way merge encounters entries with identical keys from different partitions (possible during transient partition split overlap), the coordinator must emit only one entry (the one with the higher sequence number, breaking further ties by partition identifier). The coordinator must not emit duplicate keys to the caller.

R7. The coordinator must encode a continuation token from the last emitted entry of the first page (R15-R18). If the merged stream contained fewer entries than LIMIT, the coordinator must return a null continuation token to indicate scan completion.

### Subsequent-page query (with token)

R8. The coordinator must treat a query with a LIMIT and a non-null continuation token as a subsequent-page request. The coordinator must decode the token (R19-R22) and fan out `PageRequest` messages to all partitions selected by partition pruning.

R9. Each `PageRequest` for a subsequent page must carry the token's `lastKey` as an exclusive lower bound, the original query's upper key bound as the inclusive upper bound, the token's `sequenceNumber` for snapshot binding, and LIMIT as the page size.

R10. Each partition must seek past `lastKey` and return at most LIMIT entries with keys strictly greater than `lastKey`, in ascending primary key order.

R11. The coordinator must k-way merge the partition responses from a subsequent-page request in ascending primary key order and emit exactly the next LIMIT entries from the merged stream.

R12. The coordinator must encode a new continuation token from the last emitted entry of the subsequent page (R15-R18). If the merged stream contained fewer entries than LIMIT, the coordinator must return a null continuation token to indicate scan completion.

### Per-page cost

R13. Each page request (first or subsequent) must fan out to at most P partitions (where P is the number of non-pruned partitions) and each partition must return at most LIMIT entries. The total entries transferred per page must not exceed P * LIMIT.

R14. The per-page network cost must be independent of the logical page number. The second page, the hundredth page, and the thousandth page must each transfer at most P * LIMIT entries.

### Continuation token encoding

R15. The continuation token must be encoded as a byte sequence in the following order: 8 bytes for the sequence number in big-endian format, 4 bytes for the last key length in big-endian format, N bytes for the last key (where N equals the last key length), and 1 byte for flags. The total token size must be exactly 13 + N bytes.

R16. The `sequenceNumber` field in the token must be the sequence number of the snapshot used to serve the page from which the token was produced. If the page was served from a degraded (fallback) snapshot, the token must encode the actual snapshot sequence number used, not the originally requested one.

R17. The `lastKey` field in the token must be a defensive byte-for-byte copy of the key of the last entry emitted to the caller, allocated into a heap-backed or read-only MemorySegment at token encoding time. The token must not retain a reference to the original Entry's key segment. The copy must be taken at encoding time, not deferred to serialization.

R18. The flags byte must support the following bit flags: bit 0 (`SNAPSHOT_DEGRADED`, value `0x01`) indicates the snapshot fell back to a later version, and bit 1 (`SCAN_COMPLETE`, value `0x02`) indicates the partition scan reached the end of its key range. Bits 2-7 must be reserved and set to zero on encoding.

### Continuation token decoding

R19. The token decoder must reject a null token byte array with a `NullPointerException`.

R20. The token decoder must reject a token shorter than 13 bytes (the minimum size for a zero-length key) with an `IllegalArgumentException` whose message states the minimum token size and the actual size received.

R21. The token decoder must reject a token whose encoded `last_key_length` is negative with an `IllegalArgumentException` whose message identifies the invalid length value.

R22. The token decoder must reject a token whose total byte count does not equal `13 + last_key_length` with an `IllegalArgumentException` whose message states the expected and actual sizes.

R22a. The token decoder must reject a token whose decoded sequence number is zero (the pre-write sentinel) with an `IllegalArgumentException` whose message states that the sequence number must reference a valid snapshot.

R22b. The token decoder must reject a token whose flags byte has any of bits 2-7 set with an `IllegalArgumentException` whose message identifies the invalid flags value. This enables forward-compatible flag extension.

### Snapshot binding

R23. For a first-page request, the coordinator must acquire the current latest sequence number and include it in the `PageRequest` messages sent to partitions. All partitions for the first page must receive the same sequence number.

R24. For a subsequent-page request, the coordinator must use the sequence number extracted from the continuation token. Each partition must attempt to serve the page at that sequence number.

R25. If a partition cannot serve the requested sequence number because the GC watermark has advanced past it, the partition must attempt to fall back to the latest available snapshot. If the fallback succeeds, the partition must set `snapshotDegraded = true` in its `PageResponse` and the continuation token produced from that page must include the `SNAPSHOT_DEGRADED` flag (R18).

R26. If a partition's GC watermark has advanced past the requested sequence number and no fallback snapshot is available, the partition must return a `STALE_TOKEN` error. If all partitions return STALE_TOKEN, the coordinator must propagate this as an `IOException` whose message identifies the stale sequence number. If only some partitions return STALE_TOKEN while others succeed, the coordinator must treat the stale partitions as unavailable per R47 and include the stale sequence number and partition identifier in `PartialResultMetadata`.

R27. The coordinator must propagate `snapshotDegraded` status to the caller via `PartialResultMetadata` (F04 R64). Once any partition reports `snapshotDegraded` on any page, the query-level degradation flag must remain true for the rest of the pagination sequence.

R27a. When partitions serve at different sequence numbers due to snapshot degradation, the coordinator must encode the minimum sequence number across all responding partitions in the merged continuation token. The coordinator must maintain per-partition continuation tokens internally, tracking each partition's actual serving sequence number. On subsequent pages, partitions whose last serving sequence number was higher than the merged token's minimum must fall back to the minimum sequence number (which may trigger re-degradation).

R27b. When a partition split is detected (R34-R35) and the new partition owner also reports `snapshotDegraded` (R25), the coordinator must set a `CONSISTENCY_UNKNOWN` flag in `PartialResultMetadata` indicating that gap or duplicate detection is not possible for the affected key range.

### Partition-side page handler

R28. The partition page handler must be stateless between page requests. The handler must not retain any iterator, snapshot reference, or scan state after returning a `PageResponse`.

R29. For each page request, the partition handler must: (1) decode the continuation token (or use the scan start key for a first-page request), (2) acquire a read snapshot at the requested sequence number, (3) seek past the exclusive lower bound key, (4) scan forward collecting at most LIMIT entries, (5) copy entry key and value bytes into a response-owned allocation independent of the snapshot's Arena, (6) encode a new continuation token from the last collected entry, and (7) release the snapshot. The snapshot must be released in a finally block regardless of success or failure. Entries in the PageResponse must not reference memory owned by the snapshot's Arena.

R29a. The partition handler must reject a PageRequest whose sequence number exceeds the partition's current latest sequence number with an `IllegalArgumentException` whose message states the requested and current latest sequence numbers. A future sequence number indicates a corrupted or forged token.

R30. The partition handler must return only resolved (live) entries. Tombstones and deleted keys must be resolved by the partition-side merge before page assembly. Callers must never observe tombstone entries in a `PageResponse`.

### Scan completion

R31. When a partition's scan reaches the end of its key range or the query's upper bound before collecting LIMIT entries, the partition must return a `PageResponse` with a null continuation token and the collected entries (which may be an empty list).

R32. When all partitions return null continuation tokens, the coordinator must return a null continuation token to the caller, signaling that no more pages are available.

R33. The coordinator must not issue further `PageRequest` messages to a partition that has returned a null continuation token on a previous page. That partition's scan is complete. The partition completion status (null continuation token) must be recorded at response arrival time (when the transport delivers the response), not at merge consumption time. The completion flag must be visible to the fan-out logic via a volatile or concurrent data structure.

### Partition split during scan

R34. If a partition receives a page request whose `lastKey` falls outside the partition's current key range (due to a range split or rebalancing between pages), the partition must return an empty entry list with a null continuation token and the `SCAN_COMPLETE` flag set.

R35. When the coordinator detects that a partition returned `SCAN_COMPLETE` with `lastKey` outside the partition's range, the coordinator must refresh the partition map from the table catalog (F21 R13a) and route any remaining key range to the new partition owner.

R35a. After a partition map refresh (R35), the coordinator must re-evaluate the credit allocation per F21 R3 using the updated partition count. If the refreshed partition map introduces new partitions that require additional credits beyond the original allocation, the coordinator must either acquire additional credits or mark the affected partition ranges as unavailable in `PartialResultMetadata` rather than deadlocking on credit exhaustion.

### Credit budget integration

R36. Each page request must consume one credit per partition from the query's credit budget (F21 R1-R3). The coordinator must not issue a `PageRequest` to a partition unless a credit is available for that partition.

R37. When the coordinator consumes a page from the merge queue, the coordinator must release the credit and re-issue demand via `Subscription.request(1)` (F21 R4) carrying the new continuation token from the consumed page.

R38. The total number of in-flight pages across all partitions for a single pagination query must not exceed the query's total credit allocation (F21 R3).

### Input validation

R39. The pagination handler must reject a null LIMIT value with a `NullPointerException`.

R40. The pagination handler must reject a LIMIT of zero or a negative LIMIT with an `IllegalArgumentException` whose message states that LIMIT must be a positive integer and identifies the invalid value. LIMIT must be of type `int` (32-bit signed integer). The pagination handler must reject a LIMIT exceeding a configurable maximum page size (default 10,000) with an `IllegalArgumentException`. The maximum must be chosen such that `P * max_limit` cannot overflow a 64-bit signed integer for any supported partition count.

R41. The pagination handler must reject a null lower key bound (when explicitly provided, not derived from a token) with a `NullPointerException`.

R42. The pagination handler must reject a null partition list with a `NullPointerException`. The pagination handler must reject a partition list containing any null element with a `NullPointerException` whose message identifies the index of the null element. An empty partition list (all partitions pruned) must return an empty result with a null continuation token immediately, without dispatching any page requests.

### Concurrency

R43. Continuation token handling must be partition-local and single-threaded per request. Each partition processes at most one page request for a given query at a time.

R44. Multiple concurrent pagination queries must not interfere with each other. Each query must maintain its own set of continuation tokens, credit counters, and merge state. No pagination state may be shared across queries. The partition map used for fan-out must be either immutable (snapshot-on-read) or protected by a read-write lock. A partition map refresh triggered by one query (R35) must not cause a concurrent query to observe a partially-updated map.

R45. The coordinator's k-way merge for pagination must be single-threaded per query. Partition responses arrive concurrently via the Flow API, but the merge and token encoding must not require concurrent write access to shared state.

### Error handling

R46. If one or more partitions are unavailable (timeout or transport failure) during a page request, the coordinator must return a partial page covering only the responding partitions. The result must include `PartialResultMetadata` (F04 R64, R65) indicating which partitions were unavailable.

R47. The coordinator must not fail the entire pagination query due to a single partition error. The partition must be marked as unavailable in `PartialResultMetadata`, and the coordinator must continue merging results from the remaining partitions.

R48. If all partitions are unavailable for a page request, the coordinator must return an empty result with `PartialResultMetadata` listing all partitions as unavailable. The coordinator must not throw an exception for total partition unavailability.

### Resource lifecycle

R49. The coordinator must release all pagination state (merge heap, continuation token references, per-partition tracking) when the query completes, whether by normal completion (null token returned), error, or cancellation. Release must occur in a finally block. Cancellation must set a volatile cancellation flag checked by the merge thread at each iteration. The merge thread is responsible for cleanup when it observes the flag. Cancellation must not directly mutate merge state from a different thread.

R50. The coordinator must cancel all outstanding `PageRequest` transport futures when the pagination query is cancelled or errors. Outstanding futures must be completed exceptionally, and held credits must be released (F21 R27).

## Cross-References

- ADR: .decisions/limit-offset-pushdown/adr.md
- ADR: .decisions/scatter-backpressure/adr.md
- ADR: .decisions/scatter-gather-query-execution/adr.md
- KB: .kb/distributed-systems/query-execution/distributed-scan-cursors.md
- Spec: F04 R62-R67 (scatter-gather execution, ordering preservation)
- Spec: F21 R1-R14 (credit model, continuation token protocol, Flow API)
- Spec: F30 R39 (query with partition pruning)

---

## Design Narrative

### Intent

Define the distributed pagination protocol for the scatter-gather proxy. Callers paginate through large result sets using opaque continuation tokens instead of positional OFFSET. Each page costs O(P * LIMIT) regardless of depth -- the thousandth page is as cheap as the first. The protocol is stateless on the partition side: no iterators pinned, no SSTables held, compaction runs freely between pages.

### Why keyset pagination replaces OFFSET

The ADR (limit-offset-pushdown) evaluated three approaches: naive per-partition over-fetch (LIMIT+OFFSET from every partition), global offset coordinator tracking, and top-N pushdown with keyset pagination.

Naive over-fetch is disqualified: O(P * (LIMIT+OFFSET)) network traffic makes deep pagination impractical. At P=100, OFFSET=10000, LIMIT=20: each partition sends 10,020 rows. Page 500 transfers 100 * 10,020 = 1,002,000 rows for 20 results.

Global offset tracking is disqualified: it requires per-partition row count estimates, which LSM trees cannot provide cheaply (tombstones, overlapping levels, concurrent compaction make cardinality estimates unreliable). Approximate counts produce approximate positioning -- a correctness violation.

Keyset pagination eliminates OFFSET entirely. The continuation token encodes the last key returned, and each partition seeks directly to that position. Per-page cost is O(P * LIMIT) regardless of page number. This is the standard approach used by Cosmos DB, Cassandra, and CockroachDB for distributed pagination.

### Why stateless partition handling

The scatter-backpressure ADR established that continuation tokens (not stateful server-side cursors) are the right model for the scatter-gather proxy. Stateful cursors pin SSTables on partition nodes, blocking compaction during long scans. With 100 partitions and 10 concurrent scans, 1,000 open iterators would pin SSTables cluster-wide, causing severe space amplification.

Stateless handling means: decode token, acquire snapshot, seek, scan, encode new token, release snapshot. No state between requests. Compaction runs freely. The O(log N) seek-per-page cost is amortized by the block cache -- SSTable index blocks stay cached in steady state, reducing to ~O(1) amortized.

### Why the token encodes sequence number

Without snapshot binding, concurrent writes between pages cause phantom reads (new entries appear) and non-repeatable reads (updated entries change). The token's sequence number provides point-in-time consistency across pages. The partition reads at that snapshot on resume, seeing the same logical state as the previous page.

The risk is snapshot expiry: if the GC watermark advances past the token's sequence number, the scan cannot resume at the original snapshot. The spec handles this with a two-tier fallback: prefer the requested snapshot, fall back to the latest snapshot with a degraded flag, and fail with STALE_TOKEN only when even the latest snapshot cannot serve. The scan-lease-gc-watermark decision (deferred) will provide a mechanism to prevent GC watermark advancement for active scans.

### Why LIMIT validation rejects zero

A LIMIT of zero is semantically ambiguous: does it mean "return nothing" or "return everything"? Different databases interpret it differently. Since the continuation token protocol requires at least one entry to produce a token (R17 -- the last emitted key), a zero LIMIT would produce a null token on the first page, making pagination impossible. Rejecting zero is the least-surprising behavior.

### What was ruled out

- **Positional OFFSET at the distributed API:** Eliminated by the ADR. O(P * OFFSET) cost is prohibitive.
- **Backward pagination:** Forward-only tokens. Bidirectional requires dual-direction keyset with descending index support, deferred per ADR.
- **Secondary-sort pagination:** Requires richer token encoding capturing position in a secondary index order, deferred per ADR.
- **Stateful server-side cursors:** Pin SSTables, block compaction, leak on coordinator crash.
- **Token expiration/TTL:** Tokens do not expire on their own. Staleness is determined by the GC watermark, not by wall-clock time. A token remains valid as long as its sequence number is still accessible.

### Adversarial falsification (Pass 2 — 2026-04-15)

18 findings from structured adversarial review (all mandatory probes). All promoted.
Critical: R26/R47 STALE_TOKEN contradiction (R26 amended), divergent sequence numbers
across degraded partitions (R27a, R27b). High: P*LIMIT overflow (R40 amended), defensive
key copy (R17 amended), snapshot-owned entry memory (R29 amended), cancel/merge thread
race (R49 amended), split+degradation compound case (R27b), credit exhaustion on refresh
(R35a), token authentication (OB-F39-01 deferred), future sequence number (R29a).
Medium: sequence-0 sentinel (R22a), reserved flags (R22b), null partition elements (R42
amended), duplicate keys in merge (R6a), key comparison semantics (R6 amended),
partition-complete tracking (R33 amended), shared partition map (R44 amended). Low:
empty key ordering, LIMIT consistency across pages, upper/lower bound validation.

The following degenerate cases were also considered during initial spec authoring:

1. **Zero entries across all partitions (first page).** R6 emits all available entries (zero). R7 returns null token (scan complete). No continuation possible, which is correct -- there is nothing to paginate.

2. **Fewer entries than LIMIT on first page.** R6 emits all entries. R7 returns null token. Single-page result with no continuation.

3. **Single partition.** Merge is trivially correct -- one partition's response is the merged output. Credit budget (R36) still applies (1 credit per page).

4. **LIMIT = 1.** Each page returns exactly one entry (or zero on the last page). Token encodes that single entry's key. Subsequent page seeks past it. Correct but maximally expensive in round trips.

5. **All partitions unavailable.** R48 returns empty result with full `PartialResultMetadata`. No exception thrown. Caller can inspect and retry.

6. **Partition split between pages.** R34-R35 handle this: the old partition returns empty with scan_complete, the coordinator refreshes the partition map and routes the remaining range to the new owner. No entries are lost or duplicated because the token's lastKey provides the resume position in the new partition.

7. **GC watermark advances between pages.** R25 falls back to latest snapshot with `snapshotDegraded`. R26 fails with STALE_TOKEN if even fallback is impossible. R27 propagates degradation to caller. No silent inconsistency.

8. **Concurrent writes between pages with same snapshot.** The sequence number binding ensures point-in-time consistency. New writes with higher sequence numbers are invisible to the scan.

9. **Concurrent writes between pages with degraded snapshot.** If the snapshot fell back (R25), the new snapshot may include writes not visible on the previous page. This is a known consistency trade-off documented by the `SNAPSHOT_DEGRADED` flag. The caller can detect this and decide whether to trust the result.

10. **Token with zero-length key.** Token size is 13 + 0 = 13 bytes. R20 accepts this (13 >= 13). R22 validates 13 == 13. The zero-length key is a valid lower bound -- the seek resumes from the beginning of the key space, which is correct only if the last emitted entry truly had an empty key. This is degenerate but consistent.

11. **Malformed token (truncated, garbage bytes).** R20-R22 reject tokens that fail size validation. Negative key length (R21) is caught explicitly. A token with valid structure but corrupted key bytes will cause the partition to seek to a wrong position, returning incorrect results. Token integrity (HMAC, CRC) is not in scope but would be the correct mitigation for adversarial token tampering.

12. **Double credit release on concurrent cancel + error.** Inherited from F21 R27 -- single-release guard (AtomicBoolean) per credit prevents double-release.

13. **Coordinator sends PageRequest to completed partition.** R33 prevents this -- the coordinator tracks which partitions have returned null tokens and excludes them from subsequent fan-outs.

14. **Partition returns LIMIT entries but scan is actually complete.** The partition returns a non-null token. The next page request gets zero entries and a null token. One extra round trip but no data loss or duplication. This is inherent to the stateless model -- the partition cannot know whether more entries exist without scanning one past LIMIT.

15. **Long key in token.** Token size is 13 + N bytes. The token max size parameter (256 bytes from KB) implies a practical key limit of 243 bytes. Keys longer than this produce valid but large tokens. The spec does not enforce a maximum key size in the token -- that is a transport-level concern (frame size limits from F19).
