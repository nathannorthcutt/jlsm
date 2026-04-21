---
{
  "id": "partitioning.corruption-repair-recovery",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "partitioning"
  ],
  "requires": [
    "F26",
    "F32"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "corruption-repair-recovery",
    "per-block-checksums",
    "sstable-end-to-end-integrity"
  ],
  "kb_refs": [
    "systems/database-engines/corruption-detection-repair",
    "systems/database-engines/wal-recovery-patterns"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F48"
  ]
}
---
# partitioning.corruption-repair-recovery — Corruption Repair and Recovery

## Requirements

### Quarantine

R1. When a `CorruptBlockException` (F26) is thrown during a read, the partition manager must mark the affected SSTable as quarantined in the partition's metadata. The quarantine record must include the SSTable file path, the corrupt block offset, the expected CRC32C, the actual CRC32C, and the timestamp of detection.

R2. A quarantined SSTable must remain on storage (object storage or local disk). Quarantine must never delete an SSTable — the file is preserved for forensic analysis and possible manual recovery.

R3. The partition manager must exclude quarantined SSTables from the normal read path. Reads that would have consulted a quarantined SSTable must instead return results from the remaining SSTables and MemTable, with a boolean flag on the response indicating that results may be incomplete due to quarantined data.

R4. The partition manager must expose a method to list all quarantined SSTables for a partition, returning the quarantine records from R1.

R5. The partition manager must expose a method to un-quarantine an SSTable (restore it to the active read path). Un-quarantine must re-verify the previously-corrupt block's CRC32C before restoring. If the block is still corrupt, un-quarantine must fail with `CorruptBlockException`.

R6. When a quarantined SSTable is successfully repaired (replaced by a new SSTable covering the same key range via any repair strategy in this spec), the quarantine record must be removed and the quarantined file must be eligible for deletion by the normal SSTable cleanup process.

### Background scrubbing

R7. The partition manager must support a `scrub()` operation that sequentially reads every block of every active (non-quarantined) SSTable for a partition and verifies each block's CRC32C checksum.

R8. The `scrub()` operation must accept a rate limiter parameter (bytes per second) that throttles scrub read I/O. The scrub must acquire byte allowance from the rate limiter before reading each block, blocking if insufficient allowance is available.

R9. The `scrub()` operation must be resumable. It must accept an optional start position (SSTable file path and block index) and resume from that position. If no start position is provided, scrubbing starts from the first block of the first SSTable.

R10. The `scrub()` operation must return a scrub result containing: the number of blocks verified, the number of corrupt blocks found, a list of quarantine records for newly-quarantined SSTables, and the position at which scrubbing stopped (for resumption).

R11. If `scrub()` detects a corrupt block, it must quarantine the affected SSTable (per R1) and continue scrubbing the remaining SSTables. A single corrupt block must not abort the entire scrub.

R12. The `scrub()` operation must not block concurrent read or write operations on the partition. Scrubbing reads are independent of the foreground I/O path.

R13. The `scrub()` operation must be safe to invoke concurrently from multiple threads for different partitions. Concurrent scrubbing of the same partition must be serialized — a second invocation while a scrub is in progress must return immediately with an empty result and a flag indicating that a scrub is already running.

### Single-node repair: compaction-based

R14. When an SSTable is quarantined and the corrupt key range overlaps with entries in other SSTable levels, the partition manager must support triggering a targeted compaction of the overlapping key range from the non-quarantined levels. The compaction output replaces the quarantined SSTable's contribution for the overlapping range.

R15. Targeted compaction (R14) must use the same compaction mechanism as normal compaction (SpookyCompactor or configured strategy). The only difference is input selection: only non-quarantined SSTables overlapping the corrupt key range are selected as input.

R16. After targeted compaction completes, the partition manager must verify that the output SSTable covers the entire key range of the quarantined SSTable. If coverage is incomplete (some keys existed only in the quarantined SSTable and no other level), the partition manager must log a warning identifying the uncovered key range and retain the quarantine record.

R17. Targeted compaction must verify CRC32C checksums on all input blocks (per F26). If a second SSTable is discovered to be corrupt during targeted compaction, it must be quarantined and the compaction must continue with the remaining inputs.

### Replica-based repair: read repair

R18. When replication is enabled (F32, replication factor > 1) and a `CorruptBlockException` is detected during a leader read, the leader must attempt read repair before returning an error to the client.

R19. Read repair must request the affected key range from a follower replica. The leader must select the follower with the highest `matchIndex` (F32 R6) as the repair source, because it is most likely to have the complete data.

R20. If the follower returns valid data for the requested key range, the leader must write the repaired entries to a new SSTable, quarantine the corrupt SSTable (R1), and serve the read from the repaired data. The read must succeed transparently — the client must not observe the corruption.

R21. If no follower can provide valid data for the key range (all replicas are corrupt for that range), read repair must fail and the leader must return the read error to the client with the quarantine metadata from R3.

R22. Read repair must be bounded by a configurable timeout (default: 5 seconds). If the repair does not complete within the timeout, the leader must return the read error to the client and schedule an asynchronous anti-entropy repair (R26) for the affected partition.

R23. The partition manager must emit a structured event when read repair succeeds, containing: partition ID, SSTable file path, block offset, repair source node ID, and repair duration in milliseconds.

R24. The partition manager must emit a structured event when read repair fails, containing: partition ID, SSTable file path, block offset, failure reason (timeout, all-replicas-corrupt, or follower-unavailable), and the number of followers attempted.

### Replica-based repair: anti-entropy

R25. The partition manager must support an anti-entropy repair operation that compares the partition's data against a replica using Merkle tree comparison. Anti-entropy detects all data divergence between two replicas, not just corruption discovered during reads.

R26. The anti-entropy operation must build a Merkle tree over the partition's SSTable data. Each leaf of the Merkle tree must cover a configurable key range granularity (default: one SSTable data block's key range). The hash for each leaf must be computed over the sorted key-value entries in that range.

R27. The Merkle tree depth must be bounded. The maximum number of leaves must be configurable (default: 32,768). If the partition contains more blocks than the maximum leaf count, multiple blocks must be grouped into a single leaf.

R28. Anti-entropy must exchange Merkle trees with the repair source replica. Only the tree structure (hashes, not data) is transferred initially. The tree exchange must identify all divergent leaf ranges in O(tree size) comparisons.

R29. For each divergent range identified by the Merkle tree comparison, the partition manager must stream the key-value entries from the repair source for that range and write them to a new SSTable. The existing local data for the divergent range must be quarantined.

R30. The anti-entropy operation must accept a rate limiter parameter (bytes per second) that throttles data streaming from the repair source. This limit is independent of the scrub rate limiter (R8).

R31. The anti-entropy operation must be safe to run concurrently with foreground reads and writes. Divergent ranges are repaired incrementally — each repaired range becomes available as soon as its replacement SSTable is written.

R32. The partition manager must emit a structured event when anti-entropy completes, containing: partition ID, repair source node ID, number of divergent ranges found, total bytes streamed, and duration in milliseconds.

R33. The partition manager must emit a structured event when anti-entropy detects no divergence, containing: partition ID, repair source node ID, number of leaves compared, and duration in milliseconds.

### Replica-based repair: targeted replica fetch

R34. When a specific block is quarantined and the partition has replication enabled (F32), the partition manager must support requesting the quarantined block's key range from a replica and writing the result to a replacement SSTable.

R35. Targeted replica fetch must select the repair source using the same follower selection as read repair (R19).

R36. Targeted replica fetch must verify the CRC32C of the received data before writing the replacement SSTable. If the received data fails verification, the partition manager must try the next available follower. If all followers fail, the fetch must fail with an `IOException` identifying the partition ID, block offset, and the number of replicas attempted.

R37. After a successful targeted replica fetch, the quarantined SSTable must be updated per R6 (quarantine record removed, file eligible for cleanup).

### Repair strategy selection

R38. The partition manager must select a repair strategy based on the current configuration. When replication is enabled (replication factor > 1), the default repair order must be: (1) read repair for corruption discovered during reads, (2) targeted replica fetch for quarantined blocks discovered by scrubbing, (3) anti-entropy for full partition verification. When replication is disabled (replication factor = 1), only single-node strategies are available: quarantine (R1-R6) and compaction-based repair (R14-R17).

R39. The repair strategy must be overridable per invocation. The partition manager must accept an optional `RepairStrategy` enum parameter (QUARANTINE_ONLY, COMPACTION, REPLICA_FETCH, ANTI_ENTROPY) on repair operations to force a specific strategy.

### Configuration

R40. The scrub rate limiter default must be 10 MiB/s (10,485,760 bytes per second). The builder must reject values less than or equal to zero with an `IllegalArgumentException`.

R41. The anti-entropy rate limiter default must be 32 MiB/s (33,554,432 bytes per second). The builder must reject values less than or equal to zero with an `IllegalArgumentException`.

R42. The read repair timeout (R22) must be configurable via the partition manager builder. The default must be 5 seconds. The builder must reject values less than or equal to zero with an `IllegalArgumentException`.

R43. The Merkle tree maximum leaf count (R27) must be configurable via the partition manager builder. The default must be 32,768. The builder must reject values less than 1 with an `IllegalArgumentException`.

R44. The scrub rate limiter, anti-entropy rate limiter, read repair timeout, and Merkle tree maximum leaf count must all be settable via the partition manager builder. Each must enforce the validation constraints from R40-R43.

### Thread safety

R45. Quarantine metadata updates (R1, R5, R6) must be atomic with respect to concurrent reads. A read must observe either the pre-quarantine or post-quarantine state, never a partial update.

R46. The quarantine metadata must be readable without blocking by all request-processing threads.

---

## Design Narrative

### Intent

Define the repair and recovery strategies for data corruption detected by
per-block CRC32C checksums (per-block-checksums ADR, F26 detection). This
spec resolves the deferred decision corruption-repair-recovery. The approach
layers three repair strategies — single-node quarantine + compaction, and
replica-based read repair + anti-entropy + targeted fetch — composable based
on whether replication is configured.

### Why layered strategies

No single repair strategy handles all corruption scenarios:

- **Read repair** (R18-R24) is opportunistic and fast (sub-second for a single
  block) but only fixes corruption encountered during active reads. Data that
  is never read remains corrupt.

- **Targeted replica fetch** (R34-R37) is precise (repairs exactly the affected
  block's key range) but requires knowing which blocks are corrupt. It pairs
  naturally with scrubbing, which identifies corrupt blocks proactively.

- **Anti-entropy** (R25-R33) is comprehensive (detects all divergence, not just
  CRC failures) but expensive (requires building and exchanging Merkle trees).
  It is the fallback when targeted strategies cannot resolve corruption or
  when a full consistency check is desired.

- **Compaction-based repair** (R14-R17) is the only option when replication is
  not configured. It works when the corrupt key range has overlapping data in
  other SSTable levels, which is common in LSM trees with multiple levels.
  It cannot repair data that exists only in the quarantined SSTable.

The layered approach lets consumers choose their durability/cost tradeoff:
a single-node deployment gets quarantine + compaction repair; a replicated
deployment additionally gets automatic read repair and periodic anti-entropy.

### Why Merkle trees for anti-entropy

Cassandra's anti-entropy repair uses Merkle trees (depth 15, ~32K leaves)
and this is the proven approach for detecting divergence between replicas.
The key property is that Merkle tree exchange is O(tree size), not O(data
size) — two replicas exchange ~260 KB of hashes (32K leaves x 8 bytes) to
identify divergent ranges in a partition that may contain gigabytes of data.
Only the divergent ranges are streamed, making repair I/O proportional to
the amount of corruption, not the partition size.

The default leaf count of 32,768 provides block-level granularity for
partitions up to ~32K blocks (128 MiB at 4 KiB blocks). For larger
partitions, multiple blocks are grouped per leaf, reducing granularity but
keeping the tree size bounded.

### Why quarantine over deletion

Quarantined SSTables are preserved, not deleted, for three reasons:
(1) forensic analysis — understanding why corruption occurred may reveal
hardware faults or software bugs that need systemic fixes; (2) manual
recovery — an operator may be able to extract valid data from a partially
corrupt SSTable; (3) safety — automatic deletion of data, even corrupt
data, is a destructive operation that should require explicit operator
action.

### Interaction with F26 (SSTable End-to-End Integrity)

F26 defines the detection mechanisms: per-block CRC32C verification on every
read, `CorruptBlockException` for failures, and the recovery scan for
walking the data section without the compression map. This spec (F45) builds
on F26 by defining what happens after corruption is detected: quarantine the
SSTable, serve from remaining data, and repair using one of the layered
strategies.

### Interaction with F32 (Partition Replication)

Replica-based repair strategies (R18-R37) depend on F32's Raft replication
protocol. The leader performs read repair using follower data. Anti-entropy
exchanges Merkle trees between replicas in the same Raft group. Targeted
replica fetch uses the same follower-selection logic. All three strategies
use the Raft `matchIndex` to select the most up-to-date follower as the
repair source.

### What was ruled out

- **Erasure coding within SSTables:** Intra-file parity blocks (one parity
  block per N data blocks) enable local repair without replica fetch. Deferred
  as an optimization — the complexity of managing parity blocks across
  compaction rewrites is significant, and replica-based repair covers the
  common case.

- **Automatic scrub scheduling:** The spec exposes `scrub()` as a library
  operation with a rate limiter. Scheduling (how often, at what priority) is
  left to the embedding application. A library should not run background
  threads on its own schedule — the consumer controls thread lifecycle.

- **WAL-based SSTable rebuild:** Replaying WAL segments to reconstruct a
  corrupt SSTable requires retaining WAL segments beyond flush. The current
  WAL lifecycle discards segments after flush, making this strategy rarely
  applicable without explicit WAL retention configuration. Noted in the KB
  but not specified here.

- **ML-based anomaly detection:** Statistical analysis of read error rates
  and latency outliers to predict failing media. Interesting but requires
  telemetry infrastructure that a library cannot assume. Noted in the KB
  for future consideration.
