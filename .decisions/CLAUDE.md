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
| Per-Block Checksums | per-block-checksums | 2026-04-10 | CRC32C per-block checksum in CompressionMap.Entry |
| Backend-Optimal Block Size | backend-optimal-block-size | 2026-04-10 | Parameterize block size on writer builder with named constants |
| WAL Compression | wal-compression | 2026-04-12 | Per-record compression with MemorySegment-native codec API evolution |
| Codec Dictionary Support | codec-dictionary-support | 2026-04-12 | Writer-orchestrated dictionary lifecycle, tiered Panama FFM detection |
| Compaction Re-Compression | compaction-recompression | 2026-04-12 | Writer-factory injection with per-level codec policy |

## Deferred
<!-- Topics recorded but not yet evaluated. Resume with /architect "<problem>" -->
<!-- 65 items. Grouped by parent ADR for readability. -->

| Problem | Slug | Deferred | Parent ADR |
|---------|------|----------|------------|
| Distributed Join Execution | distributed-join-execution | 2026-03-20 | scatter-gather-query-execution |
| Binary Field Type | binary-field-type | 2026-03-30 | bounded-string-field-type |
| Parameterized Field Bounds | parameterized-field-bounds | 2026-03-30 | bounded-string-field-type |
| String to BoundedString Migration | string-to-bounded-string-migration | 2026-03-30 | bounded-string-field-type |
| Automatic Backend Detection | automatic-backend-detection | 2026-04-11 | backend-optimal-block-size |
| Block Cache / Block Size Interaction | block-cache-block-size-interaction | 2026-04-11 | backend-optimal-block-size |
| Membership View Stall Recovery | membership-view-stall-recovery | 2026-03-30 | cluster-membership-protocol |
| Slow Node Detection | slow-node-detection | 2026-03-30 | cluster-membership-protocol |
| Dynamic Membership Threshold | dynamic-membership-threshold | 2026-03-30 | cluster-membership-protocol |
| Piggybacked State Exchange | piggybacked-state-exchange | 2026-03-30 | cluster-membership-protocol |
| Atomic Cross-Stripe Eviction | atomic-cross-stripe-eviction | 2026-03-30 | cross-stripe-eviction |
| Parallel Large Cache Eviction | parallel-large-cache-eviction | 2026-03-30 | cross-stripe-eviction |
| Continuous Re-Discovery | continuous-rediscovery | 2026-03-30 | discovery-spi-design |
| Discovery Environment Config | discovery-environment-config | 2026-03-30 | discovery-spi-design |
| Authenticated Discovery | authenticated-discovery | 2026-03-30 | discovery-spi-design |
| Table Ownership Discovery | table-ownership-discovery | 2026-03-30 | discovery-spi-design |
| Encrypted Prefix/Wildcard Queries | encrypted-prefix-wildcard-queries | 2026-03-30 | encrypted-index-strategy |
| Encrypted Fuzzy Matching | encrypted-fuzzy-matching | 2026-03-30 | encrypted-index-strategy |
| Encrypted Cross-Field Joins | encrypted-cross-field-joins | 2026-03-30 | encrypted-index-strategy |
| Index Access Pattern Leakage | index-access-pattern-leakage | 2026-03-30 | encrypted-index-strategy |
| Handle Priority Levels | handle-priority-levels | 2026-03-30 | engine-api-surface-design |
| Cross-Table Handle Budgets | cross-table-handle-budgets | 2026-03-30 | engine-api-surface-design |
| Handle Timeout/TTL | handle-timeout-ttl | 2026-03-30 | engine-api-surface-design |
| Cross-Table Transactions | cross-table-transactions | 2026-03-30 | engine-api-surface-design |
| Remote Serialization Protocol | remote-serialization-protocol | 2026-03-30 | engine-api-surface-design |
| Full MemorySegment Codec API | memorysegment-codec-api | 2026-04-11 | max-compressed-length |
| Encryption Key Rotation | encryption-key-rotation | 2026-03-30 | field-encryption-api-design |
| WAL Entry Encryption | wal-entry-encryption | 2026-03-30 | field-encryption-api-design |
| Unencrypted-to-Encrypted Migration | unencrypted-to-encrypted-migration | 2026-03-30 | field-encryption-api-design |
| Per-Field Key Binding | per-field-key-binding | 2026-03-30 | field-encryption-api-design |
| Non-Vector Index Type Review | non-vector-index-type-review | 2026-03-30 | index-definition-api-simplification |
| Weighted Node Capacity | weighted-node-capacity | 2026-03-30 | partition-to-node-ownership |
| Partition Affinity | partition-affinity | 2026-03-30 | partition-to-node-ownership |
| Ownership Lookup Optimization | ownership-lookup-optimization | 2026-03-30 | partition-to-node-ownership |
| Rebalancing Trigger Policy | rebalancing-trigger-policy | 2026-03-30 | partition-to-node-ownership |
| SSTable End-to-End Integrity | sstable-end-to-end-integrity | 2026-04-11 | per-block-checksums |
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
| Transport Traffic Priority | transport-traffic-priority | 2026-03-30 | transport-abstraction-design |
| Message Serialization Format | message-serialization-format | 2026-03-30 | transport-abstraction-design |
| Connection Pooling | connection-pooling | 2026-03-30 | transport-abstraction-design |
| Scatter Backpressure | scatter-backpressure | 2026-03-30 | transport-abstraction-design |
| Vector Storage Cost Optimization | vector-storage-cost-optimization | 2026-03-30 | vector-type-serialization-encoding |
| Sparse Vector Support | sparse-vector-support | 2026-03-30 | vector-type-serialization-encoding |
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

## Archived
21 accepted decisions older than the 5 most recent: [history.md](history.md)
