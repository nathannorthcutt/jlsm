---
title: "WAL Recovery Patterns"
aliases: ["WAL replay", "crash recovery", "journal recovery", "WAL corruption handling"]
topic: "systems"
category: "database-engines"
tags: ["wal", "recovery", "crash", "replay", "corruption", "checkpoint", "idempotent", "remote-wal"]
complexity:
  time_build: "N/A"
  time_query: "O(WAL records since last checkpoint)"
  space: "O(MemTable capacity)"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "systems/database-engines/corruption-detection-repair.md"
  - "systems/database-engines/wal-group-commit.md"
  - "systems/security/wal-encryption-approaches.md"
decision_refs: ["corruption-repair-recovery"]
sources:
  - url: "https://github.com/facebook/rocksdb/wiki/WAL-Recovery-Modes"
    accessed: "2026-04-13"
    description: "RocksDB WAL recovery modes documentation"
  - url: "https://github.com/google/leveldb/blob/main/doc/log_format.md"
    accessed: "2026-04-13"
    description: "LevelDB log format specification"
---

## summary

WAL recovery rebuilds MemTable state from durable log records after a crash.
The core tension is between **consistency** (reject any corruption) and
**availability** (recover as much data as possible). Production systems resolve
this with configurable recovery modes that sit on a spectrum from strict to
permissive. Two physical models dominate: multi-record segment files (local
disk) and one-file-per-record (remote/object storage), each with distinct
failure modes and recovery strategies.

## how-it-works

Recovery runs at startup, before the store accepts new writes. It has two
distinct flows depending on the WAL's physical layout.

**Local (segment-based):** The WAL directory contains ordered segment files,
each holding many records. Recovery memory-maps each segment, scans forward
decoding records via CRC validation, and rebuilds the segment index (segment
number to first sequence number). The last segment becomes the active write
target; `writePosition` is set to the byte offset after the last valid record.

**Remote (one-file-per-record):** Each WAL record is an immutable object named
by its sequence number (`wal-{seqnum:016d}.log`). Recovery lists objects,
sorts by name, and derives the next sequence number from the highest filename.
No record scanning is needed for sequence recovery -- O(1) via the last
filename. Replay reads each file independently, which is trivially
parallelizable.

## algorithm-steps

1. **List segments** -- enumerate WAL files in the directory, sorted by segment
   number (local) or sequence number (remote).
2. **Determine checkpoint fence** -- identify the highest sequence number that
   was durably flushed to SSTables. Records at or below this fence are
   redundant; replay starts from `fence + 1`.
3. **Scan segments** -- for each segment file from the starting point:
   a. Map or read the file contents.
   b. Decode records sequentially: read frame length, payload, and CRC.
   c. On CRC match: accept the record, advance position, reset skip counter.
   d. On CRC mismatch: apply the configured recovery mode (see tradeoffs).
   e. Track first and last valid sequence numbers per segment.
4. **Restore sequence counter** -- set `nextSequence` to `lastValidSeq + 1`.
5. **Replay into MemTable** -- iterate accepted records in sequence order,
   applying Put/Delete operations. Replay is idempotent: applying the same
   record twice produces the same MemTable state because each record carries
   a unique sequence number and key, and the MemTable is keyed on
   `(logicalKey, sequenceNumber)`.
6. **Resume normal operation** -- open the last segment for writing (local) or
   prepare to create new files (remote). The store is now accepting writes.

## implementation-notes

### tail corruption

The most common crash artifact: the final record was mid-write when power
failed. The frame length field may be written but the CRC or payload is
incomplete. Detection: frame length indicates N bytes needed but fewer than N
remain in the segment, or the CRC does not match. Treatment under
tolerant-tail mode: truncate the segment to `validBytes` and continue. This
is safe because `fsync`/`force()` after every record means all prior records
are durable -- only the in-flight record is lost.

### mid-segment corruption

A valid record appears after a corrupt one (bit rot, media error). This is
rarer but harder: skipping one record might land in the middle of the next
record's payload, misinterpreting data as a frame header. Strategies:

- **Frame-length skip:** read the corrupt record's frame length to compute the
  next record boundary. If the frame length itself is corrupt, recovery cannot
  advance safely -- stop or scan for a known sentinel.
- **Consecutive skip limit:** jlsm enforces `maxConsecutiveSkips` (default 10).
  If N consecutive records fail CRC, this indicates systematic corruption (wrong
  codec, media failure) rather than isolated bit flips. Recovery aborts with
  `IOException` to prevent silently accepting garbage.

### checkpoint/fence integration

WAL records before the last successful SSTable flush are redundant.
`truncateBefore(upTo)` deletes segments whose records all have sequence numbers
below `upTo`. At recovery time, replay starts from the first segment whose
first sequence number is at or after the fence. This bounds recovery time to
the volume of unflushed data -- typically one MemTable's worth.

### remote WAL specifics

- **Partial uploads:** S3 `PutObject` is atomic -- the object exists completely
  or not at all. No tail corruption at the object level. A crash between WAL
  write and MemTable update is handled by idempotent replay.
- **Listing consistency:** S3 and GCS both provide strong read-after-write
  consistency. Listing after a crash returns all successfully written objects.
- **Sequence recovery:** O(1) -- parse the highest filename. No scanning.

### sequence number recovery

Both implementations derive `nextSequence` from WAL contents at startup: local
scans all segments for the max sequence number; remote reads the last filename.
The counter is set to `max + 1` before accepting new writes.

## complexity-analysis

| Dimension | Local (segment) | Remote (per-record) |
|---|---|---|
| Recovery scan | O(records since checkpoint) | O(list operation + records) |
| Sequence restore | O(records) -- full scan | O(1) -- last filename |
| Replay into MemTable | O(records) both models | O(records) both models |
| Space during recovery | O(segment size) mmap | O(record size) read buffer |

## tradeoffs

Four recovery modes, modeled after RocksDB's WAL recovery modes:

| Mode | On tail corruption | On mid-segment corruption | Guarantees |
|---|---|---|---|
| **Strict** (`kAbsoluteConsistency`) | Fail | Fail | All-or-nothing. Zero tolerance. Use when external replication provides replay. |
| **Tolerant-tail** (`kTolerateCorruptedTailRecords`) | Truncate, continue | Fail | Loses at most the last in-flight record. Safe default for single-node. |
| **Point-in-time** (`kPointInTimeRecovery`) | Stop at error | Stop at error | Recovers to last consistent point. Default in RocksDB >=6.6. Ideal for replicated systems that can catch up from peers. |
| **Skip-all** (`kSkipAnyCorruptedRecords`) | Skip, continue | Skip, continue | Maximum data salvage. May produce gaps in sequence space. Disaster recovery only. |

The tolerant-tail mode is the pragmatic default for most embedded/library
usage: crash-induced tail damage is by far the most common failure, and
discarding one uncommitted record is acceptable when `force()` guarantees
all prior records are durable.

jlsm's current implementation is closest to **point-in-time with bounded
skip tolerance**: it skips corrupt records up to `maxConsecutiveSkips`, then
aborts. This prevents silent data loss from systematic corruption while
tolerating isolated bit flips.

## practical-usage

- **Bound recovery time** by tuning MemTable flush thresholds. Smaller
  MemTables mean more frequent checkpoints and shorter WAL tails.
- **Call `truncateBefore` after every successful flush** to prune obsolete
  segments. Without truncation, recovery replays the entire WAL history.
- **Set `maxConsecutiveSkips`** based on tolerance: 0 for strict, 5-10 for
  tolerant, higher for disaster recovery.
- **Parallel replay** is safe for independent segments (remote model). Local
  segments replay in order, but independent segments can run concurrently if
  the MemTable supports concurrent insertion (`ConcurrentSkipListMap`).
- **Test recovery paths** by killing the process mid-write and verifying
  restart recovers to the expected state.

## code-skeleton

```java
// Simplified local WAL recovery -- core pattern
void recover(Path walDir, long checkpointSeq, MemTable memTable) throws IOException {
    var segments = SegmentFile.listSorted(walDir);
    long maxSeq = checkpointSeq;
    int skips = 0;
    for (Path seg : segments) {
        MemorySegment mapped = mapReadOnly(seg);
        long pos = 0, size = mapped.byteSize();
        while (pos < size) {
            Entry entry;
            try { entry = WalRecord.decode(mapped, pos, size - pos, codecMap); }
            catch (IOException e) {
                int frameLen = readFrameLen(mapped, pos);
                if (frameLen < MIN_FRAME || pos + 4 + frameLen > size) break;
                pos += 4 + frameLen;
                if (++skips > maxSkips) throw e;
                continue;
            }
            if (entry == null) break; // partial tail record
            skips = 0;
            long seq = entry.sequenceNumber().value();
            if (seq > checkpointSeq) memTable.put(entry); // idempotent
            maxSeq = Math.max(maxSeq, seq);
            pos += 4 + readFrameLen(mapped, pos);
        }
    }
    nextSequence.set(maxSeq + 1);
}
```

## sources

- [RocksDB WAL Recovery Modes](https://github.com/facebook/rocksdb/wiki/WAL-Recovery-Modes) -- canonical reference for the four recovery modes and their tradeoffs.
- [LevelDB Log Format](https://github.com/google/leveldb/blob/main/doc/log_format.md) -- original log format with block-aligned records and CRC32C checksums, basis for RocksDB's WAL format.
- jlsm `LocalWriteAheadLog.recover()` and `scanSegment()` -- current implementation using mmap'd segments with CRC validation and consecutive-skip abort.
- jlsm `RemoteWriteAheadLog.Builder.build()` -- O(1) sequence recovery from filename ordering.
