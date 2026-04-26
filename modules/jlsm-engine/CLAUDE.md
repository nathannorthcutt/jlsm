# jlsm-engine

In-process database engine providing multi-table management with self-organized
storage. Supports table creation, metadata introspection, CRUD operations, and
querying via pass-through to the `jlsm-table` fluent API. Thread-safe for
concurrent callers.

## Dependencies

- `jlsm.table` (transitive) — document model, schema, query API
- `jlsm.cluster` (transitive) — `ClusterTransport` SPI + `Message`, `MessageType`, `MessageHandler`, `NodeAddress` (migrated out of jlsm-engine into the new jlsm-cluster module per `transport-module-placement` ADR)
- `jlsm.core` — LSM tree, WAL, MemTable, SSTable, bloom filters

## Exported Packages

- `jlsm.engine` — public API: `Engine`, `Table`, `TableMetadata`,
  `EncryptionMetadata`, `EngineMetrics`, `AllocationTracking`,
  `HandleEvictedException`
- `jlsm.engine.cluster` — clustering API: `ClusteredEngine`,
  `ClusterOperationalMode`, `QuorumLostException`,
  `PartitionKeySpace`, `SinglePartitionKeySpace`, `LexicographicPartitionKeySpace`,
  `ClusterConfig`, `Member`, `MemberState`,
  `MembershipView`, `PartialResultMetadata`,
  `DiscoveryProvider`, `MembershipProtocol`, `MembershipListener`

(Note: `NodeAddress`, `Message`, `MessageType`, `ClusterTransport`, `MessageHandler` previously
exported here have been migrated to the public `jlsm.cluster` package in the new `jlsm-cluster`
module. Consumers reach them via the transitive `requires jlsm.cluster`.)

## Internal Packages

Not exported in `module-info.java` and must not be made public:

- `jlsm.engine.internal` — `LocalEngine`, `CatalogTable` (was `LocalTable` —
  renamed by WD-02 WU-1 to one of two permits of sealed `Table`),
  `HandleTracker`, `HandleRegistration`, `TableCatalog`, `CatalogIndex`
  (R9a-mono format-version high-water), `CatalogLock` SPI +
  `FileBasedCatalogLock` + `CatalogLockFactory` (per-table exclusive lock
  shared by `Engine.enableEncryption` R7b step 1 and `TrieSSTableWriter`
  R10c step 2)
- `jlsm.engine.cluster.internal` — `CatalogClusteredTable` (was public
  `ClusteredTable` — relocated + renamed by WD-02 WU-1 to one of two permits
  of sealed `Table`), `InJvmDiscoveryProvider`,
  `PhiAccrualFailureDetector`, `RapidMembership`, `RendezvousOwnership`,
  `GracePeriodManager`, `RemotePartitionClient`,
  `ViewReconciler`, `SeedRetryTask`, `GraceGatedRebalancer`

(Note: `InJvmTransport` and `NodeAddressCodec` previously here have been migrated to
`jlsm.cluster.internal` in the new `jlsm-cluster` module. `NodeAddressCodec` is reachable from
this module via the qualified export `jlsm.cluster/jlsm.cluster.internal to jlsm.engine`.)

## Key Design Decisions

- **Engine API Surface:** Interface-based handle pattern with tracked lifecycle
  and lease eviction ([ADR](.decisions/engine-api-surface-design/adr.md))
- **Table Catalog:** Per-table metadata directories with lazy recovery
  ([ADR](.decisions/table-catalog-persistence/adr.md))
- **Cluster Membership:** Rapid protocol + phi accrual failure detection
  ([ADR](.decisions/cluster-membership-protocol/adr.md))
- **Partition Ownership:** Rendezvous hashing (HRW) — stateless, deterministic
  ([ADR](.decisions/partition-to-node-ownership/adr.md))
- **Scatter-Gather:** Partition-aware proxy table with k-way merge
  ([ADR](.decisions/scatter-gather-query-execution/adr.md))
- **Transport:** Message-oriented SPI with fire-and-forget + request-response
  ([ADR](.decisions/transport-abstraction-design/adr.md)). Implementation lives in jlsm-cluster
  (per [`transport-module-placement` ADR](.decisions/transport-module-placement/adr.md)) — see
  `transport.multiplexed-framing` v3 APPROVED spec.

## Known Gaps

- `CatalogClusteredTable.query()` and `insert(JlsmDocument)` throw
  `UnsupportedOperationException` in clustered mode.
- `RemotePartitionClient.doQuery(...)` returns an empty list — scored-entry
  response framing over the cluster transport is not yet wired.

## Resolved Gaps

- `Table.query()` now delegates to the underlying `JlsmTable.StringKeyed.query()`
  which, for schema-configured tables, returns a `TableQuery<String>` bound
  through `QueryExecutor`. OBL-F05-R37 resolved by WD-03
  (cross-module-integration work group).
