# Changelog

All notable changes to jlsm are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project uses sequential PR numbers for version tracking until a formal
semver release cadence is established.

---

## [Unreleased]

_No unreleased changes._

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
