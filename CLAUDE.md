# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

`jlsm` is a pure Java 25 modular library implementing LSM-Tree (Log-Structured Merge-Tree) components. It is designed to be composable — consumers can use individual components to build higher-level products such as key-value stores and vector database indices.

## Technology Stack

- **Java 25** — uses modern language features; target Java version is 25
- **JPMS** — all modules declare `module-info.java`; keep inter-module dependencies explicit and minimal
- **Gradle** (Groovy DSL, `build.gradle`) — multi-project build

## Build Commands

```bash
./gradlew build          # Compile + test + assemble all modules
./gradlew test           # Run all tests
./gradlew check          # Run all verification (tests, linting, etc.)
./gradlew :module:test   # Run tests for a specific submodule

# Run a single test class
./gradlew :module:test --tests "com.example.SomeTest"

# Run a single test method
./gradlew :module:test --tests "com.example.SomeTest.methodName"
```

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
jlsm-core/          # Interfaces: Memtable, SSTable, Compaction, WAL, etc.
jlsm-memtable/      # MemTable implementations (e.g., ConcurrentSkipListMap-backed)
jlsm-sstable/       # SSTable read/write, block encoding, index structures
jlsm-wal/           # Write-ahead log implementation
jlsm-bloom/         # Bloom filter and other probabilistic filters
jlsm-compaction/    # Compaction strategies (size-tiered, leveled)
jlsm-cache/         # Block cache implementations
```

Each submodule directory contains its own `build.gradle` and `src/main/java/module-info.java`.

## Test-Driven Development

All implementation work follows a strict TDD cycle:

1. **Write tests first** — before creating any implementation class, write the test class covering the intended behaviour
2. **Confirm tests fail** — run the tests and verify they fail with a compilation error or assertion failure (not an infrastructure error); a test that passes before the implementation exists is a bad test
3. **Implement** — write the minimum implementation to make the tests pass
4. **Verify** — run the tests again and confirm all pass before committing

Never write an implementation class without a preceding failing test. Never skip the failure-confirmation step.

## Git Workflow

- **Never commit directly to `main`** — all work must be done on a feature branch
- **Branch naming** — use kebab-case names that describe the work (e.g., `add-bloom-filter`, `fix-wal-recovery`)
- **Starting work** — if currently on `main`, ask whether there is an existing branch to switch to or gather enough context to create an appropriate branch before proceeding
- **Finishing work** — when work is complete, open a PR with a summary of changes and request a review before merging to `main`
