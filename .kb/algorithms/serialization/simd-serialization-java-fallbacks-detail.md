# SIMD Serialization Java Fallback Strategy — Detail

Extended content for [simd-serialization-java-fallbacks.md](simd-serialization-java-fallbacks.md).

## tier-2-vector-api-operations

| Operation | Vector API Method | SIMD Lowering |
|-----------|-------------------|---------------|
| Character scan | `ByteVector.eq(scalar)` → `VectorMask` | pcmpeqb / cmeq |
| Escape detection | `v.lanewise(AND, 0xE0).eq(0)` | vpand + pcmpeqb |
| Mask extraction | `mask.toLong()` | pmovmskb / bitfield extract |
| Batch load | `ByteVector.fromMemorySegment()` | vmovdqu / ldr |
| Batch store | `v.intoMemorySegment()` | vmovdqu / str |

**What the Vector API cannot do**:
- Carry-less multiplication (PCLMULQDQ/PMULL) — not exposed in any JEP
- Byte shuffle with arbitrary index (`vpshufb`) — `VectorShuffle` is
  restricted to lane rearrangement, not the two-table lookup trick
- Horizontal prefix operations — no scan/prefix-sum primitives

## vector-api-species-selection

```java
static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
// Typical values:
//   x86 AVX2:    SPECIES_256 (32 bytes per vector)
//   x86 AVX-512: SPECIES_512 (64 bytes per vector)
//   ARM NEON:    SPECIES_128 (16 bytes per vector)
//   Fallback:    SPECIES_64  (8 bytes, barely useful)

for (int i = 0; i < length; i += SPECIES.length()) {
    var v = ByteVector.fromMemorySegment(SPECIES, input, i, ByteOrder.LITTLE_ENDIAN);
    var quoteMask = v.eq((byte) '"');
    long quoteBits = quoteMask.toLong();
    // ... process quoteBits
}
```

## per-block-dispatch-steps

1. **Load** input block (tier 1: raw pointer; tier 2: ByteVector.fromMemorySegment;
   tier 3: byte array access)
2. **Classify** characters (tier 1: vpshufb; tier 2: vector.eq(); tier 3: lookup)
3. **Build quote bitmap** (all tiers: SIMD or scalar compare against `'"'`)
4. **Handle escapes** (all tiers: backslash detection + odd-length propagation)
5. **Prefix-XOR** (tier 1: PCLMULQDQ; tier 2: 6-step shift-XOR; tier 3: loop)
6. **Extract positions** (tier 1/2: tzcnt/blsr; tier 3: Long.numberOfTrailingZeros)

## initialization-sequence

1. **Detect OS and architecture**: `os.arch`, `os.name`
2. **Probe tier 1**: Panama FFM module access + CPUID/cpuinfo check
3. **Probe tier 2**: `jdk.incubator.vector` module + species width
4. **Select tier**: highest available, store in `static final`
5. **Initialize tier 1** (if selected): mmap + copy + mprotect; cache handles
6. **Initialize tier 2** (if selected): resolve SPECIES_PREFERRED, pre-compute
   broadcast vectors for comparison constants

## edge-cases-and-gotchas

- **Vector API incubator churn**: 11 rounds (JDK 16-26). API stable but module
  name may change on graduation. Plan for module name update.
- **Valhalla dependency**: Vector API graduation blocked on value classes.
  Earliest preview: JDK 28. Earliest GA: JDK 30-31.
- **SPECIES_PREFERRED variability**: on x86 without AVX, may be 128-bit. On
  ARM, always 128-bit (NEON) unless SVE available. Never hardcode widths.
- **JIT warmup**: Vector API slow in interpreter. Warmup ~1000 iterations.
- **Tier 2 prefix-XOR correctness**: 6-step shift-XOR is mathematically
  equivalent to PCLMULQDQ-by-all-ones. Verify with tier 3 oracle in tests.
- **Cross-tier consistency**: all tiers must produce identical output. Use
  tier 3 as oracle in property-based tests.

## code-skeleton

```java
sealed interface QuoteMasker {
    long quoteMask(MemorySegment input, long offset);

    static QuoteMasker create() {
        int tier = detectTier();
        return switch (tier) {
            case 1 -> new ClmulQuoteMasker();    // Panama FFM
            case 2 -> new VectorQuoteMasker();   // Vector API
            default -> new ScalarQuoteMasker();  // Pure scalar
        };
    }
}

// Tier 2 implementation sketch
final class VectorQuoteMasker implements QuoteMasker {
    private static final VectorSpecies<Byte> S = ByteVector.SPECIES_PREFERRED;
    private static final ByteVector QUOTE = ByteVector.broadcast(S, (byte) '"');

    @Override
    public long quoteMask(MemorySegment input, long offset) {
        var v = ByteVector.fromMemorySegment(S, input, offset, LITTLE_ENDIAN);
        long quoteBits = v.eq(QUOTE).toLong();
        // ... handle escapes ...
        return prefixXor(quoteBits);
    }

    static long prefixXor(long x) {
        x ^= x << 1;  x ^= x << 2;
        x ^= x << 4;  x ^= x << 8;
        x ^= x << 16; x ^= x << 32;
        return x;
    }
}

// Tier 3 implementation sketch
final class ScalarQuoteMasker implements QuoteMasker {
    @Override
    public long quoteMask(MemorySegment input, long offset) {
        long mask = 0;
        boolean inside = false;
        for (int i = 0; i < 64; i++) {
            byte b = input.get(ValueLayout.JAVA_BYTE, offset + i);
            if (b == '"') inside = !inside;  // simplified
            if (inside) mask |= (1L << i);
        }
        return mask;
    }
}
```
