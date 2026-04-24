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
| Table-Handle Scope Exposure | table-handle-scope-exposure | 2026-04-24 | Extend `TableMetadata` with `Optional<EncryptionMetadata>` sub-record; `TableScope(TenantId, DomainId, TableId)` composes WD-01 identity records; new `Engine.createEncryptedTable` + `Engine.enableEncryption`; encryption is one-way (in-place disable deferred) |
| SSTable Footer Scope Format | sstable-footer-scope-format | 2026-04-24 | v5→v6 format bump with fixed-position scope section `[tenantId][domainId][tableId][dek-version-set]`; CRC32C-covered via existing v5 section-checksum scheme; fast-fail cross-scope comparison before DEK lookup (R22b/R23a); per-block AES-GCM transition explicitly deferred |
| AAD Canonical Encoding for Context-Bound Ciphertext Wrapping | aad-canonical-encoding | 2026-04-23 | Length-prefixed TLV — `[4B BE Purpose.code() \| 4B BE attr-count \| sorted (4B BE key-len \| UTF-8 key \| 4B BE val-len \| UTF-8 val) pairs]`; zero-dep; mirrors R11 HKDF info pattern; amends `kms-integration-model` |
| KMS Integration Model | kms-integration-model | 2026-04-21 | KmsClient SPI (wrap/unwrap/isUsable + transient/permanent exceptions); 30min cache TTL; 3-retry exp-backoff (100ms→400ms→1.6s, ±25% jitter); 10s call timeout; encryption context carries tenantId+domainId+purpose |
| DEK Scoping Granularity | dek-scoping-granularity | 2026-04-21 | Per-(tenant, domain, table) DEK with version; domain groups tables; HKDF field derivation from tableDek with length-prefixed info (tenantId, domainId, tableName, fieldName, dekVersion) |

## Deferred
<!-- Topics recorded but not yet evaluated. Resume with /architect "<problem>" -->
<!-- 46 items (was 43; +3 from WD-02 ADRs + spec-author Pass 2 on 2026-04-24). Grouped by parent ADR for readability. -->

| Problem | Slug | Deferred | Parent ADR |
|---------|------|----------|------------|
| SSTable Active Tamper Defence | sstable-active-tamper-defence | 2026-04-24 | sstable.footer-encryption-scope (spec) |
| Encryption Disable Policy | encryption-disable-policy | 2026-04-24 | table-handle-scope-exposure |
| Encryption Granularity: Per-Field vs Per-Block | encryption-granularity-per-field-vs-per-block | 2026-04-24 | sstable-footer-scope-format |
| AAD Attribute-Set Evolution | aad-attribute-set-evolution | 2026-04-23 | aad-canonical-encoding |
| AAD Identifier Normalization (Unicode NFC) | aad-identifier-normalization | 2026-04-23 | aad-canonical-encoding |
| AAD Non-Java Consumer Interoperability | aad-non-java-consumer-interop | 2026-04-23 | aad-canonical-encoding |
| AAD Heterogeneous Attribute Value Types | aad-heterogeneous-value-types | 2026-04-23 | aad-canonical-encoding |
| Tenant Lifecycle (decommission, data erasure, audit retention) | tenant-lifecycle | 2026-04-21 | tenant-key-revocation-and-external-rotation |
| Shuffle/Repartition Joins | shuffle-repartition-joins | 2026-04-14 | distributed-join-execution |
| Semi-Join Reduction | semi-join-reduction | 2026-04-14 | distributed-join-execution |
| Query Planner Integration | query-planner-integration | 2026-04-14 | distributed-join-execution |
| Join Ordering Optimization | join-ordering-optimization | 2026-04-14 | distributed-join-execution |
| Secondary-Sort Pagination | secondary-sort-pagination | 2026-04-14 | limit-offset-pushdown |
| Backward Pagination | backward-pagination | 2026-04-14 | limit-offset-pushdown |
| DISTINCT Aggregate Decomposition | distinct-aggregate-decomposition | 2026-04-14 | aggregation-query-merge |
| Holistic Aggregate Support | holistic-aggregate-support | 2026-04-14 | aggregation-query-merge |
| Default LSM-Backed BlobStore | default-lsm-blob-store | 2026-04-13 | binary-field-type |
| Blob Storage Strategy for Object Storage | blob-object-storage-strategy | 2026-04-13 | binary-field-type |
| Blob Streaming API Design | blob-streaming-api | 2026-04-13 | binary-field-type |
| Inline Small Blob Optimization | inline-small-blob-optimization | 2026-04-13 | binary-field-type |
| Numeric Field Range Bounds | numeric-field-range-bounds | 2026-04-13 | parameterized-field-bounds |
| Quarantine Resolution Policy | quarantine-resolution-policy | 2026-04-13 | string-to-bounded-string-migration |
| Cross-Table Schema Migration | cross-table-schema-migration | 2026-04-13 | string-to-bounded-string-migration |
| Authenticated Discovery | authenticated-discovery | 2026-03-30 | discovery-spi-design |
| Encrypted Cross-Field Joins | encrypted-cross-field-joins | 2026-04-15 | encrypted-index-strategy |
| Handle Priority Levels | handle-priority-levels | 2026-03-30 | engine-api-surface-design |
| Cross-Table Handle Budgets | cross-table-handle-budgets | 2026-03-30 | engine-api-surface-design |
| Handle Timeout/TTL | handle-timeout-ttl | 2026-03-30 | engine-api-surface-design |
| Cross-Table Transactions | cross-table-transactions | 2026-03-30 | engine-api-surface-design |
| Remote Serialization Protocol | remote-serialization-protocol | 2026-03-30 | engine-api-surface-design |
| Weighted Node Capacity | weighted-node-capacity | 2026-03-30 | partition-to-node-ownership |
| Partition Affinity | partition-affinity | 2026-03-30 | partition-to-node-ownership |
| Rebalancing Trigger Policy | rebalancing-trigger-policy | 2026-03-30 | partition-to-node-ownership |
| Atomic Multi-Table DDL | atomic-multi-table-ddl | 2026-03-30 | table-catalog-persistence |
| Partition Replication Protocol | partition-replication-protocol | 2026-03-30 | table-partitioning |
| Cross-Partition Transactions | cross-partition-transactions | 2026-03-30 | table-partitioning |
| Adaptive Weight Tuning | adaptive-weight-tuning | 2026-04-13 | transport-traffic-priority |
| Hierarchical Query Memory Budget | hierarchical-query-memory-budget | 2026-04-13 | scatter-backpressure |
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
| Pre-Encrypted Flag Persistence | pre-encrypted-flag-persistence | 2026-04-14 | Non-issue — write-side flag is intentionally ephemeral; per-field markers belong in client-side-encryption-sdk |

## Archived
42 accepted decisions older than the 5 most recent: [history.md](history.md)
