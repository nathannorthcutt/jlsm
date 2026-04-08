# Spec Analysis — Cluster 1: Codecs + Writer

**Date:** 2026-03-26
**Scope:** CompressionCodec, DeflateCodec, NoneCodec, CompressionMap, SSTableFormat, TrieSSTableWriter
**Lenses:** A (contract gaps), B (implementation pitfalls, 4-level trace)

---

## CompressionCodec (interface)

### C1-F1: `compress` bounds check integer overflow on `offset + length`

- **Construct:** `DeflateCodec.compress()` line 59, `NoneCodec.compress()` line 43
- **Description:** The bounds check `offset > input.length - length` uses subtraction which
  underflows when `length > input.length`. For example, `input.length=0, offset=0, length=1`:
  `input.length - length` evaluates to `-1`, and `0 > -1` is `true`, so this case IS caught.
  However, `input.length=5, offset=0, length=Integer.MAX_VALUE`: `5 - Integer.MAX_VALUE` wraps
  to a large negative number, and `0 > (large negative)` is `false` — the check passes and
  the subsequent array operations will throw `ArrayIndexOutOfBoundsException` instead of the
  documented `IllegalArgumentException`. This same pattern exists in `decompress()` on both
  codecs.
- **Lens B level:** Level 2 (inputs) — semantically wrong but valid value bypasses trust boundary
- **Suggested test:** Call `compress(new byte[5], 0, Integer.MAX_VALUE)` and verify
  `IllegalArgumentException` is thrown, not `ArrayIndexOutOfBoundsException` or
  `NegativeArraySizeException`.

### C1-F2: `decompress` allows `uncompressedLength == 0` in DeflateCodec but spec says `> 0`

- **Construct:** `DeflateCodec.decompress()` line 91, work-plan line 73
- **Description:** The work-plan contract says `uncompressedLength` must be `> 0` for
  `decompress()`. The implementation validates `< 0` (line 91) but permits `0`. When
  `uncompressedLength == 0`, the while loop is skipped (0 < 0 is false), `totalRead == 0`
  equals `uncompressedLength == 0`, and an empty array is returned. This succeeds even if
  `input` contains valid compressed data that decompresses to non-zero bytes — the mismatch
  is silently ignored. `NoneCodec` has the same gap: `length == 0` and
  `uncompressedLength == 0` passes the `length != uncompressedLength` check.
- **Lens B level:** Level 2 (inputs) — zero slips through validation intended to reject it
- **Suggested test:** Compress a non-empty payload, then call `decompress(compressed, 0,
  compressed.length, 0)`. Verify it either throws or returns empty (document which the
  contract requires). Separately test `decompress(new byte[0], 0, 0, 0)`.

### C1-F3: DeflateCodec `compress` buffer growth can produce zero-progress infinite loop

- **Construct:** `DeflateCodec.compress()` lines 70-76
- **Description:** The compress loop checks `if (totalWritten == buf.length && !def.finished())`
  to decide whether to grow the buffer. If `deflate()` returns 0 without setting `finished()`
  (which can happen when the Deflater needs more output space but hasn't consumed all input),
  `totalWritten` remains less than `buf.length`, the growth condition is never met, and the
  loop spins indefinitely calling `deflate()` which keeps returning 0. This is a bounded
  iteration violation per `.claude/rules/coding-guidelines.md` ("Every iteration must
  terminate").
- **Lens B level:** Level 1 (construct) — incomplete loop termination logic
- **IMPL-RISK: project-rule** — violates "Every iteration must terminate: bounded loop counts,
  early termination conditions" from coding-guidelines.md
- **Suggested test:** Craft a scenario where `deflate()` returns 0 before `finished()` is true
  (e.g., extremely small initial buffer size via reflection or a mock codec). Alternatively,
  verify the loop has a maximum iteration guard by calling compress on adversarial input
  and asserting it completes within a timeout.

### C1-F4: `decompress` with corrupted/truncated compressed data — `inflate()` returns 0 detection

- **Construct:** `DeflateCodec.decompress()` lines 100-108
- **Description:** The stall detection (`if (read == 0 && !inf.finished())`) correctly throws
  when inflate produces zero bytes without finishing. However, if the input data is truncated
  such that `inf.finished()` returns true before `totalRead == uncompressedLength`, the loop
  exits and the size mismatch check on line 109 catches it. This is correct. BUT: if the
  compressed data is crafted so that `inflate()` returns exactly `uncompressedLength` bytes
  but the stream is NOT finished (more data follows), the method returns without checking
  `inf.finished()`. This means extra trailing data in the compressed stream is silently
  ignored — a potential data integrity concern if a block boundary is off by one.
- **Lens B level:** Level 2 (inputs) — corrupted input not fully validated
- **Suggested test:** Compress data A, then append garbage bytes to the compressed output.
  Call decompress with the original `uncompressedLength`. Verify the method either throws
  (strict) or returns correct data (lenient). Document which behavior the spec requires.

---

## NoneCodec

### C1-F5: `decompress` does not validate `uncompressedLength > 0` per spec

- **Construct:** `NoneCodec.decompress()` line 52-68
- **Description:** Same as C1-F2 for NoneCodec specifically. The spec says
  `uncompressedLength > 0` but the implementation accepts 0. Additionally, there is no
  explicit validation of `uncompressedLength` being non-negative — the `length !=
  uncompressedLength` check catches mismatches but not the `uncompressedLength < 0` case
  on its own. Wait — line 59-61 DOES validate `uncompressedLength < 0`. So the only gap
  is `uncompressedLength == 0` vs the `> 0` spec requirement.
- **Lens B level:** Level 2 (inputs) — zero value slips through
- **Suggested test:** Call `NoneCodec.decompress(new byte[0], 0, 0, 0)` — verify behavior
  matches spec. If spec says `> 0`, this should throw.

---

## CompressionMap

### C1-F6: `deserialize` accepts data longer than expected without error

- **Construct:** `CompressionMap.deserialize()` line 141
- **Description:** The length check is `data.length < expectedLength` — it rejects data
  that is too short but silently ignores trailing bytes beyond the expected length. If the
  serialized form has trailing garbage (e.g., from a corrupted file or a buffer reuse bug),
  `deserialize()` returns a valid map from the prefix, hiding the corruption. This is
  inconsistent with the brief's error case: "Corrupted compressed block -> IOException with
  descriptive message."
- **Lens B level:** Level 2 (inputs) — semantically wrong but valid trailing data accepted
- **Suggested test:** Serialize a map, append extra bytes, deserialize. Verify it either
  throws `IllegalArgumentException` (strict) or the behavior is explicitly documented as
  acceptable.

### C1-F7: `deserialize` does not validate entry field values from untrusted data

- **Construct:** `CompressionMap.deserialize()` lines 148-157
- **Description:** When deserializing from on-disk data, the `Entry` record constructor
  validates `blockOffset >= 0`, `compressedSize >= 0`, `uncompressedSize >= 0`. However,
  a malicious or corrupted file could contain `blockCount = Integer.MAX_VALUE` which would
  cause `4 + Integer.MAX_VALUE * 17` to overflow. `Integer.MAX_VALUE * 17` overflows to a
  negative value, making `expectedLength` negative, and the `data.length < expectedLength`
  check passes (any positive length is >= a negative number). The loop then tries to read
  `Integer.MAX_VALUE` entries, causing `ArrayIndexOutOfBoundsException` instead of a clean
  `IllegalArgumentException`.
- **Lens B level:** Level 2 (inputs) — integer overflow in size calculation from untrusted data
- **IMPL-RISK: project-rule** — violates "Validate all inputs at public API boundaries
  eagerly" and "Bound all in-memory collections" from coding-guidelines.md
- **Suggested test:** Create a byte array with blockCount set to `Integer.MAX_VALUE` (4 bytes
  big-endian), total array length of only 21 bytes. Call `deserialize()`. Verify
  `IllegalArgumentException` is thrown, not `ArrayIndexOutOfBoundsException` or OOM.

### C1-F8: `Entry` record allows `compressedSize == 0` and `uncompressedSize == 0`

- **Construct:** `CompressionMap.Entry` record constructor, lines 44-57
- **Description:** The record validates `>= 0` but allows zero for both sizes. A
  `compressedSize` of 0 means a zero-length block on disk, and `uncompressedSize` of 0
  means the block decompresses to nothing. While technically valid for an edge case, if
  used with DeflateCodec, `decompress(input, offset, 0, 0)` would pass validation but
  produce an empty Inflater with no input. This could cause subtle issues depending on
  codec behavior. More critically, `compressedSize == 0` with `uncompressedSize > 0`
  would mean a block claims to decompress from zero bytes to non-zero bytes — this is
  physically impossible and should be rejected at construction time.
- **Lens B level:** Level 4 (carriers) — construction-time invariant not enforced
- **Suggested test:** Construct `Entry(0L, 0, 4096, (byte) 0x02)` — zero compressed bytes
  claiming to decompress to 4096 bytes. Then attempt to use it during a read path. Also
  test `Entry(0L, 4096, 0, (byte) 0x02)`.

### C1-F9: `serialize()`/`deserialize()` integer overflow for very large entry lists

- **Construct:** `CompressionMap.serialize()` line 108
- **Description:** `4 + entries.size() * ENTRY_SIZE` overflows if `entries.size()` is large
  enough. With `ENTRY_SIZE = 17`, overflow occurs at ~126M entries, which is unlikely but
  violates the principle of "scrutinize every allocation." The `new byte[size]` call with a
  negative (overflowed) size throws `NegativeArraySizeException` instead of a clear error.
- **Lens B level:** Level 1 (construct) — silent truncation via integer overflow
- **Suggested test:** This is more of a defensive-coding gap than a practical attack vector.
  Test with a very large blockCount value in deserialization (see C1-F7 which covers the
  same overflow path from the deserialization side).

---

## SSTableFormat

### C1-F10: No validation utility — magic numbers and sizes are raw constants

- **Construct:** `SSTableFormat` class, lines 51-73
- **Description:** This is a constants-only utility class with no validation methods. Any
  code that checks the magic number or footer size must inline its own validation. This is
  not a bug per se, but it means validation is scattered and inconsistent. No finding here
  that would produce a failing test — noted for completeness.
- **Lens B level:** N/A — design observation, not a bug vector
- **Suggested test:** None — this is not a Breaker vector.

---

## TrieSSTableWriter

### C1-F11: `largestKey` uses non-cloned `keyBytes` reference that is aliased to `lastKeyBytes`

- **Construct:** `TrieSSTableWriter.append()` line 180
- **Description:** `largestKey = MemorySegment.ofArray(keyBytes)` wraps the `keyBytes` array
  without cloning. On the very next line, `lastKeyBytes = keyBytes` (line 187) stores the
  same reference. On the NEXT call to `append()`, line 146 does
  `byte[] keyBytes = entry.key().toArray(...)` which creates a NEW array, so `lastKeyBytes`
  still points to the previous key's array. The `largestKey` MemorySegment wraps that same
  array. Then `largestKey` is reassigned on line 180 to wrap the NEW key. So the previous
  `largestKey` MemorySegment is orphaned and its backing array is the previous key — but
  since `largestKey` is overwritten each time, only the final value matters. On `finish()`,
  `largestKey` wraps the last `keyBytes` array which is also `lastKeyBytes`. After
  `finish()`, `lastKeyBytes` is never modified again. **This is actually safe** — the array
  is never mutated after wrapping. However, `smallestKey` (line 179) IS cloned via
  `keyBytes.clone()`, suggesting the author was aware of aliasing concerns for `smallestKey`
  but chose not to clone for `largestKey`. The asymmetry is suspicious but correct.
- **Lens B level:** Level 1 (construct) — mutable ref aliasing (confirmed safe on analysis)
- **Suggested test:** Not a Breaker vector — aliasing is safe because array is never mutated
  after wrapping and `largestKey` is always the last entry's key.

### C1-F12: `approximateSizeBytes` tracks uncompressed entry sizes, not on-disk size

- **Construct:** `TrieSSTableWriter.append()` line 174
- **Description:** `approximateSizeBytes += encoded.length` accumulates raw (uncompressed)
  encoded entry sizes. When compression is enabled, the actual on-disk size is smaller. The
  field name `approximateSizeBytes` is used by callers (e.g., to decide when to flush a
  MemTable to SSTable). If callers use this to estimate disk usage, the estimate will be
  too high with compression enabled. The `SSTableWriter` interface exposes this via
  `approximateSizeBytes()` (line 398). This is a semantic mismatch — the method reports
  pre-compression size when the actual file will be smaller.
- **Lens B level:** Level 3 (outputs) — accessor returns misleading value under compression
- **Suggested test:** Write entries with DeflateCodec, compare `approximateSizeBytes()` to
  actual file size after `finish()`. If the ratio is > 2x, this is a meaningful inaccuracy
  that could cause premature MemTable flushes or incorrect capacity planning.

### C1-F13: `entryCount` cast to `int` for bloom filter creation risks overflow

- **Construct:** `TrieSSTableWriter.finish()` line 246
- **Description:** `bloomFactory.create((int) Math.max(1, entryCount))` casts `long
  entryCount` to `int`. If `entryCount > Integer.MAX_VALUE`, the cast silently truncates,
  creating a bloom filter sized for a negative or small number of entries. This would cause
  an extremely high false-positive rate. While SSTable files with > 2B entries are unusual,
  the `entryCount` field is `long` precisely to support large counts.
- **Lens B level:** Level 1 (construct) — silent truncation
- **IMPL-RISK: project-rule** — violates "scrutinize every allocation" and "bound all
  in-memory collections" principles
- **Suggested test:** This is hard to test directly (would require billions of entries).
  A targeted test could verify that `entryCount` near `Integer.MAX_VALUE` produces a
  correctly-sized bloom filter, or that the cast is guarded with an explicit check.

### C1-F14: Writer does not validate `codec.codecId()` uniqueness or known-ness

- **Construct:** `TrieSSTableWriter` constructor + `flushCurrentBlock()` lines 125-137, 200-222
- **Description:** The writer accepts any `CompressionCodec` implementation (the interface
  is open/non-sealed). If a custom codec returns `codecId() == 0x00` (same as NoneCodec)
  but compresses differently, the reader would use NoneCodec to decompress data that was
  compressed with the custom codec, producing corrupted output. There is no validation
  that the codec ID matches a known codec, or that it is consistent.
- **Lens B level:** Level 2 (inputs) — trust boundary: caller-provided codec could have
  conflicting ID
- **Suggested test:** Create a mock codec with `codecId() == 0x00` that actually XORs
  data. Write an SSTable, then read with standard NoneCodec. Verify the data is corrupted
  (proving the vulnerability exists) or that the writer rejects unknown codec IDs.

### C1-F15: `flushCurrentBlock()` incompressible fallback uses `CompressionCodec.none().codecId()` indirection

- **Construct:** `TrieSSTableWriter.flushCurrentBlock()` line 209
- **Description:** When a block is incompressible (`compressed.length >= blockBytes.length`),
  the writer stores `CompressionCodec.none().codecId()` as the codec ID. This calls
  `NoneCodec.INSTANCE.codecId()` which returns `0x00`. This is correct but fragile — it
  depends on `CompressionCodec.none()` always returning a codec with ID `0x00`. If someone
  changes NoneCodec's ID, the incompressible fallback would record the wrong codec. A
  hardcoded constant `(byte) 0x00` would be more robust. Minor finding.
- **Lens B level:** Level 1 (construct) — fragile indirection
- **Suggested test:** Not a high-priority Breaker vector — the indirection is correct today.

### C1-F16: Writer state machine allows `close()` after `finish()` without error, but partial file deleted only if `state == OPEN`

- **Construct:** `TrieSSTableWriter.close()` lines 402-427
- **Description:** The close logic sets `shouldDelete = (state == State.OPEN)` — meaning if
  `finish()` was called (state is FINISHED), the file is kept. If `close()` is called without
  `finish()`, the partial file is deleted. This is correct and intentional. However, if
  `finish()` throws an exception partway through (e.g., during bloom filter creation, key
  index writing, or footer writing), `state` remains `OPEN` because the `state = State.FINISHED`
  assignment on line 291 hasn't been reached. The subsequent `close()` in the try-with-resources
  will see `state == OPEN` and delete the partial file. This is actually the correct behavior —
  a failed `finish()` should not leave a partial file. Confirmed safe.
- **Lens B level:** Level 1 (construct) — state machine analysis (confirmed correct)
- **Suggested test:** Not a Breaker vector — behavior is correct.

### C1-F17: No maximum block count bound in writer

- **Construct:** `TrieSSTableWriter` — `blockCount` field, `compressionMapEntries` list
- **Description:** The `compressionMapEntries` list and `indexKeys`/`indexOffsets` lists grow
  without bound as entries are appended. For a very large SSTable with millions of small
  entries, these lists could consume significant heap. The `compressionMapEntries` list has
  one entry per block (~4KB), so ~250K entries per GB of data — manageable. The `indexKeys`
  list has one entry per KEY, which could be millions. This is not specific to compression
  but the compression map adds an additional unbounded list.
- **Lens B level:** Level 1 (construct) — unbounded collection growth
- **IMPL-RISK: project-rule** — violates "Bound all in-memory collections — every Map, List,
  or queue that grows with input must have a configured capacity or eviction policy"
- **Suggested test:** This is a pre-existing concern amplified by compression. A targeted
  test could measure memory growth when writing a large number of entries and verify it
  stays within expected bounds.

---

## Summary of Breaker-Priority Findings

| ID | Construct | Priority | Category |
|----|-----------|----------|----------|
| C1-F1 | DeflateCodec/NoneCodec bounds check | HIGH | Security — integer overflow bypasses validation |
| C1-F7 | CompressionMap.deserialize overflow | HIGH | Security — integer overflow from untrusted data |
| C1-F3 | DeflateCodec compress loop | HIGH | Functional — potential infinite loop (project-rule) |
| C1-F4 | DeflateCodec decompress trailing data | MEDIUM | Functional — data integrity |
| C1-F2 | DeflateCodec decompress uncompressedLength=0 | MEDIUM | Contract — spec says > 0 |
| C1-F6 | CompressionMap trailing bytes | MEDIUM | Functional — silent corruption acceptance |
| C1-F8 | CompressionMap.Entry zero-size invariants | MEDIUM | Contract — impossible state allowed |
| C1-F12 | Writer approximateSizeBytes inaccuracy | MEDIUM | Functional — misleading output |
| C1-F14 | Writer no codec ID validation | MEDIUM | Security — conflicting custom codec IDs |
| C1-F17 | Writer unbounded collection growth | LOW | Memory — project-rule violation |
| C1-F13 | Writer bloom filter int cast | LOW | Functional — silent truncation for extreme input |
| C1-F5 | NoneCodec decompress uncompressedLength=0 | LOW | Contract — same as C1-F2 |
| C1-F9 | CompressionMap serialize overflow | LOW | Functional — same root cause as C1-F7 |
| C1-F15 | Writer incompressible fallback indirection | LOW | Design — fragile but correct |

**Total findings:** 14 actionable vectors (excluding 2 confirmed-safe and 1 design observation)
