## Architecture

The library is organized as a Gradle multi-project build where each subproject is a distinct JPMS module. Modules should have minimal dependencies on each other; prefer defining interfaces in `jlsm-core` and providing implementations in focused submodules.

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

- **Interfaces in core, implementations in submodules** — `jlsm-core` defines the contracts; submodules provide pluggable implementations
- **No external runtime dependencies** — this is a pure library; avoid pulling in third-party frameworks
- **Designed for composition** — consumers wire components together; the library does not mandate a single configuration
- **Java NIO / memory-mapped I/O** — prefer `java.nio` for SSTable and WAL file operations
- **Off-heap friendliness** — key/value representations should be compatible with `ByteBuffer` / `MemorySegment` (Panama
  FFM API) to support off-heap vector data
- **Defensive assertions** — use `assert` statements throughout all code (public and private) to document and enforce assumptions; validate all inputs to public methods eagerly with explicit exceptions (`IllegalArgumentException`, `NullPointerException`, etc.) — never trust external callers

### Expected Module Structure

```
jlsm-core/          # Interfaces: MemTable, SSTable, Compaction, WAL, Bloom, Cache, LsmTree, etc. and shared utilities
jlsm-memtable/      # MemTable implementations (e.g., ConcurrentSkipListMap-backed)
jlsm-sstable/       # SSTable read/write, block encoding, trie key index
jlsm-wal/           # Write-ahead log implementations (local mmap, remote single-file-per-record)
jlsm-bloom/         # Bloom filter implementations (e.g., BlockedBloomFilter with MurmurHash3)
jlsm-compaction/    # Compaction strategies (size-tiered, leveled, spooky)
jlsm-cache/         # Block cache implementations (e.g., LRU)
jlsm-tree/          # High-level LSM tree (StandardLsmTree, TypedLsmTree) wiring all components
jlsm-indexing/      # (planned) Higher-level index structures built on LSM tree components (e.g., vector, inverted index)
```

Each submodule directory contains its own `build.gradle` and `src/main/java/module-info.java`.
