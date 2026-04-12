---
problem: "sstable-block-compression-format"
date: "2026-03-17"
version: 1
status: "confirmed"
supersedes: null
---

# ADR — SSTable Block Compression Format

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Block Compression Algorithms | Informed codec design and per-block overhead analysis | [`.kb/algorithms/compression/block-compression-algorithms.md`](../../.kb/algorithms/compression/block-compression-algorithms.md) |

---

## Problem
How should per-block compression metadata be encoded in the SSTable on-disk format to support
mixed compressed/uncompressed blocks, backward compatibility with v1 SSTables, and efficient
multi-block I/O on remote (S3/GCS) backends?

## Constraints That Drove This Decision
- **Remote-backend multi-block prefetch**: format must enable planned batch reads of multiple
  compressed blocks without sequential header parsing — this was the most differentiating constraint
- **Self-describing compression**: readers must determine codec + block sizes from file metadata
  alone, enabling mixed compressed/uncompressed interop
- **Performance over complexity**: codebase complexity is acceptable for better I/O patterns;
  diagnostic tooling can be built separately

## Decision
**Chosen approach: Compression Offset Map**

Add a new file section — the compression map — between the data blocks and the key index.
The map is a flat array of per-block metadata (file offset, compressed size, uncompressed size,
codec ID) loaded once at reader open time. This gives the reader a complete picture of every
block's physical layout, enabling planned multi-block I/O in a single read. Individual blocks
remain small enough to fit well into page caches for decompression, while the map enables large
sequential scans to pull many blocks from remote storage in one request.

### File format v2

```
[Data Block 0 payload ... N payload]   ← compressed or raw, no inline headers
[Compression Map                   ]   ← NEW: per-block metadata array
[Key Index                         ]
[Bloom Filter                      ]
[Footer — 64 bytes                 ]   ← expanded from 48 bytes
```

### Compression map format

```
Offset  Field          Size     Description
0       blockCount     4 bytes  Number of data blocks (big-endian int)
4       entries[0..N]  17 bytes each:
          blockOffset  8 bytes  Absolute file offset of this block's payload
          compressedSz 4 bytes  Byte length of compressed payload on disk
          uncompressSz 4 bytes  Byte length of decompressed block data
          codecId      1 byte   Compression codec (0x00=NONE, 0x01=LZ4, 0x02=DEFLATE)
```

Total map size: `4 + (blockCount × 17)` bytes.
For 1000 blocks: ~17 KiB — negligible, loaded eagerly at open time.

### Footer v2 (64 bytes)

```
Offset  Field          Size     Description
0       mapOffset      8 bytes  File offset of compression map section
8       mapLength      8 bytes  Byte length of compression map section
16      idxOffset      8 bytes  File offset of key index section
24      idxLength      8 bytes  Byte length of key index section
32      fltOffset      8 bytes  File offset of bloom filter section
40      fltLength      8 bytes  Byte length of bloom filter section
48      entryCount     8 bytes  Total entries in the SSTable
56      magic          8 bytes  0x4A4C534D53535402L (JLSMSST\x02)
```

### Key index entry v2

```
Offset  Field            Size     Description
0       keyLen           4 bytes  Key byte length
4       key              keyLen   Raw key bytes
4+kL    blockIndex       4 bytes  Index into compression map (0-based)
8+kL    intraBlockOffset 4 bytes  Byte offset of entry within decompressed block
```

### Backward compatibility

- v2 readers detect v1 files by magic byte (`\x01` vs `\x02`) and footer size (48 vs 64)
- v1 files: read with current logic (absolute entry offsets, no compression map)
- Uncompressed v2 files: all blocks have `codecId=0x00` and `compressedSz==uncompressSz`

## Rationale

### Why Compression Offset Map
- **Remote prefetch**: map loaded at open → reader knows all block positions → can issue a
  single large read for blocks N..M; optimal for S3/GCS where per-request latency is high
- **Page cache friendly**: blocks remain small (4–16 KiB) for individual decompression while
  the map enables large batch reads for sequential scans
- **Self-describing**: codec and sizes stored in the map; reader needs no external configuration

### Why not Per-block Inline Header (Candidate A)
- **Remote prefetch failure**: cannot plan multi-block reads without parsing headers sequentially;
  forces multiple I/O round-trips to discover block boundaries

### Why not Hybrid (Candidate C)
- **Redundancy**: stores compression metadata in two places (inline + index) with no practical
  benefit since the map is always loaded at open time; adds code to keep consistent

## Implementation Guidance
Key parameters from KB `block-compression-algorithms.md#key-parameters`:
- Block size: configurable (4 KiB default), larger blocks improve ratio but reduce random-access granularity
- Codec IDs: `0x00`=NONE, `0x01`=LZ4, `0x02`=DEFLATE (extensible)
- For NONE codec: `compressedSz == uncompressSz`, payload is raw block data

Known edge cases from KB `block-compression-algorithms.md#edge-cases-and-gotchas`:
- Incompressible data: if compressed output ≥ input, store as NONE (no wasted space)
- Last-literals rule (LZ4): last 5 bytes must be literals — format requirement
- `Deflater`/`Inflater` must be `end()`ed to free native memory

## What This Decision Does NOT Solve
- Per-block checksums (map structure can be extended with a checksum field later) — **Resolved:** see `per-block-checksums` (accepted 2026-04-10)
- Optimal block size selection for different backends (separate concern) — **Resolved:** see `backend-optimal-block-size` (accepted 2026-04-10)
- WAL compression (out of scope)
- Compaction-time re-compression with a different codec

## Conditions for Revision
This ADR should be re-evaluated if:
- Per-block checksums are needed (map entry grows from 17 to 21+ bytes)
- A streaming reader is needed that cannot load the compression map eagerly
- File sizes exceed what a single compression map can index efficiently (billions of blocks)

---
*Confirmed by: user deliberation | Date: 2026-03-17*
*Full scoring: [evaluation.md](evaluation.md)*
