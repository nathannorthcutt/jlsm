---
title: "Panama FFM Inline Machine Code for SIMD Serialization"
aliases: ["inline machine code", "Panama FFM PCLMULQDQ", "embedded native code", "zero-build SIMD"]
topic: "algorithms"
category: "serialization"
tags: ["panama", "ffm", "pclmulqdq", "pmull", "carry-less-multiplication", "simd", "machine-code", "java-25"]
complexity:
  time_build: "O(1) — one-time mmap + mprotect per code fragment"
  time_query: "O(n/W) per invocation where W = SIMD width in bytes"
  space: "O(1) — fixed-size code fragments (typically <256 bytes each)"
research_status: "active"
confidence: "medium"
last_researched: "2026-04-09"
applies_to: []
related: ["algorithms/serialization/simd-on-demand-serialization", "algorithms/serialization/simd-serialization-java-fallbacks"]
decision_refs: []
sources:
  - url: "https://branchfree.org/2019/03/06/code-fragment-finding-quote-pairs-with-carry-less-multiply-pclmulqdq/"
    title: "Finding Quote Pairs with Carry-Less Multiply (PCLMULQDQ)"
    accessed: "2026-04-09"
    type: "blog"
  - url: "https://wunkolo.github.io/post/2020/05/pclmulqdq-tricks/"
    title: "PCLMULQDQ Tricks"
    accessed: "2026-04-09"
    type: "blog"
  - url: "https://openjdk.org/jeps/454"
    title: "JEP 454: Foreign Function & Memory API"
    accessed: "2026-04-09"
    type: "docs"
  - url: "https://mail.openjdk.org/pipermail/panama-dev/2021-November/015767.html"
    title: "Support for allocation of executable memory (panama-dev)"
    accessed: "2026-04-09"
    type: "docs"
  - url: "https://arxiv.org/html/1902.08318v7"
    title: "Parsing Gigabytes of JSON per Second"
    accessed: "2026-04-09"
    type: "paper"
---

# Panama FFM Inline Machine Code for SIMD Serialization

## summary

The Panama FFM API (standard since Java 22, JEP 454) can invoke raw machine
code embedded as Java byte-array constants, requiring zero native build tools.
The technique: allocate anonymous memory via `mmap`, copy pre-assembled machine
code bytes into it, set executable permission via `mprotect`, then invoke via
`Linker.nativeLinker().downcallHandle()`. This enables PCLMULQDQ (x86-64) and
PMULL (AArch64) carry-less multiplication for JSON string escape masking
entirely from Java, with no JNI, no `.so`/`.dylib`, and no build-time native
compiler.

## how-it-works

### carry-less-multiplication-for-quote-masking

The core algorithm uses carry-less multiplication to compute a prefix-XOR scan,
transforming a bitmask of quote positions into a mask of bytes inside quotes.

In GF(2), when multiplier `b` = all-ones (`0xFFFFFFFFFFFFFFFF`):
```
c_i = XOR(a_0, a_1, ..., a_i)
```
Each output bit is the cumulative parity of all input bits up to that position.

**x86-64 (PCLMULQDQ)**: `pclmulqdq xmm0, xmm1, 0x00` — 7 cycle latency
(Skylake), 1/cycle throughput. Available since Westmere (2010).

**AArch64 (PMULL)**: `pmull v0.1q, v0.1d, v1.1d` — ~3 cycle latency
(Cortex-A76). Requires ARMv8 Crypto Extensions.

**C++ reference** (from simdjson):
```cpp
quote_mask = _mm_cvtsi128_si64(_mm_clmulepi64_si128(
    _mm_set_epi64x(0ULL, quote_bits),
    _mm_set1_epi8(0xFF), 0));
quote_mask ^= prev_iter_inside_quote;
```

**ARM equivalent**: `vmull_p64(quote_bits, poly64_t(~0x0ul))`

### executable-memory-pipeline

```
byte[] machineCode → mmap(RW) → memcpy → mprotect(RX) → downcallHandle → invoke
```

1. **Embed** machine code as `static final byte[]` — position-independent,
   ABI-compliant functions
2. **mmap** anonymous RW memory via Panama downcall to libc
3. **Copy** code bytes via `MemorySegment.copyFrom()`
4. **mprotect** to RX (W^X: never RWX)
5. **downcallHandle** the segment as a function pointer
6. **Invoke** via `MethodHandle.invokeExact()` on hot path

See detail file for full Java code examples of each step.

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| Code size | Bytes per fragment | 64-256 bytes | Fits in L1 icache |
| PCLMULQDQ latency | Carry-less multiply | 7 (x86), 3 (ARM) | Hidden by interleaving |
| Block size | Input per invocation | 16-64 bytes | Matches SIMD width |

## algorithm-steps

### prefix-xor-via-clmul

1. **Input**: 64-bit bitmask `Q` (bit i = 1 if byte i is unescaped quote)
2. **Load** `Q` into low 64 bits of 128-bit register
3. **Load** all-ones constant into second register
4. **Execute** PCLMULQDQ (x86) or PMULL (ARM)
5. **Extract** low 64 bits → quote mask
6. **XOR** with carry from previous block
7. **Update** carry: `prev = mask >> 63`

### runtime-dispatch

1. Detect `os.arch` at class-load time
2. Probe CPUID (x86) or `/proc/cpuinfo` (ARM) for CLMUL support
3. Select: native CLMUL → Vector API → scalar
4. Cache `MethodHandle` in `static final` for JIT constant-folding

## complexity-analysis

- **Init**: ~3 syscalls + JIT stub, <1ms total, amortized to zero
- **Per block**: ~10-15 cycles for PCLMULQDQ path (64 bytes)
- **Memory**: <1 KB code + 1-2 pages (4-8 KB) executable segments

## tradeoffs

### strengths
- **Zero build**: machine code ships as Java source constants
- **Auditable**: every byte visible in source
- **Fast**: matches hand-written C for these specific operations
- **Portable dispatch**: same Java code, x86 or ARM at runtime

### weaknesses
- Maintenance burden: re-assemble when changing function behavior
- Per-ISA byte arrays needed (x86-64, AArch64, potentially RISC-V)
- Requires `--enable-native-access` JVM flag
- macOS MAP_JIT adds platform-specific init complexity
- No JIT optimization across the native boundary

### compared-to-alternatives
- vs **JNI + .so**: Panama FFM simpler, but JNI lets C compiler optimize
- vs **Vector API only**: PCLMULQDQ has no Vector API equivalent — Panama FFM
  is the only pure-Java path to carry-less multiply
- vs **Pure scalar**: 10-50x slower for prefix-XOR on 64-bit blocks

## current-research

### key-papers
- Langdale & Lemire (2019). https://arxiv.org/abs/1902.08318
- Wunk (2020). "PCLMULQDQ Tricks." https://wunkolo.github.io/post/2020/05/pclmulqdq-tricks/

### active-research-directions
- Vector API CLMUL support: no current JEP proposes it
- RISC-V Zbc extension: may need a third machine code variant

## practical-usage

### when-to-use
- JSON parsing hot paths where string escape masking is a bottleneck
- Any Java app needing carry-less multiply (CRC32, GF(2) arithmetic)
- Libraries shipping as pure Java artifacts, no native dependencies

### when-not-to-use
- Vector API scalar fallback is fast enough
- `--enable-native-access` cannot be granted
- Pre-Westmere x86 / pre-ARMv8 platforms

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| simdjson | C++ | https://github.com/simdjson/simdjson | Active |
| simdjson-java | Java | https://github.com/simdjson/simdjson-java | Active (JNI) |

## sources

1. [Finding Quote Pairs with PCLMULQDQ](https://branchfree.org/2019/03/06/code-fragment-finding-quote-pairs-with-carry-less-multiply-pclmulqdq/) — original technique
2. [PCLMULQDQ Tricks](https://wunkolo.github.io/post/2020/05/pclmulqdq-tricks/) — generalized patterns with x86 + ARM code
3. [JEP 454: FFM API](https://openjdk.org/jeps/454) — Java standard for native invocation
4. [panama-dev: Executable Memory](https://mail.openjdk.org/pipermail/panama-dev/2021-November/015767.html) — OpenJDK discussion

@./panama-ffm-inline-machine-code-detail.md

---
*Researched: 2026-04-09 | Next review: 2026-10-09*
