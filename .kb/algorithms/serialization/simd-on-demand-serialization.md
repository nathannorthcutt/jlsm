---
title: "SIMD On-Demand Serialization"
aliases: ["simdjson", "on-demand parsing", "SIMD JSON", "structural indexing"]
topic: "algorithms"
category: "serialization"
tags: ["simd", "json", "parsing", "serialization", "on-demand", "structural-indexing", "vectorized"]
complexity:
  time_build: "O(n) — single pass structural index"
  time_query: "O(n) worst case, O(k) typical where k = accessed bytes"
  space: "O(n/16) structural index + O(1) iterator state"
research_status: "active"
confidence: "high"
last_researched: "2026-04-09"
applies_to: []
related: ["algorithms/serialization/panama-ffm-inline-machine-code", "algorithms/serialization/simd-serialization-java-fallbacks"]
decision_refs: []
sources:
  - url: "https://arxiv.org/html/1902.08318v7"
    title: "Parsing Gigabytes of JSON per Second"
    accessed: "2026-04-09"
    type: "paper"
  - url: "https://arxiv.org/html/2312.17149v1"
    title: "On-Demand JSON: A Better Way to Parse Documents?"
    accessed: "2026-04-09"
    type: "paper"
  - url: "https://github.com/simdjson/simdjson/blob/master/doc/ondemand_design.md"
    title: "simdjson On-Demand Design"
    accessed: "2026-04-09"
    type: "docs"
  - url: "https://branchfree.org/2019/03/06/code-fragment-finding-quote-pairs-with-carry-less-multiply-pclmulqdq/"
    title: "Finding Quote Pairs with Carry-Less Multiply"
    accessed: "2026-04-09"
    type: "blog"
  - url: "https://deepwiki.com/simdjson/simdjson"
    title: "simdjson DeepWiki"
    accessed: "2026-04-09"
    type: "docs"
---

# SIMD On-Demand Serialization

## summary

SIMD on-demand serialization is a two-stage approach to JSON parsing that uses
wide SIMD instructions to build a structural index of all tokens in a single
pass (stage 1), then lazily materializes only the values the caller accesses
via a forward-only iterator (stage 2). Pioneered by simdjson, it achieves
2-8x speedups over conventional DOM parsers by eliminating unused work. The
technique applies equally to serialization (output) where SIMD accelerates
string escaping and batch integer formatting. simdjson 4.3 (Feb 2026) added
SIMD string escape optimizations for NEON/SSE2, yielding a further 30% gain
on string-heavy workloads.

## how-it-works

### stage-1-structural-indexing

Stage 1 scans the entire input in 64-byte blocks using SIMD, producing a
compressed index of every structural character position.

**Character classification** uses a two-table `vpshufb` lookup:
1. Low nibble (4 LSB) indexes a 16-byte table
2. High nibble (after right-shift) indexes a second table
3. Bitwise AND of results classifies into 5 categories:
   commas (bit 0), colons (bit 1), brackets/braces (bit 2),
   whitespace (bit 3), spaces (bit 4)

Two instructions, zero branches, 64 bytes classified per iteration.

**Escape handling** distinguishes odd-length backslash sequences from even:
build 64-bit backslash bitmap, process even/odd offset starts via add-carry,
merge to find odd-length sequence ends, AND quote bitmap with inverted mask.

**Quote pairing** via PCLMULQDQ carry-less multiplication — see
[panama-ffm-inline-machine-code.md](panama-ffm-inline-machine-code.md).
Result: 64-bit mask where 1-bits = bytes inside quoted strings.

**Pseudo-structural identification** finds atom starts (numbers, true, false,
null): merge structural + whitespace, left-shift by 1, AND with non-white
non-quoted regions.

**Index extraction**: `tzcnt` + `blsr` branchless loop, 8 indexes per iter.

### stage-2-on-demand-iteration

A single `json_iterator` walks the structural index forward-only:
- **Lazy materialization**: strings unescaped, numbers parsed only on access
- **Forward-only**: no restart, no backtracking, single index pointer + depth
- **Buffer reuse**: pre-allocated string buffer, reused across documents

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| Block size | SIMD register width | 128/256/512 bit | Wider = fewer iterations |
| String buffer | Unescape target | 1-4x input | Avoids realloc |
| Index density | Structural chars/64B | 1-8 typical | >8 = extra loops |

## complexity-analysis

- **Build**: O(n), 2-4 GiB/s throughput regardless of document complexity
- **Query**: O(k) typical (k = accessed bytes), O(n) worst case
- **Memory**: ~1.25n total vs ~2-4n for DOM approaches

## tradeoffs

### strengths
- 2-8x faster than DOM parsers for partial access patterns
- Minimal memory — no tree construction
- SIMD throughput is complexity-independent

### weaknesses
- Forward-only: cannot revisit values
- Requires SIMD for full performance (fallback 5-10x slower)
- Stage 1 processes entire document even for single-field access

### compared-to-alternatives
- vs **DOM**: 2-8x faster, 3-4x less memory, no random access
- vs **SAX/streaming**: faster due to SIMD stage 1, similar memory
- vs **schema codegen** (protobuf): schema-based faster for known schemas

## current-research

### key-papers
- Langdale & Lemire (2019). "Parsing Gigabytes of JSON per Second." *VLDB J.* https://arxiv.org/abs/1902.08318
- Keiser & Lemire (2024). "On-Demand JSON." *SPE*, 54(6). https://arxiv.org/abs/2312.17149

### active-research-directions
- SIMD serialization output: simdjson 4.3 NEON/SSE2 string escaping (30% gain)
- AVX-512 full 512-bit stage 1 processing
- Runtime dispatch: icelake > haswell > westmere > arm64 > fallback

## practical-usage

### when-to-use
- Large JSON where only a subset of fields is needed
- High-throughput pipelines (log ingestion, API gateways)

### when-not-to-use
- Full DOM materialization required
- Very small documents (<1 KB)

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| simdjson | C++ | https://github.com/simdjson/simdjson | Active (v4.3) |
| simdjson-java | Java | https://github.com/simdjson/simdjson-java | Active |
| simd-json | Rust | https://github.com/simd-lite/simd-json | Active |

## sources

1. [Parsing Gigabytes of JSON per Second](https://arxiv.org/html/1902.08318v7) — foundational two-stage SIMD paper
2. [On-Demand JSON](https://arxiv.org/html/2312.17149v1) — peer-reviewed on-demand design (2024)
3. [simdjson On-Demand Design](https://github.com/simdjson/simdjson/blob/master/doc/ondemand_design.md) — official design doc
4. [Finding Quote Pairs with PCLMULQDQ](https://branchfree.org/2019/03/06/code-fragment-finding-quote-pairs-with-carry-less-multiply-pclmulqdq/) — prefix-XOR technique

@./simd-on-demand-serialization-detail.md

---
*Researched: 2026-04-09 | Next review: 2026-10-09*
