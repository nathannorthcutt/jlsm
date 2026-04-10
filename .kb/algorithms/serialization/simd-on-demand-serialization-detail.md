# SIMD On-Demand Serialization — Detail

Extended content for [simd-on-demand-serialization.md](simd-on-demand-serialization.md).

## algorithm-steps

### stage-1

1. **Load** 64 bytes into SIMD register(s)
2. **Classify** characters via two-table vpshufb lookup
3. **Build backslash bitmap**, identify odd-length sequences via add-carry
4. **Build quote bitmap**, mask out escaped quotes
5. **Compute quote mask** via PCLMULQDQ prefix-XOR (multiply by all-ones)
6. **XOR with carry** from previous 64-byte block for cross-block quote state
7. **Identify pseudo-structural** positions (atom starts)
8. **Extract** structural byte offsets via tzcnt/blsr loop
9. **Validate UTF-8** in parallel (saturating subtract + shift checks)
10. **Advance** to next 64-byte block; repeat until input exhausted

### stage-2-on-demand

1. **iterate()**: run stage 1, allocate/reuse string buffer
2. **Navigation**: caller traverses via `get_object()`, `find_field()`, etc.
3. **Skip**: unneeded values advance the index pointer past the value's
   structural extent (depth tracking for nested containers)
4. **Materialize**: on `.get_string()` / `.get_int64()`, parse the raw bytes
   at the current index position into the target type
5. **Advance**: move index pointer to next structural character

### simd-serialization-output

1. **Scan** output string for characters requiring escape (`"`, `\`, control
   chars 0x00-0x1F) using SIMD comparison
2. **Build escape mask** as a bitmask of positions needing escaping
3. **Fast path**: if mask is zero, memcpy the entire chunk
4. **Slow path**: process escapes at flagged positions, copy gaps between them
5. **Integer formatting**: SIMD batch conversion of digits (reverse of the
   parsing 8-digit trick)

## implementation-notes

### data-structure-requirements

- Structural index: `int[]` array, ~4 bytes per structural character
  (typically n/16 entries for well-formed JSON)
- String buffer: contiguous byte array, reusable across documents
- Iterator state: single index pointer + depth counter (fits in registers)

### edge-cases-and-gotchas

- **Cross-block quote state**: the PCLMULQDQ result must be XOR'd with a
  carry bit from the previous block to handle strings spanning 64-byte
  boundaries
- **Backslash runs crossing blocks**: the odd-length detection must carry
  state across block boundaries
- **UTF-8 continuation bytes**: a multi-byte sequence can straddle two blocks;
  stage 1 must buffer the tail of the previous block
- **Very long strings**: stage 2 unescape may need to resize the string buffer
  if a single string exceeds the pre-allocated size
- **Depth overflow**: deeply nested documents can exhaust the depth counter
  (simdjson defaults to max depth 1024)

### simd-number-parsing

For 8-digit sequences, SIMD converts ASCII digits to a 32-bit integer:
1. Subtract ASCII '0' from all bytes (`psubb`)
2. Multiply alternates by 10 and sum pairs (`pmaddubsw`)
3. Multiply alternates by 100 and sum groups (`pmaddwd`)
4. Pack 32-bit results to 16-bit (`packusdw`)
5. Multiply alternates by 10,000 and sum (`pmaddwd` again)
Result: 8 digits → 32-bit integer in ~7 instructions.

### utf-8-validation

Three-phase SIMD validation:
1. **ASCII fast-path**: check all bytes have MSB=0; branch only on non-ASCII
2. **Byte-value checks**: saturated subtract to verify ranges (<=0xF4, etc.)
3. **Sequence validation**: map high nibbles to byte classes, verify transitions

## code-skeleton

```java
// Conceptual Java skeleton — not a direct simdjson port
class SimdOnDemandParser {
    private final int[] structuralIndex;  // stage 1 output
    private int indexPos;                  // stage 2 cursor
    private int depth;

    // Stage 1: build structural index using SIMD
    int buildIndex(MemorySegment input, long length) {
        int count = 0;
        for (long offset = 0; offset < length; offset += 64) {
            long charClassMask = classifyBlock(input, offset);   // vpshufb
            long quoteMask = computeQuoteMask(input, offset);    // PCLMULQDQ
            long structuralMask = extractStructural(charClassMask, quoteMask);
            count += extractPositions(structuralMask, offset, structuralIndex, count);
        }
        return count;
    }

    // Stage 2: on-demand lazy iteration
    JsonValue findField(String key) {
        while (indexPos < structuralIndex.length) {
            if (matchKey(key, structuralIndex[indexPos])) {
                indexPos++; // skip colon
                return materializeValue();
            }
            skipValue(); // advance past unneeded value
        }
        return null;
    }
}
```
