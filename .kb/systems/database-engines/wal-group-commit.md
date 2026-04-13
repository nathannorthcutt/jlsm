---
title: "WAL Group Commit Patterns"
aliases: ["group commit", "batch fsync", "write coalescing"]
topic: "systems"
category: "database-engines"
tags: ["wal", "group-commit", "fsync", "batching", "throughput", "latency", "write-coalescing"]
complexity:
  time_build: "N/A"
  time_query: "O(1) amortized per record"
  space: "O(batch size) buffer"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "systems/database-engines/in-process-database-engine.md"
decision_refs: ["wal-group-commit"]
sources:
  - url: "https://www.postgresql.org/docs/current/wal-configuration.html"
    accessed: "2026-04-13"
    description: "PostgreSQL WAL configuration — commit_delay, commit_siblings"
  - url: "https://sirupsen.com/napkin/problem-10-mysql-transactions-per-second"
    accessed: "2026-04-13"
    description: "MySQL InnoDB group commit — fsync batching measured at ~5.5 tx/fsync"
  - url: "https://www.postgresql.org/docs/current/wal-async-commit.html"
    accessed: "2026-04-13"
    description: "PostgreSQL asynchronous commit and group commit interaction"
  - url: "https://lmax-exchange.github.io/disruptor/disruptor.html"
    accessed: "2026-04-13"
    description: "LMAX Disruptor — lock-free ring buffer for batched event processing"
  - url: "https://infoscience.epfl.ch/server/api/core/bitstreams/afd5d2c0-8d90-49ef-9aa6-d75eb70aabdd/content"
    accessed: "2026-04-13"
    description: "Aether: A Scalable Approach to Logging (VLDB 2010) — four WAL bottlenecks and solutions"
  - url: "http://www.vldb.org/pvldb/vol11/p135-jung.pdf"
    accessed: "2026-04-13"
    description: "Scalable Database Logging for Multicores (VLDB 2018) — per-core log partitioning"
  - url: "https://link.springer.com/chapter/10.1007/978-3-031-77153-8_2"
    accessed: "2026-04-13"
    description: "Asynchronous I/O Persistence via io_uring for In-Memory DB Servers (LNCS 2024)"
  - url: "https://link.springer.com/chapter/10.1007/978-3-319-96893-3_3"
    accessed: "2026-04-13"
    description: "Plover: Parallel In-Memory Database Logging on Scalable Storage Devices (Springer 2018)"
---

## Problem

Per-record fsync dominates WAL write latency. A single fsync on commodity SSD
takes 50--200 us; on HDD, 5--10 ms. When each WAL append triggers its own
fsync, throughput is bounded by `1 / fsync_latency` — roughly 5,000--20,000
records/s on SSD, and 100--200 records/s on spinning disk. Group commit
amortizes one fsync across N concurrent records, raising effective throughput
to `N / fsync_latency`.

## Core Mechanic

Group commit collects WAL records from multiple concurrent writers into a
shared buffer and flushes them with a single fsync. The sequence is:

1. **Enqueue** — each writer appends its record to a shared WAL buffer and
   parks (blocks) on a per-record completion signal.
2. **Drain** — a designated flush thread (or elected leader) waits for a
   trigger condition, then copies the accumulated batch to the WAL file.
3. **Sync** — the flush thread issues one fsync (or `MappedByteBuffer.force()`
   / `FileChannel.force(true)`) covering the entire batch.
4. **Wake** — all parked writers whose records were included in the flush are
   unparked and may return to their callers.

The amortization ratio is `batch_size / 1` — one I/O syscall replaces N.

## Leader-Follower Model

PostgreSQL uses a leader-follower variant. The first transaction to reach the
commit point becomes the **group leader**. It optionally sleeps for
`commit_delay` microseconds (default 0) to allow other transactions to queue
behind it, then performs XLogFlush for the entire batch. Followers detect that
their LSN has already been flushed and return immediately.

Key parameters:
- **commit_delay**: max microseconds the leader waits before flushing.
- **commit_siblings**: minimum number of concurrently active transactions
  required before the leader applies `commit_delay` (avoids wasting time when
  load is low).

InnoDB uses a similar staged model (prepare/redo/binlog/commit), applying
group commit at each flush stage. Measurements show ~5.5 transactions per
fsync on moderate load.

## Batching Strategies

| Strategy | Trigger | Trade-off |
|---|---|---|
| **Time-based** | Flush every N ms (or us) | Predictable tail latency; wastes time at low load |
| **Count-based** | Flush every N records | Good throughput; unbounded latency if records arrive slowly |
| **Size-based** | Flush every N bytes | Matches I/O granularity; same latency risk as count-based |
| **Hybrid** | Whichever trigger fires first | Best general-purpose; slightly more complex |
| **Opportunistic** | Flush when leader is ready, take whoever is queued | Zero added latency; batch size depends on contention |

PostgreSQL uses opportunistic + optional time-based delay (`commit_delay`).
RocksDB uses opportunistic grouping in its `WriteThread` — the first writer
becomes leader, collects a batch from the write queue, writes and syncs, then
wakes followers.

## Latency vs Throughput

- **No batching**: latency = 1 x fsync; throughput = 1 / fsync_latency.
- **With batching**: latency = fsync + batch_wait; throughput = batch_size / (fsync + batch_wait).

On SSD (fsync ~100 us), a 100 us commit_delay doubles the batch window —
worthwhile only above ~5,000 concurrent writes/s. On HDD (fsync ~8 ms), even
a 1 ms delay is negligible relative to fsync cost, so group commit is almost
always beneficial. Time-based delays add a fixed floor to p99 latency;
opportunistic batching avoids this.

## Implementation Patterns

### Disruptor-Style Ring Buffer

The LMAX Disruptor pattern maps well to group commit:
- Pre-allocated ring buffer holds WAL record slots.
- Producers claim a sequence number (CAS on a counter), write their record
  into the slot, and publish by advancing their sequence.
- A single flush consumer reads all published-but-unflushed entries in one
  batch, writes them to the WAL file, issues fsync, then advances the flush
  cursor.
- Producers that are waiting on durability spin or park until the flush cursor
  passes their sequence number.

Advantages: lock-free on the hot path, mechanical sympathy (sequential memory
access), bounded buffer.

### Dual-Buffer Swap

Two buffers alternate roles: one accepts new writes ("front"), the other is
being flushed ("back"). When a flush trigger fires, the buffers swap
atomically (single CAS or lock). Writers never block on flush I/O unless both
buffers are full.

Simpler than the ring buffer but front buffer must absorb writes for the
duration of one fsync.

### Write-Behind Queue

A `BlockingQueue` collects write requests. A dedicated flush thread drains,
writes, fsyncs, and completes `CompletableFuture` handles. Simple but
introduces allocation pressure from queue nodes and futures.

## Interaction with mmap

jlsm's `LocalWriteAheadLog` uses `MappedByteBuffer` with `force()` after each
record. Group commit is compatible with mmap, but with caveats:

- **`MappedByteBuffer.force(offset, length)`** (Java 21+): flushes only the
  dirty pages in the specified range. A group commit leader can write N records
  to the mapped buffer, then call `force(batchStart, batchLength)` once.
- **Page granularity**: the OS flushes at page boundaries (4 KiB). Savings
  are proportional to pages avoided versus per-record flushes.
- **No atomic multi-record guarantee**: mmap writes hit the page cache
  immediately. A crash before `force()` may leave partial batches. The WAL
  handles this via per-record CRC — incomplete trailing records are skipped
  during recovery.

Alternative: switch to `FileChannel.write()` + `FileChannel.force(true)` for
explicit write ordering, avoiding mmap's page-cache visibility issue. The
trade-off is losing mmap's zero-copy read path for recovery.

## Virtual Thread Considerations

Java 21+ virtual threads change the group commit calculus:

- **Parking is cheap**: `LockSupport.park()` on a virtual thread unmounts it
  from the carrier. Thousands of writers can park without consuming platform
  threads.
- **No condition variable needed**: each writer parks on its own `Thread`
  reference. The flush leader calls `LockSupport.unpark(writerThread)` after
  fsync.
- **Avoid pinning**: do not hold a `synchronized` monitor across the fsync
  call — this pins the virtual thread to its carrier. Use `ReentrantLock` or
  lock-free structures.
- **Structured concurrency**: `StructuredTaskScope` adds overhead for a tight
  loop — prefer direct park/unpark.

Practical pattern: writers submit to a ring buffer and park. A single platform
thread runs the flush loop (fsync should not be on a virtual thread). After
fsync, the flush thread unparks all waiting virtual threads.

## How Production Systems Do It

| System | Model | Notes |
|---|---|---|
| **PostgreSQL** | Leader-follower, opportunistic | `commit_delay` / `commit_siblings` for time-based delay |
| **InnoDB** | Staged group commit | ~5.5 tx/fsync; lock-free redo log buffer since 8.0 |
| **RocksDB** | Leader-follower via `WriteThread` | Leader collects batch, writes + syncs, wakes followers |
| **LevelDB** | `WriteBatch` — caller-side | No automatic group commit; caller builds batch |
| **LMDB** | Single-writer, COW B-tree | No WAL; one fsync per tx; `MDB_NOSYNC` for batching |
| **SQLite** | WAL mode, frame batching | `PRAGMA synchronous=NORMAL` for group commit |

## Applicability to jlsm

The current `LocalWriteAheadLog` calls `mappedBuffer.force()` after every
record append. This is the correct baseline for durability but becomes the
throughput bottleneck under concurrent write load. Group commit is the
standard mitigation:

- **Minimal change**: keep mmap, add a flush coordinator that accumulates
  records and calls `force(offset, length)` once per batch.
- **Higher throughput ceiling**: switch to `FileChannel.write()` +
  `force(true)` to decouple write buffering from page cache visibility.
- **Virtual thread fit**: jlsm targets Java 25; writers on virtual threads
  can park cheaply while the flush coordinator runs on a platform thread.
- **Configuration surface**: expose `maxBatchWait` (Duration) and
  `maxBatchSize` (int) on the WAL builder; default to opportunistic batching
  (zero delay) so behavior is unchanged unless configured.

## Updates 2026-04-13

### Persistent Memory WAL (No fsync)

Byte-addressable NVM eliminates the fsync that group commit amortizes. The
persistence primitive sequence: `store → clwb → sfence` (~300 ns total, no
syscall). Group commit still reduces fence frequency under extreme concurrency,
but the payoff shrinks by ~300x vs SSD fsync.

```
store(pmem_addr, record)   // durable on store to PM-mapped region
clwb(pmem_addr)            // write-back cache line (non-invalidating)
sfence()                   // ordering fence — all prior clwb visible
```

### io_uring Async WAL

io_uring chains write + fsync into one submission (one syscall replaces two).
~20--30% throughput gain on NVMe vs synchronous write+fdatasync. No JDK API
as of Java 25 — requires Panama FFI or JNI.

```
sqe1 = io_uring_prep_write(fd, buf, len, off)
sqe2 = io_uring_prep_fsync(fd, IORING_FSYNC_DATASYNC)
sqe2.flags |= IOSQE_IO_LINK   // chain: fsync waits for write
io_uring_submit(ring)           // single syscall
cqe = io_uring_wait_cqe(ring)  // block until durable
```

### Per-Core Log Partitioning (Aether / Plover)

Aether (VLDB 2010) identified four bottlenecks: lock contention, flush
latency, buffer coordination, cache coherency. Solutions (early lock release,
flush pipelining, buffer decoupling) yield 2--3x throughput. VLDB 2018 extends
to per-core partitions; Plover adds workload-aware assignment (2x over
centralized). Trade-off: recovery merges N partitions in commit-timestamp
order, scaling linearly with partition count.

```
core_log[core_id].append(record)  // no cross-core contention
core_log[core_id].flush()         // independent flush per core
// recovery: merge-sort all partitions by commit timestamp
```

### CXL Shared Memory Impact

CXL 3.0 memory pooling exposes PM across hosts — log records are durable on
store without network round-trips. Combines NVM primitives (clwb + sfence)
with per-node partitioned regions. Open question: CXL 3.0 does not guarantee
cross-host fence ordering; cross-node commit ordering still needs a
coordination protocol (e.g., CXL.cache back-invalidation).
