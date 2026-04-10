# Decisions Roadmap

**Generated:** 2026-04-10
**Deferred:** 68 decisions in 9 clusters

## Summary

11 gap-fill | 39 minor feature | 18 full feature

## Clusters (priority order)

### 1. Storage & Compression (8 decisions)

Foundational I/O layer — codec contracts, block integrity, and write-path compression affect every module above.

**Gap-fills:** codec-thread-safety, max-compressed-length, per-block-checksums, backend-optimal-block-size
**Minor features:** codec-negotiation, codec-dictionary-support, wal-compression, compaction-recompression

**Dependencies:** None — this cluster is a dependency for others (encryption, networking).

### 2. Cache & Hashing (4 decisions)

Performance infrastructure — stripe hash and cache eviction underpin the block cache and all striped data structures.

**Gap-fills:** hash-distribution-uniformity, power-of-two-stripe-optimization
**Minor features:** atomic-cross-stripe-eviction, parallel-large-cache-eviction

**Dependencies:** None — self-contained within jlsm-core.

### 3. Schema & Field Types (5 decisions)

Data model completeness — field types and index definitions are the contract surface for jlsm-table consumers.

**Gap-fills:** similarity-function-placement
**Minor features:** binary-field-type, parameterized-field-bounds, string-to-bounded-string-migration, non-vector-index-type-review

**Dependencies:** None — self-contained within jlsm-table.

### 4. Vector (2 decisions)

Vector storage efficiency at scale — quantization and sparse support for billion-vector workloads.

**Minor features:** vector-storage-cost-optimization, sparse-vector-support

**Dependencies:** None — self-contained within jlsm-vector.

### 5. Cluster Networking & Discovery (12 decisions)

The communication substrate — transport, membership, and discovery must stabilize before any distributed feature can proceed.

**Gap-fills:** discovery-environment-config, ownership-lookup-optimization
**Minor features:** transport-traffic-priority, scatter-backpressure, continuous-rediscovery, authenticated-discovery, table-ownership-discovery, membership-view-stall-recovery, slow-node-detection, dynamic-membership-threshold, piggybacked-state-exchange
**Full features:** message-serialization-format, connection-pooling

**Dependencies:** Cluster 1 (Storage & Compression) should be stable — codecs are used in message serialization. This cluster blocks Clusters 6, 7, and 8.

### 6. Engine API & Catalog (9 decisions)

Database engine surface — handle lifecycle, cross-table operations, and catalog management are the engine's public contract.

**Gap-fills:** handle-timeout-ttl
**Minor features:** handle-priority-levels, cross-table-handle-budgets
**Full features:** cross-table-transactions, remote-serialization-protocol, atomic-multi-table-ddl, cross-table-transaction-coordination, catalog-replication, table-migration-protocol

**Dependencies:** Cluster 5 (Networking) for remote-serialization-protocol, catalog-replication, and table-migration-protocol. Handle-related items (timeout, priority, budgets) can proceed independently.

### 7. Partitioning & Rebalancing (13 decisions)

Distributed data placement — partition ownership, replication, rebalancing, and compaction for multi-node operation.

**Gap-fills:** rebalancing-trigger-policy
**Minor features:** weighted-node-capacity, partition-affinity, partition-takeover-priority, concurrent-wal-replay-throttling, in-flight-write-protection, un-walled-memtable-data-loss, sequential-insert-hotspot, vector-query-partition-pruning, partition-aware-compaction
**Full features:** cross-partition-transactions, partition-replication-protocol, cross-partition-atomic-writes

**Dependencies:** Cluster 5 (Networking) must be stable — replication and rebalancing require transport + membership. Cluster 6 (Engine) for catalog-aware partition management.

### 8. Query Execution (4 decisions)

Distributed query — aggregation merges, pagination pushdown, joins, and atomic writes across partitions.

**Minor features:** aggregation-query-merge, limit-offset-pushdown
**Full features:** distributed-join-execution, cross-partition-atomic-writes (duplicate — see below)

**Dependencies:** Cluster 7 (Partitioning) must be stable — queries route through the partition layer. distributed-join-execution also depends on jlsm-sql.

### 9. Encryption & Security (11 decisions)

Field-level encryption lifecycle — key rotation, WAL encryption, migration, and advanced encrypted query capabilities.

**Gap-fills:** pre-encrypted-flag-persistence
**Minor features:** per-field-pre-encryption, per-field-key-binding, encryption-key-rotation, wal-entry-encryption
**Full features:** unencrypted-to-encrypted-migration, client-side-encryption-sdk, encrypted-prefix-wildcard-queries, encrypted-fuzzy-matching, encrypted-cross-field-joins, index-access-pattern-leakage

**Dependencies:** Cluster 3 (Schema) for field type stability. Encryption minor features can proceed in parallel with networking work. Full features (encrypted query types) require research.

## Immediate Actions

- **Promote:** un-walled-memtable-data-loss, in-flight-write-protection — **data loss risks** during rebalancing; should be addressed as soon as rebalancing implementation begins, regardless of cluster ordering
- **Promote:** codec-thread-safety — **correctness risk**; thread-safety contract is undefined, any concurrent codec use is implicitly unsafe
- **Merge:** cross-partition-transactions (table-partitioning) + cross-partition-atomic-writes (scatter-gather-query-execution) — same problem (atomic multi-partition operations), different parent ADRs
- **Merge:** cross-table-transactions (engine-api-surface-design) + cross-table-transaction-coordination (table-catalog-persistence) — same problem (cross-table transaction coordination), different parent ADRs
- **Research first:** partition-replication-protocol — Raft vs Paxos vs leaderless replication; `/research "consensus protocols for partition replication"`
- **Research first:** encrypted-prefix-wildcard-queries, encrypted-fuzzy-matching — order-preserving and searchable encryption schemes; `/research "searchable encryption for prefix and fuzzy queries"`
- **Research first:** distributed-join-execution — distributed join strategies (broadcast, shuffle, semi-join); `/research "distributed join execution strategies"`

## Suggested Sequence

**Phase 1:** Cluster 1 gap-fills + Cluster 2 gap-fills (batch TDD pass)
  codec-thread-safety, max-compressed-length, per-block-checksums, backend-optimal-block-size,
  hash-distribution-uniformity, power-of-two-stripe-optimization

**Phase 2:** Cluster 3 (Schema & Field Types) + Cluster 1 minor features
  similarity-function-placement, binary-field-type, parameterized-field-bounds,
  string-to-bounded-string-migration, non-vector-index-type-review,
  codec-negotiation, codec-dictionary-support, wal-compression, compaction-recompression

**Phase 3:** Cluster 2 minor features + Cluster 4 (Vector) — can run in parallel
  atomic-cross-stripe-eviction, parallel-large-cache-eviction,
  vector-storage-cost-optimization, sparse-vector-support

**Phase 4:** Cluster 5 (Networking & Discovery) — foundation for all distributed work
  Start with gap-fills (discovery-environment-config, ownership-lookup-optimization),
  then minor features, then full features (message-serialization-format, connection-pooling)

**Phase 5:** Cluster 6 (Engine API) handle items (independent of networking)
  handle-timeout-ttl, handle-priority-levels, cross-table-handle-budgets

**Phase 6:** Cluster 9 (Encryption) minor features — can parallel with Phase 5
  pre-encrypted-flag-persistence, per-field-pre-encryption, per-field-key-binding,
  encryption-key-rotation, wal-entry-encryption

**Phase 7:** Cluster 7 (Partitioning & Rebalancing) — after networking stabilizes
  Start with safety promotions: un-walled-memtable-data-loss, in-flight-write-protection
  Then: rebalancing-trigger-policy, weighted-node-capacity, partition-affinity, etc.
  Full features last: partition-replication-protocol (research first), cross-partition-transactions

**Phase 8:** Cluster 6 (Engine) distributed items + Cluster 8 (Query Execution)
  remote-serialization-protocol, catalog-replication, table-migration-protocol,
  atomic-multi-table-ddl, cross-table-transactions (merged),
  aggregation-query-merge, limit-offset-pushdown, distributed-join-execution (research first)

**Phase 9:** Cluster 9 (Encryption) full features — after research
  unencrypted-to-encrypted-migration, client-side-encryption-sdk,
  encrypted-prefix-wildcard-queries, encrypted-fuzzy-matching,
  encrypted-cross-field-joins, index-access-pattern-leakage
