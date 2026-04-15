# Architecture Decisions — Master Index

> **Managed by vallorcine agents. Use slash commands to modify this file.**
> To start a decision: `/architect "<problem>"`
> To review a decision: `/decisions review "<slug>"`

> Pull model. Load on demand only.
> Structure: .decisions/<problem-slug>/adr.md
> Full history: [history.md](history.md)

## Active Decisions
<!-- Proposed or in-progress only. Confirmed/superseded rows move to history.md. -->

| Problem | Slug | Date | Status | Recommendation |
|---------|------|------|--------|----------------|

## Recently Accepted (last 5)
<!-- Once this section exceeds 5 rows, oldest row moves to history.md -->

| Problem | Slug | Accepted | Recommendation |
|---------|------|----------|----------------|
| SSTable End-to-End Integrity | sstable-end-to-end-integrity | 2026-04-14 | Three-layer — fsync discipline + VarInt-prefixed blocks + per-section CRC32C |
| Block Cache / Block Size Interaction | block-cache-block-size-interaction | 2026-04-14 | Per-entry byte-budget eviction via MemorySegment.byteSize() |
| Automatic Backend Detection | automatic-backend-detection | 2026-04-14 | Pool-aware block size configuration — derive from ArenaBufferPool |
| Continuous Re-Discovery | continuous-rediscovery | 2026-04-13 | Periodic loop + optional watchSeeds() for sub-second push-based discovery |
| Membership View Stall Recovery | membership-view-stall-recovery | 2026-04-13 | Tiered Escalation — piggyback → anti-entropy → forced rejoin |

## Deferred
<!-- Topics recorded but not yet evaluated. Resume with /architect "<problem>" -->
<!-- 67 items (was 64: +4 new from binary-field-type, -1 binary-field-type confirmed). Grouped by parent ADR for readability. -->

| Problem | Slug | Deferred | Parent ADR |
|---------|------|----------|------------|
| Distributed Join Execution | distributed-join-execution | 2026-03-20 | scatter-gather-query-execution |
| Default LSM-Backed BlobStore | default-lsm-blob-store | 2026-04-13 | binary-field-type |
| Blob Storage Strategy for Object Storage | blob-object-storage-strategy | 2026-04-13 | binary-field-type |
| Blob Streaming API Design | blob-streaming-api | 2026-04-13 | binary-field-type |
| Inline Small Blob Optimization | inline-small-blob-optimization | 2026-04-13 | binary-field-type |
| Numeric Field Range Bounds | numeric-field-range-bounds | 2026-04-13 | parameterized-field-bounds |
| Quarantine Resolution Policy | quarantine-resolution-policy | 2026-04-13 | string-to-bounded-string-migration |
| Cross-Table Schema Migration | cross-table-schema-migration | 2026-04-13 | string-to-bounded-string-migration |
| Authenticated Discovery | authenticated-discovery | 2026-03-30 | discovery-spi-design |
| Encrypted Prefix/Wildcard Queries | encrypted-prefix-wildcard-queries | 2026-03-30 | encrypted-index-strategy |
| Encrypted Fuzzy Matching | encrypted-fuzzy-matching | 2026-03-30 | encrypted-index-strategy |
| Encrypted Cross-Field Joins | encrypted-cross-field-joins | 2026-03-30 | encrypted-index-strategy |
| Index Access Pattern Leakage | index-access-pattern-leakage | 2026-03-30 | encrypted-index-strategy |
| Handle Priority Levels | handle-priority-levels | 2026-03-30 | engine-api-surface-design |
| Cross-Table Handle Budgets | cross-table-handle-budgets | 2026-03-30 | engine-api-surface-design |
| Handle Timeout/TTL | handle-timeout-ttl | 2026-03-30 | engine-api-surface-design |
| Cross-Table Transactions | cross-table-transactions | 2026-03-30 | engine-api-surface-design |
| Remote Serialization Protocol | remote-serialization-protocol | 2026-03-30 | engine-api-surface-design |
| Encryption Key Rotation | encryption-key-rotation | 2026-03-30 | field-encryption-api-design |
| WAL Entry Encryption | wal-entry-encryption | 2026-03-30 | field-encryption-api-design |
| Unencrypted-to-Encrypted Migration | unencrypted-to-encrypted-migration | 2026-03-30 | field-encryption-api-design |
| Per-Field Key Binding | per-field-key-binding | 2026-03-30 | field-encryption-api-design |
| Weighted Node Capacity | weighted-node-capacity | 2026-03-30 | partition-to-node-ownership |
| Partition Affinity | partition-affinity | 2026-03-30 | partition-to-node-ownership |
| Rebalancing Trigger Policy | rebalancing-trigger-policy | 2026-03-30 | partition-to-node-ownership |
| Corruption Repair and Recovery | corruption-repair-recovery | 2026-04-11 | per-block-checksums |
| Per-Field Pre-Encryption | per-field-pre-encryption | 2026-03-30 | pre-encrypted-document-signaling |
| Pre-Encrypted Flag Persistence | pre-encrypted-flag-persistence | 2026-03-30 | pre-encrypted-document-signaling |
| Client-Side Encryption SDK | client-side-encryption-sdk | 2026-03-30 | pre-encrypted-document-signaling |
| In-Flight Write Protection | in-flight-write-protection | 2026-03-30 | rebalancing-grace-period-strategy |
| Partition Takeover Priority | partition-takeover-priority | 2026-03-30 | rebalancing-grace-period-strategy |
| Concurrent WAL Replay Throttling | concurrent-wal-replay-throttling | 2026-03-30 | rebalancing-grace-period-strategy |
| Un-WAL'd Memtable Data Loss | un-walled-memtable-data-loss | 2026-03-30 | rebalancing-grace-period-strategy |
| Aggregation Query Merge | aggregation-query-merge | 2026-03-30 | scatter-gather-query-execution |
| LIMIT/OFFSET Partition Pushdown | limit-offset-pushdown | 2026-03-30 | scatter-gather-query-execution |
| Atomic Multi-Table DDL | atomic-multi-table-ddl | 2026-03-30 | table-catalog-persistence |
| Catalog Replication | catalog-replication | 2026-03-30 | table-catalog-persistence |
| Table Migration Protocol | table-migration-protocol | 2026-03-30 | table-catalog-persistence |
| Partition Replication Protocol | partition-replication-protocol | 2026-03-30 | table-partitioning |
| Cross-Partition Transactions | cross-partition-transactions | 2026-03-30 | table-partitioning |
| Vector Query Partition Pruning | vector-query-partition-pruning | 2026-03-30 | table-partitioning |
| Sequential Insert Hotspot | sequential-insert-hotspot | 2026-03-30 | table-partitioning |
| Partition-Aware Compaction | partition-aware-compaction | 2026-03-30 | table-partitioning |
| Adaptive Weight Tuning | adaptive-weight-tuning | 2026-04-13 | transport-traffic-priority |
| Hierarchical Query Memory Budget | hierarchical-query-memory-budget | 2026-04-13 | scatter-backpressure |
| Scan Snapshot Binding | scan-snapshot-binding | 2026-04-13 | scatter-backpressure |
| Scan Lease GC Watermark | scan-lease-gc-watermark | 2026-04-13 | scatter-backpressure |
| Bulk Data Transfer Channel | bulk-data-transfer-channel | 2026-04-13 | connection-pooling |
| Vector Index Query Routing | vector-index-query-routing | 2026-04-13 | vector-storage-cost-optimization |
| Automatic Quantization Selection | automatic-quantization-selection | 2026-04-13 | vector-storage-cost-optimization |
| Cross-SST Dictionary Sharing | cross-sst-dictionary-sharing | 2026-04-12 | codec-dictionary-support |
| WAL Dictionary Compression | wal-dictionary-compression | 2026-04-12 | codec-dictionary-support |
| Pure-Java ZSTD Compressor | pure-java-zstd-compressor | 2026-04-12 | codec-dictionary-support |
| Pure-Java LZ4 Codec | pure-java-lz4-codec | 2026-04-12 | wal-compression |
| WAL Group Commit | wal-group-commit | 2026-04-12 | wal-compression |

## Closed
<!-- Topics explicitly ruled out. Won't be raised again unless reopened. -->

| Problem | Slug | Closed | Reason |
|---------|------|--------|--------|
| Hash Distribution Uniformity | hash-distribution-uniformity | 2026-04-10 | Non-issue — splitmix64 near-perfect uniformity, modulo bias negligible |
| Similarity Function Placement | similarity-function-placement | 2026-04-12 | Non-issue — current IndexDefinition placement is correct, no awkwardness materialized |
| Codec Negotiation | codec-negotiation | 2026-04-12 | Already solved — codecId in compression map IS the negotiation protocol |
| Adaptive Compression Strategy | adaptive-compression-strategy | 2026-04-12 | Resolved by compaction-recompression — per-level policy inherent in factory design |
| Message Serialization Format | message-serialization-format | 2026-04-13 | Subsumed by connection-pooling — framing protocol IS the message serialization format |
| Non-Vector Index Type Review | non-vector-index-type-review | 2026-04-13 | Non-issue — EQUALITY/RANGE/UNIQUE/FULL_TEXT correctly implemented, compatibility matrix complete |
| Atomic Cross-Stripe Eviction | atomic-cross-stripe-eviction | 2026-04-13 | Non-issue — brief inconsistency window is harmless (cache holds data, not file refs) |
| Discovery Environment Config | discovery-environment-config | 2026-04-13 | Already resolved by discovery-spi-design — constructor injection per implementation |
| Table Ownership Discovery | table-ownership-discovery | 2026-04-13 | Already resolved by partition-to-node-ownership (HRW) + table-catalog-persistence |
| Dynamic Membership Threshold | dynamic-membership-threshold | 2026-04-13 | Static config via F04 R2 is sufficient; dynamic adjustment risks split-brain safety |
| Ownership Lookup Optimization | ownership-lookup-optimization | 2026-04-13 | Premature optimization — epoch-keyed caching is O(1) hit, O(N) miss is ~10µs at 1000 nodes |
| Parallel Large Cache Eviction | parallel-large-cache-eviction | 2026-04-13 | Non-issue at current scale — sequential eviction of 16 stripes is negligible |
| Full MemorySegment Codec API | memorysegment-codec-api | 2026-04-14 | Subsumed by wal-compression — MemorySegment compress/decompress already added |

## Archived
28 accepted decisions older than the 5 most recent: [history.md](history.md)
