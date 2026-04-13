---
title: "Cross-Table Transaction Patterns (Single-Node)"
aliases: ["multi-table transaction", "WriteBatch", "cross-table atomicity"]
topic: "systems"
category: "database-engines"
tags: ["transaction", "atomicity", "wal", "write-batch", "multi-table", "mvcc", "single-node"]
complexity:
  time_build: "N/A"
  time_query: "O(1) per transaction commit"
  space: "O(transaction size)"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "distributed-systems/transactions/cross-partition-protocols.md"
  - "systems/database-engines/catalog-persistence-patterns.md"
  - "systems/query-processing/lsm-join-snapshot-consistency.md"
decision_refs: ["cross-table-transactions"]
sources:
  - url: "https://github.com/facebook/rocksdb/wiki/Column-Families"
    accessed: "2026-04-13"
    description: "RocksDB column families — shared WAL and cross-CF atomic writes"
  - url: "https://github.com/facebook/rocksdb/wiki/Transactions"
    accessed: "2026-04-13"
    description: "RocksDB transaction support — pessimistic and optimistic modes"
  - url: "https://www.sqlite.org/atomiccommit.html"
    accessed: "2026-04-13"
    description: "SQLite atomic commit — rollback journal, super-journal, crash recovery"
---

## summary

Atomically writing to multiple tables on a single node is simpler than
distributed transactions — no network coordination — but still requires a WAL
design that prevents partial commits from becoming visible after a crash. Three
patterns dominate: shared WAL with batch records, per-table WAL with local 2PC,
and write-batch accumulation. The key design choice is whether tables share a
WAL or each own one, which determines recovery complexity and flush coupling.

## pattern 1 — shared WAL with transaction boundaries

All tables write to a single WAL. A transaction groups writes to multiple
tables into one atomic WAL record. Recovery replays only complete records.

**How it works:**

```
// Write path
txn = wal.beginBatch()
txn.put(tableA, key1, val1)
txn.put(tableB, key2, val2)
txn.put(tableA, key3, val3)
wal.commitBatch(txn)   // single fsync, one WAL record

// WAL record layout:
// [batch_header: seqnum, count=3]
// [table_id=A, PUT, key1, val1]
// [table_id=B, PUT, key2, val2]
// [table_id=A, PUT, key3, val3]
// [CRC32 over entire batch]
```

**Recovery:** scan WAL forward. A record is complete if its CRC validates and
the declared count matches parsed entries. Incomplete tail records are
discarded. Each complete record is replayed into the appropriate table's
MemTable.

**This is the RocksDB model.** Column families share a single WAL. A WriteBatch
can reference multiple column families and is written as one atomic WAL record.
The guarantee: "atomically execute Write({cf1, key1, value1}, {cf2, key2,
value2})". Recovery replays the shared WAL into all column families.

**Trade-off:** flush coupling. When one table flushes its MemTable, the WAL
cannot be truncated until *all* tables have flushed past that point. A
low-traffic table delays WAL reclamation for high-traffic tables. RocksDB
mitigates this with `max_total_wal_size` which triggers automatic flushes of
stale column families.

**Best for:** engines where tables share a process and cross-table writes are
common.

## pattern 2 — per-table WAL with local two-phase commit

Each table owns its own WAL. Cross-table atomicity uses a lightweight 2PC
protocol — no network, just coordinated file operations.

**How it works:**

```
// Phase 1 — prepare
txnId = globalTxnCounter.next()
walA.writePrepare(txnId, [{PUT, key1, val1}])
walA.fsync()
walB.writePrepare(txnId, [{PUT, key2, val2}])
walB.fsync()

// Phase 2 — commit
commitLog.writeCommit(txnId, [tableA, tableB])
commitLog.fsync()   // <-- commit point

// Phase 3 — apply
tableA.applyPrepared(txnId)
tableB.applyPrepared(txnId)
```

**Recovery:** scan the commit log. For each committed txnId, replay prepared
records from each table's WAL. For any txnId with prepare records but no
commit, discard (rollback). The commit log entry is the single atomic marker.

**This is the SQLite multi-database model.** When ATTACHed databases are
modified in one transaction, SQLite creates a "super-journal" file listing all
per-database rollback journals. The super-journal's existence is the commit
point — its deletion signals completion. Recovery checks: super-journal exists
and references this journal? Transaction incomplete, roll back.

**Trade-off:** two fsyncs minimum (prepare + commit), versus one for shared
WAL. But each table flushes independently — no flush coupling.

**Best for:** engines where tables have independent lifecycles and cross-table
writes are infrequent.

## pattern 3 — write-batch accumulation

Buffer all writes in memory, then apply as a single atomic operation. No
separate prepare phase — the batch itself is the transaction.

**How it works:**

```
batch = new WriteBatch()
batch.put(tableA, key1, val1)
batch.put(tableB, key2, val2)
batch.delete(tableA, key3)

// Commit: serialize entire batch as one WAL record, then apply
wal.write(batch.serialize())   // single fsync
wal.fsync()
tableA.applyFromBatch(batch)
tableB.applyFromBatch(batch)
```

**Recovery:** identical to pattern 1 — the WAL record is self-contained. If
the record is complete (CRC valid), replay all entries. If truncated, discard.

**Distinction from pattern 1:** pattern 1 streams writes into the WAL as they
happen (with begin/commit markers). Pattern 3 buffers the entire transaction in
memory and writes it as a single record at commit time. Pattern 3 bounds WAL
write amplification (one record per transaction) but requires memory
proportional to transaction size.

**This is the RocksDB WriteBatch model** at the API level. The application
accumulates operations, then calls `db.write(batch)` which serializes and
fsyncs as one unit.

**Best for:** small-to-medium transactions where buffering cost is acceptable.

## MVCC for cross-table reads

Consistent reads across tables require a global sequence number, not per-table
counters. See `lsm-join-snapshot-consistency.md` for full treatment. Key point:
writes from an uncommitted transaction must not be visible to other readers.
Two approaches: (1) assign sequence numbers at commit time (RocksDB — WriteBatch
gets its sequence number when applied), or (2) assign at write time but gate
visibility on a commit map.

## design decision matrix

| Dimension | Shared WAL (pattern 1/3) | Per-table WAL + 2PC (pattern 2) |
|-----------|-------------------------|--------------------------------|
| Atomicity mechanism | Single WAL record | Commit log as coordinator |
| Minimum fsyncs per txn | 1 | 2+ (prepare + commit) |
| Flush coupling | Yes — all tables block WAL reclaim | No — independent flush |
| Recovery complexity | Simple — replay one WAL | Moderate — correlate commit log with per-table WALs |
| Memory overhead | O(batch size) for pattern 3 | O(prepare record size) |
| Table independence | Low — shared lifecycle | High — tables can open/close independently |
| Implementation in jlsm | Natural fit — single WAL exists | Requires commit log infrastructure |

## production references

**RocksDB:** shared WAL across column families. WriteBatch writes one atomic
WAL record spanning multiple CFs. Flush coupling mitigated by
`max_total_wal_size`. **SQLite:** single rollback journal covers all tables in
one database; for ATTACHed databases, a super-journal coordinates per-database
journals (pattern 2). **LevelDB:** no multi-table support; WriteBatch is
single-keyspace only. **InnoDB:** global redo log across all tablespaces;
mini-transactions (mtr) group related page modifications.

## jlsm applicability

jlsm currently uses per-table WAL instances. Cross-table atomicity would
require either: (a) a shared WAL that multiple table handles write to
(pattern 1/3), or (b) a lightweight commit log coordinating per-table WALs
(pattern 2). The shared WAL approach is simpler but introduces flush coupling.
The per-table approach preserves table independence but adds recovery
complexity. The write-batch pattern (pattern 3) layered on a shared WAL is the
most natural fit given that jlsm's WAL already supports batch records.
