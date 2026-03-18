---
problem: "compression-codec-api-design"
evaluated: "2026-03-17"
candidates:
  - name: "Sealed interface + exhaustive switch"
    label: "A"
  - name: "Open interface + explicit codec list"
    label: "B"
  - name: "Enum-based type + strategy pattern"
    label: "C"
constraint_weights:
  scale: 1
  resources: 1
  complexity: 1
  accuracy: 3
  operational: 3
  fit: 3
---

# Evaluation — compression-codec-api-design

## References
- Constraints: [constraints.md](constraints.md)
- KB: [`.kb/algorithms/compression/block-compression-algorithms.md`](../../.kb/algorithms/compression/block-compression-algorithms.md)

## Constraint Summary
The codec API must be extensible (consumers may plug in JNI codecs), the reader must
resolve codec IDs explicitly without global state, and the design must integrate cleanly
with the existing builder/factory pattern. Performance on the read path matters most.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 1 | Small number of codecs; not a scaling concern |
| Resources | 1 | All candidates are pure Java; no dependency differences |
| Complexity | 1 | User deprioritized; performance matters more |
| Accuracy | 3 | Codec ID resolution correctness is critical — wrong codec = corrupt data |
| Operational | 3 | Decompression on hot read path; codec design affects allocation patterns |
| Fit | 3 | Must integrate with existing builder/factory/sealed patterns |

---

## Candidate A: Sealed interface + exhaustive switch

```java
public sealed interface CompressionCodec
    permits NoneCodec, DeflateCodec {
    byte codecId();
    byte[] compress(byte[] input, int off, int len);
    byte[] decompress(byte[] input, int off, int len, int uncompressedLen);

    static CompressionCodec none() { ... }
    static CompressionCodec deflate() { ... }
    static CompressionCodec forId(byte id) { // exhaustive switch }
}
```
- Reader: `CompressionCodec.forId(byte)` — exhaustive switch, compile-time safe
- Writer: takes `CompressionCodec` parameter
- Builder: `.compression(CompressionCodec)` on tree builder

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | Trivial for small codec count |
| Resources | 1 | 5 | 5 | Pure Java, no deps |
| Complexity | 1 | 5 | 5 | Simplest — one type, exhaustive switch |
| Accuracy | 3 | 5 | 15 | Compile-time exhaustiveness; unknown ID → exception |
| Operational | 3 | 4 | 12 | Minimal overhead; but sealed prevents reusable JNI codec instances |
| Fit | 3 | 3 | 9 | Matches project's sealed pattern (TypedLsmTree); but **blocks extensibility** |
| **Total** | | | **51** | |

**Hard disqualifiers:** Consumers cannot add their own codecs (e.g., JNI ZSTD). Adding a new
codec requires modifying the library source and the permits clause.

---

## Candidate B: Open interface + explicit codec list

```java
public interface CompressionCodec {
    byte codecId();
    byte[] compress(byte[] input, int off, int len);
    byte[] decompress(byte[] input, int off, int len, int uncompressedLen);

    static CompressionCodec none() { return NoneCodec.INSTANCE; }
    static CompressionCodec deflate() { return new DeflateCodec(6); }
    static CompressionCodec deflate(int level) { return new DeflateCodec(level); }
}
```
- Reader: takes `CompressionCodec... codecs` — builds ID→codec map at open time
- Writer: takes single `CompressionCodec` parameter
- Builder: `.compression(CompressionCodec)` for writer; reader factory captures same codec + NONE
- No global registry; codec availability is explicit per reader instance

**Integration with existing API:**
```java
// Tree builder adds:
Builder compression(CompressionCodec codec)

// Writer factory lambda captures codec:
(id, level, path) -> new TrieSSTableWriter(id, level, path, bloomFactory, codec)

// Reader factory lambda captures codec list:
(path) -> TrieSSTableReader.open(path, bloomDeserializer, blockCache, codec, CompressionCodec.none())

// TrieSSTableReader.open gains codecs parameter:
static TrieSSTableReader open(Path path, BloomFilter.Deserializer bloom,
    BlockCache cache, CompressionCodec... codecs)
```

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | Trivial for small codec count |
| Resources | 1 | 5 | 5 | Pure Java, no deps |
| Complexity | 1 | 4 | 4 | One more concept (codec list on reader) but straightforward |
| Accuracy | 3 | 5 | 15 | Unknown ID → IOException at read time; explicit codec availability |
| Operational | 3 | 5 | 15 | Open for JNI codecs; no per-block allocation; reusable instances |
| Fit | 3 | 5 | 15 | Works with @FunctionalInterface factories; builder captures codec |
| **Total** | | | **59** | |

**Hard disqualifiers:** None

**Key strength:** Fully extensible — consumers can implement `CompressionCodec` for ZSTD, LZ4
via JNI, or hardware-accelerated codecs. Reader gets explicit codec availability per instance
without global mutable state.

---

## Candidate C: Enum-based type + strategy pattern

```java
public enum CompressionType {
    NONE(0x00), DEFLATE(0x02);
    public byte codecId() { ... }
    public static CompressionType forId(byte id) { ... }
}

public interface CompressionCodec {
    CompressionType type();
    byte[] compress(byte[] input, int off, int len);
    byte[] decompress(byte[] input, int off, int len, int uncompressedLen);
}
```
- Two concepts: `CompressionType` (enum for ID registry) + `CompressionCodec` (behavior)
- Reader: resolves `CompressionType` from ID, then needs codec instance for that type
- Writer: takes `CompressionCodec` parameter

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | Trivial |
| Resources | 1 | 5 | 5 | Pure Java |
| Complexity | 1 | 2 | 2 | Two parallel concepts (type + codec) — more indirection |
| Accuracy | 3 | 4 | 12 | Enum gives exhaustive type coverage, but codec-to-type binding is loose |
| Operational | 3 | 4 | 12 | Open for extensions, but enum not extensible — new types need library change |
| Fit | 3 | 3 | 9 | Two concepts to learn; enum + interface split is unusual for this codebase |
| **Total** | | | **45** | |

**Hard disqualifiers:** `CompressionType` enum is not extensible — same limitation as sealed
for adding new codec types. Adds indirection without clear benefit.

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| A: Sealed | 5 | 5 | 5 | 15 | 12 | 9 | **51** |
| B: Open + explicit | 5 | 5 | 4 | 15 | 15 | 15 | **59** |
| C: Enum + strategy | 5 | 5 | 2 | 12 | 12 | 9 | **45** |

## Preliminary Recommendation
**Candidate B (Open interface + explicit codec list)** — highest score by a clear margin.
Open interface enables consumer-defined codecs, explicit codec list on reader avoids global
state, and the design fits cleanly into the existing factory/builder pattern.

## Risks and Open Questions
- Risk: consumers could create conflicting codec IDs (two codecs with same ID byte) — mitigated
  by reader throwing IOException on duplicate IDs in the codec list
- Risk: byte[] interface may not be optimal if future codecs want ByteBuffer — but all current
  and planned codecs (Deflater, pure-Java LZ4) use byte[]
- Open: should the interface include a `maxCompressedLength(int inputLength)` method for
  pre-allocating output buffers?
