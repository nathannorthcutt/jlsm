---
problem: "compression-codec-api-design"
date: "2026-03-17"
version: 1
status: "confirmed"
supersedes: null
---

# ADR — Compression Codec API Design

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Block Compression Algorithms | Informed codec interface design and byte[] parameter choice | [`.kb/algorithms/compression/block-compression-algorithms.md`](../../.kb/algorithms/compression/block-compression-algorithms.md) |

---

## Problem
What should the `CompressionCodec` interface look like, how does the reader resolve codec IDs
from the compression map back to implementations, and how does the tree builder expose
compression configuration?

## Constraints That Drove This Decision
- **Extensibility**: consumers may plug in JNI-backed codecs (ZSTD, hardware-accelerated) —
  the interface must not be sealed to library-provided implementations
- **Reader codec resolution**: codec availability must be explicit per reader instance,
  no global mutable registry
- **Builder integration**: must fit the existing `@FunctionalInterface` factory pattern
  without breaking `SSTableWriterFactory` or `SSTableReaderFactory`

## Decision
**Chosen approach: Open interface + explicit codec list**

`CompressionCodec` is an open (non-sealed) interface with three core methods. The library
ships two implementations accessible via static factory methods. Writers take a single codec;
readers take a varargs codec list to build an internal ID→codec map. The tree builder captures
the codec in factory lambdas. No global registry, no mutable static state.

### Interface

```java
public interface CompressionCodec {
    byte codecId();
    byte[] compress(byte[] input, int offset, int length);
    byte[] decompress(byte[] input, int offset, int length, int uncompressedLength);

    static CompressionCodec none() { return NoneCodec.INSTANCE; }
    static CompressionCodec deflate() { return new DeflateCodec(Deflater.DEFAULT_COMPRESSION); }
    static CompressionCodec deflate(int level) { return new DeflateCodec(level); }
}
```

### Codec IDs (extensible)

| ID | Codec | Notes |
|----|-------|-------|
| `0x00` | NONE | Uncompressed — passthrough |
| `0x01` | LZ4 | Reserved for future pure-Java LZ4 |
| `0x02` | DEFLATE | `java.util.zip.Deflater`/`Inflater` |
| `0x03–0x7F` | Reserved | Future library-provided codecs |
| `0x80–0xFF` | User-defined | Consumer-provided codec implementations |

### Writer integration

```java
// TrieSSTableWriter gains codec parameter:
public TrieSSTableWriter(long id, Level level, Path outputPath,
    BloomFilter.Factory bloomFactory, CompressionCodec codec)
```

### Reader integration

```java
// TrieSSTableReader gains codecs parameter:
public static TrieSSTableReader open(Path path,
    BloomFilter.Deserializer bloomDeserializer, BlockCache blockCache,
    CompressionCodec... codecs)

// Reader builds Map<Byte, CompressionCodec> at open time
// Throws IOException on unknown codec ID or duplicate codec IDs
```

### Tree builder integration

```java
// StandardLsmTree.Builder gains:
public Builder compression(CompressionCodec codec)

// Default: CompressionCodec.none() (backward compatible)
// The builder captures the codec into writer/reader factory lambdas
```

## Rationale

### Why Open interface + explicit codec list
- **Extensibility**: consumers implement `CompressionCodec` for JNI-backed ZSTD, LZ4, or
  hardware-accelerated codecs without modifying library source
- **Explicit availability**: reader's codec list makes it clear which codecs a reader can
  handle — no hidden global state, no runtime surprises
- **Builder fit**: factory lambdas capture the codec naturally; `@FunctionalInterface` contracts
  unchanged

### Why not Sealed interface (Candidate A)
- **Extensibility**: adding a new codec requires modifying the library's `permits` clause —
  contradicts the composable design philosophy

### Why not Enum + strategy (Candidate C)
- **Complexity**: two parallel concepts (`CompressionType` enum + `CompressionCodec` interface)
  with no benefit; the enum is also not extensible

## Implementation Guidance

Key parameters from KB `block-compression-algorithms.md#implementation-notes`:
- `byte[]` parameters: both `Deflater`/`Inflater` and pure-Java LZ4 operate on byte arrays
- `Deflater`/`Inflater` must be `end()`ed to free native memory — `DeflateCodec` must manage
  lifecycle internally (pool or per-call creation)
- Codec instances should be reusable across blocks (no per-block allocation)

Known edge cases from KB `block-compression-algorithms.md#edge-cases-and-gotchas`:
- Incompressible data: if `compress()` output ≥ input length, writer should store as NONE
  (the writer handles this, not the codec)
- Duplicate codec IDs in reader's codec list: throw `IllegalArgumentException` at open time

## What This Decision Does NOT Solve
- Thread safety of codec instances — left to each implementation
- `maxCompressedLength(int)` for output buffer pre-allocation — can be added later
- Codec negotiation between writer and reader — reader must be configured with all codecs
  it might encounter
- Codec-specific configuration beyond the constructor (e.g., dictionary support)

## Conditions for Revision
This ADR should be re-evaluated if:
- `byte[]` becomes a bottleneck and `MemorySegment`/`ByteBuffer` parameters are needed
- A codec registry pattern is needed for service-loader-style discovery
- The number of codec parameters grows beyond what static factory methods can handle

---
*Confirmed by: user deliberation | Date: 2026-03-17*
*Full scoring: [evaluation.md](evaluation.md)*
