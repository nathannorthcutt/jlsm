# Changelog

All notable changes to jlsm are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project uses sequential PR numbers for version tracking until a formal
semver release cadence is established.

---

## [Unreleased]

### Added — Wire Vector Index Integration (WD-02)
- `VectorIndex.Factory` nested SPI in `jlsm.core.indexing.VectorIndex` — bridges `jlsm-table` and `jlsm-vector` without a static module dependency. Keyed on `(tableName, fieldName, dimensions, precision, similarityFunction)`; implementations pick the algorithm (IvfFlat vs Hnsw).
- `LsmVectorIndexFactory` in `jlsm-vector` — concrete factory producing `LsmVectorIndex` instances under per-(table, field) subdirectories. Two static builders: `ivfFlat(Path root, int numCentroids)` and `hnsw(Path root, int maxConnections, int efConstruction)`.
- `StandardJlsmTable.stringKeyedBuilder().vectorFactory(VectorIndex.Factory)` — optional builder parameter; tables that register a `VECTOR` index without a factory fail fast at `build()` with `IllegalArgumentException` instead of silently dropping writes.
- `VectorFieldIndex` (production implementation, in `jlsm.table.internal`) — adapts per-field `SecondaryIndex` mutation callbacks (`onInsert`/`onUpdate`/`onDelete`) to `VectorIndex.index/remove`; translates `VectorNearest(field, query, topK)` predicates to `VectorIndex.search(query, topK)` returning primary keys; handles null old-vector (update after unset field) and null new-vector (delete-semantics insert) without throwing.
- F10 spec v2 → v3 on this branch: R87–R90 extended to describe the factory SPI and shipped behaviour; R6 promoted PARTIAL → SATISFIED.
- 3 new test classes: `VectorFieldIndexTest` (in-memory backing + lifecycle), `LsmVectorIndexFactoryTest` (IvfFlat/Hnsw factory construction), `VectorTableIntegrationTest` (end-to-end JlsmTable + VECTOR index + nearest-neighbour query).

### Changed — Wire Vector Index Integration (WD-02)
- `VectorFieldIndex.onInsert/onUpdate/onDelete` no longer silent no-ops — writes to tables with `VECTOR` indices are now actually indexed (or the build fails fast). Previously the stub silently dropped all vector writes, a data-integrity hazard for tables with `VECTOR` indices.
- `VectorFieldIndex.supports(Predicate)` now returns `true` for `VectorNearest` whose field matches the index's field; previously always threw `UnsupportedOperationException`.
- `VectorFieldIndex.lookup(VectorNearest)` returns nearest-neighbour primary keys via the backing `VectorIndex.search`; previously threw `UnsupportedOperationException`.
- `IndexRegistry` and `StringKeyedTable` route vector-index registration through the builder-supplied factory; absence surfaces as a clear `IllegalArgumentException` instead of a late `UnsupportedOperationException` at first write.
- `VectorIndex` interface gained `precision()` accessor so `VectorFieldIndex` can validate incoming vectors match the configured precision.

### Fixed — Wire Vector Index Integration (WD-02)
- Silent-drop hazard: tables registering a `VECTOR` index previously accepted writes that were never indexed. This PR eliminates that path — all `VECTOR` writes either persist to the backing index or fail at `build()` time.

### Removed — Wire Vector Index Integration (WD-02)
- `OBL-F10-vector` flipped to `resolved` in `.spec/registry/_obligations.json`. `F10.open_obligations` now lists only `OBL-F10-fulltext` on this branch (WD-01 resolves that obligation on a parallel PR #36).

### Known Gaps — Wire Vector Index Integration (WD-02)
- `LongKeyedTable` (integer-keyed table variant) is not wired for secondary indices. No caller currently creates a `LongKeyedTable` with a secondary index, so this is deferred until there is one.
- The factory does not yet use a shared `ArenaBufferPool` — each backing `LsmVectorIndex` allocates its own arena. Revisit if multi-index memory pressure becomes a concern.
- **F10 version merge-order note:** this PR bumps F10 v2 → v3 on a branch from main. WD-01 (PR #36) does the same. Whichever PR merges second must bump F10 to v4 and empty `open_obligations`.

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
