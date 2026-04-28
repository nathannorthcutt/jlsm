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
| dek-scoping-granularity | dek-scoping-granularity | 2026-04-21 | **DEK identity: `(tenantId, domainId, tableId, dekVersion)` |
| catalog-replication | catalog-replication | 2026-04-15 | **Dedicated Raft catalog group** — the catalog is replica... |
| piggybacked-state-exchange | piggybacked-state-exchange | 2026-04-13 | Fixed-Field Heartbeat Metadata with Version Byte |
| bounded-string-field-type | bounded-string-field-type | 2026-03-19 | BoundedString record as a new sealed permit with STRING-d... |
| concurrent-wal-replay-throttling | concurrent-wal-replay-throttling | 2026-04-15 | **Three-gate resource bounding** — concurrency limit, I/O... |
| un-walled-memtable-data-loss | un-walled-memtable-data-loss | 2026-04-15 | **Documented data loss window (single-node) eliminated by... |
| max-compressed-length | max-compressed-length | 2026-04-10 | **Add `int maxCompressedLength(int inputLength)` to `Comp... |
| sstable-end-to-end-integrity | sstable-end-to-end-integrity | 2026-04-14 | **Three-layer end-to-end integrity: fsync discipline + Va... |
| partition-takeover-priority | partition-takeover-priority | 2026-04-15 | **Pluggable TakeoverPrioritizer with smallest-first defau... |
| automatic-backend-detection | automatic-backend-detection | 2026-04-14 | **Pool-aware block size configuration — derive block size... |
| index-definition-api-simplification | index-definition-api-simplification | 2026-03-17 | Derive from Schema |
| encryption-key-rotation | encryption-key-rotation | 2026-04-14 | Envelope Encryption with Compaction-Driven Re-Encryption |
| per-field-pre-encryption | per-field-pre-encryption | 2026-04-14 | Bitset Flag** — replace `boolean preEncrypted` with `long... |
| stripe-hash-function | stripe-hash-function | 2026-03-17 | Stafford variant 13 (splitmix64 finalizer) |
| membership-view-stall-recovery | membership-view-stall-recovery | 2026-04-13 | [Tiered Escalation](../../.kb/distributed-systems/cluster... |
| vector-query-partition-pruning | vector-query-partition-pruning | 2026-04-15 | **Pluggable PartitionPruner SPI with centroid-based and m... |
| compression-codec-api-design | compression-codec-api-design | 2026-03-17 | Open interface + explicit codec list |
| rebalancing-grace-period-strategy | rebalancing-grace-period-strategy | 2026-03-20 | Eager Reassignment with Deferred Cleanup |
| sparse-vector-support | sparse-vector-support | 2026-04-13 | SparseVectorType sealed permit + inverted index storage |
| in-flight-write-protection | in-flight-write-protection | 2026-04-15 | **Drain-and-reject with in-flight completion guarantee** ... |
| encrypted-fuzzy-matching | encrypted-fuzzy-matching | 2026-04-15 | **LSH + Bloom filter with AES-GCM encryption** -- the `En... |
| wal-entry-encryption | wal-entry-encryption | 2026-04-14 | Per-Record AES-GCM-256 with Sequence-Number Nonce |
| backend-optimal-block-size | backend-optimal-block-size | 2026-04-10 | **Parameterize block size as a builder option on `TrieSST... |
| sstable-block-compression-format | sstable-block-compression-format | 2026-03-17 | Compression Offset Map |
| scatter-gather-query-execution | scatter-gather-query-execution | 2026-03-20 | Partition-Aware Proxy Table |
| scan-snapshot-binding | scan-snapshot-binding | 2026-04-15 | **Sequence-number binding with degraded fallback** — cont... |
| partition-aware-compaction | partition-aware-compaction | 2026-04-15 | **Scheduler-based compaction coordination** — a `Compacti... |
| continuous-rediscovery | continuous-rediscovery | 2026-04-13 | Periodic Rediscovery + Optional Reactive Watch (composite) |
| corruption-repair-recovery | corruption-repair-recovery | 2026-04-15 | **Layered repair strategies — quarantine + compaction (si... |
| table-migration-protocol | table-migration-protocol | 2026-04-15 | **Raft-based learner replica with phased state machine** ... |
| vector-type-serialization-encoding | vector-type-serialization-encoding | 2026-03-17 | [Flat Vector Encoding](../../.kb/algorithms/vector-encodi... |
| field-encryption-api-design | field-encryption-api-design | 2026-03-18 | Schema Annotation (FieldDefinition carries EncryptionSpec) |
| unencrypted-to-encrypted-migration | unencrypted-to-encrypted-migration | 2026-04-14 | Compaction-Driven Migration** — same mechanism as key rot... |
| encrypted-prefix-wildcard-queries | encrypted-prefix-wildcard-queries | 2026-04-15 | **Prefix tokenization + DET encryption** -- the `Encrypte... |
| cluster-membership-protocol | cluster-membership-protocol | 2026-03-20 | Rapid + Phi Accrual Composite |
| per-block-checksums | per-block-checksums | 2026-04-10 | **Add a CRC32C checksum per block in the `CompressionMap`. |
| per-field-key-binding | per-field-key-binding | 2026-04-14 | HKDF Derivation from Master Key |
| power-of-two-stripe-optimization | power-of-two-stripe-optimization | 2026-04-10 | **Use power-of-2 stripe counts with bitmask exclusively. ... |
| pre-encrypted-document-signaling | pre-encrypted-document-signaling | 2026-03-19 | Factory method with boolean field** — `JlsmDocument.preEn... |
| slow-node-detection | slow-node-detection | 2026-04-13 | [Composite Detection](../../.kb/distributed-systems/clust... |
| codec-thread-safety | codec-thread-safety | 2026-04-10 | **Codecs MUST be stateless and thread-safe.** Per-call na... |
| block-cache-block-size-interaction | block-cache-block-size-interaction | 2026-04-14 | **Per-entry byte-budget eviction — track total cached byt... |
| sequential-insert-hotspot | sequential-insert-hotspot | 2026-04-15 | Adopt a `WriteDistributor` interface with pluggable imple... |
| scan-lease-gc-watermark | scan-lease-gc-watermark | 2026-04-15 | **Lease-based watermark hold with bounded duration** — a ... |
| index-access-pattern-leakage | index-access-pattern-leakage | 2026-04-14 | Low-Cost Mitigation Bundle** — per-field HKDF keys + powe... |
| three-tier-key-hierarchy | three-tier-key-hierarchy | 2026-04-21 | **Three-tier envelope key hierarchy** with per-tenant KMS... |
| Transport Module Placement | transport-module-placement | 2026-04-26 | New `jlsm-cluster` Gradle subproject below jlsm-engine in the DAG; public `jlsm.cluster` package + non-exported `jlsm.cluster.internal`; migrate `ClusterTransport` SPI + `Message`/`MessageType`/`MessageHandler`/`NodeAddress`/`InJvmTransport` from jlsm-engine; amend `connection-pooling` and `transport-abstraction-design` ADR `files:` fields |
| Module-DAG Sealed-Type Public-Factory Carve-Out | module-dag-sealed-type-public-factory-carve-out | 2026-04-25 | Public static factory + non-exported package + package-private ctor; `module-info.java` exports boundary is the load-bearing trust mechanism; factory is 1:1 delegation to ctor; spec authors use module-graph-aware phrasing instead of literal "non-public constructor" |
| Pre-GA Format-Version Deprecation Policy | pre-ga-format-deprecation-policy | 2026-04-24 | Full mechanism set — Prefer-current-version rule + bounded low-priority sweep + format inventory + per-collection watermark + operator-triggered targeted upgrade; pre-GA window=zero; post-GA ≥1 major; writable past-window=inline rewrite, read-only past-window=hard error; first exercise SSTable v1–v4 collapse |
| Table-Handle Scope Exposure | table-handle-scope-exposure | 2026-04-24 | Extend `TableMetadata` with `Optional<EncryptionMetadata>` sub-record; `TableScope(TenantId, DomainId, TableId)` composes WD-01 identity records; new `Engine.createEncryptedTable` + `Engine.enableEncryption`; encryption is one-way (in-place disable deferred) |
| SSTable Footer Scope Format | sstable-footer-scope-format | 2026-04-24 | v5→v6 format bump with fixed-position scope section `[tenantId][domainId][tableId][dek-version-set]`; CRC32C-covered via existing v5 section-checksum scheme; fast-fail cross-scope comparison before DEK lookup (R22b/R23a); per-block AES-GCM transition explicitly deferred |
| AAD Canonical Encoding for Context-Bound Ciphertext Wrapping | aad-canonical-encoding | 2026-04-23 | Length-prefixed TLV — `[4B BE Purpose.code() \| 4B BE attr-count \| sorted (4B BE key-len \| UTF-8 key \| 4B BE val-len \| UTF-8 val) pairs]`; zero-dep; mirrors R11 HKDF info pattern; amends `kms-integration-model` |

## Deferred
<!-- Topics recorded but not yet evaluated. Resume with /architect "<problem>" -->
<!-- 48 items (was 46; +2 from pre-ga-format-deprecation-policy on 2026-04-24). Grouped by parent ADR for readability. -->

| Problem | Slug | Deferred | Parent ADR |
|---------|------|----------|------------|
| Cluster Format-Version Coexistence | cluster-format-version-coexistence | 2026-04-24 | pre-ga-format-deprecation-policy |
| jlsm Release Cadence | jlsm-release-cadence | 2026-04-24 | pre-ga-format-deprecation-policy |
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
43 accepted decisions older than the 5 most recent: [history.md](history.md)
