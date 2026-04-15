---
title: "Distributed Scan Cursor Management"
aliases: ["scan cursor", "continuation token", "paging state", "keyset pagination", "distributed iterator"]
topic: "distributed-systems"
category: "query-execution"
tags: ["cursor", "paging", "continuation-token", "keyset", "snapshot", "compaction", "iterator-pinning", "backpressure"]
complexity:
  time_build: "N/A"
  time_query: "O(log N) seek per page (stateless) or O(1) resume (stateful)"
  space: "O(1) per cursor (stateless) or O(open iterators × SSTable refs) (stateful)"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/"
related:
  - "distributed-systems/networking/scatter-gather-backpressure.md"
  - "distributed-systems/query-execution/distributed-join-strategies.md"
decision_refs: ["scatter-backpressure", "scatter-gather-query-execution"]
sources:
  - url: "https://github.com/facebook/rocksdb/wiki/Iterator"
    title: "RocksDB Iterator Wiki"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://github.com/facebook/rocksdb/wiki/Snapshot"
    title: "RocksDB Snapshot Wiki"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://learn.microsoft.com/en-us/azure/cosmos-db/nosql/query/pagination"
    title: "Cosmos DB Pagination — Continuation Tokens"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://www.cockroachlabs.com/docs/stable/cursors"
    title: "CockroachDB Cursors Documentation"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://docs.datastax.com/en/cql-oss/3.1/cql/cql_using/paging_c.html"
    title: "Cassandra Paging Through Unordered Results"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://docs.scylladb.com/manual/stable/architecture/compaction/compaction-strategies.html"
    title: "ScyllaDB Compaction Strategies"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://www.usenix.org/system/files/atc25-qiu.pdf"
    title: "HotRAP: Hot Record Retention and Promotion for LSM-trees (USENIX ATC 2025)"
    accessed: "2026-04-13"
    type: "paper"
---

# Distributed Scan Cursor Management

## summary

When a scatter-gather coordinator pages through scan results from multiple
partitions, each partition must track where the scan left off between page
requests. Three models exist: stateful server-side cursors (partition holds
an open iterator), stateless continuation tokens (scan position encoded in
an opaque token returned to the coordinator), and keyset pagination (next
page uses `WHERE key > last_key`). The choice interacts with compaction
(stateful cursors pin SSTables, blocking reclamation), snapshot consistency
(stateless tokens must handle SSTable set changes between pages), and
backpressure (demand-driven flow control determines when the next page is
requested). For LSM-tree stores, stateless continuation tokens with
point-in-time snapshot binding are the recommended approach — they avoid
iterator pinning while preserving read consistency.

## how-it-works

### cursor-model-taxonomy

| Model | Server State | Consistency | Compaction Impact | Seek Cost |
|-------|-------------|-------------|-------------------|-----------|
| **Stateful cursor** | Open iterator + snapshot ref | Point-in-time (pinned) | Pins SSTables — blocks reclamation | O(1) resume |
| **Continuation token** | None | Requires snapshot binding | No pinning — compaction-safe | O(log N) seek per page |
| **Keyset pagination** | None | Read-committed (no snapshot) | No pinning — compaction-safe | O(log N) seek per page |

### stateful-server-side-cursors

The partition node keeps an LSM iterator open between page requests.
RocksDB's model is the canonical reference: an iterator pins a
**SuperVersion** — the set of memtables and SST files at creation time.
Even after flush or compaction produces new files, the old files remain on
disk because the iterator holds a reference count. `Iterator::Refresh()`
(RocksDB 5.7+) releases stale resources and re-pins the current
SuperVersion, trading consistency for resource reclamation.

**Lifecycle:**
1. Coordinator sends first page request → partition creates iterator, pins
   SuperVersion, returns page + opaque cursor ID
2. Subsequent requests carry cursor ID → partition looks up iterator, calls
   `next()` N times, returns page
3. Coordinator sends close or cursor times out → partition releases iterator,
   unpins SuperVersion, files eligible for deletion

**Resource concerns:**
- `concurrent_queries × partitions_per_query` open iterators across the
  cluster. At 100 concurrent scans × 50 partitions = 5,000 open iterators,
  each pinning a SuperVersion.
- Pinned SSTables cannot be deleted by compaction. Long-running scans cause
  space amplification — the same data exists in both old (pinned) and new
  (compacted) files. RocksDB documentation explicitly warns: "keep iterators
  short-lived so these resources are freed timely."
- Coordinator crash leaks cursors until server-side timeout fires.

**Used by:** CockroachDB (SQL cursors hold open transactions), traditional
RDBMS cursor implementations.

### stateless-continuation-tokens

Each page response includes an opaque token encoding the scan position.
The partition node holds zero state between requests. On the next page
request, the partition decodes the token and seeks to the encoded position.

**Token contents (for an LSM-tree range scan):**
```
token = encode(last_key, sequence_number, [filter_state])
```
- `last_key`: the key of the last entry returned (exclusive lower bound for
  next page)
- `sequence_number`: the LSM sequence number at scan start — used to bind
  the scan to a point-in-time snapshot without holding an iterator open
- `filter_state`: optional — predicate evaluation checkpoint for complex
  queries (Cosmos DB encodes query plan state in its continuation token)

**Resume protocol:**
1. Decode token → extract `last_key` and `sequence_number`
2. Acquire a read snapshot at `sequence_number` (or the nearest valid
   snapshot if the original has been garbage-collected)
3. Seek to `last_key` in each SSTable's index → O(log N) per SSTable
4. Scan forward, applying filters, until page is full or range exhausted
5. Encode new token from the last returned entry

**Compaction safety:** no SSTables are pinned. Between pages, compaction
may merge SSTables freely. The seek on resume finds the correct position
in the new SSTable layout because keys are preserved across compaction
(compaction merges entries but does not change key values or ordering).

**Snapshot binding strategies:**
- **Sequence-number binding:** token encodes the sequence number at scan
  start. On resume, the partition reads at that sequence number, seeing the
  same logical snapshot. This requires that the WAL or SSTable metadata
  retains enough version history. If the sequence number has been
  garbage-collected (e.g., by a GC watermark advancing past it), the scan
  fails with a stale-token error.
- **Best-effort consistency:** token encodes only `last_key`. Resume reads
  at the current latest snapshot. Concurrent writes between pages may cause
  phantom reads (new entries appear) or non-repeatable reads (updated
  entries differ). Acceptable for analytics workloads where strict
  consistency is not required.
- **Hybrid:** encode sequence number but fall back to latest snapshot if
  the original is unavailable, with a warning flag in the response metadata.

**Used by:** Cosmos DB (fully stateless server-side execution, tokens never
expire for same SDK version), Cassandra (PagingState encodes partition
position + row offset within partition).

### keyset-pagination

The simplest stateless model. The coordinator sends `WHERE key > last_key
ORDER BY key LIMIT page_size`. No opaque token — the last key IS the
pagination state, visible to the caller.

**Limitations:**
- Requires a total order on the scan key — works for primary key scans,
  not for secondary index scans or filtered queries with no key predicate
- No snapshot binding — reads at current latest, subject to phantoms
- Cannot express complex resume positions (e.g., composite keys, multi-
  column sort orders) without encoding them into the WHERE clause

**Best for:** simple key-ordered scans where read-committed consistency
is acceptable. The scatter-gather proxy's `getRange(from, to)` naturally
supports this if the coordinator tracks the last key per partition.

## compaction-interaction

### sstable-pinning-cost

Stateful cursors pin SSTables via reference counting. The cost model:

```
wasted_space = pinned_sstables_size × (1 - live_data_ratio)
```

After compaction produces a new SSTable from the pinned inputs, both old
(pinned) and new (compacted) files exist on disk. The old files hold dead
entries (overwritten or deleted keys) that cannot be reclaimed until the
cursor closes. For a long-running scan over a write-heavy table:

- 10 L1 SSTables pinned (100 MB each) × 30% dead data = 300 MB wasted
- Compaction writes new L1 SSTables but old ones persist = 2× space for
  those key ranges
- If the scan runs for 10 minutes and compaction runs every 2 minutes,
  multiple generations of SSTables accumulate

**Mitigation in stateful model:**
- Server-side cursor timeout (e.g., 60 seconds idle → close)
- Maximum scan duration limit → forcibly close after N seconds, return
  partial results with a continuation token for retry
- `Iterator::Refresh()` (RocksDB) → release pinned files, re-pin current
  SuperVersion, accept consistency break at the page boundary

### stateless-compaction-safety

Continuation tokens avoid pinning entirely. Between pages:
1. Compaction merges SSTables → old files deleted, new files created
2. Next page request seeks from `last_key` in the new SSTable layout
3. Key ordering is preserved across compaction → seek finds correct position

The only risk is **snapshot expiry**: if the token encodes a sequence
number and the LSM's version history has been trimmed past that number,
the scan cannot resume at the original snapshot. Options:
- Fail with `STALE_TOKEN` error → client retries from scratch or accepts
  partial results
- Fall back to latest snapshot with a consistency warning
- Extend the GC watermark for active scans (register scan start with a
  lightweight "scan lease" that prevents version trimming)

## backpressure-integration

### interaction-with-flow-control

The cursor model determines how backpressure demand signals translate to
partition-side work:

**Credit-Based + Flow API (composite):**
- `Subscription.request(1)` → coordinator sends page request with
  continuation token → partition seeks, scans one page, returns it
- Credit return in `onNext()` → coordinator releases buffer, re-issues
  demand when ready
- Continuation token travels with each demand signal — no partition-side
  state between demands
- **Natural fit:** demand-driven paging IS pull-based iteration with
  credit-bounded concurrency

**Stateful cursors + credits:**
- `Subscription.request(1)` → coordinator sends "resume cursor X" →
  partition calls `iterator.next()` N times
- Cursor remains open between demands — partition holds resources even
  when coordinator is applying backpressure (not consuming pages)
- **Tension:** backpressure means "slow down" but stateful cursors pay
  resources whether active or idle

**Recommendation:** stateless continuation tokens align better with
credit-based backpressure because resource consumption is proportional to
active demand, not to the number of open scans.

### demand-signal-protocol

With continuation tokens, each demand signal (page request) is self-
contained:

```java
record PageRequest(
    MemorySegment fromKey,     // exclusive lower bound (from token)
    MemorySegment toKey,       // scan upper bound (from original query)
    long sequenceNumber,       // snapshot binding (from token)
    int pageSize               // max entries to return
) {}

record PageResponse(
    List<Entry> entries,
    byte[] continuationToken,  // null if scan complete
    boolean snapshotDegraded   // true if fell back to latest snapshot
) {}
```

The transport carries `PageRequest` as a standard `request()` message.
No special cursor protocol — just request-response with a token.

## key-parameters

| Parameter | Default | Range | Impact |
|-----------|---------|-------|--------|
| Page size | 1000 entries | 100–10,000 | Larger pages = fewer round trips, more memory per page |
| Cursor timeout | 60s | 10s–300s | Stateful only — shorter = less pinning, more timeout errors |
| Scan lease duration | 300s | 60s–3600s | How long to prevent GC watermark from advancing past scan snapshot |
| Token max size | 256 bytes | 64–1024 | Limits complexity of encoded scan state |

## trade-offs

| Model | Pros | Cons | Best For |
|-------|------|------|----------|
| Stateful cursor | O(1) resume, natural snapshot | Pins SSTables, leak on crash, resource-proportional-to-open-scans | Short-lived OLTP queries with strong consistency needs |
| Continuation token | No pinning, crash-safe, backpressure-aligned | O(log N) seek per page, snapshot expiry risk | Long-running scans, write-heavy tables, distributed fan-out |
| Keyset pagination | Simplest, no state, no token encoding | No snapshot binding, primary key only, phantoms | Simple key-ordered scans with read-committed semantics |

### jlsm-applicability

For the scatter-gather proxy in jlsm:

1. **Continuation tokens are recommended** — the proxy fans out to ~100
   partitions and scans may run for seconds to minutes. Stateful cursors
   would pin SSTables across 100 partitions simultaneously, blocking
   compaction cluster-wide during large scans.

2. **Sequence-number snapshot binding** is feasible — jlsm's MemTable
   and SSTable already use monotonic sequence numbers (`SequenceNumber`).
   The token encodes the scan's starting sequence number; the partition
   reads at that snapshot on resume.

3. **Integration with Credit-Based + Flow API backpressure:** each
   `Subscription.request(1)` sends a `PageRequest` with the continuation
   token. The partition seeks, scans one page, returns `PageResponse`
   with a new token. No server-side state between requests. Credit count
   bounds how many pages are in-flight across all partitions.

4. **Compaction safety:** no SSTables pinned. The scan lease mechanism
   (lightweight registration of active scan sequence numbers) prevents
   the GC watermark from trimming version history needed by active scans,
   without holding file references.

## implementation-notes

### continuation-token-encoding

For jlsm range scans, the token needs:
```
[8 bytes: sequence_number]
[4 bytes: last_key_length]
[N bytes: last_key]
[1 byte:  flags (snapshot_degraded, scan_complete)]
```
Total overhead: 13 + key_length bytes. For typical keys (8–128 bytes),
tokens are 21–141 bytes — well within a single transport frame.

### seek-cost-amortization

The O(log N) seek per page (per SSTable) can be amortized:
- **Block cache:** SSTable index blocks are cached; seek on resume hits
  cached index blocks in steady state, reducing to O(1) amortized
- **Bloom filter skip:** for point lookups within a scan (e.g., filtered
  scans that skip many keys), bloom filters skip SSTables that don't
  contain keys in the page's range
- **Prefetch:** on seek, prefetch the next data block into the block
  cache, amortizing the next page's first read

### edge-cases-and-gotchas

- **Token after compaction:** key is guaranteed to exist in the merged
  SSTable (compaction preserves all live keys). Seek to `last_key` and
  advance — the first entry with key > `last_key` is the correct resume
  point.
- **Token after tombstone compaction at bottom level:** if `last_key` was
  a tombstone that was dropped during bottom-level compaction, the seek
  still works — the next live key after the tombstone's position is
  correct because the scan already returned the tombstone (or skipped it
  if tombstone filtering was active).
- **Concurrent schema change:** if a schema migration changes field
  encoding between pages, the continuation token's `last_key` may not
  decode correctly in the new schema. Defense: include a schema version
  byte in the token; reject tokens from a prior schema version with
  `STALE_TOKEN`.
- **Partition split during scan:** if the partition's key range changes
  between pages (rebalancing), the token's `last_key` may fall outside
  the new range. The partition returns an empty page with scan_complete;
  the coordinator routes the remaining range to the new owner.

## current-research

### key-papers

- HotRAP (USENIX ATC 2025) — hot record retention and promotion in LSM
  trees with tiered storage; uses compaction iterators advanced in
  sort-merge manner, relevant to understanding iterator lifecycle costs.
- EcoTune (SIGMOD 2025) — dynamic compaction policy selection; relevant
  to understanding how compaction frequency affects cursor timeout tuning.
- Distributed Speculative Execution (arXiv 2412.13314, 2024) — durable
  execution without synchronous persistence; relevant to straggler
  mitigation in paged scans.

### active-research-directions

- CXL-attached memory for disaggregated scan buffers (SIGMOD 2025, VLDB
  2025) — shared memory pools could host continuation token metadata
  without per-node state, enabling cursor migration between nodes.
- Adaptive scan timeout based on compaction pressure — when compaction
  is backlogged, shorten cursor timeout to release pinned files faster.

## practical-usage

### when-to-use

- Distributed range scans over partitioned LSM-tree tables
- Long-running analytical scans where pinning SSTables is unacceptable
- Scatter-gather proxies with credit-based backpressure (demand-driven
  paging maps directly to continuation token exchange)

### when-not-to-use

- Sub-millisecond point lookups (no paging needed — single request)
- Transactions requiring server-side cursor state (e.g., UPDATE ... WHERE
  CURRENT OF cursor — not applicable to LSM-tree key-value stores)
- Queries requiring backward pagination (continuation tokens are
  forward-only; bidirectional requires offset-based approaches or
  dual-direction keyset with descending index)

## code-skeleton

```java
// Continuation token for a range scan
record ScanToken(
    MemorySegment lastKey,
    long sequenceNumber,
    byte flags
) {
    static final byte SNAPSHOT_DEGRADED = 0x01;
    static final byte SCAN_COMPLETE     = 0x02;

    byte[] encode() {
        int len = 8 + 4 + (int) lastKey.byteSize() + 1;
        var buf = new byte[len];
        // ... big-endian encoding
        return buf;
    }

    static ScanToken decode(byte[] token) {
        // ... big-endian decoding
        return new ScanToken(key, seqNum, flags);
    }
}

// Partition-side page handler (stateless)
PageResponse handlePageRequest(PageRequest req) {
    var snapshot = acquireSnapshot(req.sequenceNumber());
    boolean degraded = (snapshot.actualSequence() != req.sequenceNumber());

    try (var iter = newIterator(snapshot, req.fromKey(), req.toKey())) {
        var entries = new ArrayList<Entry>(req.pageSize());
        MemorySegment lastKey = req.fromKey();
        while (iter.hasNext() && entries.size() < req.pageSize()) {
            Entry e = iter.next();
            entries.add(e);
            lastKey = e.key();
        }
        byte[] token = iter.hasNext()
            ? new ScanToken(lastKey, snapshot.actualSequence(),
                            degraded ? SNAPSHOT_DEGRADED : 0).encode()
            : null;
        return new PageResponse(entries, token, degraded);
    } finally {
        releaseSnapshot(snapshot);
    }
}
```

---
*Researched: 2026-04-13 | Next review: 2026-07-13*
