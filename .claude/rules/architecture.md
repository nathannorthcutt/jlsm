## Architecture

The library is organized as a Gradle multi-project build where each subproject is a distinct JPMS module. All interfaces and implementations live in `jlsm-core`; higher-level modules build on top in layers:

- `jlsm-indexing` and `jlsm-vector` depend only on `jlsm-core` (domain-specific index structures)
- `jlsm-table` depends on `jlsm-core` (document model, secondary indices, fluent query API)
- `jlsm-sql` depends on `jlsm-table` (SQL SELECT parser and translator)

### Core LSM-Tree Components

The canonical LSM-Tree pipeline — **write path**: WAL → MemTable → flush to SSTable; **read path**: MemTable → SSTable levels (newest first) → compaction merges levels down:

| Component | Purpose |
|---|---|
| **MemTable** | In-memory write buffer; holds recent mutations; flushed to SSTable when full |
| **SSTable** | Immutable sorted on-disk file; multiple levels (L0–Ln) |
| **WAL (Write-Ahead Log)** | Durability log; replayed on recovery before MemTable is rebuilt |
| **Bloom Filter** | Probabilistic membership test to skip SSTable reads for missing keys |
| **Compaction** | Background merge of SSTables to bound read amplification; strategies include size-tiered, leveled and spooky |
| **Block Cache** | LRU cache for hot SSTable blocks to reduce I/O |

### Key Design Principles

- **Interfaces and implementations in jlsm-core** — all contracts and their implementations live in `jlsm-core`; higher-level modules layer on top (see dependency graph above)
- **No external runtime dependencies** — this is a pure library; avoid pulling in third-party frameworks
- **Designed for composition** — consumers wire components together; the library does not mandate a single configuration
- **Java NIO / memory-mapped I/O** — prefer `java.nio` for SSTable and WAL file operations
- **Off-heap friendliness** — key/value representations should be compatible with `ByteBuffer` / `MemorySegment` (Panama FFM API) to support off-heap vector data

### Module Structure

```
jlsm-core/          # ALL interfaces AND all implementations (bloom, wal, memtable,
                    #   sstable, compaction, cache, tree) + shared utilities
                    #   Internal packages (not exported): jlsm.bloom.hash,
                    #   jlsm.wal.internal, jlsm.memtable.internal, jlsm.sstable.internal,
                    #   jlsm.compaction.internal, jlsm.tree.internal
jlsm-indexing/      # Higher-level index structures: inverted index (LsmInvertedIndex)
jlsm-vector/        # Vector index: IvfFlat, Hnsw backed by LSM tree; uses jdk.incubator.vector
jlsm-table/         # Document model, schema, secondary indices, fluent query API
                    #   Internal packages (not exported): jlsm.table.internal
jlsm-sql/           # SQL SELECT parser and translator; depends on jlsm-table
tests/
  jlsm-remote-integration/  # Integration tests against remote (S3) backends
examples/
  sample-db/         # Example application wiring components together
benchmarks/
  jlsm-bloom-benchmarks/    # JMH benchmarks for bloom filter implementations
  jlsm-tree-benchmarks/     # JMH benchmarks for LSM tree operations
```

Each module directory contains its own `build.gradle` and `src/main/java/module-info.java`.
