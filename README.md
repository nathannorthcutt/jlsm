# jlsm

A pure Java 25 modular library of LSM-Tree (Log-Structured Merge-Tree) components.

> **Built entirely by [Claude Code](https://claude.ai/claude-code).** Every line of code, test, and design decision in this repository was written by Anthropic's Claude Code CLI under human direction. The project serves as both a serious LSM-Tree library and an experiment in how far AI-assisted development can be pushed — exploring productivity gains from AI agents working in a complex, multi-module codebase with strict TDD, JPMS boundaries, and real architectural tradeoffs.

## About

`jlsm` is an exploration of what a modern LSM-Tree library looks like when built from scratch with current Java features. The goal is not just to replicate established designs, but to take a fresh look at each component — experimenting with new data structures, novel compaction strategies, and storage abstractions that work across local and remote file systems.

The library is composed of focused, independently usable modules:

- **MemTable** — in-memory write buffer backed by modern concurrent data structures
- **SSTable** — immutable sorted files with efficient block encoding and sparse index structures
- **Write-Ahead Log (WAL)** — durable append-only log for crash recovery
- **Bloom Filters** — probabilistic membership tests to short-circuit unnecessary SSTable reads; multiple filter variants and tuning strategies
- **Compaction** — background merge strategies including size-tiered, leveled, and experimental approaches
- **Block Cache** — LRU and CLOCK cache implementations for hot SSTable blocks, with off-heap support via the Panama FFM API

All key/value representations use `MemorySegment` (Java's Foreign Function & Memory API), keeping the door open for off-heap storage and direct integration with vector data without extra copies.

File I/O is built on `java.nio` with an abstraction layer designed to support both local filesystems and remote object stores (e.g., S3-compatible APIs).

There are no external runtime dependencies. The library is a pure composition of Java standard library components.

### Built on top of jlsm

The library is designed to be a foundation. Planned sample implementations include:

- **Key-value store** — a complete embedded KV engine wiring all components together
- **Search index** — an inverted index built on the sorted SSTable layer
- **Vector data store** — an approximate nearest-neighbor index using off-heap `MemorySegment` values and LSM-style compaction

---

## Developer Setup

### Prerequisites

- **Java 25** JDK — the project uses Java 25 language features and APIs; earlier versions are not supported
- **Gradle** — a wrapper is included; no separate installation is needed

### Building

```bash
# Compile, test, and assemble all modules
./gradlew build

# Run all tests
./gradlew test

# Run all verification (tests + static checks)
./gradlew check

# Run tests for a specific submodule
./gradlew :modules:jlsm-core:test

# Run a single test class
./gradlew :modules:jlsm-core:test --tests "jlsm.core.memtable.SomeTest"

# Run a single test method
./gradlew :modules:jlsm-core:test --tests "jlsm.core.memtable.SomeTest.methodName"
```

### Project structure

The build is a Gradle multi-project build. Every submodule is also a JPMS module and declares a `module-info.java`. Inter-module dependencies are kept explicit and minimal — `jlsm-core` defines interfaces; other modules provide implementations.

---

## Architecture

All interfaces live in `jlsm-core`. Implementations are in separate submodules (`jlsm-memtable`, `jlsm-sstable`, `jlsm-wal`, `jlsm-bloom`, `jlsm-compaction`, `jlsm-cache`). Consumers compose the pieces they need.

The diagram below shows the core interface components and how data flows through them on the write path, read path, and during background compaction.

```mermaid
graph TD
    Client([Client])

    subgraph Write Path
        WAL[WriteAheadLog\nappend / replay / truncateBefore]
        MemTable[MemTable\napply / get / scan]
        SSTableWriter[SSTableWriter\nwrite sorted entries]
        BFWrite[BloomFilter\nadd keys during write\nserialize to footer]
    end

    subgraph Read Path
        BFRead[BloomFilter\nmightContain]
        Cache[BlockCache\ngetOrLoad / evict]
        SSTableReader[SSTableReader\nget / scan]
        SSTableMeta[SSTableMetadata\nlevel · key range · size]
    end

    subgraph Background Compaction
        Compactor[Compactor\nselectCompaction / compact]
        CompactionTask[CompactionTask\nsource + output SSTables]
    end

    %% Write path
    Client -->|write| WAL
    WAL -->|durability confirmed| MemTable
    MemTable -->|flush when full| SSTableWriter
    SSTableWriter -->|embed filter| BFWrite
    SSTableWriter -->|produces| SSTableMeta

    %% Read path
    Client -->|read| MemTable
    MemTable -->|miss| BFRead
    BFRead -->|might contain| Cache
    Cache -->|miss: load block| SSTableReader
    SSTableReader -->|describes file| SSTableMeta

    %% Compaction
    SSTableMeta -->|level manifest| Compactor
    Compactor -->|selects| CompactionTask
    CompactionTask -->|execute merge| Compactor
    Compactor -->|evict stale blocks| Cache
    Compactor -->|produces new| SSTableMeta
```

### Component responsibilities

| Interface | Package | Responsibility |
|---|---|---|
| `WriteAheadLog` | `jlsm.core.wal` | Append entries to durable storage; replay on recovery; truncate after flush |
| `MemTable` | `jlsm.core.memtable` | In-memory write buffer; point lookup and range scan; tracks approximate size |
| `SSTableWriter` | `jlsm.core.sstable` | Write sorted entries to an immutable SSTable file; embeds a `BloomFilter` in the footer |
| `SSTableReader` | `jlsm.core.sstable` | Read-only view of an SSTable; point lookup and range scan; uses `BlockCache` |
| `SSTableMetadata` | `jlsm.core.sstable` | Descriptor (key range, level, size) for a single SSTable file; no I/O |
| `BloomFilter` | `jlsm.core.bloom` | Probabilistic key membership test; serialize/deserialize for SSTable embedding |
| `Compactor` | `jlsm.core.compaction` | Select compaction candidates from level manifest; execute sorted merge; return new metadata |
| `BlockCache` | `jlsm.core.cache` | Cache hot SSTable blocks by `(sstableId, blockOffset)`; evict on compaction |
