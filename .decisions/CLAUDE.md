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
| index-definition-api-simplification | index-definition-api-simplification | 2026-03-17 | Derive from Schema |
| cross-stripe-eviction | cross-stripe-eviction | 2026-03-17 | Sequential loop |
| sstable-block-compression-format | sstable-block-compression-format | 2026-03-17 | Compression Offset Map |
| field-encryption-api-design | field-encryption-api-design | 2026-03-18 | Schema Annotation (FieldDefinition carries EncryptionSpec) |
| Transport Abstraction Design | transport-abstraction-design | 2026-03-20 | Message-Oriented Transport — send + request with type dispatch, virtual/platform thread split |
| Discovery SPI Design | discovery-spi-design | 2026-03-20 | Minimal Seed Provider with Optional Registration — discoverSeeds + default register/deregister |
| Scatter-Gather Query Execution | scatter-gather-query-execution | 2026-03-20 | Partition-Aware Proxy Table — transparent Table interface, k-way merge, partition pruning |
| Rebalancing & Grace Period Strategy | rebalancing-grace-period-strategy | 2026-03-20 | Eager Reassignment with Deferred Cleanup — immediate HRW recompute, grace controls cleanup |
| Partition-to-Node Ownership | partition-to-node-ownership | 2026-03-20 | Rendezvous Hashing (HRW) — stateless pure function, O(K/N) minimal movement |

## Deferred
<!-- Topics recorded but not yet evaluated. Resume with /architect "<problem>" -->
<!-- 68 items. Grouped by parent ADR for readability. -->

| Problem | Slug | Deferred | Parent ADR |
|---------|------|----------|------------|
| Distributed Join Execution | distributed-join-execution | 2026-03-20 | scatter-gather-query-execution |
| Binary Field Type | binary-field-type | 2026-03-30 | bounded-string-field-type |
| Parameterized Field Bounds | parameterized-field-bounds | 2026-03-30 | bounded-string-field-type |
| String to BoundedString Migration | string-to-bounded-string-migration | 2026-03-30 | bounded-string-field-type |
| Membership View Stall Recovery | membership-view-stall-recovery | 2026-03-30 | cluster-membership-protocol |
| Slow Node Detection | slow-node-detection | 2026-03-30 | cluster-membership-protocol |
| Dynamic Membership Threshold | dynamic-membership-threshold | 2026-03-30 | cluster-membership-protocol |
| Piggybacked State Exchange | piggybacked-state-exchange | 2026-03-30 | cluster-membership-protocol |
| Codec Thread Safety | codec-thread-safety | 2026-03-30 | compression-codec-api-design |
| Max Compressed Length | max-compressed-length | 2026-03-30 | compression-codec-api-design |
| Codec Negotiation | codec-negotiation | 2026-03-30 | compression-codec-api-design |
| Codec Dictionary Support | codec-dictionary-support | 2026-03-30 | compression-codec-api-design |
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
| Encryption Key Rotation | encryption-key-rotation | 2026-03-30 | field-encryption-api-design |
| WAL Entry Encryption | wal-entry-encryption | 2026-03-30 | field-encryption-api-design |
| Unencrypted-to-Encrypted Migration | unencrypted-to-encrypted-migration | 2026-03-30 | field-encryption-api-design |
| Per-Field Key Binding | per-field-key-binding | 2026-03-30 | field-encryption-api-design |
| Similarity Function Placement | similarity-function-placement | 2026-03-30 | index-definition-api-simplification |
| Non-Vector Index Type Review | non-vector-index-type-review | 2026-03-30 | index-definition-api-simplification |
| Weighted Node Capacity | weighted-node-capacity | 2026-03-30 | partition-to-node-ownership |
| Partition Affinity | partition-affinity | 2026-03-30 | partition-to-node-ownership |
| Ownership Lookup Optimization | ownership-lookup-optimization | 2026-03-30 | partition-to-node-ownership |
| Rebalancing Trigger Policy | rebalancing-trigger-policy | 2026-03-30 | partition-to-node-ownership |
| Per-Field Pre-Encryption | per-field-pre-encryption | 2026-03-30 | pre-encrypted-document-signaling |
| Pre-Encrypted Flag Persistence | pre-encrypted-flag-persistence | 2026-03-30 | pre-encrypted-document-signaling |
| Client-Side Encryption SDK | client-side-encryption-sdk | 2026-03-30 | pre-encrypted-document-signaling |
| In-Flight Write Protection | in-flight-write-protection | 2026-03-30 | rebalancing-grace-period-strategy |
| Partition Takeover Priority | partition-takeover-priority | 2026-03-30 | rebalancing-grace-period-strategy |
| Concurrent WAL Replay Throttling | concurrent-wal-replay-throttling | 2026-03-30 | rebalancing-grace-period-strategy |
| Un-WAL'd Memtable Data Loss | un-walled-memtable-data-loss | 2026-03-30 | rebalancing-grace-period-strategy |
| Aggregation Query Merge | aggregation-query-merge | 2026-03-30 | scatter-gather-query-execution |
| LIMIT/OFFSET Partition Pushdown | limit-offset-pushdown | 2026-03-30 | scatter-gather-query-execution |
| Cross-Partition Atomic Writes | cross-partition-atomic-writes | 2026-03-30 | scatter-gather-query-execution |
| Per-Block Checksums | per-block-checksums | 2026-03-30 | sstable-block-compression-format |
| Backend-Optimal Block Size | backend-optimal-block-size | 2026-03-30 | sstable-block-compression-format |
| WAL Compression | wal-compression | 2026-03-30 | sstable-block-compression-format |
| Compaction-Time Re-Compression | compaction-recompression | 2026-03-30 | sstable-block-compression-format |
| Hash Distribution Uniformity | hash-distribution-uniformity | 2026-03-30 | stripe-hash-function |
| Power-of-Two Stripe Optimization | power-of-two-stripe-optimization | 2026-03-30 | stripe-hash-function |
| Atomic Multi-Table DDL | atomic-multi-table-ddl | 2026-03-30 | table-catalog-persistence |
| Cross-Table Transaction Coordination | cross-table-transaction-coordination | 2026-03-30 | table-catalog-persistence |
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

## Closed
<!-- Topics explicitly ruled out. Won't be raised again unless reopened. -->

| Problem | Slug | Closed | Reason |
|---------|------|--------|--------|

## Archived
Decisions older than the 5 most recent: [history.md](history.md)

| Problem | Slug | Accepted | Recommendation |
|---------|------|----------|----------------|
| Compression Codec API Design | compression-codec-api-design | 2026-03-17 | Open interface + explicit codec list — non-sealed, reader takes varargs codecs |
| VectorType Serialization Encoding | vector-type-serialization-encoding | 2026-03-17 | Flat Vector Encoding — contiguous d×sizeof(T) bytes, no per-vector metadata |
| Stripe Hash Function | stripe-hash-function | 2026-03-17 | Stafford variant 13 (splitmix64) — zero-allocation, sub-nanosecond |
| Cluster Membership Protocol | cluster-membership-protocol | 2026-03-20 | Rapid + Phi Accrual Composite — leaderless consistent membership with adaptive failure detection |
| Engine API Surface Design | engine-api-surface-design | 2026-03-19 | Interface-Based Handle Pattern with Tracked Lifecycle and Lease Eviction |
| Table Catalog Persistence | table-catalog-persistence | 2026-03-19 | Per-Table Metadata Directories — lazy recovery, per-table failure isolation |
| BoundedString Field Type Design | bounded-string-field-type | 2026-03-19 | BoundedString record as 5th sealed permit with STRING-delegating switch arms |
| Pre-Encrypted Document Signaling | pre-encrypted-document-signaling | 2026-03-19 | Factory method with boolean field — `JlsmDocument.preEncrypted(schema, ...)` |
| Encrypted Index Strategy | encrypted-index-strategy | 2026-03-18 | Static Capability Matrix with 3-tier full-text search (keyword, phrase, SSE) |
| Table Partitioning | table-partitioning | 2026-03-16 | Range partitioning with per-partition co-located indices |
