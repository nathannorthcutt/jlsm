# Architecture Decisions — History

> Full history of accepted decisions. Most recent 5 are in [CLAUDE.md](CLAUDE.md).

| Problem | Slug | Accepted | Recommendation |
|---------|------|----------|----------------|
| In-Flight Write Protection | in-flight-write-protection | 2026-04-15 | Drain-and-reject with in-flight completion guarantee — structured rejection metadata |
| Concurrent WAL Replay Throttling | concurrent-wal-replay-throttling | 2026-04-15 | Three-gate resource bounding — concurrency, I/O throughput, memory budget, compaction backpressure |
| Partition Takeover Priority | partition-takeover-priority | 2026-04-15 | Pluggable TakeoverPrioritizer — SmallestFirstPrioritizer default, LargestFirstPrioritizer alternative |
| Scan Lease GC Watermark | scan-lease-gc-watermark | 2026-04-15 | Lease-based watermark hold — bounded duration, compaction consultation, per-partition tracking |
| Table Migration Protocol | table-migration-protocol | 2026-04-15 | Raft learner migration — 5-phase state machine with rollback from every phase |
| Catalog Replication | catalog-replication | 2026-04-15 | Dedicated Raft catalog group — epoch-based caching, strong DDL consistency |
| Scan Snapshot Binding | scan-snapshot-binding | 2026-04-15 | Sequence-number binding with degraded fallback — point-in-time consistency via continuation token |
| Partition-Aware Compaction | partition-aware-compaction | 2026-04-15 | CompactionScheduler SPI — round-robin + size-weighted strategies, configurable concurrency limits |
| Vector Query Partition Pruning | vector-query-partition-pruning | 2026-04-15 | PartitionPruner SPI — centroid-based + metadata-based pruning with composite strategy |
| Sequential Insert Hotspot | sequential-insert-hotspot | 2026-04-15 | PrefixHashDistributor — MurmurHash3 prefix prepended to logical key; deterministic, cross-platform, configurable prefix length |
| Index Access Pattern Leakage | index-access-pattern-leakage | 2026-04-14 | Low-Cost Mitigation Bundle — per-field HKDF + padding + leakage docs |
| Unencrypted-to-Encrypted Migration | unencrypted-to-encrypted-migration | 2026-04-14 | Compaction-Driven Migration — same mechanism as key rotation |
| WAL Entry Encryption | wal-entry-encryption | 2026-04-14 | Per-Record AES-GCM-256 with Sequence-Number Nonce |
| Encryption Key Rotation | encryption-key-rotation | 2026-04-14 | Envelope Encryption + Compaction-Driven Re-Encryption |
| Per-Field Pre-Encryption | per-field-pre-encryption | 2026-04-14 | Bitset Flag — long preEncryptedBitset in JlsmDocument |
| Block Cache / Block Size Interaction | block-cache-block-size-interaction | 2026-04-14 | Per-entry byte-budget eviction via MemorySegment.byteSize() |
| SSTable End-to-End Integrity | sstable-end-to-end-integrity | 2026-04-14 | Three-layer — fsync discipline + VarInt-prefixed blocks + per-section CRC32C |
| Aggregation Query Merge | aggregation-query-merge | 2026-04-14 | Two-Phase Partial Aggregation with Cardinality Guard |
| LIMIT/OFFSET Partition Pushdown | limit-offset-pushdown | 2026-04-14 | Top-N Pushdown with Keyset Pagination |
| Distributed Join Execution | distributed-join-execution | 2026-04-14 | Co-Partitioned + Broadcast two-tier strategy |
| Slow Node Detection | slow-node-detection | 2026-04-13 | Composite — Phi Bands + Peer Comparison + Request Latency |
| Piggybacked State Exchange | piggybacked-state-exchange | 2026-04-13 | Fixed-Field Heartbeat Metadata — 10 bytes, O(1) parsing, version byte |
| Scatter Backpressure | scatter-backpressure | 2026-04-13 | Credit-Based + Flow API composite — hard memory cap via ArenaBufferPool, non-blocking demand |
| Transport Traffic Priority | transport-traffic-priority | 2026-04-13 | DRR + Strict-Priority Bypass — O(1) scheduling, heartbeat bypass, starvation-free |
| Vector Storage Cost Optimization | vector-storage-cost-optimization | 2026-04-13 | QuantizationConfig on IndexDefinition + custom SPI escape hatch |
| Schema Migration Policy | string-to-bounded-string-migration | 2026-04-13 | Compaction-time migration + on-demand scan with quarantine |
| Parameterized Field Bounds | parameterized-field-bounds | 2026-04-13 | BoundedArray sealed permit; numeric bounds deferred |
| Binary Field Type | binary-field-type | 2026-04-13 | Binary sealed permit + opaque BlobRef + BlobStore SPI |
| Field Encryption API Design | field-encryption-api-design | 2026-03-18 | Schema Annotation — FieldDefinition carries sealed EncryptionSpec, keys in Arena-backed holder |
| Index Definition API Simplification | index-definition-api-simplification | 2026-03-17 | Derive dimensions from schema VectorType — remove vectorDimensions from record |
| Stripe Hash Function | stripe-hash-function | 2026-03-17 | Stafford variant 13 (splitmix64) — zero-allocation, sub-nanosecond |
| Compression Codec API Design | compression-codec-api-design | 2026-03-17 | Open interface + explicit codec list — non-sealed, reader takes varargs codecs |
| Cross-Stripe Eviction | cross-stripe-eviction | 2026-03-17 | Sequential loop — iterate stripes, call evict on each |
| SSTable Block Compression Format | sstable-block-compression-format | 2026-03-17 | Compression Offset Map — separate file section with per-block metadata array |
| VectorType Serialization Encoding | vector-type-serialization-encoding | 2026-03-17 | Flat Vector Encoding — contiguous d×sizeof(T) bytes, no per-vector metadata |
| Table Partitioning | table-partitioning | 2026-03-16 | Range partitioning with per-partition co-located indices |
| Encrypted Index Strategy | encrypted-index-strategy | 2026-03-18 | Static Capability Matrix with 3-tier full-text search (keyword, phrase, SSE) |
| Engine API Surface Design | engine-api-surface-design | 2026-03-19 | Interface-Based Handle Pattern with Tracked Lifecycle and Lease Eviction |
| Table Catalog Persistence | table-catalog-persistence | 2026-03-19 | Per-Table Metadata Directories — lazy recovery, per-table failure isolation |
| BoundedString Field Type Design | bounded-string-field-type | 2026-03-19 | BoundedString record as 5th sealed permit with STRING-delegating switch arms |
| Pre-Encrypted Document Signaling | pre-encrypted-document-signaling | 2026-03-19 | Factory method with boolean field — `JlsmDocument.preEncrypted(schema, ...)` |
| Cluster Membership Protocol | cluster-membership-protocol | 2026-03-20 | Rapid + Phi Accrual Composite — leaderless consistent membership with adaptive failure detection |
| Backend-Optimal Block Size | backend-optimal-block-size | 2026-04-10 | Parameterize block size on writer builder with named constants |
| WAL Compression | wal-compression | 2026-04-12 | Per-record compression with MemorySegment-native codec API evolution |
| Codec Dictionary Support | codec-dictionary-support | 2026-04-12 | Writer-orchestrated dictionary lifecycle, tiered Panama FFM detection |
| Compaction Re-Compression | compaction-recompression | 2026-04-12 | Writer-factory injection with per-level codec policy |
| Connection Pooling | connection-pooling | 2026-04-13 | Single-Connection Multiplexing — Kafka-style framing, int32 stream IDs, ReentrantLock write serialization |
| Transport Abstraction Design | transport-abstraction-design | 2026-03-20 | Message-Oriented Transport — send + request with type dispatch, virtual/platform thread split |
| Discovery SPI Design | discovery-spi-design | 2026-03-20 | Minimal Seed Provider with Optional Registration — discoverSeeds + default register/deregister |
| Scatter-Gather Query Execution | scatter-gather-query-execution | 2026-03-20 | Partition-Aware Proxy Table — transparent Table interface, k-way merge, partition pruning |
| Rebalancing & Grace Period Strategy | rebalancing-grace-period-strategy | 2026-03-20 | Eager Reassignment with Deferred Cleanup — immediate HRW recompute, grace controls cleanup |
| Partition-to-Node Ownership | partition-to-node-ownership | 2026-03-20 | Rendezvous Hashing (HRW) — stateless pure function, O(K/N) minimal movement |
| Power-of-Two Stripe Optimization | power-of-two-stripe-optimization | 2026-04-10 | Enforce power-of-2 stripe counts, use bitmask instead of modulo |
| Codec Thread Safety | codec-thread-safety | 2026-04-10 | Stateless and thread-safe contract on CompressionCodec interface |
| Max Compressed Length | max-compressed-length | 2026-04-10 | Add maxCompressedLength(int) default method to CompressionCodec |
| Per-Block Checksums | per-block-checksums | 2026-04-10 | CRC32C per-block checksum in CompressionMap.Entry |
| Continuous Re-Discovery | continuous-rediscovery | 2026-04-13 | Periodic loop + optional watchSeeds() for sub-second push-based discovery |
| Membership View Stall Recovery | membership-view-stall-recovery | 2026-04-13 | Tiered Escalation — piggyback → anti-entropy → forced rejoin |
| Automatic Backend Detection | automatic-backend-detection | 2026-04-14 | Pool-aware block size configuration — derive from ArenaBufferPool |
