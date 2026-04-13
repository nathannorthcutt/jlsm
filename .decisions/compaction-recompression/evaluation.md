---
problem: "compaction-recompression"
evaluated: "2026-04-12"
candidates:
  - name: "Compactor-level output codec"
    label: "A"
  - name: "Per-level codec policy on tree"
    label: "B"
  - name: "Deferred — codec passthrough only"
    label: "C"
constraint_weights:
  scale: 1
  resources: 1
  complexity: 1
  accuracy: 2
  operational: 2
  fit: 3
---

# Evaluation — compaction-recompression

## References
- Constraints: [constraints.md](constraints.md)
- KB sources: general industry practice (RocksDB per-level compression)

## Constraint Summary
The binding constraints are composability with dictionary support (Fit, weight 3)
and data integrity across codec changes (Accuracy, weight 2). The compactor already
streams entries through a merge iterator — re-compression must work within this
existing pattern.

## Weighted Constraint Priorities
| Constraint | Weight (1-3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 1 | Block count doesn't change the design — streaming handles any size |
| Resources | 1 | Same resource model as all compression work — not a differentiator |
| Complexity | 1 | Unlimited budget |
| Accuracy | 2 | Data integrity through re-compression is critical |
| Operational | 2 | Throughput matters for background work |
| Fit | 3 | Integration with compactor, writer, tree, and dictionary support is the core question |

---

## Candidate A: Compactor-Level Output Codec

**Design:** SpookyCompactor gains a `CompressionCodec outputCodec` field. Each
TrieSSTableWriter created during compaction uses this codec. The tree builder
configures the compactor's output codec. Source SSTables are decompressed by
their readers (already handles any codec transparently).

| Constraint | Weight | Score (1-5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | Streaming — no additional memory or I/O |
| Resources | 1 | 5 | 5 | No new dependencies |
| Complexity | 1 | 5 | 5 | One new field + constructor parameter on SpookyCompactor |
|            |   |   |   | **Would be a 2 if:** compactor needed to handle mixed codecs per-writer within a single compact() call |
| Accuracy | 2 | 5 | 10 | Entries flow through merge iterator as key/value pairs — codec is applied at write time, data is unchanged |
|          |   |   |    | **Would be a 2 if:** some entries were written without decompression (raw block copy) and the source codec differs |
| Operational | 2 | 4 | 8 | Re-compression adds CPU cost (decompress + recompress per block). Acceptable for background. |
|             |   |   |   | **Would be a 2 if:** re-compression doubled compaction time and there was a latency SLA on compaction |
| Fit | 3 | 5 | 15 | Minimal change: one field on compactor, wire through tree builder. Composes with dictionary support — writer receives codec with dictionary at construction. |
|     |   |   |    | **Would be a 2 if:** the tree builder's factory pattern couldn't accommodate a separate compaction codec |
| **Total** | | | **48** | |

**Hard disqualifiers:** None

**Key strengths:** Minimal change. Composable. Doesn't preclude per-level policies later.

**Key weaknesses:** No automatic level-awareness — caller must set the codec explicitly.

---

## Candidate B: Per-Level Codec Policy on Tree

**Design:** StandardLsmTree maintains a `Map<Level, CompressionCodec>` or a
`Function<Level, CompressionCodec>` policy. Compactor queries the tree for the
target level's codec automatically.

| Constraint | Weight | Score (1-5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | Same streaming model |
| Resources | 1 | 5 | 5 | No new dependencies |
| Complexity | 1 | 4 | 4 | Map/function on tree + compactor queries it + builder API for per-level config |
| Accuracy | 2 | 5 | 10 | Same data flow as A |
| Operational | 2 | 5 | 10 | Automatic level-aware selection — no manual configuration per compaction |
|             |   |   |    | **Would be a 2 if:** the policy was too coarse and needed per-file granularity |
| Fit | 3 | 3 | 9 | Changes tree builder API significantly (per-level codec map). Couples compactor to tree's level model. This IS the `adaptive-compression-strategy` deferred decision. |
| **Total** | | | **43** | |

**Hard disqualifiers:** None, but this is the `adaptive-compression-strategy` decision in disguise — evaluating it here would duplicate that deferred decision's scope.

**Key strengths:** Automatic, no per-compaction configuration needed.

**Key weaknesses:** Larger API surface. Couples compactor to tree policy. Is actually the deferred `adaptive-compression-strategy` problem.

---

## Candidate C: Deferred — Codec Passthrough Only

| Constraint | Weight | Score (1-5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | N/A |
| Resources | 1 | 5 | 5 | N/A |
| Complexity | 1 | 5 | 5 | Zero change |
| Accuracy | 2 | 5 | 10 | No re-compression, no risk |
| Operational | 2 | 3 | 6 | Cannot upgrade codec without full rewrite |
| Fit | 3 | 1 | 3 | **HARD DISQUALIFIER:** Blocks codec-dictionary-support's primary use case. Dictionary training during compaction requires the compactor to write with a dictionary-bound codec. |
| **Total** | | | **34** | |

**Hard disqualifiers:** Blocks the dictionary compression feature.

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| A: Compactor output codec | 5 | 5 | 5 | 10 | 8 | 15 | **48** |
| B: Per-level policy | 5 | 5 | 4 | 10 | 10 | 9 | **43** |
| C: Deferred | 5 | 5 | 5 | 10 | 6 | 3 | **34** |

---

## Candidate D: Writer-Factory Codec Injection [ADDED AFTER FALSIFICATION]

**Design:** SpookyCompactor takes an `SSTableWriterFactory` (the interface already
exists at `jlsm.tree.SSTableWriterFactory`) instead of hardcoding
`new TrieSSTableWriter(...)`. The factory encapsulates the codec choice. The
compactor becomes codec-agnostic.

The factory already receives `Level` — a codec-aware factory inspects the level
and returns a writer with the appropriate codec. This unifies the flush and
compaction writer creation paths (tree already uses the same factory for flushes).

| Constraint | Weight | Score (1-5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | Same streaming model |
| Resources | 1 | 5 | 5 | No new dependencies |
| Complexity | 1 | 5 | 5 | Replace hardcoded constructor with factory call — simpler than adding a field |
|            |   |   |   | **Would be a 2 if:** SSTableWriterFactory needed breaking changes to support codec |
| Accuracy | 2 | 5 | 10 | Same data flow — entries through merge iterator, codec applied at write time |
| Operational | 2 | 4 | 8 | Same re-compression cost as A |
| Fit | 3 | 5 | 15 | Uses existing factory interface (zero new types). Unifies flush/compaction writer paths. Level parameter enables per-level codec policies naturally. |
|     |   |   |    | **Would be a 2 if:** the factory interface needed a signature change, breaking all existing consumers |
| **Total** | | | **48** | |

**Hard disqualifiers:** None

**Key strengths:**
- No new field on compactor — compactor becomes codec-agnostic
- Unifies the two divergent writer creation paths (flush + compaction)
- `Level` parameter on factory naturally supports per-level policies without new API
- Zero new types — uses the existing `SSTableWriterFactory` functional interface

**Key weaknesses:**
- SpookyCompactor currently hardcodes writer creation — needs to accept a factory dependency

---

## Revised Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| D: Writer-factory injection | 5 | 5 | 5 | 10 | 8 | 15 | **48** |
| A: Compactor output codec | 5 | 5 | 5 | 10 | 8 | 12 | **45** |
| B: Per-level policy | 5 | 5 | 4 | 10 | 10 | 9 | **43** |
| C: Deferred | 5 | 5 | 5 | 10 | 6 | 3 | **34** |

Note: A's Fit revised from 5 to 4 (weighted 12) after falsification identified the
dual writer-creation-path asymmetry and tree builder API impact.

## Revised Preliminary Recommendation
Candidate D (Writer-factory injection) wins. Same score as original A but with
cleaner properties: no new compactor field, unified writer creation, natural
upgrade path to per-level policies. The `SSTableWriterFactory` interface already
exists with the right signature.

## Risks and Open Questions
- F02.R38 needs amendment to allow output codec != source codec
- Dictionary training during compaction: the factory creates writers with
  dictionary training enabled; the writer's internal lifecycle handles buffering
  and training per the codec-dictionary-support ADR
- SpookyCompactor must transition from hardcoded writer creation to
  factory-based — a refactoring step that touches the constructor/builder
