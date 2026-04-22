# Changelog

All notable changes to jlsm are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project uses sequential PR numbers for version tracking until a formal
semver release cadence is established.

---

## [Unreleased]

### Added — Pool-aware block size (implement-sstable-enhancements WD-02)
- `sstable.pool-aware-block-size` spec v2 DRAFT → v5 APPROVED — 22 adversarial findings across spec-author Pass 2/3, all applied. Final v5 adds audit-surfaced ArenaBufferPool lifecycle requirements (R0b/c/d/e) and Builder-wide atomicity (R11b)
- `ArenaBufferPool.isClosed()` (R0) — canonical closure observer; class-final mandate (R0a) prevents subclass spoofing
- `ArenaBufferPool.bufferSize()` (R1) — backing field is mandated `final` (R1a) for safe publication without synchronization; returns configured value after close (R2)
- `TrieSSTableWriter.Builder.pool(ArenaBufferPool)` — derives block size from `pool.bufferSize()` when no explicit `blockSize(int)` override; eager R8 overflow + R9 validator checks at `pool()` call time for fail-fast symmetry with the explicit setter
- Build-time closed-pool re-check (R5a) catches pools closed between `pool()` and `build()`, scoped to the case where the pool is still the active block-size source
- Repeat-`pool()` atomicity (R11a) — failed validation is a no-op on all builder state; `Builder.build()` atomicity (R11b) extends this across the full fluent-API surface
- 43 new feature tests (10 in `ArenaBufferPoolTest`, 33 in new `TrieSSTableWriterPoolAwareBlockSizeTest`) covering every requirement + N1/N4 observable non-goals
- One-sentence README update describing the pool-derived block-size flow

### Fixed — Audit round-001 on ArenaBufferPool and TrieSSTableWriter.Builder
- **`ArenaBufferPool.close()` is now thread-safe** — `AtomicBoolean.compareAndSet(false, true)` replaces check-then-act; `Arena.ofShared().close()` (non-idempotent in JDK 25) no longer throws `IllegalStateException` under concurrent invocation
- **`isClosed()` publishes a happens-before edge** to Arena teardown — split into `closing` (atomic claim) + `closed` (post-teardown ack) so observers of `isClosed() == true` are guaranteed the Arena has been released
- **ArenaBufferPool constructor is transactional** — allocation-loop failures (including `OutOfMemoryError`) now close the partially-initialized Arena before propagating; NMT measurement confirms ~27 MiB leak eliminated per failed ctor
- **`close()` refuses to tear down while acquires are outstanding** — throws `IllegalStateException` with outstanding count instead of silently invalidating in-use segments
- **`TrieSSTableWriter.Builder.build()` no longer mutates `bloomFactory` on failed validation** — default lambda resolved into a method-local `final` variable only after all validation gates pass; retry with corrected configuration is now clean
- 5 regression adversarial tests added in two new `SharedStateAdversarialTest.java` + `ConcurrencyAdversarialTest.java` classes

### Added — KB pattern entries
- Updated `patterns/concurrency/non-atomic-lifecycle-flags.md` + `patterns/resource-management/non-idempotent-close.md` — ArenaBufferPool as confirmed instance, nuance on flag-after-teardown ordering when callee is non-idempotent
- New `patterns/validation/partial-init-no-rollback.md` — ctor off-heap variant + Builder commit-before-validate variant (both confirmed by audit)
- New `patterns/validation/mutation-outside-rollback-scope.md` — Builder-specific variant, guidance to resolve defaults into method-local `final` variables
- New `patterns/resource-management/pool-close-with-outstanding-acquires.md` — fail-fast lifecycle protocol for explicit-acquire pools

### Known Gaps
- R0b/R0c/R0d/R0e (ArenaBufferPool lifecycle requirements) currently live in `sstable.pool-aware-block-size` v5 as prerequisite amendments. They should migrate to a dedicated `io.arena-buffer-pool` spec when that is authored (noted in the v5 spec's design narrative)

### Added — Byte-budget block cache (implement-sstable-enhancements WD-01)
- `sstable.byte-budget-block-cache` spec v2 DRAFT → v4 APPROVED — byte-budget LRU displacing entry-count; 40+ requirements covering chokepoint structural enforcement (R6/R7), overflow protection (R29), oversized-entry admission (R11), zero-length rejection (R9/R9a), entry-count cap (R28a), reference lifetime contract (R15a), constructor-side sentinel detection (R3a), close-ordering assertions (R16), and use-after-close guards (R31)
- `LruBlockCache.Builder.byteBudget(long)` replaces removed `capacity(long)`; new `byteBudget()` accessor; transactional setter semantics (R2)
- `StripedBlockCache.Builder.byteBudget(long)` / `expectedMinimumBlockSize(long)` (R20a/R20b); constructor-side MAX_STRIPE_COUNT revalidation (R18a); partial-construction rollback closes already-constructed stripes (R48)
- `BlockCache` interface Javadoc: `capacity()` unit is bytes, `close()` mandates use-after-close ISE, default `getOrLoad` documents monitor-collision risk
- 45 new tests in `ByteBudgetBlockCacheTest.java` across 7 @Nested classes covering byte-budget eviction, overflow protection, R11 oversized-entry admission, R8 put-replace atomicity, R9/R9a zero-length rejection, R32 loader-exception guarantees, MemorySegment slice-size accounting, and reference-lifetime contracts
- 3 KB pattern entries: `reflective-bypass-of-builder-validation`, `interface-contract-missing-from-javadoc`, `fan-out-dispatch-deferred-exception-pattern`

### Changed — `sstable.striped-block-cache` spec v2 → v4
- v3 in-place amendment: R8, R9, R15, R43, R44 invalidated with strike-through notes pointing at superseding byte-budget requirements; R-number gaps preserved so 28 existing `@spec` annotations remain valid
- v4 audit reconciliation: R5 extended for deferred-exception eviction, R11 relaxed to permit non-linear splitmix64 pre-avalanche (defeats algebraic pre-image collisions), R46 extended for ISE translation across stripes
- `splitmix64Hash` gets pre-avalanche multiply-XOR-shift round before combining sstableId with blockOffset — cache key distribution changes on deploy (one-time cache rewarm, no on-disk format change)

### Performance
- Fixed-byte-budget eviction replaces fixed-entry-count eviction — cache memory usage is now predictable and proportional to configured byte budget, regardless of block size variation across SSTables (4 KiB local to 8 MiB remote)
- Added one multiply-XOR-shift per stripe dispatch (~1–2ns on x86) as the cost of eliminating algebraic hash pre-image collisions

### Fixed — Audit round-001 reconciliation
- `subtractBytes` invariant now enforced by runtime check (was `assert`-only, disappeared with `-da`)
- `StripedBlockCache.evict` uses deferred-exception pattern — exceptions from one stripe no longer abort fan-out to others
- `StripedBlockCache.size` translates concurrent-close ISE to a striped-level ISE (consistent with get/put/evict/getOrLoad)
- Reflective-bypass callers of `<init>(Builder)` now get `IllegalArgumentException` with the same diagnostic as `build()` (previously leaked sentinel values or threw `NegativeArraySizeException`)

### Known Gaps
- R28a entry-count cap test (`entryCountCap_R28a_rejectsNearIntegerMaxValue`) is `@Disabled` — requires ~Integer.MAX_VALUE entries which is impractical in a unit test; R28a is enforced in the implementation regardless

### Added — Spec coverage gap closure (close-coverage-gaps WD-01 + WD-02)
- `engine.clustering` v6 → v7 promoted DRAFT → APPROVED — 114/114 requirements reach direct `@spec` annotation coverage (impl + test)
- `engine.in-process-database-engine` v3 → v4 promoted DRAFT → APPROVED — 89/91 traced (R61 and R79 documented UNTESTABLE, retained as impl-only)
- `query.index-types` v1 promoted DRAFT → APPROVED — 31/31 requirements traced
- `query.query-executor` v1 promoted DRAFT → APPROVED — 22/22 requirements traced
- New test `LocalEngineTest.closeContinuesClosingRemainingTablesWhenOneFails` (R78); new tests for R4 / R12 / R19 / R20 in jlsm-table including `ModuleBoundariesTest` covering module-exports boundary

### Added — Encryption architecture decisions (implement-encryption-lifecycle WD-01)
- ADR `three-tier-key-hierarchy` (confirmed) — Tenant KEK → data-domain KEK → DEK; per-tenant KMS isolation always-on; 3 KMS flavors (`none` / `local` / `external`); HKDF hybrid deterministic derivation; sharded per-tenant registry; synthetic `_wal` domain per tenant; plaintext bounded to ingress; primary keys remain plaintext
- ADR `dek-scoping-granularity` (confirmed) — DEK identity is `(tenantId, domainId, tableId, dekVersion)`; per-SSTable and per-object scopes rejected by the encrypt-once invariant
- ADR `tenant-key-revocation-and-external-rotation` (confirmed) — `rekey` API with proof-of-control sentinel; streaming paginated execution with dual-reference migration; three-state per-tenant failure machine (N=5 permanent-failure / 1h grace defaults); opt-in polling
- ADR `kms-integration-model` (confirmed) — `KmsClient` SPI + transient/permanent exception hierarchy; 30 min cache TTL; 3-retry exponential backoff (100 ms → 400 ms → 1.6 s, ±25 % jitter); 10 s per-call timeout; encryption context carries `tenantId` + `domainId` + `purpose`
- ADR `tenant-lifecycle` (deferred) — decommission + data-erasure semantics parked until compliance requirement surfaces
- Amends `encryption-key-rotation` and `per-field-key-binding` (both previously assumed two-tier)

### Changed — Encryption spec v6 APPROVED (implement-encryption-lifecycle WD-01)
- `encryption.primitives-lifecycle` v4 DRAFT → v6 APPROVED — ~40 requirements amended for three-tier + new R71–R82b plus R32b-1, R32c, R75c, R75d, R78c-1, R78f, R80a-1
- Adversarial Pass 5 landed 3 Critical + 7 High + 6 Medium + 7 Low findings; all Critical + High + selected Low fixed in v6
- Verification Note added to `wal.encryption` — F42's "KEK" parameter resolves internally to the tenant's `_wal`-domain DEK-resolver; no F42 requirement text changes

### Added — Research KB entries (staged ahead of architect phases)
- `.kb/systems/security/three-level-key-hierarchy{,-detail}.md` — envelope, HKDF-info domain separation, wrap-primitive choices, reference designs
- `.kb/data-structures/caching/byte-budget-cache-variable-size-entries{,-detail}.md` — admission modes, W-TinyLFU weight-blind admission, pin-count overrun
- `.kb/systems/database-engines/pool-aware-sstable-block-sizing{,-detail}.md` — `block_size == pool.slotSize`, Panama FFM alignment constraints, jemalloc 25 % fragmentation bound

### Known Gaps
- `encryption.primitives-lifecycle` v6 is APPROVED but unimplemented (obligation `implement-f41-lifecycle` remains). Downstream encryption WDs (WD-02 ciphertext format, WD-03 DEK lifecycle + rotation, WD-04 compaction migration, WD-05 runtime concerns) are blocked on WD-01 implementation.
- 6 Medium adversarial findings (M1, M2, M4, M6) tracked for v6.1 amendment during implementation; M3 and M5 effectively resolved in v6.

### Added — Fault Tolerance and Smart Rebalancing (WD-05)
- `ClusterOperationalMode` enum (`NORMAL`, `READ_ONLY`) + `ClusteredEngine.operationalMode()` accessor — engine transitions to `READ_ONLY` when quorum is lost (F04.R41)
- `QuorumLostException` (checked `IOException` subtype) — thrown by `ClusteredTable.create/update/delete/insert` while the engine is in `READ_ONLY` mode; reads remain available (F04.R41)
- `SeedRetryTask` — background task that reinvokes `membership.start(seeds)` on a configurable interval while quorum is lost; idempotent start/stop (F04.R42)
- `ViewReconciler.reconcile(localView, proposedView)` — pure per-member merge applying higher-incarnation-wins with severity `DEAD > SUSPECTED > ALIVE` on ties; called from `RapidMembership.handleViewChangeProposal` before view installation (F04.R43)
- `GraceGatedRebalancer` — scheduled coordinator that drains `GracePeriodManager.expiredDepartures()` and invokes `RendezvousOwnership.differentialAssign(...)` for only the departed member's partitions; `cancelPending(NodeAddress)` aborts a pending rebalance when a node rejoins within grace (F04.R47, R48, R50)
- `RendezvousOwnership.differentialAssign(oldView, newView, affectedPartitionIds)` — partial recomputation that mutates cache entries only for the supplied partition IDs, so assignments for still-live members' partitions remain stable (F04.R48)
- `PartitionKeySpace` SPI — `partitionForKey`, `partitionsForRange`, `partitionCount`, `allPartitions`; thread-safe and immutable after construction (F04.R63)
- `SinglePartitionKeySpace` — trivial fallback mapping every key to one partition (no pruning, backward-compat)
- `LexicographicPartitionKeySpace(splitKeys, partitionIds)` — range-based partition layout with binary-search lookup; enables scan pruning to only overlapping partitions
- `RendezvousOwnership.ownersForKeyRange(tableName, fromKey, toKey, view, keyspace)` — resolves the set of owners whose partitions intersect `[fromKey, toKey)` (F04.R63)
- F04 spec version 5 → 6: R41–R43, R47–R50, R63 rewritten forward to describe shipped behaviour; `open_obligations` now empty
- 115 new tests across 11 test classes: `ViewReconcilerTest`, `SeedRetryTaskTest`, `GraceGatedRebalancerTest`, `RendezvousOwnershipDifferentialTest`, `RapidMembershipReconciliationTest`, `ClusteredTableReadOnlyTest`, `ClusteredEngineQuorumTest`, `SinglePartitionKeySpaceTest`, `LexicographicPartitionKeySpaceTest`, `ClusteredTableScanPruningTest`, `RendezvousOwnershipOwnersForKeyRangeTest`

### Changed — Fault Tolerance and Smart Rebalancing (WD-05)
- `ClusteredEngine.onViewChanged` — evaluates `newView.hasQuorum(config.consensusQuorumPercent())` on every view change and transitions `operationalMode` accordingly; replaces the prior immediate-rebalance logic with a grace-gated pathway that records departures into `GracePeriodManager` and lets `GraceGatedRebalancer` drive rebalancing asynchronously
- `RapidMembership.handleViewChangeProposal` — when a higher-epoch proposal is accepted (subject to R90's no-drop-alive check), delegates per-member reconciliation to `ViewReconciler.reconcile(...)` instead of overwriting the local view wholesale
- `ClusteredTable` — gained an 8-arg canonical constructor accepting `(TableMetadata, ClusterTransport, MembershipProtocol, NodeAddress, RendezvousOwnership, Engine, PartitionKeySpace, Supplier<ClusterOperationalMode>)`; legacy constructors delegate to it with `SinglePartitionKeySpace("default")` and a `() -> NORMAL` mode supplier (backward-compat)
- `ClusteredTable.scan(fromKey, toKey)` — delegates owner resolution to `RendezvousOwnership.ownersForKeyRange(...)` using the configured `PartitionKeySpace`; extracted `resolveScanOwners` and `emptyScanWithMetadata` helpers; preserves R60 local short-circuit, R77 parallel fanout, R100 client close, R67 ordered merge, R64 partial metadata
- `ClusteredTable.create/update/delete/insert` — consult `operationalMode` supplier at method entry and throw `QuorumLostException` when `READ_ONLY`
- `RendezvousOwnership` is now non-`final` to permit in-tree test spying (`GraceGatedRebalancerTest`); behaviour is unchanged

### Performance — Fault Tolerance and Smart Rebalancing (WD-05)
- Scans narrowed by `LexicographicPartitionKeySpace` contact only the partitions whose lexicographic range overlaps `[fromKey, toKey)` instead of every live member — scatter cost now scales with the number of intersecting partitions, not cluster size
- `differentialAssign` avoids full-cache invalidation on member departure: only the departed member's partition IDs are recomputed, so stable assignments on still-live members incur zero cache-miss cost after rebalance

### Known Gaps — Fault Tolerance and Smart Rebalancing (WD-05)
- Table-to-`PartitionKeySpace` configuration is currently by constructor argument; there is no declarative `TableMetadata` or SQL path to assign a range-partitioned layout yet — pruning is opt-in via the new `ClusteredTable` ctor overload
- `SeedRetryTask` retry interval is a construction parameter with no live tuning; per-retry failures are logged and swallowed without surfacing backoff state to the caller

### Added — Wire Query Binding Through StringKeyedTable (WD-03)
- `jlsm.table.QueryRunner<K>` — public functional interface (one method: `run(Predicate)`) used as the bridge between `TableQuery.execute()` and the internal `QueryExecutor` so table implementations can plug in an execution backend without leaking `jlsm.table.internal` types on the builder API
- `TableQuery.unbound()` and `TableQuery.bound(QueryRunner)` — explicit public factories replacing reflection-based construction for the unbound form; internal callers use `bound(...)` to wire a runner
- `JlsmTable.StringKeyed.query()` — default interface method returning an unbound `TableQuery<String>`; production implementations override it to return a bound instance
- `StringKeyedTable.query()` — returns a `TableQuery<String>` bound to the table's schema and `IndexRegistry` via `QueryExecutor.forStringKeys(...)`; empty predicate trees yield an empty iterator rather than an exception
- F05 spec v2 → v3: R37 rewritten forward — `table.query()` now returns a functional `TableQuery` bound to the table's storage and indices; UOE is retained only for schemaless tables. `OBL-F05-R37` resolved.
- 9 new tests in `TableQueryExecutionTest`: index-backed equality, scan-fallback on unindexed field, AND across index + scan predicates, OR union, empty result, Gte scan fallback, schema-mismatch IAE, predicate-tree inspection, unbound `execute()` UOE

### Changed — Wire Query Binding Through StringKeyedTable (WD-03)
- `StandardJlsmTable.StringKeyedBuilder` now materialises an `IndexRegistry` whenever a schema is configured, even with zero index definitions — the registry's document store acts as the schema-aware mirror used for scan-and-filter fallback. Schema-less tables continue to have no registry (and no queries).
- `LocalTable.query()` (jlsm-engine) no longer throws `UnsupportedOperationException` — it delegates to the underlying `JlsmTable.StringKeyed.query()`
- `FullTextTableIntegrationTest.noIndexDefinitions_tableBehavesAsBefore` updated to assert that the registry is present and empty instead of null (the `registry != null && isEmpty()` contract)
- `LocalTableTest.queryThrowsUnsupportedOperationException` renamed and rewritten to `queryReturnsUnboundTableQueryFromStub` — verifies the new delegation contract against a stub delegate

### Fixed — Wire Query Binding Through StringKeyedTable (WD-03)
- Long-standing known gap: `Table.query()` no longer throws `UnsupportedOperationException` for schema-configured `StringKeyed` tables — predicate execution routes through `QueryExecutor`, using registered secondary indices where supported and scan-and-filter fallback otherwise

### Added — Wire Full-Text Index Integration (WD-01)
- `jlsm.core.indexing.FullTextIndex.Factory` — SPI producing `FullTextIndex<MemorySegment>` per `(tableName, fieldName)`, the module-boundary contract between `jlsm-table` and `jlsm-indexing`
- `jlsm.indexing.LsmFullTextIndexFactory` — LSM-backed factory isolating each index on its own `LocalWriteAheadLog` + `TrieSSTable` + `LsmInvertedIndex.StringTermed` + `LsmFullTextIndex.Impl` chain
- `StandardJlsmTable.StringKeyedBuilder.addIndex(IndexDefinition)` and `.fullTextFactory(FullTextIndex.Factory)` — table-builder surface for registering secondary indices with the required factory; rejects FULL_TEXT with no factory at `build()`
- `StringKeyedTable` now routes `create/update/delete` through an optional `IndexRegistry` so FULL_TEXT indices stay synchronised with the primary tree
- F10 spec v2 → v3: R5/R79-R84 rewritten forward to describe the delegation contract; new Amendments section summarises WD-01
- 31 new tests: 18 in `FullTextFieldIndexTest` (adapter semantics against a fake backing), 9 in `LsmFullTextIndexFactoryTest` (factory round-trip against a real LSM-backed index), 4 in `FullTextTableIntegrationTest` (end-to-end: builder + registry + factory through `JlsmTable.StringKeyed`)

### Changed — Wire Full-Text Index Integration (WD-01)
- `IndexRegistry` gained a three-arg constructor accepting a `FullTextIndex.Factory`; the two-arg constructor remains and delegates with `null`. FULL_TEXT definitions without a factory now fail fast with `IllegalArgumentException` at construction instead of throwing `UnsupportedOperationException` on the first write
- `FullTextFieldIndex` is no longer a stub — it adapts `SecondaryIndex` mutations to the batch `FullTextIndex.index`/`remove` API and translates `FullTextMatch` predicates to `Query.TermQuery`
- `jlsm-indexing` test classpath now includes `jlsm-table` (test-only — production code still depends one way: `jlsm-table → jlsm-core`; `jlsm-indexing → jlsm-core`)
- `ResourceLifecycleAdversarialTest` + `IndexRegistryEncryptionTest` updated to reflect the new failure mode (IAE on missing factory / injectable-factory-that-throws) rather than the prior FULL_TEXT stub UOE

### Removed — Wire Full-Text Index Integration (WD-01)
- Obligation `OBL-F10-fulltext` resolved (removed from F10 `open_obligations`); WD-01 marked `COMPLETE` in the cross-module-integration work group

### Known Gaps — Wire Full-Text Index Integration (WD-01)
- Query-time wiring through `JlsmTable.query()` / `TableQuery.execute()` is still scope of `OBL-F05-R37` (a separate WD). The current PR exposes a `StringKeyedTable.indexRegistry()` accessor so integration tests can drive `SecondaryIndex.lookup` directly until that binding lands
- `LongKeyedTable` has not been wired for secondary indices — deferred; no WD caller currently requires it
- `FullTextIndex.Factory` does not yet thread through a shared `ArenaBufferPool`; each per-index LSM tree owns its own WAL + memtable allocations

### Added — Wire Vector Index Integration (WD-02)
- `VectorIndex.Factory` nested SPI in `jlsm.core.indexing.VectorIndex` — bridges `jlsm-table` and `jlsm-vector` without a static module dependency. Keyed on `(tableName, fieldName, dimensions, precision, similarityFunction)`; implementations pick the algorithm (IvfFlat vs Hnsw).
- `LsmVectorIndexFactory` in `jlsm-vector` — concrete factory producing `LsmVectorIndex` instances under per-(table, field) subdirectories. Two static builders: `ivfFlat(Path root, int numCentroids)` and `hnsw(Path root, int maxConnections, int efConstruction)`.
- `StandardJlsmTable.stringKeyedBuilder().vectorFactory(VectorIndex.Factory)` — optional builder parameter; tables that register a `VECTOR` index without a factory fail fast at `build()` with `IllegalArgumentException` instead of silently dropping writes.
- `VectorFieldIndex` (production implementation, in `jlsm.table.internal`) — adapts per-field `SecondaryIndex` mutation callbacks (`onInsert`/`onUpdate`/`onDelete`) to `VectorIndex.index/remove`; translates `VectorNearest(field, query, topK)` predicates to `VectorIndex.search(query, topK)` returning primary keys; handles null old-vector (update after unset field) and null new-vector (delete-semantics insert) without throwing.
- F10 spec v3 → v4: R87–R90 extended to describe the vector factory SPI and shipped behaviour; R6 promoted PARTIAL → SATISFIED; `open_obligations` now empty (both `OBL-F10-fulltext` and `OBL-F10-vector` resolved).
- 3 new test classes: `VectorFieldIndexTest` (in-memory backing + lifecycle), `LsmVectorIndexFactoryTest` (IvfFlat/Hnsw factory construction), `VectorTableIntegrationTest` (end-to-end JlsmTable + VECTOR index + nearest-neighbour query).

### Changed — Wire Vector Index Integration (WD-02)
- `VectorFieldIndex.onInsert/onUpdate/onDelete` no longer silent no-ops — writes to tables with `VECTOR` indices are now actually indexed (or the build fails fast). Previously the stub silently dropped all vector writes, a data-integrity hazard for tables with `VECTOR` indices.
- `VectorFieldIndex.supports(Predicate)` now returns `true` for `VectorNearest` whose field matches the index's field; previously always threw `UnsupportedOperationException`.
- `VectorFieldIndex.lookup(VectorNearest)` returns nearest-neighbour primary keys via the backing `VectorIndex.search`; previously threw `UnsupportedOperationException`.
- `IndexRegistry` gained a four-arg constructor `(schema, definitions, fullTextFactory, vectorFactory)` accepting both factories; the three-arg overload is retained for call-sites that do not register VECTOR indices.
- `VectorIndex` interface gained `precision()` accessor so `VectorFieldIndex` can validate incoming vectors match the configured precision.

### Fixed — Wire Vector Index Integration (WD-02)
- Silent-drop hazard: tables registering a `VECTOR` index previously accepted writes that were never indexed. This PR eliminates that path — all `VECTOR` writes either persist to the backing index or fail at `build()` time.

### Removed — Wire Vector Index Integration (WD-02)
- `OBL-F10-vector` flipped to `resolved` in `.spec/registry/_obligations.json`. Together with WD-01's resolution of `OBL-F10-fulltext`, `F10.open_obligations` is now empty.

### Known Gaps — Wire Vector Index Integration (WD-02)
- `LongKeyedTable` (integer-keyed table variant) is not wired for secondary indices. No caller currently creates a `LongKeyedTable` with a secondary index, so this is deferred until there is one.
- The factory does not yet use a shared `ArenaBufferPool` — each backing `LsmVectorIndex` allocates its own arena. Revisit if multi-index memory pressure becomes a concern.

### Added — Remote Dispatch and Parallel Scatter (WD-03)
- `QueryRequestPayload` — shared encoder/decoder for cluster `QUERY_REQUEST` payloads with `[tableNameLen][tableName UTF-8][partitionId][opcode][body]` format (F04.R68)
- `QueryRequestHandler` — server-side `MessageHandler` that routes `QUERY_REQUEST` messages to the correct local table via `Engine.getTable(name)` and serializes the `QUERY_RESPONSE`
- `PartitionClient.getRangeAsync(...)` — new default interface method returning `CompletableFuture<Iterator<TableEntry<String>>>` for async scatter-gather (F04.R77)
- `MembershipProtocol.removeListener(...)` — new default SPI method for ctor-failure rollback
- F04 spec version 4 → 5: 13 new requirements R102–R114 (ctor/close ordering, listener rollback, scatter cancellation propagation, response encoder overflow guard, merge iterator null-key rejection, range decode malformed-payload semantics)
- 3 new KB adversarial-finding patterns: `unsafe-this-escape-via-listener-registration`, `local-failure-masquerading-as-remote-outage`, `timeout-wrapper-does-not-cancel-source-future`
- 103 tests total across WD-03 (61 cycle-1 + 24 adversarial hardening + 18 audit)

### Changed — Remote Dispatch and Parallel Scatter (WD-03)
- `RemotePartitionClient` ctors now require a trailing `String tableName` parameter (breaking); all payload encoders use the new `QueryRequestPayload` format carrying table name + partition id
- `RemotePartitionClient.getRangeAsync(...)` override — transport-based async path with `orTimeout`, explicit source-future cancellation, and defensive handling of null/truncated/malformed responses
- `ClusteredTable.scan(...)` — parallel fanout via virtual-thread scatter executor + `CompletableFuture.allOf`; preserves local short-circuit (R60), ordered k-way merge (R67), partial-result metadata (R64), and per-node client close on every path (R100)
- `ClusteredEngine` — registers `QueryRequestHandler` in the constructor after all final fields are assigned; symmetric deregister before `transport.close()` in `close()`; rollback of membership listener if handler registration throws
- `ClusteredEngine.onViewChanged` — no-ops when close has begun, preventing post-close state mutations

### Performance — Remote Dispatch and Parallel Scatter (WD-03)
- `ClusteredTable.scan` fans out remote partition requests in parallel; prior implementation issued partitions sequentially with each request blocking on the previous response. Virtual-thread scatter executor keeps fanout non-blocking even when the transport layer has synchronous delivery semantics
- `QueryRequestHandler.handle` reads the incoming payload once instead of twice, halving defensive-clone allocation per dispatch

### Fixed — Remote Dispatch and Parallel Scatter (WD-03)
- `RemotePartitionClient.close()` check-then-set race that could corrupt `OPEN_INSTANCES` counter under concurrent close (replaced `volatile boolean` with `AtomicBoolean.compareAndSet`)
- `ClusteredTable.mergeOrdered` — explicit `IllegalStateException` on null `TableEntry` instead of `AssertionError`/NPE propagation from the heap comparator
- `decodeRangeResponsePayload` — distinguishes legitimately empty range from a populated payload with null schema (previously silent data loss)
- `getRangeAsync` — encoding errors propagate synchronously so upstream scatter logic does not mis-classify a local failure as a remote node outage
- `QueryRequestHandler.encodeRangeResponse` — `Math.addExact` overflow guard + `OutOfMemoryError` catch surface response-too-large as `IOException`
- `join()` rollback catches `Exception` so a checked-exception-leaking `DiscoveryProvider.deregister` no longer hides the original `membership.start` failure
- 12 additional issues surfaced by adversarial audit across concurrency, resource_lifecycle, and shared_state domains

### Removed — Remote Dispatch and Parallel Scatter (WD-03)
- Obligations `OBL-F04-R68-payload-table-id` and `OBL-F04-R77-parallel-scatter` resolved (removed from F04 open obligations); `WD-03` marked `COMPLETE` in the work group manifest

### Known Gaps — Remote Dispatch and Parallel Scatter (WD-03)
- `RemotePartitionClient.doQuery(...)` still returns an empty list — scored-entry response framing over the cluster transport is not yet wired (deferred)
- Wire-format versioning / magic byte deferred to a coordinated protocol spec cycle
- `doGet` null-schema conflation with not-found deferred pending a pre-existing test relaxation

### Added — SSTable v3 format
- SSTable v3 format: per-block CRC32C checksums for silent corruption detection and configurable block size for remote-backend optimization
- `CorruptBlockException` — diagnostic `IOException` subclass with block index and checksum mismatch details
- `TrieSSTableWriter.Builder` — new builder API for v3 format with `blockSize()` and `codec()` configuration
- `SSTableFormat` — v3 constants: `MAGIC_V3`, `FOOTER_SIZE_V3`, `HUGE_PAGE_BLOCK_SIZE` (2 MiB), `REMOTE_BLOCK_SIZE` (8 MiB), `validateBlockSize()`
- `CompressionMap` — v3 21-byte entries with CRC32C checksum, version-aware `deserialize(data, version)`
- F16 spec: SSTable v3 format upgrade (24 requirements)
- 32 new tests covering v3 write/read, backward compatibility, corruption detection, block size validation

### Known Gaps
- Old constructors still produce v2 files — v3 requires explicit opt-in via `TrieSSTableWriter.builder()`

---

### Added
- Engine clustering: peer-to-peer cluster membership, table/partition ownership, and scatter-gather queries in `jlsm-engine`
- `jlsm.engine.cluster` package: `ClusteredEngine`, `ClusteredTable`, `NodeAddress`, `ClusterConfig`, `Message`, `MembershipView`, `PartialResultMetadata`
- SPI interfaces: `ClusterTransport`, `DiscoveryProvider`, `MembershipProtocol`, `MembershipListener`
- `RapidMembership` — Rapid protocol with phi accrual failure detection
- `RendezvousOwnership` — HRW hashing for stateless partition-to-node assignment
- `GracePeriodManager` — configurable grace period before rebalancing on node departure
- `RemotePartitionClient` — serializes CRUD operations over cluster transport
- In-JVM test implementations: `InJvmTransport`, `InJvmDiscoveryProvider`
- 6 ADRs: cluster-membership-protocol, partition-to-node-ownership, rebalancing-grace-period-strategy, scatter-gather-query-execution, discovery-spi-design, transport-abstraction-design
- KB: cluster-membership-protocols (SWIM, Rapid, phi accrual)
- 172 new tests (340 total in jlsm-engine)

### Known Gaps
- `ClusteredTable.scan()` returns empty iterators — full document serialization over transport deferred
- `Table.query()` and `insert(JlsmDocument)` throw `UnsupportedOperationException` in clustered mode

---

## #21 — In-Process Database Engine (2026-03-19)

### Added
- New `jlsm-engine` module — in-process database engine with multi-table management
- `Engine` and `Table` interfaces with interface-based handle pattern
- `HandleTracker` — per-source handle lifecycle tracking with greedy-source-first eviction
- `TableCatalog` — per-table metadata directories with lazy recovery and partial-creation cleanup
- `LocalEngine` wires the full LSM stack (WAL, MemTable, SSTable, DocumentSerializer) per table
- ADR: engine-api-surface-design (interface-based handle pattern with lease eviction)
- ADR: table-catalog-persistence (per-table metadata directories)
- KB: catalog-persistence-patterns (4 patterns evaluated)
- 134 tests across 9 test classes

### Known Gaps
- `Table.query()` throws `UnsupportedOperationException` — `TableQuery` has a private constructor preventing cross-module instantiation

---

## #20 — Field-Level Encryption (2026-03-19)

### Added
- Field-level encryption support in `jlsm-core`: AES-GCM, AES-SIV, OPE (Boldyreva), DCPE-SAP
- `FieldEncryptionDispatch` for coordinating per-field encryption in document serialization
- Pre-encrypted document ingestion via `JlsmDocument.preEncrypted()`
- Searchable encryption via OPE-indexed fields
- `BoundedString` field type for OPE range bounds
- ADR: encrypted-index-strategy, pre-encrypted-document-signaling, bounded-string-field-type

### Performance
- Encryption hot-path optimized to avoid redundant key derivation

---

## #19 — DocumentSerializer Optimization (2026-03-19)

### Performance
- Heap fast path for deserialization — avoids MemorySegment allocation for common cases
- Precomputed constants and dispatch table for field type routing
- Measurable reduction in deserialization latency for schema-driven reads

---

## #18 — Streaming Block Decompression (2026-03-18)

### Performance
- Stream block decompression during v2 compressed SSTable scans
- Eliminates full-block materialization before iteration — reduces peak memory and latency

---

## #17 — Block-Level SSTable Compression (2026-03-18)

### Added
- `CompressionCodec` interface in `jlsm-core` with pluggable codec support
- `DeflateCodec` implementation with configurable compression level
- SSTable v2 format with per-block compression and framing
- ADR: compression-codec-api-design, sstable-block-compression-format

---

## #16 — Vector Field Type (2026-03-17)

### Added
- `FieldType.VectorType` with configurable element type (FLOAT16, FLOAT32) and fixed dimensions
- `Float16` half-precision floating-point support in `jlsm-table`
- ADR: vector-type-serialization-encoding (flat encoding, no per-vector metadata)

---

## #15 — Striped Block Cache (2026-03-17)

### Added
- `StripedBlockCache` — multi-threaded block cache using splitmix64 stripe hashing
- Configurable stripe count for concurrent access patterns
- ADR: stripe-hash-function (Stafford variant 13)

### Performance
- Significant reduction in cache contention under concurrent read workloads

---

## #14 — Block Cache Benchmark (2026-03-17)

### Added
- `LruBlockCacheBenchmark` JMH regression benchmark
- perf-review findings documented in `perf-output/findings.md`

---

## #13 — Table Partitioning (2026-03-17)

### Added
- Range-based table partitioning in `jlsm-table`
- `PartitionedTable`, `PartitionConfig`, `PartitionDescriptor` APIs
- Per-partition co-located secondary indices
- ADR: table-partitioning (range partitioning chosen over hash)

---

## #12 — JMH Benchmarking Infrastructure (2026-03-17)

### Added
- JMH benchmarking infrastructure with shared `jmh-common.gradle`
- `jlsm-bloom-benchmarks` and `jlsm-tree-benchmarks` modules
- Two run modes: snapshot (2 forks, 5 iterations) and sustained (1 fork, 30 iterations)
- JFR recording and async-profiler integration

### Performance
- Three performance fixes identified and applied during initial profiling

---

## #11 — Architecture Review (2026-03-16)

### Changed
- Codebase cleanup from architecture review pass
- ADR index and history updates

---

## #10 — SQL Parser (2026-03-16)

### Added
- New `jlsm-sql` module — hand-written recursive descent SQL SELECT parser
- Supports WHERE, ORDER BY, LIMIT, OFFSET, MATCH(), VECTOR_DISTANCE(), bind parameters
- Translates SQL queries into jlsm-table `Predicate` tree
- Schema validation at translation time

---

## #9 — Secondary Indices and Query API (2026-03-16)

### Added
- Secondary index support in `jlsm-table`: scalar field indices, full-text, vector
- Fluent `TableQuery` API with predicate tree (AND, OR, comparison operators)
- `QueryExecutor` for index-accelerated query execution

---

## #8 — Float16 Vector Support (2026-03-16)

### Added
- Half-precision floating-point (`Float16`) support
- Vector field storage with Float16 element type

---

## #7 — Vallorcine Integration (2026-03-16)

### Added
- Vallorcine tooling integration for feature development workflow
- TDD pipeline automation

---

## #6 — Document Table Module (2026-03-16)

### Added
- New `jlsm-table` module — schema-driven document model
- `JlsmSchema`, `JlsmDocument`, `FieldType`, `FieldDefinition`
- `DocumentSerializer` with schema-driven binary serialization
- CRUD operations via `JlsmTable.StringKeyed` and `JlsmTable.LongKeyed`
- JSON and YAML parsing/writing (no external libraries)

---

## #5 — Full-Text Search (2026-03-16)

### Added
- `LsmInvertedIndex` and `LsmFullTextIndex` in `jlsm-indexing`
- Tokenization pipeline with Porter stemming and English stop word filtering

---

## #3 — Module Consolidation (2026-03-16)

### Changed
- Consolidated 7 separate implementation modules into single `jlsm-core` module
- All interfaces and implementations now co-located for simpler dependency management

---

## #2 — S3 Remote Integration (2026-03-16)

### Added
- `jlsm-remote-integration` test module with S3Mock
- `RemoteWriteAheadLog` — one-file-per-record WAL for object store backends
- Remote filesystem support via Java NIO FileSystem SPI

---

## #1 — Initial Implementation (2026-03-16)

### Added
- Core LSM-Tree components: MemTable, SSTable (TrieSSTableWriter/Reader), WAL (LocalWriteAheadLog)
- Bloom filters (simple and blocked 512-bit variants)
- Compaction strategies: size-tiered, leveled, SPOOKY
- LRU block cache with off-heap support
- `StandardLsmTree` and typed wrappers (StringKeyed, LongKeyed, SegmentKeyed)
- JPMS module structure with `jlsm-core` as the foundation
- Full test suite with JUnit Jupiter
