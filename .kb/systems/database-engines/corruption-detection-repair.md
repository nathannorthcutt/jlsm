---
title: "Corruption Detection and Repair Strategies"
aliases: ["corruption repair", "scrubbing", "checksum verification", "data integrity"]
topic: "systems"
category: "database-engines"
tags: ["corruption", "checksum", "repair", "scrubbing", "anti-entropy", "read-repair", "wal-replay"]
complexity:
  time_build: "N/A"
  time_query: "O(1) per block verification"
  space: "O(checksum size) per block"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "systems/database-engines/wal-recovery-patterns.md"
decision_refs: ["corruption-repair-recovery", "sstable-end-to-end-integrity"]
sources:
  - url: "https://rocksdb.org/blog/2021/05/26/online-validation.html"
    title: "Online Validation | RocksDB"
    accessed: "2026-04-13"
  - url: "https://www.gosquared.com/blog/dealing-corrupt-sstable-cassandra"
    title: "Dealing with a corrupt SSTable in Cassandra"
    accessed: "2026-04-13"
  - url: "https://github.com/dgraph-io/badger/issues/1180"
    title: "Proposal: Data integrity check (Silent data corruption detection) - Badger"
    accessed: "2026-04-13"
  - url: "https://docs.datastax.com/en/cassandra-oss/3.x/cassandra/operations/opsRepairNodesManualRepair.html"
    title: "Manual repair: Anti-entropy repair | Apache Cassandra 3.x"
    accessed: "2026-04-13"
  - url: "https://research.google/pubs/detection-and-prevention-of-silent-data-corruption-in-an-exabyte-scale-database-system/"
    title: "Detection and Prevention of Silent Data Corruption in an Exabyte-scale Database System — Bacon, IEEE SELSE 2022"
    accessed: "2026-04-13"
  - url: "https://engineering.fb.com/2025/07/22/data-infrastructure/how-meta-keeps-its-ai-hardware-reliable/"
    title: "How Meta keeps its AI hardware reliable — Engineering at Meta"
    accessed: "2026-04-13"
  - url: "https://dl.acm.org/doi/10.1145/3708994"
    title: "A Survey of the Past, Present, and Future of Erasure Coding for Storage Systems — ACM TOS 2024"
    accessed: "2026-04-13"
---

# Corruption Detection and Repair Strategies

## Overview

LSM-tree storage engines face corruption from hardware faults (bit rot, failed
writes), software bugs (incorrect serialization, compaction errors), and
environmental issues (power loss during flush). The immutability of SSTables
simplifies the problem — once written, an SSTable should never change, so any
change is corruption. Detection requires checksums at every layer; repair
requires either redundant copies or the ability to rebuild from the write-ahead
log.

## Detection Methods

### Per-Layer Checksums

Effective corruption detection requires checksums at every persistence boundary:

| Layer | What to checksum | When to verify |
|---|---|---|
| **WAL records** | CRC per record (covers type + key + value) | On replay; optionally on every append (read-back) |
| **SSTable data blocks** | CRC per block (covers compressed payload) | On every read; on compaction input |
| **SSTable index blocks** | CRC per index block | On SSTable open; on compaction input |
| **Bloom filter** | CRC over serialized filter bytes | On SSTable open |
| **SSTable footer** | Magic number + CRC over metadata | On SSTable open |
| **File-level** | Whole-file checksum in manifest/metadata | On background scrub; after compaction output |

RocksDB verifies block checksums on every read by default. Its
`paranoid_file_checks` option re-reads every key-value pair after SST file
generation to verify comparator order and a content hash. The
`check_flush_compaction_key_order` option (on by default) validates key ordering
during writes.

### Structural Validation

Beyond checksums, structural invariants catch logical corruption:

- **Key ordering** — keys within a block and across blocks must be in comparator
  order; a misordered key indicates a write bug or memory corruption
- **Entry count** — track expected vs actual entry counts during flush and
  compaction (RocksDB added memtable entry count checks in v6.21)
- **Block size bounds** — a block claiming to be larger than the file is corrupt
- **Magic numbers** — footer and header magic values confirm file format identity

### Background Scrubbing

Scrubbing reads every block of every SSTable and verifies checksums without
serving client reads. Design considerations:

- **Frequency**: every 1-4 weeks is typical; randomize start times across nodes
  to avoid I/O storms
- **I/O budget**: throttle scrub reads to a fraction of disk bandwidth (e.g.,
  10-20%) using rate limiters; scrubbing must not starve foreground I/O
- **Incremental**: track progress so a scrub can resume after restart rather than
  restarting from the beginning
- **Reporting**: log corrupted blocks with file path, offset, and expected vs
  actual checksum; surface via metrics/health endpoints

## Single-Node Repair Strategies

### Skip and Quarantine

When corruption is detected during a read:

1. **Return an error for the affected range** — do not silently skip corrupted
   data; the caller must know the read is incomplete
2. **Mark the SSTable as partially corrupt** in metadata — record which blocks
   failed verification
3. **Serve from other levels** — if the same key range exists in another SSTable
   (common in LSM trees with overlapping levels), prefer the uncorrupted copy
4. **Quarantine the SSTable** — move it out of the active set so compaction does
   not propagate corruption; Cassandra's `sstablescrub` rewrites valid data from
   a corrupt SSTable into new clean files, discarding unreadable portions

### WAL-Based Rebuild

If WAL segments covering the corrupted SSTable's key range are still available:

1. Identify the sequence number range of the corrupt SSTable from its metadata
2. Replay the corresponding WAL segments into a new MemTable
3. Flush to a replacement SSTable
4. Remove the corrupt SSTable from the manifest

**Limitations**: WAL segments are typically discarded after successful flush, so
this only works if WAL retention is configured to keep segments longer than the
SSTable lifetime. This is expensive in disk space.

### WAL Corruption

If the WAL itself is corrupt:

- **Tail corruption** (incomplete final record from crash): skip the partial
  record; this is expected and safe — the incomplete write never acknowledged
- **Mid-log corruption**: skip the corrupted record, log a warning, continue
  replay. Data in the corrupted record is lost. CRC per record isolates damage
  to a single entry rather than invalidating the entire log
- **Head corruption** (file header unreadable): the segment is unrecoverable;
  fall back to the previous segment's end state

### Compaction-Based Repair

Compaction naturally heals some corruption scenarios:

- If a corrupted block in level N is compacted with an overlapping range from
  level N+1, the uncorrupted entries from the other level survive
- **Compaction must verify input blocks** — if a corrupted block is read during
  compaction without verification, corruption propagates to the output SSTable
- After quarantining a corrupt SSTable, trigger a targeted compaction of the
  affected key range to reconsolidate data from other levels

## Replica-Based Repair

### Read Repair

On a read involving multiple replicas, compare responses and stream the most
recent version to any stale or corrupt replica. Opportunistic — only fixes data
that is actively read.

### Anti-Entropy Repair

Cassandra uses Merkle trees (depth 15, ~32K leaves) to detect divergence between
replicas. Nodes exchange trees, identify differing subtrees, and stream only the
affected ranges. For 1M partitions with 1 corrupted, roughly 30 partitions are
streamed. Incremental repair tracks already-repaired SSTables to avoid redundant
comparison. Schedule full repair within the tombstone grace period.

### Targeted Replica Fetch

On checksum failure for a specific block, request that block's key range from a
replica, write to a new SSTable, and remove the corrupt one. Faster than full
anti-entropy but requires per-block corruption tracking.

## End-to-End Integrity Across Compaction

Checksums that are tied to block position break across compaction because
compaction rewrites blocks. Two approaches:

- **Recompute on write**: generate new checksums for every compaction output
  block. Simple, but a bug in the compaction writer can produce a valid checksum
  over corrupt output
- **Content-addressed checksums**: hash key-value content independently of block
  position. A whole-file content hash (or per-entry hash) can be compared before
  and after compaction to verify no data was lost or altered. More expensive but
  catches compaction bugs

RocksDB's `paranoid_file_checks` takes the content-addressed approach: it
computes a hash of all key-value pairs during write and re-verifies after the
file is complete.

## Design Recommendations for jlsm

1. **Verify on every read path** — check block CRC on every SSTable read, not
   just on scrub. The cost is small relative to I/O
2. **Verify compaction inputs** — never trust an SSTable block during compaction
   without checking its CRC first
3. **Expose corruption as a typed exception** — callers need to distinguish
   `CorruptBlockException` from `IOException` to decide whether to retry,
   skip, or escalate
4. **Quarantine API** — provide a mechanism to mark an SSTable as corrupt in the
   manifest without deleting it, allowing the tree to route around it
5. **Scrub as a library operation** — expose `scrub()` on the tree or SSTable
   reader with a rate limiter parameter; the embedding application controls
   scheduling
6. **WAL retention option** — allow configurable WAL retention beyond flush to
   enable WAL-based SSTable rebuild
7. **Content hash during flush/compaction** — compute a running hash of key-value
   content during SSTable writes; store in footer for post-write verification

## Updates 2026-04-13

### Fleet-Scale Silent Data Corruption Rates

Google's Spanner team reports detecting and preventing SDC events "several times
per week" across their exabyte-scale fleet (Bacon, IEEE SELSE 2022). Meta
observes ~1 fault per 1,000 devices — far exceeding cosmic-ray soft error rates.
Over 66% of Meta's Llama 3 training interruptions traced to hardware SDC in
SRAMs/HBMs. Both confirm that SDC is an operational reality, not a theoretical
concern, for any system writing persistent data.

### Content-Addressed Integrity (Merkle Trees / Hash DAGs)

Per-entry content hashes survive compaction without recomputation of positional
checksums. A Merkle tree over SSTable entries enables O(log n) divergence
detection between replicas (same principle as Cassandra anti-entropy, but
applicable within a single node across SSTable levels). For jlsm: computing an
incremental hash (e.g., streaming xxHash) per key-value pair during flush and
storing a root hash in the SSTable footer provides end-to-end integrity that
spans compaction rewrites.

### ML-Based Anomaly Detection for Corruption

Meta's Hardware Sentinel detects SDC without dedicated test workloads by
analysing application-level exception patterns (segfaults, core dumps, anomalous
logs) in kernel space. It outperforms testing-based methods by 41% across
architectures. The principle applies at the storage layer: statistical anomaly
detection on read error rates, latency outliers, and checksum failure clustering
can identify failing media before corruption propagates — without requiring
per-block checksums on legacy data.

### Erasure Coding for Local Redundancy

SD codes (sector-disk codes) provide storage-efficient redundancy against mixed
disk and sector failures without full replication overhead. Locally Repairable
Codes (LRC) minimise the number of nodes contacted per repair. For a
single-node LSM library, a lightweight intra-file parity block (e.g., one parity
block per N data blocks within an SSTable) enables local repair of single-block
corruption without replica fetch or WAL replay.

### Adaptive Scrub Scheduling

Rather than fixed-interval scrubbing, schedule scrub I/O proportional to idle
bandwidth. Pseudocode for an adaptive scrubber:

```
scrub_budget = max(0, disk_bandwidth - foreground_io) * SCRUB_FRACTION
bytes_to_scrub = scrub_budget * interval_seconds
for block in next_unscrubed_blocks(bytes_to_scrub):
    if not verify_checksum(block):
        quarantine(block.sstable, block.offset)
    mark_scrubbed(block, now())
```

Meta's Fleetscanner covers their entire fleet every 45-60 days; Ripple achieves
coverage in days by co-locating micro-benchmarks with production workloads.
Adaptive scheduling ensures scrubbing makes progress without starving foreground
I/O during peak load.
