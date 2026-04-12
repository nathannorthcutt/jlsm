---
title: "Multi-Writer WAL Design Patterns"
aliases: ["concurrent-wal", "shared-wal", "distributed-wal"]
topic: "distributed-systems"
category: "data-partitioning"
tags: ["wal", "concurrency", "multi-writer", "distributed", "partitioning"]
complexity:
  time_build: "N/A"
  time_query: "N/A"
  space: "per-partition WAL: O(partitions × segment_size)"
research_status: "active"
confidence: "medium"
last_researched: "2026-04-12"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/wal/local/LocalWriteAheadLog.java"
  - "modules/jlsm-core/src/main/java/jlsm/wal/remote/RemoteWriteAheadLog.java"
related:
  - "algorithms/compression/wal-compression-patterns.md"
  - "distributed-systems/data-partitioning/partitioning-strategies.md"
  - "distributed-systems/data-partitioning/cross-partition-query-planning.md"
decision_refs:
  - "wal-compression"
  - "table-partitioning"
  - "concurrent-wal-replay-throttling"
  - "in-flight-write-protection"
  - "partition-replication-protocol"
sources:
  - url: "https://martinfowler.com/articles/patterns-of-distributed-systems/write-ahead-log.html"
    title: "Write-Ahead Log - Patterns of Distributed Systems"
    accessed: "2026-04-12"
    type: "docs"
  - url: "https://en.wikipedia.org/wiki/Write-ahead_logging"
    title: "Write-ahead logging - Wikipedia"
    accessed: "2026-04-12"
    type: "docs"
  - url: "https://questdb.com/docs/concepts/write-ahead-log/"
    title: "QuestDB Write-Ahead Log Documentation"
    accessed: "2026-04-12"
    type: "docs"
  - url: "https://wecode.wepay.com/posts/waltz-a-distributed-write-ahead-log"
    title: "Waltz: A Distributed Write-Ahead Log (WePay)"
    accessed: "2026-04-12"
    type: "blog"
    note: "(not fetched — certificate expired)"
  - url: "https://www.sqlite.org/wal.html"
    title: "SQLite Write-Ahead Logging"
    accessed: "2026-04-12"
    type: "docs"
---

# Multi-Writer WAL Design Patterns

## summary

Multi-writer WAL enables concurrent writers to append to a write-ahead log
without global serialization. Three primary patterns exist: **per-partition
WAL** (each partition owns an independent log — simplest, best isolation),
**shared WAL with segment locking** (single log, writers coordinate via locks
on segments — lower file count), and **optimistic concurrency with sequencer**
(writers append optimistically, a sequencer resolves ordering — highest
throughput). For jlsm's partitioned table model (range partitions with
per-partition co-located indices), per-partition WAL is the natural fit — it
aligns with the existing `WriteAheadLog` interface and requires no coordination
protocol.

## how-it-works

### pattern-1-per-partition-wal

Each partition owns an independent WAL instance. Writers for different
partitions never contend. This is the most common pattern in partitioned
databases.

```
Partition 0 ──→ WAL-0 (independent)
Partition 1 ──→ WAL-1 (independent)
Partition 2 ──→ WAL-2 (independent)
```

**Coordination:** None between partitions. Within a partition, the existing
single-writer lock (`ReentrantLock` in jlsm's WAL implementations)
serializes writes.

**Recovery:** Each partition replays its own WAL independently. No cross-
partition ordering dependency. Partitions can recover in parallel.

**Advantages:** Zero coordination overhead. Perfect isolation — a slow or
failed partition doesn't affect others. Compression context is per-partition
(if streaming compression is used). Aligns with jlsm's existing
`WriteAheadLog` interface — each partition instantiates its own.

**Disadvantages:** More files/directories to manage. Cross-partition
operations (atomic multi-partition writes) require a separate coordination
log. Total WAL storage = sum of per-partition WALs (no sharing).

### pattern-2-shared-wal-segment-locking

A single WAL is shared across partitions/writers. Writers acquire a lock
(or CAS on a write position) to append records. Each record carries a
partition identifier.

```
Writer A ──┐
Writer B ──┼──→ Shared WAL (lock per append)
Writer C ──┘
```

**Coordination:** Lock or CAS on the write position. Writers serialize at
the append point but can prepare their records concurrently.

**Recovery:** Replay scans all records and routes to the correct partition
by the partition ID in the record header. Cross-partition ordering is
naturally preserved (single log = total order).

**Advantages:** Fewer files. Natural total ordering across partitions.
Single fsync covers all writers (group commit optimization).

**Disadvantages:** Contention at the append point limits write throughput.
A failed writer can block others. Recovery must scan the entire log even
if only one partition needs replay.

### pattern-3-optimistic-concurrency-sequencer

Writers append to per-writer staging areas. A sequencer assigns a global
order and merges into the committed log. This is the Waltz pattern (WePay).

```
Writer A ──→ Staging A ──┐
Writer B ──→ Staging B ──┼──→ Sequencer ──→ Committed Log
Writer C ──→ Staging C ──┘
```

**Coordination:** Optimistic — writers don't coordinate with each other.
The sequencer resolves conflicts and assigns sequence numbers. Conflicts
are detected by comparing the writer's expected sequence with the actual.

**Recovery:** Replay the committed log. Uncommitted staging entries are
discarded (the writer retries).

**Advantages:** Highest throughput — writers never block each other.
Sequencer can batch commits. Natural total ordering.

**Disadvantages:** Complexity — requires sequencer infrastructure, staging
areas, conflict resolution. Not a fit for a library (sequencer is a
service-level concern).

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| partitions | Number of independent WAL instances | 1-1000+ | File count, recovery parallelism |
| fsync_strategy | Per-record vs group commit | per-record / batched | Latency vs throughput |
| partition_id_size | Bytes for partition identifier | 4-8 bytes | Per-record overhead in shared WAL |
| max_writers | Concurrent writer threads | 1-32 | Contention in shared WAL pattern |

## algorithm-steps

### per-partition-wal-setup

1. **Partition creation:** when a new partition is assigned to a node, create
   a new `WriteAheadLog` instance for that partition. Use the partition's
   data directory as the WAL directory.
2. **Write path:** route each write to the partition's WAL instance. The
   partition routing is already done by the table layer (PartitionedTable).
3. **Recovery:** on node startup, iterate all partition directories and
   recover each WAL independently. Partitions can recover in parallel using
   virtual threads.
4. **Partition migration:** when a partition moves to another node, the WAL
   segments are part of the transfer. The new owner replays from the last
   checkpoint.

### cross-partition-atomic-writes

For operations that must atomically span multiple partitions:

1. **Two-phase approach:** write a "prepare" record to each involved
   partition's WAL, then write a "commit" record to a coordination log.
   Recovery checks the coordination log to determine which prepared
   transactions committed.
2. **Single coordination WAL:** a separate WAL instance for cross-partition
   transaction records only. This is NOT a shared data WAL — it only
   records commit/abort decisions.

## implementation-notes

### jlsm-integration

jlsm's existing architecture maps naturally to per-partition WAL:

- `table-partitioning` ADR: range partitions with per-partition co-located
  indices. Each partition already has its own data directory.
- `WriteAheadLog` interface: stateless contract — each partition instantiates
  its own. Both `LocalWriteAheadLog` and `RemoteWriteAheadLog` work
  unmodified.
- `PartitionedTable`: already routes writes to the correct partition. The WAL
  is per-partition, not per-table.

**No changes to the WAL interface are needed for multi-writer support.** The
coordination happens at the table/partition layer, not the WAL layer.

### group-commit-optimization

For high-throughput workloads, multiple writes to the same partition can be
batched into a single fsync:

- Writers append to the mmap'd segment (fast, no I/O)
- A background flush thread calls `force()` periodically or when a batch
  threshold is reached
- Writers wait on a condition variable until their batch is flushed

This is an optimization on top of per-partition WAL, not a separate pattern.
It reduces fsync overhead from O(writes) to O(batches).

### edge-cases-and-gotchas

- **Partition split during writes:** if a partition splits while writes are
  in-flight, both halves need their own WAL from the split point forward.
  In-flight writes to the old partition must complete or be rolled back
  before the split is final.
- **Remote WAL and partition migration:** one-file-per-record pattern makes
  migration simple (copy files), but the file count can be large. Consider
  compacting old WAL segments during migration.
- **Recovery ordering across partitions:** per-partition WAL provides no
  cross-partition ordering. If ordering matters (e.g., foreign key
  constraints), a coordination log is needed.

## tradeoffs

### strengths

- Per-partition WAL is the simplest multi-writer pattern
- Zero coordination overhead — scales linearly with partition count
- Perfect isolation — partition failures are independent
- Recovery is parallelizable across partitions
- Maps directly to jlsm's existing architecture

### weaknesses

- More files/directories (one WAL directory per partition)
- Cross-partition atomicity requires separate coordination mechanism
- No total ordering across partitions (only per-partition ordering)

### compared-to-alternatives

- **Shared WAL:** simpler file management, natural total order, but
  contention at append point limits throughput. See
  [partitioning-strategies.md](partitioning-strategies.md) for partition
  model context.
- **Sequencer pattern (Waltz):** highest throughput, but requires service
  infrastructure — not suitable for a library.

## practical-usage

### when-to-use

- Partitioned tables with per-partition data isolation (jlsm's model)
- When partition count is bounded (< 10,000 per node)
- When cross-partition transactions are rare or can use a separate
  coordination mechanism

### when-not-to-use

- When total ordering across all writes is required
- When partition count is very high (millions) — file descriptor pressure
- When cross-partition atomicity is the common case, not the exception

## code-skeleton

```java
// Per-partition WAL lifecycle — table layer coordination
public final class PartitionWalManager {

    private final Map<PartitionId, WriteAheadLog> wals;
    private final WriteAheadLog.Factory walFactory;

    // Called when a partition is assigned to this node
    public void assignPartition(PartitionId id, Path dataDir) {
        Path walDir = dataDir.resolve("wal");
        WriteAheadLog wal = walFactory.create(walDir);
        wals.put(id, wal);
    }

    // Write path — called by PartitionedTable
    public SequenceNumber append(PartitionId id, Entry entry) {
        return wals.get(id).append(entry);
    }

    // Recovery — parallel across partitions
    public void recover() {
        wals.entrySet().parallelStream()
            .forEach(e -> e.getValue().recover());
    }
}
```

## sources

1. [Write-Ahead Log - Patterns of Distributed Systems](https://martinfowler.com/articles/patterns-of-distributed-systems/write-ahead-log.html)
   — foundational pattern definition, single-writer model
2. [QuestDB WAL Documentation](https://questdb.com/docs/concepts/write-ahead-log/)
   — multi-writer WAL enabling concurrent table writes via partitioned WAL
3. [Waltz: Distributed Write-Ahead Log](https://wecode.wepay.com/posts/waltz-a-distributed-write-ahead-log)
   — optimistic concurrency sequencer pattern (not fetched — cert expired)
4. [SQLite WAL](https://www.sqlite.org/wal.html)
   — single-writer WAL with concurrent readers, shared memory coordination

---
*Researched: 2026-04-12 | Next review: 2026-10-12*
