# Spec Analysis — block-compression Cluster 2 (Reader)

**Scope:** `TrieSSTableReader` (final class), inner record `Footer`, inner classes
`DataRegionIterator`, `CompressedBlockIterator`, `IndexRangeIterator`.

**Shared dependencies (read-only):** `CompressionCodec`, `CompressionMap`, `SSTableFormat`,
`EntryCodec`, `KeyIndex`.

---

## TrieSSTableReader (top-level class)

### C2-F1: `readAndDecompressBlock` uses assert for codec lookup — silent in production

**Construct:** `TrieSSTableReader.readAndDecompressBlock`, line 350
**Also:** `readAndDecompressBlockNoCache`, line 381

The codec lookup `codecMap.get(mapEntry.codecId())` may return null if the compression
map contains a codec ID not present in the codec map. The null check is guarded only
by `assert`:

```java
CompressionCodec codec = codecMap.get(mapEntry.codecId());
assert codec != null : "codec not found for ID ...";
byte[] decompressed = codec.decompress(...);
```

With assertions disabled (production default), `codec` is null and the next line
throws `NullPointerException` with no diagnostic context. The `validateCodecMap()`
call at open time validates all block entries, so this should not happen for
well-formed files. However, if a file is corrupted after open (e.g., memory-mapped
data changed, or a reader is reused across file replacement), the NPE gives no clue
what codec ID was missing.

**Lens B level:** Level 1 (deferred validation — assert instead of runtime check)
**Project rule:** `coding-guidelines.md` — "assert statements ... do not replace runtime validation"
**Tag:** IMPL-RISK (project-rule)
**Suggested test:** Open a v2 SSTable normally, then use reflection or a custom
`CompressionMap` with an extra block entry referencing an unknown codec ID (bypassing
`validateCodecMap`). Call `get()` or `scan()` and verify an `IOException` (not NPE)
is thrown.

---

### C2-F2: `long` to `int` truncation for data region size in eager open

**Construct:** `TrieSSTableReader.open` (v2 factory, line 194), `TrieSSTableReader.open` (v1 factory, line 112)

```java
int dataLen = (int) dataEnd;            // line 194 (v2), line 112 (v1)
byte[] data = readBytes(ch, 0L, dataLen);
```

`dataEnd` is a `long` (footer field offset). If the data region exceeds 2 GiB
(Integer.MAX_VALUE bytes), the cast silently truncates the upper 32 bits.
`dataLen` becomes a small or negative int. A negative value causes
`ByteBuffer.allocate(negative)` to throw `IllegalArgumentException` with no
descriptive message. A small positive value causes a short read that corrupts
all subsequent lookups silently.

The same pattern exists in `getAllDataV1()` (line 447): `(int) dataEnd`.

The `readDataAtV1` method (line 418) also casts: `int len = (int) Math.min(maxBytes, dataEnd - fileOffset)`.

**Lens B level:** Level 2 (semantically valid but extreme input — large file)
**Project rule:** `coding-guidelines.md` — "scrutinize every allocation", "fail with a clear IOException rather than producing a corrupt partial file"
**Tag:** IMPL-RISK (project-rule)
**Suggested test:** Construct a `Footer` with `idxOffset` or `mapOffset` >
Integer.MAX_VALUE. Verify the reader throws a clear `IOException` rather than
truncating or throwing IAE/NegativeArraySizeException.

---

### C2-F3: `buildCodecMap` silently accepts null elements in codecs varargs

**Construct:** `TrieSSTableReader.buildCodecMap`, lines 385-395

```java
for (CompressionCodec codec : codecs) {
    map.put(codec.codecId(), codec);
}
```

The caller validates `Objects.requireNonNull(codecs, ...)` for the array itself,
but individual elements are not validated. A call like
`open(path, deser, null, CompressionCodec.deflate(), null)` would NPE on
`codec.codecId()` inside the loop with no diagnostic message.

**Lens B level:** Level 2 (trust boundary — external caller provides array contents)
**Project rule:** `coding-guidelines.md` — "validate all inputs to public methods eagerly"
**Tag:** IMPL-RISK (project-rule)
**Suggested test:** Pass a codecs varargs array containing a null element to a v2
`open()` or `openLazy()`. Verify an `NullPointerException` or `IllegalArgumentException`
with a descriptive message is thrown, not a raw NPE from inside `buildCodecMap`.

---

### C2-F4: `buildCodecMap` allows codec ID collisions silently

**Construct:** `TrieSSTableReader.buildCodecMap`, lines 385-395

If the caller passes two different `CompressionCodec` instances with the same
`codecId()`, the second silently overwrites the first in the `HashMap`. This could
produce subtle decompression failures if one codec is a custom implementation with
the same ID but different behaviour.

The NoneCodec auto-inclusion (line 389-390) can also be silently overwritten if
the caller passes a codec with `codecId() == 0x00` that is not actually NoneCodec.

**Lens B level:** Level 2 (semantically wrong but structurally valid input)
**Suggested test:** Pass two different codecs with the same `codecId()` byte. Verify
the reader either throws or uses consistent/documented precedence.

---

### C2-F5: `close()` is not thread-safe — double-close race on `lazyChannel`

**Construct:** `TrieSSTableReader.close()`, lines 313-320

```java
if (closed) return;
closed = true;
if (lazyChannel != null) {
    lazyChannel.close();
}
```

`closed` is `volatile`, but the check-then-set on lines 314-315 is not atomic.
Two threads calling `close()` concurrently can both see `closed == false`,
both set it to `true`, and both call `lazyChannel.close()`. The second
`lazyChannel.close()` call throws `ClosedChannelException`.

While `SeekableByteChannel.close()` is documented as idempotent for
`FileChannel`, the `SeekableByteChannel` interface contract does not guarantee
idempotent close for all implementations (relevant for remote backends per
`io-internals.md`).

**Lens B level:** Level 1 (non-atomic mutation of shared state)
**Project rule:** `io-internals.md` — remote backend compatibility
**Tag:** IMPL-RISK
**Suggested test:** Call `close()` concurrently from multiple threads on a lazy
reader backed by a channel wrapper that throws on double-close. Verify no exception
propagates.

---

### C2-F6: v2 `readFooter` does not validate footer field consistency

**Construct:** `TrieSSTableReader.readFooter`, lines 475-510

The v2 footer fields are read without cross-validation:
- `mapOffset` could be negative or larger than `fileSize`
- `mapLength` could be negative (cast to int in caller, line 180/233)
- `idxOffset` could overlap `mapOffset + mapLength`
- `entryCount` could be negative
- No check that `mapOffset + mapLength <= idxOffset <= fltOffset <= fileSize - FOOTER_SIZE_V2`

A malformed or corrupt footer with, say, `mapLength = -1` would produce
`readBytes(ch, mapOffset, -1)` which allocates `ByteBuffer.allocate(-1)` and
throws `IllegalArgumentException` with no SSTable context.

The same issue exists for v1 in `readFooterV1` (line 457-472), but v2 adds
more fields and more ways to be inconsistent.

**Lens B level:** Level 2 (corrupt file input — trust boundary at file I/O)
**Project rule:** `coding-guidelines.md` — "validate all inputs eagerly", "fail with informative IOException"
**Brief spec:** "Corrupted compressed block -> IOException with descriptive message"
**Tag:** IMPL-RISK (project-rule)
**Suggested test:** Craft a binary file with valid v2 magic but negative or
overlapping footer offsets. Verify `open()` throws `IOException` with a descriptive
message rather than `IllegalArgumentException` or `NegativeArraySizeException`.

---

### C2-F7: Eager `open()` on v1 path reads `(int) footer.idxOffset` bytes — no file-size validation

**Construct:** `TrieSSTableReader.open` (v1, line 112), `TrieSSTableReader.open` (v2, line 194)

The v1 eager path reads `(int) footer.idxOffset` bytes from offset 0. If
`footer.idxOffset` is crafted to be larger than actual file size (but less than
Integer.MAX_VALUE), `readBytes` will read past EOF and throw a generic
"unexpected EOF" IOException. While this is caught, there is no pre-check that
`idxOffset <= fileSize`. More critically, if `idxOffset` is exactly file size
(the entire file), the reader allocates a byte array of that size and reads
the entire file including footer/index/bloom — the data array then contains
garbage metadata bytes that will be treated as entry data, causing silent
corruption in point lookups rather than a clear error.

**Lens B level:** Level 2 (crafted input — footer offset equals file size)
**Tag:** IMPL-RISK
**Suggested test:** Create a minimal valid SSTable, then modify the footer's
`idxOffset` to equal `fileSize`. Open eagerly and verify an IOException rather
than silent corruption.

---

## Footer (inner record, lines 452-454)

### C2-F8: `Footer` record has no compact constructor validation

**Construct:** `Footer` record, line 452-454

```java
private record Footer(int version, long mapOffset, long mapLength, long idxOffset,
        long idxLength, long fltOffset, long fltLength, long entryCount) {
}
```

The record accepts any values without validation. Negative offsets, negative
lengths, version values other than 1 or 2, and negative entryCount are all
silently accepted. This is the same pattern as the RESOLVED finding
`COMPRESSION-MAP-ENTRY-NO-VALIDATION` from Cluster 1 (TENDENCY:
`RECORD-MISSING-VALIDATION`).

Since `Footer` is private and only constructed from `readFooterV1()` and
`readFooter()`, the risk is that corrupt file data flows through without
early detection.

**Lens B level:** Level 4 (construction-time invariants missing)
**TENDENCY match:** `RECORD-MISSING-VALIDATION`
**Project rule:** `coding-guidelines.md` — "use assert statements throughout all code"
**Tag:** IMPL-RISK (project-rule, tendency)
**Suggested test:** This is defense-in-depth. Verify that passing a corrupt footer
byte array (e.g., with negative idxOffset) to `readFooter()` produces a clear
IOException before constructing the Footer record.

---

## DataRegionIterator (inner class, lines 622-676)

### C2-F9: `DataRegionIterator` does not validate block entry count

**Construct:** `DataRegionIterator.advance`, lines 646-653

```java
int count = readInt(data, offset);
offset += 4;
blockEntries = new ArrayList<>(count);
for (int i = 0; i < count; i++) {
    Entry e = EntryCodec.decode(data, offset);
    ...
}
```

The `count` value is read directly from the data array with no validation:
- Negative `count`: `new ArrayList<>(negative)` throws `IllegalArgumentException`
  (no SSTable context). The loop `i < count` wouldn't execute anyway, but the
  `ArrayList` constructor fails first.
- Very large `count`: `new ArrayList<>(count)` attempts to allocate a huge
  backing array, potentially causing `OutOfMemoryError`.
- `count` exceeding remaining data: `EntryCodec.decode` will eventually read
  past the array bounds, throwing `ArrayIndexOutOfBoundsException` (not
  IOException).

Since this iterator operates on already-loaded data (not raw file I/O), the
data could be corrupted in-memory or simply a v1 file with a corrupt data block.

**Lens B level:** Level 2 (trust boundary — data read from disk, not validated)
**Project rule:** `coding-guidelines.md` — "fail with informative IOException", "bounded iteration"
**Brief spec:** "Corrupted compressed block -> IOException with descriptive message"
**Tag:** IMPL-RISK (project-rule)
**Suggested test:** Create a byte array with a block count header of
Integer.MAX_VALUE or -1, wrap in `DataRegionIterator`, and verify it throws
a descriptive exception rather than OOME or AIOOBE.

---

### C2-F10: `DataRegionIterator.dataEnd` is `long` but `offset` is `int` — comparison mismatch

**Construct:** `DataRegionIterator`, lines 624-643

```java
private final long dataEnd;
private int offset = 0;
...
if (offset >= dataEnd) return;
```

`offset` is an `int` and `dataEnd` is a `long`. For v1 files under 2 GiB this
is fine since `data.length` is int-bounded. But the widening conversion of
`offset` (signed int) to long for the comparison means a negative `offset`
(if corruption causes `offset` to wrap past Integer.MAX_VALUE via accumulated
`EntryCodec.encodedSize` values) would be sign-extended to a large negative
long, which is always `< dataEnd`, causing the loop to continue reading from
invalid array positions until AIOOBE.

This is a latent issue — it requires either corrupt block data or extremely
large entries to trigger. But the types are mismatched, which is a code smell
that could mask corruption.

**Lens B level:** Level 1 (silent truncation — type mismatch between offset and limit)
**Tag:** IMPL-RISK
**Suggested test:** Construct data where `EntryCodec.encodedSize` sum exceeds
what `offset` can represent cleanly, or where corrupt entry sizes cause offset
to go negative. Verify the iterator terminates rather than looping.

---

## CompressedBlockIterator (inner class, lines 686-751)

### C2-F11: `CompressedBlockIterator.advance()` throws `IllegalStateException` from `hasNext()`

**Construct:** `CompressedBlockIterator.advance`, lines 700-704

```java
private void advance() {
    next = null;
    if (closed) {
        throw new IllegalStateException("reader is closed");
    }
    ...
}
```

The `advance()` method is called from the constructor (line 697) and from
`next()` (line 743). It is also transitively called from `hasNext()` via the
constructor call chain — no, actually `hasNext()` just returns `next != null`
(line 733). However, the `next()` method calls `advance()` which checks
`closed`. So calling `next()` on a closed reader throws ISE. But `hasNext()`
does NOT check closed — it returns the stale `next` value from before close.

Sequence: `scan()` returns iterator -> `hasNext()` returns true -> `close()`
-> `next()` throws ISE. The caller expected to iterate but gets an exception
mid-iteration. This violates the principle of least surprise — either both
`hasNext()` and `next()` should check closed, or neither should (relying on
the reader's own state).

More critically, `advance()` throws ISE (unchecked) rather than wrapping in
`UncheckedIOException`, which is inconsistent with the `IOException` wrapping
on line 727.

**Lens B level:** Level 1 (resource lifecycle — inconsistent closed-state handling)
**Tag:** IMPL-RISK
**Suggested test:** Get a `CompressedBlockIterator` via `scan()`, call
`hasNext()` (true), then `close()` the reader, then call `next()`. Verify the
exception type and message are consistent. Also verify `hasNext()` behaviour
after close.

---

### C2-F12: `CompressedBlockIterator` does not validate decompressed block entry count

**Construct:** `CompressedBlockIterator.advance`, lines 717-724

```java
int count = readBlockInt(decompressed, 0);
blockEntries = new ArrayList<>(count);
int off = 4;
for (int i = 0; i < count; i++) {
    Entry e = EntryCodec.decode(decompressed, off);
    blockEntries.add(e);
    off += EntryCodec.encodedSize(e);
}
```

Same pattern as C2-F9 in `DataRegionIterator`: the block entry count is
trusted without bounds validation. A corrupt compressed block that decompresses
to valid-length data but with a garbage count header could cause OOME (huge
ArrayList) or AIOOBE (count exceeds actual entries). The `UncheckedIOException`
wrapping on line 727 only catches `IOException`, not `RuntimeException`.

**Lens B level:** Level 2 (trust boundary — decompressed data from disk)
**Project rule:** `coding-guidelines.md` — "bounded iteration"
**Tag:** IMPL-RISK (project-rule)
**Suggested test:** Create a v2 SSTable where one block's compressed payload
decompresses to data with a corrupted entry count (e.g., Integer.MAX_VALUE in
the first 4 bytes). Call `scan()` and iterate. Verify the exception is
meaningful (not OOME or raw AIOOBE).

---

### C2-F13: `CompressedBlockIterator` holds reference to `blockEntries` list across blocks

**Construct:** `CompressedBlockIterator`, lines 688-689, 718

```java
private List<Entry> blockEntries;
...
blockEntries = new ArrayList<>(count);
```

When advancing to the next block, the old `blockEntries` list is replaced by a
new one. However, any `Entry` objects from the previous list that a caller still
references hold `MemorySegment` instances backed by `byte[]` arrays (from
`EntryCodec.decode`). This is correct (no shared mutable state). But the
`blockEntries` field itself retains a reference to the current block's list,
preventing GC of the *current* block's entries until the next block is loaded.

This is minor — the memory held is one block's worth of entries. But it means
the "O(single block uncompressed size)" claim in the Javadoc (line 681) is
technically O(2 blocks) during the transition: the decompressed byte array from
`readAndDecompressBlockNoCache` plus the parsed entries from the previous block
(still in `blockEntries`).

**Lens B level:** Level 3 (output — memory retention beyond documented scope)
**Tag:** IMPL-RISK (minor)
**Suggested test:** Not directly testable as a correctness issue. Could verify
via weak references that previous block entries become GC-eligible after
advancing, but this is low priority.

---

## IndexRangeIterator (inner class, lines 757-816)

### C2-F14: `IndexRangeIterator.advance()` throws ISE/UncheckedIOException inconsistently

**Construct:** `IndexRangeIterator.advance`, lines 770-801

Same pattern as C2-F11: `advance()` checks `closed` (line 772-774) and throws
`IllegalStateException`, but `hasNext()` (line 804) does not check closed and
returns the stale `next` value. The `IOException` from `readAndDecompressBlockNoCache`
or `readDataAtV1` is wrapped in `UncheckedIOException` (line 799), but the
closed check throws raw `IllegalStateException`.

**Lens B level:** Level 1 (resource lifecycle — same pattern as C2-F11)
**Tag:** IMPL-RISK
**Suggested test:** Same approach as C2-F11 but using `scan(from, to)` to
obtain an `IndexRangeIterator`.

---

### C2-F15: `IndexRangeIterator` block cache uses identity comparison on `int` blockIndex

**Construct:** `IndexRangeIterator`, lines 762-763, 785-790

```java
private int cachedBlockIndex = -1;
private byte[] cachedBlock = null;
...
if (blockIndex == cachedBlockIndex) {
    block = cachedBlock;
} else {
    block = readAndDecompressBlockNoCache(blockIndex);
    cachedBlockIndex = blockIndex;
    cachedBlock = block;
}
```

The cache assumes sequential access (consecutive entries in the same block).
When the key index range spans multiple blocks non-sequentially (possible if
the range is large and blocks are interleaved), every entry misses the cache
and re-decompresses the block. This is a performance issue, not a correctness
bug. However, the v2 key index packs `(blockIndex, intraBlockOffset)`, and
entries are sorted by key — which means block indices should be monotonically
non-decreasing in a range scan. So the cache should work correctly in practice.

The real concern is: if the same `blockIndex` appears, is skipped (e.g., a
block boundary edge case), and then appears again, the cache would miss and
re-decompress. This is performance-only.

**Lens B level:** Level 1 (construct — correct but suboptimal for non-monotonic access)
**Tag:** PERF (informational, not a bug)
**Suggested test:** Write a v2 SSTable with entries distributed across many
blocks. Do a range scan that spans 3+ blocks. Verify the same block is not
decompressed more than once (instrument via a counting codec wrapper).

---

### C2-F16: `IndexRangeIterator` advances only one entry per `advance()` call — no batch

**Construct:** `IndexRangeIterator.advance`, lines 770-801

Unlike `CompressedBlockIterator` which decompresses a full block and iterates
entries within it, `IndexRangeIterator` calls `readAndDecompressBlockNoCache`
per entry (with a 1-entry block cache). When a range scan covers many entries
in the same block, it still decompresses the block once (due to cache) but
decodes from the decompressed block at a specific `intraBlockOffset` each
time. It does NOT parse all entries in the block sequentially.

This means `EntryCodec.decode(block, intraBlockOffset)` is called once per
entry, which is correct. But if the key index only stores the first entry
per block (sparse index), a range scan would miss intermediate entries. This
depends on whether the key index is sparse or dense. Reading
`readKeyIndexV2` (line 534-557), it reads `numKeys` entries — which is
every key, not just first-per-block. So this is dense and correct.

**Lens B level:** N/A (investigated, not a bug)

---

### C2-F17: `IndexRangeIterator.cachedBlock` retains decompressed block after iteration completes

**Construct:** `IndexRangeIterator`, lines 763, 790

After iteration is complete (`indexIter.hasNext()` returns false), the
`cachedBlock` field still holds a reference to the last decompressed block's
byte array. This prevents GC of potentially 4 KiB+ of data until the iterator
itself is GC'd. Unlike `CompressedBlockIterator` which replaces `blockEntries`
each block, `IndexRangeIterator` never nulls out `cachedBlock`.

For long-lived iterator references (e.g., stored in a field by a caller), this
is a minor memory retention issue.

**Lens B level:** Level 3 (output — mutable internal state retained beyond usefulness)
**Tag:** IMPL-RISK (minor)
**Suggested test:** Verify via weak reference that the decompressed block
byte array becomes GC-eligible after the iterator is exhausted. Low priority.

---

## Cross-cutting findings

### C2-F18: `readBytes` on lazy channel is not synchronized — concurrent reads corrupt position

**Construct:** `TrieSSTableReader.readBytes`, lines 590-602
**Used by:** `readAndDecompressBlock` (line 346), `readAndDecompressBlockNoCache` (line 376),
`readDataAtV1` (line 434), `getAllDataV1` (line 447)

```java
ch.position(offset);
while (buf.hasRemaining()) {
    int read = ch.read(buf);
    ...
}
```

`SeekableByteChannel.position(long)` followed by `ch.read(buf)` is a
position-then-read sequence that is not atomic. If two threads call `get()`
concurrently on a lazy reader, they share the same `lazyChannel`. Thread A
calls `position(100)`, then thread B calls `position(200)`, then thread A
calls `read()` — reading from offset 200 instead of 100. This produces
silently corrupt data.

This is a real concurrency bug for lazy readers used from multiple threads.
Eager readers are not affected (they use `eagerData` byte array).

**Lens B level:** Level 1 (non-atomic mutation — position + read on shared channel)
**Project rule:** `coding-guidelines.md` — "bounded iteration and timeouts", plus
general thread safety expectations for a library
**Tag:** IMPL-RISK (concurrency bug)
**Suggested test:** Open a lazy v2 reader. From N threads, concurrently call
`get()` for keys in different blocks. Verify all returned values are correct.
A race condition test — run many iterations to increase the probability of
interleaving.

---

## Summary

| ID | Construct | Severity | Lens Level | Tag |
|----|-----------|----------|------------|-----|
| C2-F1 | readAndDecompressBlock (assert-only codec null check) | Medium | L1 | IMPL-RISK (project-rule) |
| C2-F2 | open() long-to-int truncation for data region size | High | L2 | IMPL-RISK (project-rule) |
| C2-F3 | buildCodecMap null elements in varargs | Medium | L2 | IMPL-RISK (project-rule) |
| C2-F4 | buildCodecMap silent codec ID collision | Low | L2 | IMPL-RISK |
| C2-F5 | close() not thread-safe for lazy readers | Medium | L1 | IMPL-RISK |
| C2-F6 | readFooter no field consistency validation | High | L2 | IMPL-RISK (project-rule) |
| C2-F7 | Eager open reads past data region with crafted idxOffset | Medium | L2 | IMPL-RISK |
| C2-F8 | Footer record no compact constructor validation | Medium | L4 | IMPL-RISK (tendency) |
| C2-F9 | DataRegionIterator trusts block entry count | Medium | L2 | IMPL-RISK (project-rule) |
| C2-F10 | DataRegionIterator int/long offset mismatch | Low | L1 | IMPL-RISK |
| C2-F11 | CompressedBlockIterator closed-state inconsistency | Medium | L1 | IMPL-RISK |
| C2-F12 | CompressedBlockIterator trusts decompressed block entry count | Medium | L2 | IMPL-RISK (project-rule) |
| C2-F13 | CompressedBlockIterator O(2 blocks) memory during transition | Low | L3 | IMPL-RISK (minor) |
| C2-F14 | IndexRangeIterator same closed-state inconsistency as C2-F11 | Medium | L1 | IMPL-RISK |
| C2-F15 | IndexRangeIterator block cache non-monotonic miss | Info | L1 | PERF |
| C2-F17 | IndexRangeIterator retains cachedBlock after exhaustion | Low | L3 | IMPL-RISK (minor) |
| C2-F18 | readBytes position-then-read not synchronized (lazy readers) | High | L1 | IMPL-RISK (concurrency) |

**Priority ordering (by risk):**
1. **C2-F18** — Concurrency: silent data corruption on lazy readers under concurrent access
2. **C2-F6** — Corrupt footer: negative offsets/lengths cascade to uninformative exceptions
3. **C2-F2** — Long-to-int truncation: >2 GiB data regions silently corrupt or crash
4. **C2-F1** — Assert-only codec null guard: NPE in production instead of IOException
5. **C2-F11/F14** — Iterator closed-state: ISE vs UncheckedIOException inconsistency
6. **C2-F9/F12** — Untrusted block entry count: OOME or AIOOBE on corrupt data
7. **C2-F3** — Null codec in varargs: uninformative NPE
8. **C2-F8** — Footer record validation (tendency pattern)
