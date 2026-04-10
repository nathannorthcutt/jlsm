---
title: "SIMD Serialization Java Fallback Strategy"
aliases: ["Vector API fallback", "scalar fallback", "graceful degradation", "tiered SIMD dispatch"]
topic: "algorithms"
category: "serialization"
tags: ["java-25", "vector-api", "simd", "fallback", "graceful-degradation", "runtime-dispatch", "serialization"]
complexity:
  time_build: "O(1) — one-time feature detection + handle selection"
  time_query: "O(n) all tiers, constant factor varies 1x-50x"
  space: "O(1) — no additional allocation beyond input/output buffers"
research_status: "active"
confidence: "medium"
last_researched: "2026-04-09"
applies_to: []
related: ["algorithms/serialization/simd-on-demand-serialization", "algorithms/serialization/panama-ffm-inline-machine-code"]
decision_refs: []
sources:
  - url: "https://openjdk.org/jeps/508"
    title: "JEP 508: Vector API (Tenth Incubator)"
    accessed: "2026-04-09"
    type: "docs"
  - url: "https://openjdk.org/jeps/529"
    title: "JEP 529: Vector API (Eleventh Incubator)"
    accessed: "2026-04-09"
    type: "docs"
  - url: "https://www.javacodegeeks.com/2026/03/vector-api-at-eleven-incubations-why-this-api-takes-so-long-and-whats-blocking-it.html"
    title: "Vector API at Eleven Incubations"
    accessed: "2026-04-09"
    type: "blog"
  - url: "https://openjdk.org/jeps/454"
    title: "JEP 454: Foreign Function & Memory API"
    accessed: "2026-04-09"
    type: "docs"
---

# SIMD Serialization Java Fallback Strategy

## summary

A three-tier graceful degradation strategy for SIMD-accelerated serialization
in Java 25 with zero native build requirements. Tier 1: Panama FFM for
PCLMULQDQ/PMULL carry-less multiply. Tier 2: Vector API for character
classification and mask extraction. Tier 3: pure scalar. Runtime dispatch at
class-load time, cached in `static final` for JIT constant-folding.

## how-it-works

### three-tier-architecture

```
┌──────────────────────────────────────────────────────────┐
│ Tier 1: Panama FFM — PCLMULQDQ/PMULL                    │
│ ─ Requires: --enable-native-access, CLMUL hardware       │
│ ─ ~10-15 cycles/64B block                                │
├──────────────────────────────────────────────────────────┤
│ Tier 2: Vector API (jdk.incubator.vector)                │
│ ─ Character scan, mask extract, batch ops                │
│ ─ Prefix-XOR via 6-step shift-XOR cascade                │
│ ─ Requires: --add-modules jdk.incubator.vector           │
│ ─ ~30-60 cycles/64B block                                │
├──────────────────────────────────────────────────────────┤
│ Tier 3: Pure Scalar                                      │
│ ─ Byte-by-byte with lookup table                         │
│ ─ No special JVM flags or hardware                       │
│ ─ ~200-400 cycles/64B block                              │
└──────────────────────────────────────────────────────────┘
```

### runtime-dispatch

```java
static final int TIER = detectTier();

private static int detectTier() {
    if (canUseNativeAccess() && hasClmulSupport()) return 1;
    if (canUseVectorApi() && vectorSpeciesWidth() >= 16) return 2;
    return 3;
}
```

Detection: CPUID via Panama (x86), `/proc/cpuinfo` (ARM), module presence
checks. Stored in `static final` → JIT eliminates dead branches.

### tier-1-hybrid-approach

Tier 1 is only needed for the prefix-XOR step (carry-less multiply). Other
operations (character classification, mask extraction) use Vector API even
when tier 1 is available, creating a hybrid tier-1/tier-2 pipeline. The key
insight: PCLMULQDQ has no Vector API equivalent.

### tier-2-prefix-xor-fallback

After extracting quote bitmask via `mask.toLong()`, compute prefix-XOR in
scalar code — O(log2(64)) = 6 shifts + 6 XORs:

```java
static long prefixXor(long x) {
    x ^= x << 1;  x ^= x << 2;  x ^= x << 4;
    x ^= x << 8;  x ^= x << 16; x ^= x << 32;
    return x;
}
```

12 operations vs PCLMULQDQ's 1 instruction (7 cycles). Slower but no
hardware dependency.

### tier-3-reference-oracle

Pure byte-by-byte processing. Always correct, always available. 10-50x slower
than tier 1 but serves as the reference implementation for testing tiers 1/2.

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| SPECIES_PREFERRED | Auto-detected width | 128/256/512 bit | Tier 2 throughput |
| Block size | Bytes per iteration | 16-64 | Must match SIMD width |
| Prefix-XOR depth | Shift-XOR steps | 6 (64-bit) | Fixed for all tiers |

## complexity-analysis

| Tier | Cycles/64B | Relative | Init Cost |
|------|-----------|----------|-----------|
| 1 (CLMUL) | ~10-15 | 1.0x | ~1ms |
| 2 (Vector) | ~30-60 | 2-4x | ~100us |
| 3 (Scalar) | ~200-400 | 15-30x | 0 |

Gap between tiers 1 and 2 is primarily prefix-XOR: 1 instruction vs 12.
All tiers: O(n) with different constants. Memory: O(1) all tiers.

## tradeoffs

### strengths
- **Always works**: tier 3 on any JVM, any hardware
- **Best available**: automatic tier selection
- **Zero native build**: all native code as byte arrays
- **JIT-friendly**: static final enables dead-code elimination
- **Testable**: tier 3 as oracle for verifying 1 and 2

### weaknesses
- Three code paths to maintain; fixes verified across all tiers
- Vector API still incubating, requires `--add-modules`
- Tier 2 gap: 2-4x slower than PCLMULQDQ for quote masking
- Must test all tiers explicitly (forced tier selection in tests)

### compared-to-alternatives
- vs **JNI simdjson wrapper**: faster but requires native build/distribution
- vs **Vector API only**: loses PCLMULQDQ fast path; tier 2 ceiling
- vs **Panama only**: works but embeds more machine code; Vector API handles
  classification and masking well
- vs **Hybrid (Panama CLMUL + Vector API rest)**: **recommended approach** —
  each technology where it excels

## current-research

- **Vector API graduation**: blocked on Valhalla. Preview ~JDK 28, GA ~JDK 30+
- **CLMUL in Vector API**: no proposal exists; would eliminate tier 1 need
- **Auto-vectorization**: C2 may eventually lower shift-XOR to PCLMULQDQ
- **RISC-V RVV**: growing platform needing tier coverage

## practical-usage

### when-to-use
- Java libraries needing SIMD serialization without native build deps
- Heterogeneous deployment (cloud x86, ARM Graviton, local dev)
- Performance-critical JSON where parsing is a measured hotspot

### when-not-to-use
- JNI binding to simdjson is acceptable
- Workload not string-heavy (prefix-XOR rarely the bottleneck)
- Target JVM cannot use `--add-modules` or `--enable-native-access`

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| simdjson | C++ | https://github.com/simdjson/simdjson | Active (multi-arch dispatch) |
| simdjson-java | Java | https://github.com/simdjson/simdjson-java | Active (JNI) |

## sources

1. [JEP 529: Vector API (11th Incubator)](https://openjdk.org/jeps/529) — current status, Valhalla dependency
2. [JEP 508: Vector API (10th Incubator)](https://openjdk.org/jeps/508) — operations and species model
3. [JEP 454: FFM API](https://openjdk.org/jeps/454) — native invocation from Java
4. [Vector API at Eleven Incubations](https://www.javacodegeeks.com/2026/03/vector-api-at-eleven-incubations-why-this-api-takes-so-long-and-whats-blocking-it.html) — graduation timeline analysis

@./simd-serialization-java-fallbacks-detail.md

---
*Researched: 2026-04-09 | Next review: 2026-10-09*
