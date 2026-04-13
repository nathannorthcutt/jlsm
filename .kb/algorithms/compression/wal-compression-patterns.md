---
title: "WAL Compression Patterns in LSM Implementations"
aliases: ["wal-compression", "write-ahead-log-compression"]
topic: "algorithms"
category: "compression"
tags: ["wal", "compression", "write-amplification", "durability", "record-format"]
related:
  - "algorithms/compression/zstd-dictionary-compression.md"
complexity:
  time_build: "O(1) per record (streaming) or O(n) per block"
  time_query: "O(1) per record decompression during replay"
  space: "overhead per record: 1-5 bytes metadata"
research_status: "active"
confidence: "high"
last_researched: "2026-04-12"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/wal/internal/WalRecord.java"
  - "modules/jlsm-core/src/main/java/jlsm/wal/local/LocalWriteAheadLog.java"
  - "modules/jlsm-core/src/main/java/jlsm/wal/remote/RemoteWriteAheadLog.java"
related:
  - "algorithms/compression/block-compression-algorithms.md"
  - "distributed-systems/data-partitioning/multi-writer-wal.md"
decision_refs:
  - "wal-compression"
  - "compression-codec-api-design"
  - "codec-thread-safety"
sources:
  - url: "https://github.com/facebook/rocksdb/wiki/WAL-Compression"
    title: "RocksDB WAL Compression Wiki"
    accessed: "2026-04-12"
    type: "docs"
  - url: "https://github.com/facebook/rocksdb/wiki/Write-Ahead-Log-File-Format"
    title: "RocksDB WAL File Format Specification"
    accessed: "2026-04-12"
    type: "docs"
  - url: "https://scaleflux.com/blog/reduce-write-amplification-write-ahead-logging/"
    title: "Reduce Write Amplification of WAL - ScaleFlux"
    accessed: "2026-04-12"
    type: "blog"
---

# WAL Compression Patterns in LSM Implementations

## summary

WAL compression reduces storage cost and write amplification by compressing
records before they reach disk. Production implementations use two main
approaches: **per-record compression** (compress each logical record
independently) and **streaming compression** (maintain a compression context
across records for better ratio). The choice depends on whether recovery must
support random-access to individual records or can replay sequentially. For
jlsm, the per-record approach is the better fit — it preserves the existing
CRC-per-record integrity model and works with both local and remote WAL
implementations.

## how-it-works

### approach-1-per-record-compression

Each WAL record is compressed independently. The record header carries a flag
indicating whether the payload is compressed and, if so, the codec ID and
uncompressed size.

```
┌─────────────────────────────────────────────────────┐
│ frame length     4 bytes int                        │
│ flags            1 byte  (bit 0: compressed)        │
│ [if compressed]  codec ID     1 byte                │
│ [if compressed]  uncompressed 4 bytes int            │
│ entry type       1 byte (0x01=PUT, 0x02=DEL)        │
│ sequence number  8 bytes long                       │
│ key length       4 bytes int                        │
│ key bytes        keyLength bytes                    │
│ [PUT] value len  4 bytes int                        │
│ [PUT] value bytes valueLength bytes                 │
│ CRC32 checksum   4 bytes int                        │
└─────────────────────────────────────────────────────┘
```

**CRC placement options:**
- **CRC over uncompressed data (preferred):** CRC is computed before compression
  and stored outside the compressed payload. On read, decompress then verify CRC.
  This catches both storage corruption AND decompression bugs.
- **CRC over compressed data:** Cheaper to verify (no decompress needed to check
  integrity) but doesn't catch codec bugs. Less useful for durability guarantees.
- **Both:** Maximum safety but doubles the per-record overhead.

**Advantages:** Simple recovery — each record is self-contained. Random-access
replay possible. Mixed compressed/uncompressed records coexist naturally.
Compatible with remote WAL (one-file-per-record pattern).

**Disadvantages:** Lower compression ratio — no cross-record context. Per-record
metadata overhead (5-6 bytes) is significant for small records.

### approach-2-streaming-compression

Used by RocksDB. A compression context (e.g., ZSTD streaming) is maintained
across logical records within a WAL segment. The compressor's internal
dictionary builds across records, allowing later records to reference match
phrases from earlier ones.

RocksDB's implementation:
- Compression happens at the logical record level, then the compressed output
  is fragmented into 32KB physical blocks
- Streaming context is flushed at logical record boundaries (so each logical
  record can be independently decompressed if the context is rebuilt from the
  start of the segment)
- Only ZSTD is supported (ZSTD's streaming API is well-suited to this)

**Advantages:** Better compression ratio (2-3x vs 1.5-2x for per-record).
Particularly effective for repetitive keys (common in LSM workloads).

**Disadvantages:** Recovery must replay from segment start to rebuild context.
Cannot randomly access individual records without replaying predecessors.
Incompatible with one-file-per-record remote WAL pattern. More complex
implementation — streaming state management, error propagation.

### approach-3-block-level-compression

Compress fixed-size blocks of WAL data (e.g., every 32KB). Similar to SSTable
block compression but applied to WAL segments.

**Advantages:** Good compression ratio. Aligns with existing block compression
infrastructure.

**Disadvantages:** Records spanning block boundaries complicate decompression.
Partial-block writes on crash leave incomplete compressed blocks. Poor fit for
WAL's append-only, crash-at-any-point requirements.

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| min_record_size | Skip compression below this threshold | 64-256 bytes | Avoids overhead for tiny records |
| codec_id | Per-record codec identifier | 1 byte (0x00=none) | Enables mixed codecs in same log |
| uncompressed_size | Pre-decompression buffer allocation | 4 bytes | Required for safe buffer alloc |
| flags byte | Compression indicator in record header | 1 byte, bit field | Self-describing format |

## algorithm-steps

### per-record-compression-write-path

1. **Encode** the WAL record payload (entry type through value bytes) into a
   MemorySegment buffer via the existing WalRecord.encode path
2. **Check size threshold:** if encoded payload < `minRecordSize`, skip
   compression — write uncompressed with flags=0x00
3. **Compress** the payload using the configured codec. If compressed size >=
   uncompressed size, skip compression (write uncompressed)
4. **Compute CRC32** over the *uncompressed* payload
5. **Write header:** frame length + flags (compressed=1) + codec ID +
   uncompressed size + compressed payload + CRC32
6. **Force to disk** (fsync / MappedByteBuffer.force)

### per-record-compression-read-path

1. **Read header:** frame length + flags byte
2. **If flags indicates compressed:** read codec ID + uncompressed size,
   allocate decompression buffer of uncompressed_size bytes, decompress
3. **If flags indicates uncompressed:** read payload directly
4. **Verify CRC32** over the (now uncompressed) payload
5. **Decode** entry from the uncompressed payload via WalRecord.decode

## implementation-notes

### data-structure-requirements

- Flags byte added to WalRecord header (1 byte overhead for all records)
- Codec ID + uncompressed size (5 bytes, only when compressed)
- Total overhead: 1 byte (uncompressed records) or 6 bytes (compressed records)

### memorysegment-integration

The compression step operates on MemorySegment data. Two approaches:

**Option A — MemorySegment-native codec API:**
Evolve CompressionCodec to accept MemorySegment input/output. Eliminates
byte[] copies. Requires API evolution across all codec implementations and
all callers (SSTable writer/reader). Larger scope but consistent with
project direction.

**Option B — Adapter with copy:**
Keep byte[]-based CompressionCodec. Copy MemorySegment → byte[] before
compression, byte[] → MemorySegment after. Simple but adds allocation and
copy overhead on every compressed write.

**Option C — Dual API with default bridge:**
Add `compress(MemorySegment, MemorySegment)` as a default method on
CompressionCodec that bridges to byte[] internally. Codecs can override for
zero-copy. Callers migrate incrementally. Combines backward compatibility
with forward progress.

### edge-cases-and-gotchas

- **Small records:** Compression metadata overhead (6 bytes) exceeds savings
  for records < ~64 bytes. The minimum-size threshold prevents negative
  compression ratios.
- **Incompressible data:** Always check `compressedSize >= uncompressedSize`
  and fall back to uncompressed. The flags byte makes this seamless.
- **Crash during compressed write:** Same guarantee as uncompressed — partial
  writes are detected by CRC mismatch during recovery. The CRC covers the
  uncompressed payload, so a truncated compressed record will decompress to
  fewer bytes than expected, failing the CRC check.
- **Remote WAL (one-file-per-record):** Per-record compression works directly.
  Each file is independently compressed. Streaming compression does NOT work
  (no cross-file context).

## complexity-analysis

### write-path-overhead

Per-record compression adds: one codec compress call + one CRC compute +
1-6 bytes metadata. For LZ4-class codecs at ~780 MB/s throughput, a 10KB
record compresses in ~13 microseconds. CRC32 on 10KB is ~2 microseconds.
Total overhead: ~15 microseconds per record — negligible vs fsync cost
(~100-1000 microseconds).

### recovery-overhead

Per-record: O(N) where N = records. Each record decompressed independently.
No sequential dependency. Recovery time increases proportionally to
decompression throughput (LZ4: ~5 GB/s, negligible vs I/O).

Streaming: O(N) but must replay from segment start. Cannot skip records.

### memory-footprint

Per-record: one compression buffer (reusable, pool-managed). No streaming
state.

Streaming: compression context (~128KB for ZSTD) held per active segment.

## tradeoffs

### strengths

- Per-record compression is simple, crash-safe, and works with both local
  and remote WAL implementations
- Self-describing format (flags byte + codec ID) enables mixed records
  with zero migration cost
- Compression is optional and configurable per-instance
- CRC over uncompressed payload catches both storage and codec bugs

### weaknesses

- Per-record compression achieves lower ratio than streaming (1.5-2x vs 2-3x)
- Small-record workloads see minimal benefit (metadata overhead)
- Adds ~15 microseconds per write for codec + CRC (negligible vs fsync)

### compared-to-alternatives

- **Streaming compression (RocksDB):** better ratio but incompatible with
  remote WAL and complicates recovery. See [block-compression-algorithms.md](block-compression-algorithms.md)
  for codec comparison.
- **Block-level compression:** better ratio than per-record, but partial-block
  crash recovery is complex and poorly suited to WAL's append-only model.
- **No compression:** simplest, but WAL can become disproportionately large
  relative to compressed SSTables, especially with large values.

## practical-usage

### when-to-use

- WAL storage cost is significant (large values, high write throughput)
- Network-replicated WAL (compression reduces I/O bandwidth)
- Remote/object storage WAL (per-file size matters for cost)

### when-not-to-use

- Tiny records (< 64 bytes) — overhead exceeds savings
- Ultra-low-latency requirements where even 15 microseconds matters
- When fsync is already the bottleneck (compression savings are invisible)

## reference-implementations

| Library | Language | Approach | Compression |
|---------|----------|----------|-------------|
| RocksDB | C++ | Streaming (ZSTD only) | 2-3x ratio |
| LevelDB | C++ | None (no WAL compression) | — |
| Cassandra | Java | Commit log compression (per-segment) | Configurable |
| ScyllaDB | C++ | Commit log compression (per-segment) | LZ4/ZSTD |

## code-skeleton

```java
// Per-record WAL compression integration point
public final class CompressedWalRecord {

    // Write path: encode → compress → write
    public static int encodeCompressed(Entry entry, MemorySegment dst,
            CompressionCodec codec, int minRecordSize) {
        // 1. Encode payload to temporary buffer
        int payloadSize = WalRecord.encodePayload(entry, tmpBuf);

        // 2. Skip compression for small records
        if (payloadSize < minRecordSize || codec == null) {
            return writeUncompressed(dst, tmpBuf, payloadSize);
        }

        // 3. Compress
        byte[] compressed = codec.compress(payloadBytes, 0, payloadSize);
        if (compressed.length >= payloadSize) {
            return writeUncompressed(dst, tmpBuf, payloadSize);
        }

        // 4. Write: frameLen + flags(0x01) + codecId + uncompressedSize
        //           + compressed payload + CRC32(uncompressed)
        return writeCompressed(dst, compressed, payloadSize, codec.codecId());
    }

    // Read path: read header → decompress if needed → verify CRC → decode
    public static Entry decodeCompressed(MemorySegment src, long offset,
            Map<Byte, CompressionCodec> codecs) {
        byte flags = src.get(BYTE_LAYOUT, offset + 4);
        if ((flags & 0x01) == 0) {
            return WalRecord.decode(src, offset); // uncompressed
        }
        byte codecId = src.get(BYTE_LAYOUT, offset + 5);
        int uncompressedSize = src.get(INT_BE, offset + 6);
        // decompress, verify CRC, decode
    }
}
```

## sources

1. [RocksDB WAL Compression](https://github.com/facebook/rocksdb/wiki/WAL-Compression)
   — streaming compression design, ZSTD-only, cross-record context matching
2. [RocksDB WAL File Format](https://github.com/facebook/rocksdb/wiki/Write-Ahead-Log-File-Format)
   — 32KB block structure, fragment types, CRC handling, physical/logical record mapping
3. [ScaleFlux: Reduce WAL Write Amplification](https://scaleflux.com/blog/reduce-write-amplification-write-ahead-logging/)
   — hardware-accelerated compression for WAL, write amplification analysis

---
*Researched: 2026-04-12 | Next review: 2026-10-12*
