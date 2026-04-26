# Work Group: decisions-backlog

**Goal:** Resolve deferred decision backlog per roadmap
**Status:** active
**Created:** 2026-04-13
**Last resolved:** 2026-04-13

## Work Definitions

| WD | Title | Status | Domains | Deps | Produces |
|----|-------|--------|---------|------|----------|
| WD-01 | Schema & Field Types | COMPLETE | decisions | 0 | adr:binary-field-type, adr:parameterized-field-bounds, adr:string-to-bounded-string-migration, adr:non-vector-index-type-review |
| WD-02 | Storage & Compression | COMPLETE | decisions | 0 | adr:automatic-backend-detection, adr:block-cache-block-size-interaction, adr:sstable-end-to-end-integrity, adr:memorysegment-codec-api, adr:pure-java-lz4-codec, adr:wal-group-commit, adr:cross-sst-dictionary-sharing, adr:wal-dictionary-compression, adr:corruption-repair-recovery, adr:pure-java-zstd-compressor |
| WD-03 | Cache | COMPLETE | decisions | 0 | adr:atomic-cross-stripe-eviction, adr:parallel-large-cache-eviction |
| WD-04 | Vector | COMPLETE | decisions | 0 | adr:vector-storage-cost-optimization, adr:sparse-vector-support |
| WD-05 | Cluster Networking & Discovery | COMPLETE | decisions | 0 | adr:discovery-environment-config, adr:ownership-lookup-optimization, adr:transport-traffic-priority, adr:scatter-backpressure, adr:continuous-rediscovery, adr:authenticated-discovery, adr:table-ownership-discovery, adr:membership-view-stall-recovery, adr:slow-node-detection, adr:dynamic-membership-threshold, adr:piggybacked-state-exchange, adr:connection-pooling |
| WD-06 | Engine API & Catalog | COMPLETE | decisions | 0 | adr:handle-timeout-ttl, adr:handle-priority-levels, adr:cross-table-handle-budgets, adr:cross-table-transactions, adr:remote-serialization-protocol, adr:atomic-multi-table-ddl, adr:catalog-replication |
| WD-07 | Partitioning & Rebalancing | COMPLETE | decisions | 0 | adr:rebalancing-trigger-policy, adr:weighted-node-capacity, adr:partition-affinity, adr:partition-takeover-priority, adr:concurrent-wal-replay-throttling, adr:in-flight-write-protection, adr:un-walled-memtable-data-loss, adr:sequential-insert-hotspot, adr:vector-query-partition-pruning, adr:partition-aware-compaction, adr:cross-partition-transactions, adr:partition-replication-protocol, adr:table-migration-protocol |
| WD-08 | Query Execution | COMPLETE | decisions | 0 | adr:aggregation-query-merge, adr:limit-offset-pushdown, adr:distributed-join-execution |
| WD-09 | Encryption & Security | COMPLETE | decisions | 0 | adr:pre-encrypted-flag-persistence, adr:per-field-pre-encryption, adr:per-field-key-binding, adr:encryption-key-rotation, adr:wal-entry-encryption, adr:unencrypted-to-encrypted-migration, adr:client-side-encryption-sdk, adr:encrypted-prefix-wildcard-queries, adr:encrypted-fuzzy-matching, adr:encrypted-cross-field-joins, adr:index-access-pattern-leakage |
| WD-10 | Rebalancing Safety & Recovery | COMPLETE | decisions | 0 | adr:partition-takeover-priority, adr:concurrent-wal-replay-throttling, adr:in-flight-write-protection, adr:un-walled-memtable-data-loss, adr:corruption-repair-recovery |
| WD-11 | Partition Optimization | COMPLETE | decisions | 0 | adr:sequential-insert-hotspot, adr:partition-aware-compaction, adr:vector-query-partition-pruning |
| WD-12 | Encrypted Query Capabilities | COMPLETE | decisions | 0 | adr:encrypted-prefix-wildcard-queries, adr:encrypted-fuzzy-matching, adr:encrypted-cross-field-joins, adr:client-side-encryption-sdk |
| WD-13 | Catalog & Scan Lifecycle | COMPLETE | decisions | 0 | adr:catalog-replication, adr:table-migration-protocol, adr:scan-snapshot-binding, adr:scan-lease-gc-watermark |

**Total:** 81 decisions (11 gap-fill, 35 minor, 19 full + 16 newly unblocked)

## Dependency Graph

```
WD-01 (Schema)         ─── independent
WD-02 (Storage)        ─── independent
WD-03 (Cache)          ─── independent
WD-04 (Vector)         ─── independent
WD-05 (Networking)     ─── blocks WD-06, WD-07
WD-06 (Engine API)     ─── blocked by WD-05 (connection-pooling, message-serialization-format)
WD-07 (Partitioning)   ─── blocked by WD-05 (connection-pooling, message-serialization-format)
WD-08 (Query)          ─── blocked by WD-07 (cross-partition-transactions)
WD-09 (Encryption)     ─── independent
WD-10 (Rebalancing Safety) ─── independent (unblocked by WD-07)
WD-11 (Partition Opt)  ─── independent (unblocked by WD-07)
WD-12 (Encrypted Query)─── independent (unblocked by WD-09)
  └─ internal: fuzzy-matching after prefix-queries
WD-13 (Catalog & Scan) ─── independent (unblocked by WD-06 + WD-08)
  └─ internal: scan-lease-gc-watermark after scan-snapshot-binding
  └─ internal: table-migration-protocol after catalog-replication
```

## Suggested Execution Order

Phase 2 (current): WD-01 (Schema)
Phase 3: WD-02 gap-fills + WD-03 + WD-04 (parallel)
Phase 4: WD-02 minor/full features
Phase 5: WD-05 (Networking) — unblocks WD-06 and WD-07
Phase 6: WD-06 handle items + WD-09 minor features (parallel)
Phase 7: WD-07 (Partitioning) — safety promotions first
Phase 8: WD-06 distributed items + WD-08 (Query)
Phase 9: WD-09 full features
