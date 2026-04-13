---
problem: "compaction-recompression"
date: "2026-04-12"
version: 1
status: "confirmed"
supersedes: null
resolves: ["adaptive-compression-strategy"]
files:
  - "modules/jlsm-core/src/main/java/jlsm/compaction/SpookyCompactor.java"
  - "modules/jlsm-core/src/main/java/jlsm/tree/SSTableWriterFactory.java"
  - "modules/jlsm-core/src/main/java/jlsm/tree/StandardLsmTree.java"
  - "modules/jlsm-core/src/main/java/jlsm/tree/TypedStandardLsmTree.java"
---

# ADR — Compaction-Time Re-Compression

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Block Compression Algorithms | Codec characteristics and per-level patterns | [`.kb/algorithms/compression/block-compression-algorithms.md`](../../.kb/algorithms/compression/block-compression-algorithms.md) |
| ZSTD Dictionary Compression | Dictionary training during compaction context | [`.kb/algorithms/compression/zstd-dictionary-compression.md`](../../.kb/algorithms/compression/zstd-dictionary-compression.md) |

---

## Files Constrained by This Decision
- `SpookyCompactor.java` — replace hardcoded `new TrieSSTableWriter(...)` with `writerFactory.create(...)` call; accept `SSTableWriterFactory` in builder
- `SSTableWriterFactory.java` — no signature changes (interface already has `Level` parameter)
- `StandardLsmTree.java` — add `compressionPolicy(Function<Level, CompressionCodec>)` to builder; wire policy into a codec-aware `SSTableWriterFactory` that is shared between flush and compaction paths
- `TypedStandardLsmTree.java` — expose compression policy on typed builder

## Problem
How should the compactor support writing output SSTables with a different
compression codec than the source SSTables? This enables upgrading compression
strategy during background compaction (e.g., Deflate → ZSTD, or plain ZSTD →
ZSTD+dictionary for cold archival levels) and per-level codec selection.

## Constraints That Drove This Decision
- **Composability with dictionary support**: The primary use case is enabling
  ZSTD+dictionary on cold levels during compaction. The writer-orchestrated
  dictionary lifecycle (from `codec-dictionary-support` ADR) requires the
  compactor to create writers with the right codec.
- **Unified writer creation**: The tree currently has two divergent writer
  creation paths — `SSTableWriterFactory` for flushes, hardcoded constructor
  for compaction. These should be unified.
- **Per-level codec selection**: The RocksDB production pattern (LZ4/Deflate
  for hot levels, ZSTD+dictionary for cold) is the target. The design must
  support this naturally, not as an afterthought.

## Decision
**Chosen approach: Writer-Factory Injection with Per-Level Codec Policy**

Replace SpookyCompactor's hardcoded `new TrieSSTableWriter(id, level, path)`
with a call to `SSTableWriterFactory.create(id, level, path)`. The factory
already exists and already receives `Level`. A codec-aware factory closure
inspects the level and returns a writer with the appropriate codec. The tree
builder gains a `compressionPolicy(Function<Level, CompressionCodec>)` that
is wired into the factory for both flush and compaction paths.

### Tree Builder API

```java
// Single codec (current behavior, backward compatible default)
builder.compression(CompressionCodec.deflate())

// Per-level policy (new)
builder.compressionPolicy(level -> switch (level.index()) {
    case 0, 1, 2 -> CompressionCodec.deflate();
    default -> CompressionCodec.zstd(3);
})
```

When `compressionPolicy` is set, it takes precedence over `compression`.
When only `compression` is set, all levels use the same codec (current
behavior). Default: `CompressionCodec.none()`.

### Factory Wiring

The tree builder creates a single `SSTableWriterFactory` that captures the
compression policy. This factory is used for both flush writes and compaction
writes — unifying the two writer creation paths:

```java
SSTableWriterFactory factory = (id, level, path) ->
    new TrieSSTableWriter(id, level, path,
        bloomFactory, compressionPolicy.apply(level));
```

### Compactor Change

SpookyCompactor gains an `SSTableWriterFactory` in its builder (replaces
`idSupplier` + `pathFn` for writer creation). Line 197 changes from:

```java
writer = new TrieSSTableWriter(id, targetLevel, outputPath);
```

to:

```java
writer = writerFactory.create(id, targetLevel, outputPath);
```

No other changes to the compactor. The merge iterator, dedup, tombstone
dropping, and file splitting logic are unchanged.

### Reader Side

No changes. Readers already handle any codec transparently via the
compression map. Source SSTables compressed with Deflate are decompressed
by their readers; output SSTables are re-compressed with the target codec
by the writer. The merge iterator works with decompressed `Entry` objects.

## Rationale

### Why Writer-Factory Injection
- **Zero new types**: Uses the existing `SSTableWriterFactory` interface unchanged
- **Unified writer creation**: Flush and compaction paths both use the same factory
- **Level-aware by design**: Factory already receives `Level`, so per-level codec
  selection is a closure, not a new abstraction
- **Compactor stays codec-agnostic**: No codec field, no compression logic in compactor

### Why not Compactor-Level Output Codec (Candidate A)
- **Asymmetric writer paths**: Adds a codec field to SpookyCompactor while flush
  writes use a factory — two divergent patterns for the same concern
- **Stepping stone, not final form**: Would be replaced when per-level policies arrive

### Why not Per-Level Policy as Separate Decision (Candidate B)
- **Already solved**: The factory approach gives per-level policies for free via the
  `Level` parameter. No separate decision needed.

### Why not Deferred (Candidate C)
- **Blocks dictionary support**: Dictionary training during compaction is the primary
  use case for `codec-dictionary-support`. Deferring re-compression blocks it.

## Implementation Guidance

SpookyCompactor writer creation refactoring (line 197):
- Replace `new TrieSSTableWriter(id, targetLevel, outputPath)` with factory call
- No state carries between writers — each is independent (verified by code review)
- The `idSupplier` and `pathFn` can be captured in the factory closure or remain
  on the compactor (implementation choice)

Tree builder wiring:
- `compressionPolicy` defaults to `_ -> CompressionCodec.none()` (backward compatible)
- `compression(codec)` is sugar for `compressionPolicy(_ -> codec)`
- The factory is constructed once in `build()` and shared with both flush path
  and compactor

F02.R38 amendment needed:
- Current: "The compactor must use the same compression codec as the tree"
- Revised: "The compactor must use the codec determined by the tree's compression
  policy for the target level. The compression policy may assign different codecs
  to different levels."

## What This Decision Does NOT Solve
- Dictionary training lifecycle during compaction — governed by `codec-dictionary-support` ADR; the factory creates dictionary-enabled writers, the writer handles training internally
- Compaction scheduling policy (when to compact, which levels) — separate concern
- Cross-SST dictionary sharing — each output file trains its own dictionary

## Conditions for Revision
This ADR should be re-evaluated if:
- The `SSTableWriterFactory` interface needs a signature change for reasons unrelated
  to compression (would affect this wiring)
- Per-level policies need runtime reconfiguration (current design is build-time only)
- A more granular policy is needed (per-file, per-key-range) beyond per-level

---
*Confirmed by: user deliberation | Date: 2026-04-12*
*Full scoring: [evaluation.md](evaluation.md)*
