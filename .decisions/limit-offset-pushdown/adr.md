---
problem: "limit-offset-pushdown"
date: "2026-04-14"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/"
---

# ADR — LIMIT/OFFSET Partition Pushdown

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Distributed Join Execution Strategies | LIMIT/OFFSET pushdown section — top-N merge pattern | [`.kb/distributed-systems/query-execution/distributed-join-strategies.md`](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md) |
| Distributed Scan Cursor Management | Continuation token model, keyset pagination, token encoding | [`.kb/distributed-systems/query-execution/distributed-scan-cursors.md`](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md) |

## Related ADRs
| ADR | Relationship |
|-----|-------------|
| [scatter-gather-query-execution](../scatter-gather-query-execution/adr.md) | Parent — defines the proxy's k-way merge and naive LIMIT handling |
| [scatter-backpressure](../scatter-backpressure/adr.md) | Foundation — credit-based Flow API with continuation tokens |
| [aggregation-query-merge](../aggregation-query-merge/adr.md) | Sibling — aggregation merge at same proxy layer |

---

## Files Constrained by This Decision
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/` — proxy pagination logic

## Problem
The scatter-gather proxy handles LIMIT by requesting LIMIT rows from each partition and
merging, which is acceptable for first-page queries. OFFSET is handled by requesting
LIMIT+OFFSET rows from every partition — O(P * (LIMIT+OFFSET)) network traffic. Deep
pagination (large OFFSET) causes proportional over-fetching that scales with both
partition count and offset depth. How should LIMIT/OFFSET be optimized?

## Constraints That Drove This Decision
- **Large OFFSET must not cause proportional over-fetch** (weight 3): O(P * OFFSET) makes
  deep pagination impractical at high partition counts
- **Global ordering correctness** (weight 3): results must reflect the correct N-th through
  (N+LIMIT)-th entries in global key order
- **Continuation token compatibility** (weight 2): must build on the stateless paging model
  already adopted in scatter-backpressure ADR

## Decision
**Chosen approach: Top-N Pushdown with Keyset Pagination**

Replace positional OFFSET with cursor-based (keyset) pagination using continuation tokens.
For LIMIT queries, each partition returns its local top-LIMIT rows in key order; the
coordinator k-way merges and takes the global top-LIMIT. For subsequent pages, the
continuation token from the previous page provides the exclusive lower bound — each
partition seeks to that position and returns the next LIMIT rows. Every page costs
O(P * LIMIT) regardless of pagination depth. Traditional OFFSET semantics are not exposed
at the distributed API — callers paginate via tokens.

### Scope
- **In scope:** Key-ordered LIMIT queries, keyset pagination via continuation tokens,
  top-N pushdown to partitions
- **Out of scope:** SQL OFFSET as a positional parameter (callers use cursor tokens instead),
  secondary-sort pagination (ORDER BY non-key column), backward pagination

### How It Works

**First page (LIMIT only, no token):**
1. Coordinator fans out query to pruned partitions (partition pruning from scatter-gather ADR)
2. Each partition returns top-LIMIT rows in key order
3. Coordinator k-way merges partition results, emits top-LIMIT globally
4. Last emitted key becomes the continuation token for page 2

**Subsequent pages (LIMIT + token):**
1. Coordinator sends `PageRequest(fromKey=token.lastKey, toKey=queryBound, pageSize=LIMIT)`
2. Each partition seeks to `fromKey` (O(log N) per SSTable, amortized by block cache)
3. Each partition returns next LIMIT rows after `fromKey`
4. Coordinator k-way merges, emits next global page, encodes new token

**Per-page cost:**
- Network: O(P * LIMIT) rows — constant regardless of page number
- Partition seek: O(log N) per SSTable, amortized to ~O(1) via block cache (#seek-cost-amortization)
- Coordinator merge: O(P * LIMIT * log P) via min-heap

## Rationale

### Why Top-N Pushdown with Keyset Pagination
- **Eliminates OFFSET cost**: each page is O(P * LIMIT) regardless of depth. "Large offsets
  remain expensive — keyset (cursor) pagination is preferred"
  ([KB: #limit-offset-pushdown](../../.kb/distributed-systems/query-execution/distributed-join-strategies.md))
- **Builds on existing infrastructure**: continuation tokens already adopted by
  scatter-backpressure ADR. `PageRequest`/`PageResponse` types already defined. k-way merge
  iterator already exists ([KB: #jlsm-applicability](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md))
- **Compaction-safe**: no SSTables pinned between pages. Compaction runs freely. Seek on
  resume finds correct position in new SSTable layout
  ([KB: #stateless-compaction-safety](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md))
- **Standard pattern**: used by Cosmos DB, Cassandra, CockroachDB for distributed pagination
  ([KB: #practical-usage](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md))

### Why not Naive Per-Partition Over-Fetch
- **Hard disqualifier on Scale**: O(P * (LIMIT+OFFSET)) network traffic makes deep pagination
  impractical. At P=100, OFFSET=10000, LIMIT=20: each partition sends 10020 rows.

### Why not Global Offset Coordinator Tracking
- **Hard disqualifier on Accuracy**: requires per-partition row count estimates, which
  LSM trees cannot provide cheaply (tombstones, overlapping levels, concurrent compaction).
  Approximate counts → approximate offset positioning → correctness violation.

## Implementation Guidance

**Token encoding** from [`distributed-scan-cursors.md#continuation-token-encoding`](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md):
```
[8 bytes: sequence_number]
[4 bytes: last_key_length]
[N bytes: last_key]
[1 byte:  flags (snapshot_degraded, scan_complete)]
```
Total: 13 + key_length bytes (21-141 for typical keys).

**Partition-side stateless handler** from [`distributed-scan-cursors.md#code-skeleton`](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md):
- Decode token → extract `last_key` and `sequence_number`
- Acquire read snapshot at `sequence_number`
- Seek to `last_key`, scan forward LIMIT entries
- Encode new token from last returned entry
- Release snapshot (no state retained between pages)

**Coordinator credit budgeting:**
- k-way merge requires P page buffers simultaneously
- Credit budget from scatter-backpressure ADR: `pool_capacity / (page_buffer_size * P * concurrent_queries)`
- Each page request consumes one credit per partition

Known edge cases from [`distributed-scan-cursors.md#edge-cases-and-gotchas`](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md):
- Token after compaction: seek to `last_key` finds correct resume point (keys preserved)
- Token after tombstone compaction: next live key is correct resume position
- Partition split during scan: token's `last_key` may fall outside new range — partition
  returns empty page with `scan_complete`, coordinator routes remaining range to new owner

## What This Decision Does NOT Solve
- Secondary-sort pagination (ORDER BY non-key column) — requires richer token encoding
  that captures position in a secondary index order
- Backward pagination — continuation tokens are forward-only; bidirectional requires
  dual-direction keyset with descending index
- Scan snapshot expiry — if GC watermark advances past the token's sequence number, the
  scan fails with STALE_TOKEN (deferred: scan-snapshot-binding, scan-lease-gc-watermark)

## Conditions for Revision
This ADR should be re-evaluated if:
- jlsm-sql adds ORDER BY on non-key columns — secondary-sort pushdown needs different token format
- Bidirectional pagination becomes a requirement — may need dual-direction token encoding
- OFFSET semantics are required for SQL compatibility — may need to layer positional offset
  on top of keyset as a convenience API (with documented O(P * OFFSET) cost warning)

---
*Confirmed by: user pre-accepted all changes | Date: 2026-04-14*
*Full scoring: [evaluation.md](evaluation.md)*
