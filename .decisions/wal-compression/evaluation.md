---
problem: "wal-compression"
evaluated: "2026-04-12"
candidates:
  - path: ".kb/algorithms/compression/wal-compression-patterns.md"
    name: "Per-Record + MemorySegment-Native API"
  - path: ".kb/algorithms/compression/wal-compression-patterns.md"
    name: "Per-Record + Dual API Bridge"
  - path: ".kb/algorithms/compression/wal-compression-patterns.md"
    name: "Streaming Compression (RocksDB-style)"
constraint_weights:
  scale: 1
  resources: 3
  complexity: 2
  accuracy: 3
  operational: 2
  fit: 3
---

# Evaluation — wal-compression

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used: see candidate sections below

## Constraint Summary

The binding constraints demand MemorySegment-first I/O (no byte[] in the data
path), per-record self-describing format (no external compression map), and
negligible write-path latency impact. Both local and remote WAL implementations
must be supported. CRC32 integrity over uncompressed payload must be preserved.

## Weighted Constraint Priorities

| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 1 | WAL compression is a storage optimization, not a scale-out concern |
| Resources | 3 | MemorySegment-first is a project-wide mandate; zero byte[] in hot path |
| Complexity | 2 | WAL is durability-critical; implementation must be straightforward |
| Accuracy | 3 | Lossless only; CRC integrity non-negotiable; crash-safety essential |
| Operational | 2 | Recovery handling and mixed-format replay are important |
| Fit | 3 | Must work with both WAL impls; codec API evolution affects all modules |

---

## Candidate: Per-Record + MemorySegment-Native API

**KB source:** [`.kb/algorithms/compression/wal-compression-patterns.md`](../../.kb/algorithms/compression/wal-compression-patterns.md)
**Relevant sections read:** `#approach-1-per-record-compression`, `#memorysegment-integration` (Option A)
**Design:** Replace `byte[] compress/decompress` with `MemorySegment` overloads
on `CompressionCodec`. All callers (SSTable writer/reader + new WAL compression)
migrate to MemorySegment API. WAL records are compressed per-record with a flags
byte, codec ID, and uncompressed size in the header.

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 1 | 4 | 4 | Per-record compression: 1.5-2x ratio, ~15μs overhead (#complexity-analysis) |
|  |  |  |  | **Would be a 2 if:** records are consistently <64 bytes (metadata overhead dominates) |
| Resources | 3 | 5 | 15 | Zero byte[] in data path — MemorySegment throughout. Pool-managed buffers only (#memorysegment-integration Option A) |
|  |  |  |  | **Would be a 2 if:** Deflater/Inflater require byte[] internally and we can't avoid the copy |
| Complexity | 2 | 3 | 6 | Codec API change touches 3 production call sites + 2 codec impls + tests. Manageable but not trivial. |
| Accuracy | 3 | 5 | 15 | CRC over uncompressed payload; self-contained records; crash-safe (#crc-placement-options) |
|  |  |  |  | **Would be a 2 if:** CRC computation on MemorySegment is significantly slower than on byte[] |
| Operational | 2 | 5 | 10 | Mixed compressed/uncompressed via flags byte; independent record replay; works with remote WAL (#edge-cases-and-gotchas) |
|  |  |  |  | **Would be a 2 if:** remote backends reject the slightly larger per-record files |
| Fit | 3 | 5 | 15 | Both WAL impls supported; aligns with project MemorySegment-first direction; reuses CompressionCodec identity (codecId, thread-safety) |
|  |  |  |  | **Would be a 2 if:** a custom codec cannot implement MemorySegment compress (e.g., requires native library with byte[]) |
| **Total** | | | **65** | |

**Hard disqualifiers:** None.

**Key strengths:**
- Fully consistent with project MemorySegment-first direction
- Eliminates byte[] from the entire compression hot path (WAL + SSTable)
- Per-record format is simple, crash-safe, and remote-WAL compatible

**Key weaknesses:**
- Requires migrating all existing CompressionCodec callers (3 production sites)
- `java.util.zip.Deflater`/`Inflater` use byte[] internally — the MemorySegment
  API adds a copy inside the codec impl (hidden from callers but still present)

---

## Candidate: Per-Record + Dual API Bridge

**KB source:** [`.kb/algorithms/compression/wal-compression-patterns.md`](../../.kb/algorithms/compression/wal-compression-patterns.md)
**Relevant sections read:** `#approach-1-per-record-compression`, `#memorysegment-integration` (Option C)
**Design:** Add `compress(MemorySegment src, MemorySegment dst)` and matching
decompress as default methods on `CompressionCodec`. The defaults bridge to
byte[] internally. WAL uses the new API; SSTable callers migrate later.

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 1 | 4 | 4 | Same per-record compression characteristics |
|  |  |  |  | **Would be a 2 if:** same small-record condition |
| Resources | 3 | 3 | 9 | Default bridge copies MemorySegment → byte[] → MemorySegment. Callers see MemorySegment API but internal copy remains until codecs override. |
| Complexity | 2 | 4 | 8 | WAL uses new API; existing SSTable callers unchanged. Smaller migration scope. |
| Accuracy | 3 | 5 | 15 | Same CRC and crash-safety properties as Candidate A |
|  |  |  |  | **Would be a 2 if:** same condition |
| Operational | 2 | 5 | 10 | Same operational properties as Candidate A |
|  |  |  |  | **Would be a 2 if:** same condition |
| Fit | 3 | 4 | 12 | MemorySegment API available but byte[] bridge is a half-measure. Callers can migrate incrementally. |
|  |  |  |  | **Would be a 2 if:** dual API confuses codec implementors about which to override |
| **Total** | | | **58** | |

**Hard disqualifiers:** None.

**Key strengths:**
- Smaller blast radius — only WAL code uses new API initially
- Backward compatible — existing callers unaffected
- Incremental migration path

**Key weaknesses:**
- byte[] copy hidden in the default bridge — violates the spirit of MemorySegment-first
- Dual API surface increases maintenance burden and confusion
- Defers the SSTable migration to a future session

---

## Candidate: Streaming Compression (RocksDB-style)

**KB source:** [`.kb/algorithms/compression/wal-compression-patterns.md`](../../.kb/algorithms/compression/wal-compression-patterns.md)
**Relevant sections read:** `#approach-2-streaming-compression`
**Design:** Maintain a ZSTD streaming context across records within a WAL segment.
Compress at logical record level with cross-record dictionary matching.

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 1 | 5 | 5 | 2-3x compression ratio (vs 1.5-2x per-record) (#approach-2-streaming) |
|  |  |  |  | **Would be a 2 if:** records have no repetitive keys |
| Resources | 3 | 2 | 6 | Streaming context ~128KB per segment. ZSTD streaming API is C-native — no pure-Java impl. Would require JNI or abandon the no-external-deps constraint. |
| Complexity | 2 | 1 | 2 | Streaming state management, error propagation, segment-start recovery dependency. Significantly more complex than per-record. |
| Accuracy | 3 | 3 | 9 | CRC still possible but recovery must replay from segment start. Partial segment corruption invalidates all subsequent records in that segment. |
| Operational | 2 | 2 | 4 | Cannot randomly access records. Recovery replays from segment start. Incompatible with remote WAL (one-file-per-record). |
| Fit | 3 | 1 | 3 | Incompatible with RemoteWriteAheadLog. Requires ZSTD (JNI dependency or hand-rolled ~5000 lines). Violates no-external-deps constraint. |
| **Total** | | | **29** | |

**Hard disqualifiers:**
- **Incompatible with RemoteWriteAheadLog** — streaming needs a continuous
  context, one-file-per-record breaks the context chain
- **Requires external dependency (ZSTD JNI) or infeasible hand-roll** —
  violates the no-external-deps constraint

**Key strengths:**
- Best compression ratio (2-3x)

**Key weaknesses:**
- Two hard disqualifiers make this untenable for jlsm

---

## Comparison Matrix

| Candidate | KB Source | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| Per-Record + MemorySegment-Native | wal-compression-patterns.md | 4 | 5 | 3 | 5 | 5 | 5 | **65** |
| Per-Record + Dual API Bridge | wal-compression-patterns.md | 4 | 3 | 4 | 5 | 5 | 4 | **58** |
| Streaming (RocksDB-style) | wal-compression-patterns.md | 5 | 2 | 1 | 3 | 2 | 1 | **29** |

## Preliminary Recommendation

**Per-Record + MemorySegment-Native API** wins on weighted total (65 vs 58).
The MemorySegment-native approach fully satisfies the project's MemorySegment-first
mandate, eliminates byte[] from the compression hot path for all callers (WAL and
SSTable), and the migration scope is bounded (3 production call sites, 2 codec
implementations). The per-record format is crash-safe, self-describing, and works
with both local and remote WAL implementations.

## Risks and Open Questions

- **Deflater/Inflater internal byte[] copies:** java.util.zip uses byte[]
  internally. The MemorySegment API hides this from callers but doesn't
  eliminate the copy inside the codec. A future pure-Java LZ4 codec could
  operate directly on MemorySegment with zero copies.
- **Minimum record size threshold:** needs benchmarking to determine optimal
  cutoff (64 bytes suggested, may need tuning per workload)
