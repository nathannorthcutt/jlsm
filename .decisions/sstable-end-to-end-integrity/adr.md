---
problem: "sstable-end-to-end-integrity"
date: "2026-04-14"
version: 2
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableWriter.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableReader.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/SSTableFormat.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/CompressionMap.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/DataBlock.java"
---

# ADR — SSTable End-to-End Integrity

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Corruption Detection and Repair | Per-layer checksum requirements, recovery strategies | [`.kb/systems/database-engines/corruption-detection-repair.md`](../../.kb/systems/database-engines/corruption-detection-repair.md) |

---

## Files Constrained by This Decision
- `TrieSSTableWriter.java` — VarInt block length prefix before each data block;
  fsync ordering (data → metadata → footer); per-section CRC32C computation
- `TrieSSTableReader.java` — per-section CRC32C verification at open time;
  sequential recovery scan when compression map is corrupt
- `SSTableFormat.java` — v5 footer layout with section checksums and block count
- `CompressionMap.java` — no structural change; CRC32C of serialized map bytes
  stored in footer
- `DataBlock.java` — VarInt length prefix written before block data

## Problem
Per-block CRC32C checksums (v3) cover data blocks but not metadata sections.
More critically, the data section has no inline structure — the compression map
is the only index into the data blocks. If the compression map is corrupt, all
data is unreachable even if the blocks are intact on disk.

## Constraints That Drove This Decision
- **Data recoverability**: Block boundaries must be discoverable without the
  compression map — the compression map is a single point of failure
- **Prevention over detection**: On local filesystems, fsync discipline prevents
  the partial-write corruption scenario entirely
- **Minimal overhead**: VarInt encoding costs 2 bytes per block in the common
  case (4 KiB blocks), 4 bytes worst case (32 MiB blocks)

## Decision
**Three-layer end-to-end integrity: fsync discipline + VarInt-prefixed
self-describing blocks + per-section CRC32C checksums.**

### Layer 1 — Prevention: fsync discipline

On local filesystems (where `SeekableByteChannel` is a `FileChannel`), enforce
strict write ordering:

```
1. Write all data blocks (VarInt-prefixed)
2. fsync — force data to disk
3. Write compression map + dictionary + index + bloom filter
4. fsync — force metadata to disk
5. Write footer (with section checksums + magic)
6. fsync — force footer to disk
```

The footer magic number is the commit marker. If it's present and valid, all
preceding sections were fsynced before the footer was written. If it's absent
or invalid, the file is incomplete — discard and recover from WAL.

For remote backends (S3/GCS), object writes are atomic — multipart uploads
commit or fail. fsync is not applicable; the CRC layer provides post-write
verification.

**Conditional dispatch**: use `if (channel instanceof FileChannel fc)` to apply
fsync only on local channels. Remote-compatible channels (plain
`SeekableByteChannel`) skip fsync — the remote provider handles durability.

### Layer 2 — Recovery: VarInt-prefixed self-describing blocks

Each data block is prefixed with its byte length encoded as a VarInt (LEB128,
unsigned):

```
[VarInt: blockLength][block data bytes...]
[VarInt: blockLength][block data bytes...]
...
```

| Block size | VarInt bytes |
|------------|-------------|
| < 128 B | 1 |
| 4 KiB (common local) | 2 |
| 64 KiB | 3 |
| 8–32 MiB (remote) | 4 |

**Recovery scan**: if the compression map is corrupt or missing, the reader can
scan the data section sequentially: read VarInt → read that many bytes → next
VarInt. This rebuilds block boundaries without the compression map. Combined
with per-block CRC32C (already in v3 compression map entries), each recovered
block can be verified independently.

**Normal read path**: the compression map remains the primary random-access
index. VarInt prefixes are skipped during normal reads (the compression map
entry's `blockOffset` points past the VarInt to the block data). The prefixes
are only used during recovery scans.

### Layer 3 — Detection: per-section CRC32C checksums

Add CRC32C checksums for every metadata section, stored in the footer:

**v5 footer layout** (104 bytes):
```
[long mapOffset     ]  offset 0
[long mapLength     ]  offset 8
[long dictOffset    ]  offset 16
[long dictLength    ]  offset 24
[long idxOffset     ]  offset 32
[long idxLength     ]  offset 40
[long fltOffset     ]  offset 48
[long fltLength     ]  offset 56
[long entryCount    ]  offset 64
[long blockSize     ]  offset 72
[int  blockCount    ]  offset 80  — total number of data blocks
[int  mapChecksum   ]  offset 84  — CRC32C of compression map bytes
[int  dictChecksum  ]  offset 88  — CRC32C of dictionary bytes (0 if none)
[int  idxChecksum   ]  offset 92  — CRC32C of key index bytes
[int  fltChecksum   ]  offset 96  — CRC32C of bloom filter bytes
[int  footerChecksum]  offset 100 — CRC32C of footer bytes [0..100)
[long magic         ]  offset 104 = MAGIC_V5
```

Footer size: 112 bytes (was 88 in v4).

**Verification order at SSTable open:**
1. Read 112 bytes from end of file; identify version from magic
2. Verify footerChecksum (CRC32C of the 100 bytes at offsets [0..100))
3. If footer is intact, verify each section checksum as sections are loaded
4. On any mismatch: throw `CorruptSectionException` identifying the section

**blockCount field**: enables validation that the compression map has the
expected number of entries, and serves as a termination condition for recovery
scans.

## Rationale

### Why three layers instead of detection-only
Detection identifies corruption but leaves the compression map as a single
point of failure — if it's corrupt, all data is unreachable. VarInt-prefixed
blocks make the data section self-describing, enabling recovery. fsync
discipline prevents the most common local corruption vector (partial writes)
entirely.

### Why VarInt instead of fixed-width length prefix
Common case (4 KiB blocks) costs 2 bytes instead of 4. Worst case (32 MiB)
is still only 4 bytes. VarInt encoding is trivial to implement and widely
understood (same encoding as Protocol Buffers).

### Why fsync ordering matters
Without explicit fsync between data and metadata, the OS could reorder writes.
A footer could reach disk before the data it describes. The ordered fsync
sequence ensures that if the footer exists, all preceding data is durable.

## Implementation Guidance

### VarInt encoding
```java
static void writeVarInt(SeekableByteChannel ch, int value) {
    // LEB128 unsigned: 7 bits per byte, MSB = continuation
    while (value > 0x7F) {
        writeByte(ch, (byte) ((value & 0x7F) | 0x80));
        value >>>= 7;
    }
    writeByte(ch, (byte) value);
}

static int readVarInt(SeekableByteChannel ch) {
    int result = 0, shift = 0;
    byte b;
    do {
        b = readByte(ch);
        result |= (b & 0x7F) << shift;
        shift += 7;
    } while ((b & 0x80) != 0);
    return result;
}
```

### Compression map offset adjustment
Compression map `blockOffset` entries must account for the VarInt prefix.
The offset should point to the start of the block data (after the VarInt),
not the start of the VarInt prefix. This preserves the existing random-access
read path — the VarInt is invisible to normal reads.

For recovery scans, the scan starts at offset 0 and reads VarInt + data
sequentially.

### Version compatibility
- v5 readers encountering v4 files: no VarInt prefixes, no section checksums.
  Skip verification, skip recovery scan capability.
- v4 readers encountering v5 files: fail at footer (unknown magic). This is
  the existing behavior for version mismatches.

## What This Decision Does NOT Solve
- Corruption repair from replicas — requires replication (see corruption-repair-recovery, deferred)
- Bloom filter parameter storage for rebuild — the reader can rebuild
  the bloom filter by scanning keys, but optimal parameters require knowing
  the original configuration

## Conditions for Revision
This ADR should be re-evaluated if:
- VarInt prefix overhead becomes measurable in benchmarks (unlikely at 2 bytes/block)
- A format-level change introduces a more natural place for block boundary markers
- Recovery scan performance is too slow for large SSTables (consider adding a
  recovery index as a separate metadata section)

---
*Confirmed by: user deliberation | Date: 2026-04-14*
*Full scoring: [evaluation.md](evaluation.md)*
