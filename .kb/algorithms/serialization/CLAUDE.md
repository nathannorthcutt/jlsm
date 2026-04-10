# Serialization — Category Index
*Topic: algorithms*
*Tags: simd, json, serialization, parsing, pclmulqdq, pmull, carry-less-multiplication, panama, ffm, vector-api, on-demand, structural-indexing, simdjson, prefix-xor, quote-masking, escape-masking, java-25*

SIMD-accelerated serialization and deserialization techniques, with focus on
carry-less multiplication for string escape masking, the simdjson on-demand
parsing architecture, and pure-Java implementation strategies using Panama FFM
and the Vector API with graceful scalar degradation.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [simd-on-demand-serialization.md](simd-on-demand-serialization.md) | SIMD On-Demand Serialization | active | 2-4 GiB/s parse throughput | Understanding the two-stage SIMD parsing architecture |
| [panama-ffm-inline-machine-code.md](panama-ffm-inline-machine-code.md) | Panama FFM Inline Machine Code | active | ~10-15 cycles/64B block | PCLMULQDQ/PMULL via embedded byte arrays, zero native build |
| [simd-serialization-java-fallbacks.md](simd-serialization-java-fallbacks.md) | SIMD Serialization Java Fallbacks | active | 3-tier: 1x / 2-4x / 15-30x | Graceful degradation strategy for Java 25 |
| [json-only-simd-jsonl.md](json-only-simd-jsonl.md) | json-only-simd-jsonl (feature footprint) | stable | feature audit record | JSON value types + SIMD parser + JSONL streaming overview |

## Comparison Summary

The three entries form a cohesive implementation strategy:

1. **simd-on-demand-serialization** describes *what* to build — the two-stage
   architecture (SIMD structural indexing + lazy on-demand iteration) and
   SIMD-accelerated serialization output.

2. **panama-ffm-inline-machine-code** describes *how* to access the one
   critical instruction (PCLMULQDQ/PMULL carry-less multiply) that has no
   Java equivalent — by embedding pre-assembled machine code as byte arrays
   and invoking via Panama FFM downcall handles.

3. **simd-serialization-java-fallbacks** describes the *degradation strategy*
   — Vector API for everything except carry-less multiply, with a pure scalar
   tier for environments where neither Panama nor Vector API is available.

The key insight: carry-less multiplication is the only operation requiring
Panama FFM. All other SIMD operations (character classification, mask
extraction, batch comparison) work well through the Vector API.

## Recommended Reading Order

1. Start: [simd-on-demand-serialization.md](simd-on-demand-serialization.md) — the algorithm and architecture
2. Then: [panama-ffm-inline-machine-code.md](panama-ffm-inline-machine-code.md) — the PCLMULQDQ/PMULL technique
3. Finally: [simd-serialization-java-fallbacks.md](simd-serialization-java-fallbacks.md) — Java 25 implementation strategy

## Research Gaps

- Benchmark data for Java Vector API vs Panama FFM vs scalar on jlsm workloads
- RISC-V Zbc carry-less multiply machine code fragments
- macOS MAP_JIT + pthread_jit_write_protect_np code path verification
- SIMD-accelerated UTF-8 validation implementation in Java
- simdjson 4.3 serialization output SIMD optimization details (string escaping)

## Shared References Used
@../../_refs/complexity-notation.md
