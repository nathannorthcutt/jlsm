# Decisions Roadmap

**Generated:** 2026-04-13 (revision 4)
**Deferred:** 65 decisions in 9 clusters
**Completed since first roadmap:** Phase 1 (6 items), plus wal-compression, codec-negotiation, similarity-function-placement, codec-dictionary-support, compaction-recompression

## Summary

11 gap-fill | 35 minor feature | 19 full feature

## Progress

**Phase 1 — COMPLETE (2026-04-10):**
All 6 items resolved: `codec-thread-safety` (confirmed), `max-compressed-length`
(confirmed), `per-block-checksums` (confirmed), `backend-optimal-block-size`
(confirmed), `power-of-two-stripe-optimization` (confirmed),
`hash-distribution-uniformity` (closed — non-issue).

**Phase 2 partial — compression decisions COMPLETE (2026-04-12):**
`wal-compression` confirmed. `codec-dictionary-support` confirmed (writer-orchestrated
dictionary lifecycle, tiered Panama FFM detection). `compaction-recompression`
confirmed (writer-factory injection with per-level codec policy).
`codec-negotiation` closed (already solved by codecId in compression map).
`similarity-function-placement` closed (non-issue). `adaptive-compression-strategy`
closed (resolved by compaction-recompression design).
New deferred spawned: `pure-java-lz4-codec`, `wal-group-commit`,
`cross-sst-dictionary-sharing`, `wal-dictionary-compression`, `pure-java-zstd-compressor`.

## Clusters (priority order)

### 1. Storage & Compression (10 decisions)

Foundational I/O layer — codec contracts, block integrity, and write-path
compression. Phase 1 gap-fills are complete; wal-compression, codec-dictionary-support,
and compaction-recompression confirmed. Remaining items extend the compression,
dictionary, and integrity story.

**Gap-fills:** automatic-backend-detection, block-cache-block-size-interaction
**Minor features:** sstable-end-to-end-integrity, memorysegment-codec-api,
pure-java-lz4-codec, wal-group-commit, cross-sst-dictionary-sharing,
wal-dictionary-compression
**Full features:** corruption-repair-recovery, pure-java-zstd-compressor

**Dependencies:** None — this cluster is a dependency for others. Corruption
repair depends on replication (Cluster 7) for recovery sources.
cross-sst-dictionary-sharing and wal-dictionary-compression depend on the now-confirmed
codec-dictionary-support. pure-java-zstd-compressor also depends on codec-dictionary-support.

### 2. Cache (2 decisions)

Performance infrastructure — cache eviction refinements for the block cache.

**Minor features:** atomic-cross-stripe-eviction, parallel-large-cache-eviction

**Dependencies:** None — self-contained within jlsm-core. Low priority; current
behavior is acceptable at current scale.

### 3. Schema & Field Types (4 decisions)

Data model completeness — field types and index definitions are the contract
surface for jlsm-table consumers.

**Minor features:** binary-field-type, parameterized-field-bounds,
string-to-bounded-string-migration, non-vector-index-type-review

**Dependencies:** None — self-contained within jlsm-table.

### 4. Vector (2 decisions)

Vector storage efficiency at scale — quantization and sparse support for
billion-vector workloads.

**Minor features:** vector-storage-cost-optimization, sparse-vector-support

**Dependencies:** None — self-contained within jlsm-vector.

### 5. Cluster Networking & Discovery (12 decisions)

The communication substrate — transport, membership, and discovery must
stabilize before any distributed feature can proceed.

**Gap-fills:** discovery-environment-config, ownership-lookup-optimization
**Minor features:** transport-traffic-priority, scatter-backpressure,
continuous-rediscovery, authenticated-discovery, table-ownership-discovery,
membership-view-stall-recovery, slow-node-detection, dynamic-membership-threshold,
piggybacked-state-exchange
**Full features:** connection-pooling

**Dependencies:** This cluster blocks Clusters 6, 7, and 8 (distributed features).

### 6. Engine API & Catalog (8 decisions)

Database engine surface — handle lifecycle, cross-table operations, and catalog
management are the engine's public contract.

**Gap-fills:** handle-timeout-ttl
**Minor features:** handle-priority-levels, cross-table-handle-budgets,
message-serialization-format
**Full features:** cross-table-transactions, remote-serialization-protocol,
atomic-multi-table-ddl, catalog-replication, table-migration-protocol

**Dependencies:** Cluster 5 (Networking) for remote-serialization-protocol,
catalog-replication, and table-migration-protocol. Handle items can proceed
independently.

### 7. Partitioning & Rebalancing (12 decisions)

Distributed data placement — partition ownership, replication, rebalancing,
and compaction for multi-node operation.

**Gap-fills:** rebalancing-trigger-policy
**Minor features:** weighted-node-capacity, partition-affinity,
partition-takeover-priority, concurrent-wal-replay-throttling,
in-flight-write-protection, un-walled-memtable-data-loss,
sequential-insert-hotspot, vector-query-partition-pruning,
partition-aware-compaction
**Full features:** cross-partition-transactions, partition-replication-protocol

**Dependencies:** Cluster 5 (Networking) must be stable — replication and
rebalancing require transport + membership.

### 8. Query Execution (3 decisions)

Distributed query — aggregation merges, pagination pushdown, and joins.

**Minor features:** aggregation-query-merge, limit-offset-pushdown
**Full features:** distributed-join-execution

**Dependencies:** Cluster 7 (Partitioning) must be stable — queries route
through the partition layer.

### 9. Encryption & Security (11 decisions)

Field-level encryption lifecycle — key rotation, WAL encryption, migration,
and advanced encrypted query capabilities.

**Gap-fills:** pre-encrypted-flag-persistence
**Minor features:** per-field-pre-encryption, per-field-key-binding,
encryption-key-rotation, wal-entry-encryption
**Full features:** unencrypted-to-encrypted-migration, client-side-encryption-sdk,
encrypted-prefix-wildcard-queries, encrypted-fuzzy-matching,
encrypted-cross-field-joins, index-access-pattern-leakage

**Dependencies:** Cluster 3 (Schema) for field type stability. Minor features
can proceed in parallel with networking work. Full features (encrypted query
types) require research.

## Immediate Actions

- **Promote:** `un-walled-memtable-data-loss`, `in-flight-write-protection` —
  **data loss risks** during rebalancing; address as soon as rebalancing begins
- **Research first:** `partition-replication-protocol` — Raft vs Paxos vs
  leaderless; `/research "consensus protocols for partition replication"`
- **Research first:** `encrypted-prefix-wildcard-queries`,
  `encrypted-fuzzy-matching` — `/research "searchable encryption for prefix
  and fuzzy queries"`
- **Research first:** `distributed-join-execution` — `/research "distributed
  join execution strategies"`
- **Research first:** `corruption-repair-recovery` — needs replication context;
  defer until partition-replication-protocol is resolved
- **New (2026-04-12):** `pure-java-lz4-codec` — performance-gated; evaluate when WAL
  compression benchmarks show Deflate is a bottleneck
- **New (2026-04-12):** `wal-group-commit` — performance-gated; evaluate when WAL throughput
  benchmarks show per-record fsync is the bottleneck
- **New (2026-04-12):** `cross-sst-dictionary-sharing` — evaluate when per-SST dictionaries
  prove to have excessive overhead or poor cache behavior
- **New (2026-04-12):** `wal-dictionary-compression` — evaluate when WAL compression ratios
  with plain ZSTD/Deflate are insufficient
- **New (2026-04-12):** `pure-java-zstd-compressor` — evaluate when native libzstd dependency
  on write path is unacceptable for deployment

## Suggested Sequence

**Phase 1 — COMPLETE** (2026-04-10)

**Phase 2 — IN PROGRESS:** Cluster 3 (Schema & Field Types)
  binary-field-type, parameterized-field-bounds,
  string-to-bounded-string-migration, non-vector-index-type-review
  ~~wal-compression~~ (DONE), ~~codec-negotiation~~ (CLOSED),
  ~~similarity-function-placement~~ (CLOSED),
  ~~codec-dictionary-support~~ (DONE), ~~compaction-recompression~~ (DONE),
  ~~adaptive-compression-strategy~~ (CLOSED)

**Phase 3:** Cluster 1 gap-fills + Cluster 2 + Cluster 4 — can run in parallel
  automatic-backend-detection, block-cache-block-size-interaction,
  atomic-cross-stripe-eviction, parallel-large-cache-eviction,
  vector-storage-cost-optimization, sparse-vector-support

**Phase 4:** Cluster 1 integrity + API evolution + dictionary/WAL performance
  sstable-end-to-end-integrity, memorysegment-codec-api,
  cross-sst-dictionary-sharing, wal-dictionary-compression,
  pure-java-lz4-codec (if benchmarks warrant), wal-group-commit (if benchmarks warrant),
  pure-java-zstd-compressor (if native dependency becomes unacceptable)

**Phase 5:** Cluster 5 (Networking & Discovery) — foundation for all
  distributed work. Start with gap-fills, then minor features, then
  connection-pooling (full feature).

**Phase 6:** Cluster 6 (Engine API) handle items + Cluster 9 (Encryption)
  minor features — can run in parallel
  handle-timeout-ttl, handle-priority-levels, cross-table-handle-budgets,
  pre-encrypted-flag-persistence, per-field-pre-encryption, per-field-key-binding,
  encryption-key-rotation, wal-entry-encryption

**Phase 7:** Cluster 7 (Partitioning & Rebalancing) — after networking
  stabilizes. Safety promotions first: un-walled-memtable-data-loss,
  in-flight-write-protection. Then gap-fills and minor features.
  Full features last: partition-replication-protocol (research first),
  cross-partition-transactions.

**Phase 8:** Cluster 6 (Engine) distributed items + Cluster 8 (Query Execution)
  remote-serialization-protocol, catalog-replication, table-migration-protocol,
  atomic-multi-table-ddl, cross-table-transactions,
  aggregation-query-merge, limit-offset-pushdown, distributed-join-execution
  (research first)

**Phase 9:** Cluster 9 (Encryption) full features — after research
  unencrypted-to-encrypted-migration, client-side-encryption-sdk,
  encrypted-prefix-wildcard-queries, encrypted-fuzzy-matching,
  encrypted-cross-field-joins, index-access-pattern-leakage
