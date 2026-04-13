---
problem: "codec-dictionary-support"
evaluated: "2026-04-12"
candidates:
  - name: "Writer-orchestrated, codec stays stateless"
    label: "A"
  - name: "Dictionary-aware codec subtype"
    label: "B"
  - name: "Codec + external factory pattern"
    label: "C"
constraint_weights:
  scale: 1
  resources: 2
  complexity: 1
  accuracy: 3
  operational: 2
  fit: 3
---

# Evaluation — codec-dictionary-support

## References
- Constraints: [constraints.md](constraints.md)
- KB sources: see candidate sections below

## Constraint Summary
The binding constraints are cross-platform readability (write native, read pure-Java),
the stateless/thread-safe codec contract from F02.R2/R7, and backward compatibility
with existing CompressionCodec consumers. Complexity budget is unlimited.

## Weighted Constraint Priorities
| Constraint | Weight (1-3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 1 | Block sizes are fixed; dictionary benefit is data-dependent, not scale-dependent |
| Resources | 2 | No mandatory deps is important but well-understood — Panama probe pattern established |
| Complexity | 1 | Unlimited budget — not a differentiator |
| Accuracy | 3 | Cross-platform readability is a hard requirement; format self-description is critical |
| Operational | 2 | Read-path latency matters; dictionary loading must be amortized |
| Fit | 3 | Must not break existing consumers; stateless contract is the core tension |

---

## Candidate A: Writer-Orchestrated, Codec Stays Stateless

**Design:** Writer manages the dictionary lifecycle. A separate `ZstdDictionaryTrainer`
utility (native-only via Panama FFM) handles dictionary training. The codec is created
with dictionary bytes at construction time — it remains stateless and thread-safe.

**Key components:**
- `CompressionCodec.zstd()` — tiered: Panama FFM native → pure-Java decompressor-only → Deflate fallback
- `CompressionCodec.zstd(int level)` — with explicit compression level
- `CompressionCodec.zstd(MemorySegment dictionary)` — with pre-trained dictionary
- `ZstdDictionaryTrainer` — accumulates samples, trains via native ZSTD, produces dictionary bytes
- `TrieSSTableWriter` gains buffering mode: when dictionary training is enabled, buffers all blocks before compression
- SSTable v4 footer: adds dictionary meta-block alongside index, bloom filter, compression map
- Reader: loads dictionary meta-block at file open, creates dictionary-bound codec

**KB source:** [zstd-dictionary-compression.md](../../.kb/algorithms/compression/zstd-dictionary-compression.md) — dictionary lifecycle, per-SST training pattern, CDict/DDict thread safety

| Constraint | Weight | Score (1-5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 1 | 5 | 5 | Per-SST dictionary adapts to data distribution per file (zstd-dictionary-compression#per-sst-lifecycle) |
| Resources | 2 | 5 | 10 | Native optional; pure-Java decompressor handles dictionary frames; Panama probe established |
|           |   |   |    | **Would be a 2 if:** pure-Java ZSTD decompressor cannot handle dictionary frames (not yet verified in implementation) |
| Complexity | 1 | 4 | 4 | DictionaryTrainer is a bounded utility (~200 lines); writer buffering adds one conditional code path |
| Accuracy | 3 | 5 | 15 | Dictionary stored in-file as meta-block; reader loads from on-disk metadata per F02.R17 |
|          |   |   |    | **Would be a 2 if:** dictionary meta-block adds format version complexity that breaks v3 readers |
| Operational | 2 | 5 | 10 | DDict loaded once per file open, shared across all block decompressions; amortized cost is negligible |
|             |   |   |    | **Would be a 2 if:** dictionary loading adds measurable latency on random-access reads |
| Fit | 3 | 5 | 15 | Codec remains stateless and thread-safe. No new methods on CompressionCodec. Only new static factories. Existing consumers unaffected. |
|     |   |   |    | **Would be a 2 if:** the writer's buffering mode introduces a bifurcated write path that's hard to maintain |
| **Total** | | | **59** | |

**Hard disqualifiers:** None

**Key strengths:**
- Preserves the stateless, thread-safe CompressionCodec contract unchanged
- Dictionary lifecycle is explicit and visible in the writer — no hidden state
- Tiered detection reuses the established TierDetector pattern from JSON SIMD

**Key weaknesses:**
- Writer gains complexity (buffering mode, conditional dictionary training)
- DictionaryTrainer is native-only — no pure-Java training path

---

## Candidate B: Dictionary-Aware Codec Subtype

**Design:** New `DictionaryCodec` interface extends `CompressionCodec` with training
lifecycle methods. The codec accumulates training samples internally and produces
a dictionary when training completes.

**Key components:**
- `DictionaryCodec extends CompressionCodec` with: `addTrainingSample(MemorySegment)`, `trainDictionary()`, `dictionaryBytes()`, `fromDictionary(MemorySegment)`
- Writer checks `codec instanceof DictionaryCodec` and calls lifecycle methods
- Codec manages internal training state

| Constraint | Weight | Score (1-5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 1 | 5 | 5 | Same per-SST training capability as A |
| Resources | 2 | 4 | 8 | Same tiered approach possible, but codec now has mutable state |
| Complexity | 1 | 3 | 3 | Training state inside codec is hidden — harder to reason about lifecycle |
| Accuracy | 3 | 4 | 12 | Same on-disk format, but mutable codec complicates thread-safety guarantees |
| Operational | 2 | 4 | 8 | Same read-path performance, but training state must be cleared between files |
| Fit | 3 | 1 | 3 | **HARD DISQUALIFIER:** Violates F02.R2 (stateless) and F02.R7 (thread-safe without synchronization). Adding mutable training state to the codec breaks the foundational contract. |
| **Total** | | | **39** | |

**Hard disqualifiers:** Violates F02.R2 (stateless codec contract) and F02.R7 (thread-safe without synchronization). The `addTrainingSample()` method introduces shared mutable state.

**Key strengths:**
- Writer code is simpler — delegates to codec
- Codec encapsulates all compression logic

**Key weaknesses:**
- Breaks the fundamental CompressionCodec contract
- Mutable state means codecs can't be shared across threads or files
- `instanceof` dispatch in the writer is a code smell

---

## Candidate C: Codec + External Factory Pattern

**Design:** New `CompressionCodecFactory` creates configured codec instances.
Writer receives a factory, calls `factory.createForFile(samples)` to get a
dictionary-bound codec per SST file.

**Key components:**
- `CompressionCodecFactory` interface: `CompressionCodec create()`, `CompressionCodec createWithDictionary(MemorySegment[] samples)`
- `ZstdCodecFactory implements CompressionCodecFactory`
- Writer uses factory instead of direct codec

| Constraint | Weight | Score (1-5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 1 | 5 | 5 | Same capability |
| Resources | 2 | 4 | 8 | Factory adds abstraction layer but no new resource requirements |
| Complexity | 1 | 3 | 3 | Factory + codec + trainer = three concepts instead of two |
| Accuracy | 3 | 5 | 15 | Same on-disk format and self-describing properties |
| Operational | 2 | 5 | 10 | Same read-path performance |
| Fit | 3 | 3 | 9 | Codec stays stateless (good), but factory pattern changes the writer API — existing builder takes `CompressionCodec`, would now need to also accept `CompressionCodecFactory`. Two configuration paths for the same concern. |
| **Total** | | | **50** | |

**Hard disqualifiers:** None

**Key strengths:**
- Clean separation: factory handles creation, codec handles compression
- Extensible for future codec-specific configuration

**Key weaknesses:**
- Premature abstraction — only one codec (ZSTD) needs dictionary support
- Changes writer builder API (codec vs factory), complicating consumer experience
- Two concepts (factory + codec) where one suffices

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| A: Writer-orchestrated | 5 | 10 | 4 | 15 | 10 | 15 | **59** |
| B: Dictionary-aware subtype | 5 | 8 | 3 | 12 | 8 | 3 | **39** |
| C: Factory pattern | 5 | 8 | 3 | 15 | 10 | 9 | **50** |

## Preliminary Recommendation
Candidate A (Writer-orchestrated, codec stays stateless) wins on weighted total.
The stateless codec contract is preserved, the tiered Panama FFM pattern is
reused, and no new abstractions are introduced. The writer gains a buffering
mode for dictionary training, but this is localized and explicit.

## Risks and Open Questions
- Pure-Java ZSTD decompressor handling of dictionary frames is assumed but not
  yet verified in implementation — the noop-dev/Zstandard decompressor supports
  dictionaries in the C# port but this needs confirmation for the Java port
- SSTable format versioning: adding a dictionary meta-block may need v4 footer,
  or may be additive to v3 (dictionary as optional meta-block, backwards compatible)
- Writer buffering memory: must be bounded (configurable max, fail if exceeded)
