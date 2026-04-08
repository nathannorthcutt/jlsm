---
title: "Snapshot Consistency for Multi-Table LSM Joins — MVCC, Tombstones, and Isolation"
aliases: ["join-consistency", "lsm-mvcc-join", "snapshot-isolation-join"]
topic: "systems"
category: "query-processing"
tags: ["join", "lsm-tree", "mvcc", "snapshot", "tombstone", "isolation", "consistency"]
complexity:
  time_build: "N/A"
  time_query: "O(1) snapshot acquisition; O(versions) per key for MVCC resolution"
  space: "O(pinned_versions) space amplification during join"
research_status: "active"
last_researched: "2026-03-30"
applies_to:
  - "modules/jlsm-sql/src/main/java/jlsm/sql"
  - "modules/jlsm-table/src/main/java/jlsm/table"
  - "modules/jlsm-core/src/main/java/jlsm/tree"
sources:
  - url: "https://www.cockroachlabs.com/blog/serializable-lockless-distributed-isolation-cockroachdb/"
    title: "Serializable, Lockless, Distributed: Isolation in CockroachDB"
    accessed: "2026-03-30"
    type: "blog"
  - url: "https://www.cockroachlabs.com/blog/mvcc-range-tombstones/"
    title: "Writing History: MVCC Range Tombstones (CockroachDB)"
    accessed: "2026-03-30"
    type: "blog"
  - url: "https://www.cockroachlabs.com/blog/protected-timestamps-for-less-garbage/"
    title: "Protected Timestamps for Less Garbage (CockroachDB)"
    accessed: "2026-03-30"
    type: "blog"
  - url: "https://notes.eatonphil.com/2024-05-16-mvcc.html"
    title: "Implementing MVCC and SQL Transaction Isolation Levels (2024)"
    accessed: "2026-03-30"
    type: "blog"
  - url: "https://github.com/cockroachdb/cockroach/blob/master/docs/RFCS/20230122_read_committed_isolation.md"
    title: "CockroachDB Read Committed Isolation RFC"
    accessed: "2026-03-30"
    type: "docs"
---

# Snapshot Consistency for Multi-Table LSM Joins

## summary

Consistent joins across multiple LSM-tree-backed tables require a single read snapshot
that spans all tables. LSM trees naturally support MVCC (append-only versioning), but
cross-table consistency demands a global sequence number or timestamp — per-table sequence
numbers are insufficient. The core tension: snapshot pinning prevents garbage collection
of old versions, causing space amplification proportional to join duration. Production
systems resolve this via protected timestamps (CockroachDB), read refreshing (serializable
isolation), and per-statement snapshots (READ COMMITTED for long-running analytics).

## how-it-works

### mvcc-in-lsm-trees

LSM trees are naturally MVCC-compatible:
- **Writes never overwrite** — new versions are appended with higher sequence numbers
- **Reads resolve versions** — for a given snapshot, return the latest version ≤ snapshot
- **Compaction is GC** — removes versions below the oldest active snapshot

**Key encoding (CockroachDB/Pebble pattern):**
```
[user_key][MVCC_timestamp]
```
Pebble's `Comparer.Split` separates user key from timestamp suffix, enabling:
- Timestamp-aware iteration (skip versions above snapshot)
- Range key masking (hide point keys covered by range tombstones)
- Block property filters (skip entire blocks of invisible versions)

### global-vs-per-table-sequence-numbers

| Approach | Cross-table Consistency | Coordination Cost | Used By |
|----------|------------------------|-------------------|---------|
| Global sequence/timestamp | Consistent cut guaranteed | Central oracle or HLC | CockroachDB, TiDB, YugabyteDB |
| Per-table sequence number | Requires explicit coordination | Low (no central oracle) | Some embedded KV stores |
| Hybrid Logical Clock (HLC) | Consistent within clock skew bound | Node-local with NTP sync | CockroachDB |

**For jlsm:** a global sequence number across all tables in a database instance is the
simplest correct approach. Each write (to any table) receives the next sequence number.
A join snapshot captures one sequence number and uses it for all table reads.

### key-parameters

| Parameter | Description | Impact |
|-----------|-------------|--------|
| Snapshot sequence number | Point-in-time for consistent reads | All tables see same "time" |
| GC watermark | Oldest active snapshot sequence | Versions below this can be compacted away |
| Protected timestamp TTL | How long to prevent GC | Longer = more space amplification |
| Read refresh window | How far ahead snapshot can be advanced | Wider = fewer transaction restarts |

## algorithm-steps

### snapshot-acquisition-for-join
1. **Acquire global snapshot** — record the current max sequence number across all tables
2. **Open iterators** on all participating tables, pinned to the snapshot
3. **Execute join** — all reads resolve versions ≤ snapshot sequence number
4. **Release snapshot** — allows GC of versions below this sequence number
5. **Update GC watermark** — advance to min(active_snapshots)

### tombstone-visibility-during-joins

**Point tombstones:**
- A delete in one table writes a tombstone with a sequence number
- If tombstone sequence > join snapshot: invisible (join sees pre-delete state)
- If tombstone sequence ≤ join snapshot: visible (join correctly excludes deleted row)
- Tombstones in one table do not affect the other table's data

**Range tombstones (advanced, CockroachDB pattern):**
- Single `{StartKey, EndKey, Timestamp}` marks entire keyspan as deleted
- Pebble's range key masking transparently hides covered point keys
- Block property filters skip entire data blocks of deleted keys
- Can reduce latency "by several orders of magnitude" for bulk-delete scenarios

**Cross-table tombstone consistency:**
- If row A in table1 references row B in table2, and B is deleted:
  - With snapshot isolation: both tables read at same snapshot, so either both see B or neither does
  - Without snapshot isolation: join may see A's reference to B but not find B → dangling reference

### long-running-join-strategies

| Strategy | Isolation Level | Snapshot Behavior | Space Impact |
|----------|----------------|-------------------|-------------|
| Single snapshot | SERIALIZABLE | One snapshot for entire join | High — pins all versions |
| Per-statement | READ COMMITTED | New snapshot per batch/chunk | Low — frequent release |
| Protected timestamps | Any | Explicit GC protection range | Controlled — explicit TTL |
| Read refreshing | SERIALIZABLE | Advance snapshot if no conflicts | Medium — opportunistic |

**CockroachDB read refreshing:**
If a transaction's snapshot becomes stale (conflicting writes), verify that re-reading
at a higher timestamp would return the same results. If so, advance the snapshot without
restarting the transaction. Enables long-running joins under SERIALIZABLE without
excessive restarts.

**Chunked joins (recommended for jlsm):**
```
for each chunk of 10K outer rows:
    snapshot = acquire_snapshot()
    results = execute_join_chunk(chunk, snapshot)
    emit(results)
    release_snapshot(snapshot)
```
Tradeoff: weaker consistency (chunks may see different data versions), but prevents
unbounded space amplification. Acceptable for analytics; not for transactional joins.

## implementation-notes

### for-jlsm-implementation

**Minimum viable snapshot isolation:**
1. Global atomic counter (`AtomicLong sequenceNumber`) shared across all tables
2. Each write (put, delete) to any table stamped with `sequenceNumber.getAndIncrement()`
3. Snapshot = current value of sequenceNumber at join start
4. All iterators filter: only return entries with seq ≤ snapshot
5. Compaction GC watermark = min(all active snapshot seq numbers)

**Tombstone handling:**
- Delete writes a tombstone entry with current sequence number
- Iterator skips tombstoned keys (returns next live version ≤ snapshot)
- Compaction drops tombstones only below GC watermark AND at bottom level
- During join: tombstone in table A correctly hides deleted row from join output
  as long as tombstone seq ≤ snapshot

**Space amplification control:**
- Track active snapshots in a sorted set
- Warn or error if oldest snapshot is more than N seconds old
- Consider automatic snapshot expiry with configurable TTL
- Protected timestamp pattern: explicit registration of long-lived snapshots
  with mandatory TTL

### edge-cases-and-gotchas
- **Snapshot pinning feedback loop:** long join pins snapshot → prevents compaction →
  tombstones accumulate → scan performance degrades → join takes longer → pins longer
- **Sequence number overflow:** use long (64-bit); at 1M writes/sec, lasts 292K years
- **Iterator invalidation:** without snapshot pinning, compaction can delete SSTables
  that an iterator is reading. Must either pin SSTables or use reference counting
- **Write-write conflicts during join:** if join is part of a read-modify-write
  transaction, concurrent writes to joined rows require conflict detection
- **Cross-table atomic writes:** if a transaction writes to multiple tables, all writes
  must receive adjacent sequence numbers or use a transaction ID to group them

### compaction-interaction
- Snapshot-pinned versions cannot be GC'd — space amplification
- CockroachDB "protected timestamps" explicitly register timestamp ranges as non-GC-able
- YugabyteDB tracks per-version histograms in SST files to detect version accumulation
- **Best practice:** monitor oldest active snapshot age; alert if > configured threshold

## tradeoffs

### strengths
- LSM trees are naturally MVCC-compatible (append-only, no in-place update)
- Global sequence number is simple and correct for single-node embedded use
- Snapshot isolation eliminates phantom reads and dirty reads in joins
- Range tombstones can dramatically reduce scan overhead for bulk-delete patterns

### weaknesses
- Snapshot pinning is inherent — no way to avoid some space amplification
- Long-running joins create tension between consistency and GC
- Per-table sequence numbers are insufficient for cross-table joins
- Write-write conflict detection adds complexity for read-modify-write transactions

## practical-usage

### when-to-use
- Any join across multiple LSM-backed tables that requires consistent results
- Transaction isolation for read-modify-write patterns involving joins
- Analytics queries where point-in-time consistency matters

### when-not-to-use
- Single-table queries (snapshot per table is sufficient)
- Best-effort analytics where slight inconsistency is acceptable (per-statement snapshots)
- Write-only workloads (no reads to isolate)

## code-skeleton

```
class SnapshotManager:
    sequence: AtomicLong
    active_snapshots: SortedSet  # min element = GC watermark

    def acquire() -> Snapshot:
        seq = sequence.get()
        active_snapshots.add(seq)
        return Snapshot(seq)

    def release(snapshot):
        active_snapshots.remove(snapshot.seq)

    def gc_watermark() -> long:
        return active_snapshots.first() if active_snapshots else sequence.get()

class JoinExecutor:
    def execute_join(table_a, table_b, join_key):
        snapshot = snapshot_manager.acquire()
        try:
            iter_a = table_a.iterator(snapshot)
            iter_b = table_b.iterator(snapshot)
            yield from sort_merge(iter_a, iter_b, join_key)
        finally:
            snapshot_manager.release(snapshot)
```

## sources

1. [CockroachDB Isolation](https://www.cockroachlabs.com/blog/serializable-lockless-distributed-isolation-cockroachdb/) — SSI with HLC
2. [MVCC Range Tombstones](https://www.cockroachlabs.com/blog/mvcc-range-tombstones/) — range delete optimization
3. [Protected Timestamps](https://www.cockroachlabs.com/blog/protected-timestamps-for-less-garbage/) — snapshot pinning management
4. [MVCC Implementation (2024)](https://notes.eatonphil.com/2024-05-16-mvcc.html) — fundamentals over KV stores
5. [Read Committed RFC](https://github.com/cockroachdb/cockroach/blob/master/docs/RFCS/20230122_read_committed_isolation.md) — per-statement snapshots

---
*Researched: 2026-03-30 | Next review: 2026-09-30*
