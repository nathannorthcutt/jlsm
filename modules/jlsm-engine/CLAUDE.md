# jlsm-engine

In-process database engine providing multi-table management with self-organized
storage. Supports table creation, metadata introspection, CRUD operations, and
querying via pass-through to the `jlsm-table` fluent API. Thread-safe for
concurrent callers.

## Dependencies

- `jlsm.table` (transitive) — document model, schema, query API
- `jlsm.core` — LSM tree, WAL, MemTable, SSTable, bloom filters

## Exported Packages

- `jlsm.engine` — public API: `Engine`, `Table`, `TableMetadata`, `EngineMetrics`,
  `AllocationTracking`, `HandleEvictedException`
- `jlsm.engine.cluster` — clustering API: `ClusteredEngine`, `ClusteredTable`,
  `NodeAddress`, `ClusterConfig`, `Message`, `MessageType`, `Member`, `MemberState`,
  `MembershipView`, `PartialResultMetadata`, `ClusterTransport`, `MessageHandler`,
  `DiscoveryProvider`, `MembershipProtocol`, `MembershipListener`

## Internal Packages

Not exported in `module-info.java` and must not be made public:

- `jlsm.engine.internal` — `LocalEngine`, `LocalTable`, `HandleTracker`,
  `HandleRegistration`, `TableCatalog`
- `jlsm.engine.cluster.internal` — `InJvmTransport`, `InJvmDiscoveryProvider`,
  `PhiAccrualFailureDetector`, `RapidMembership`, `RendezvousOwnership`,
  `GracePeriodManager`, `RemotePartitionClient`

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
  ([ADR](.decisions/transport-abstraction-design/adr.md))

## Known Gaps

- `Table.query()` throws `UnsupportedOperationException` — `TableQuery` has a
  private constructor and cannot be instantiated from outside `jlsm.table`. Use
  `Table.scan()` for range queries until this is resolved.
- `ClusteredTable.scan()` returns empty iterators — full document serialization
  over the cluster transport is deferred until the message format is finalized.
- `ClusteredTable.query()` and `insert(JlsmDocument)` throw
  `UnsupportedOperationException` in clustered mode.
