---
problem: "sstable-block-compression-format"
evaluated: "2026-03-17"
candidates:
  - name: "Per-block inline header"
    label: "A"
  - name: "Compression offset map"
    label: "B"
  - name: "Hybrid (inline + block index)"
    label: "C"
constraint_weights:
  scale: 2
  resources: 1
  complexity: 1
  accuracy: 3
  operational: 3
  fit: 2
---

# Evaluation — sstable-block-compression-format

## References
- Constraints: [constraints.md](constraints.md)
- KB: [`.kb/algorithms/compression/block-compression-algorithms.md`](../../.kb/algorithms/compression/block-compression-algorithms.md)

## Constraint Summary
The format must enable efficient multi-block prefetch on remote backends (high-latency
I/O), self-describe compression per block for mixed interop, and maintain backward
compatibility with v1 SSTables. Performance > complexity.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | Block counts and file sizes are moderate, not extreme |
| Resources | 1 | All candidates are pure Java; no dependency differences |
| Complexity | 1 | User explicitly deprioritized complexity; performance matters more |
| Accuracy | 3 | Self-describing format and mixed interop are hard requirements |
| Operational | 3 | Remote-backend prefetch and read-path performance are top priorities |
| Fit | 2 | Key index format change is accepted; footer change is expected |

---

## Candidate A: Per-block inline header

**Format:**
- Each block: `[codec(1)][uncompressedSize(4)][compressedSize(4)][payload(compressedSize)]`
- Key index entry: `[keyLen(4)][key][blockFileOffset(8)][intraBlockOffset(4)]`
- Footer: 48 bytes, magic `JLSMSST\x02`
- Per-block overhead: 9 bytes (~0.2% on 4 KiB blocks)

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 2 | 4 | 8 | 9 bytes/block is negligible overhead at any block count |
| Resources | 1 | 5 | 5 | Pure Java, trivial to implement |
| Complexity | 1 | 5 | 5 | Simplest format; each block is self-contained |
| Accuracy | 3 | 5 | 15 | Self-describing: codec + sizes in every block header |
| Operational | 3 | 2 | 6 | **Weakness**: to prefetch blocks N..M, must read headers sequentially to discover block boundaries; can't plan a single large read without first scanning block sizes |
| Fit | 2 | 4 | 8 | Key index changes cleanly; footer stays 48 bytes |
| **Total** | | | **47** | |

**Hard disqualifiers:** None
**Key weakness:** Cannot plan multi-block prefetch without sequential header parsing — forces
multiple I/O round-trips on remote backends for sequential scans.

---

## Candidate B: Compression offset map

**Format:**
- Data region: compressed payloads only (no inline per-block headers)
- Compression map section: `[blockCount(4)][entries...]` where each entry is
  `[blockFileOffset(8)][compressedSize(4)][uncompressedSize(4)][codec(1)]` — 17 bytes/block
- Key index entry: `[keyLen(4)][key][blockIndex(4)][intraBlockOffset(4)]`
- Footer: 64 bytes (adds `mapOffset(8)` + `mapLength(8)`), magic `JLSMSST\x02`

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 2 | 5 | 10 | Offset map is O(blocks) — 17 bytes/block, loaded once at open |
| Resources | 1 | 5 | 5 | Pure Java; map is a simple flat array |
| Complexity | 1 | 3 | 3 | More complex: new section, footer growth, block-index indirection |
| Accuracy | 3 | 4 | 12 | Self-describing via map, but individual blocks are opaque without the map |
| Operational | 3 | 5 | 15 | **Strength**: reader loads map at open time → knows all block positions → can issue a single large read for blocks N..M; ideal for remote prefetch |
| Fit | 2 | 3 | 6 | Footer changes from 48→64 bytes; key index uses block indices instead of offsets |
| **Total** | | | **51** | |

**Hard disqualifiers:** None
**Key strength:** Compression map loaded at open time enables O(1) lookup of any block's
physical position, planned multi-block I/O, and efficient sequential scan prefetch.
**Key weakness:** Individual blocks cannot be decoded without the compression map — a block
in isolation is just opaque bytes.

---

## Candidate C: Hybrid (inline headers + block index)

**Format:**
- Each block: `[codec(1)][uncompressedSize(4)][compressedSize(4)][payload]` (like A)
- Block index section: `[blockCount(4)][entries...]` with `[blockFileOffset(8)]` per block (8 bytes/block)
- Key index entry: `[keyLen(4)][key][blockIndex(4)][intraBlockOffset(4)]`
- Footer: 64 bytes (adds `blockIdxOffset(8)` + `blockIdxLength(8)`), magic `JLSMSST\x02`

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 2 | 4 | 8 | 9 bytes inline + 8 bytes index = 17 bytes/block total (same as B but redundant) |
| Resources | 1 | 5 | 5 | Pure Java |
| Complexity | 1 | 2 | 2 | Most complex: two sources of truth for block metadata |
| Accuracy | 3 | 5 | 15 | Best of both: self-describing inline + random access via index |
| Operational | 3 | 5 | 15 | Same prefetch capability as B; inline headers also enable streaming without map |
| Fit | 2 | 3 | 6 | Same footer changes as B |
| **Total** | | | **51** | |

**Hard disqualifiers:** None
**Key strength:** Both self-describing blocks and prefetch-capable index.
**Key weakness:** Redundant metadata — compression info stored in two places. More code to
keep consistent. The streaming-without-map benefit is marginal since the map is always available.

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| A: Inline header | 8 | 5 | 5 | 15 | 6 | 8 | **47** |
| B: Offset map | 10 | 5 | 3 | 12 | 15 | 6 | **51** |
| C: Hybrid | 8 | 5 | 2 | 15 | 15 | 6 | **51** |

## Preliminary Recommendation
**Candidate B (Compression offset map)** — same weighted score as C but without the
redundancy and dual-source-of-truth complexity. The compression map provides all the
prefetch benefits, and the slight loss in per-block self-description is not a practical
concern since the map is always loaded at reader open time.

## Risks and Open Questions
- Risk: if SSTable files are truncated/corrupted, individual blocks cannot be recovered
  without the compression map (unlike A/C where inline headers enable partial recovery)
- Risk: footer size change (48→64) requires version detection logic in all readers
- Open: whether the compression map should also store a per-block checksum for integrity
