---
subject: "deterministic-encryption-performance"
topic: "algorithms"
category: "encryption"
created: "2026-03-19"
status: "active"
sources:
  - url: "https://datatracker.ietf.org/doc/html/rfc8452"
    accessed: "2026-03-19"
    description: "RFC 8452 — AES-GCM-SIV specification, nonce-misuse-resistant AEAD"
  - url: "https://engineering.linecorp.com/en/blog/AES-GCM-SIV-optimization"
    accessed: "2026-03-19"
    description: "LINE Engineering — AES-GCM-SIV optimization with hardware acceleration, 7-30x speedups"
  - url: "https://github.com/codahale/aes-gcm-siv"
    accessed: "2026-03-19"
    description: "Pure Java AES-GCM-SIV implementation with JMH benchmarks across Java 8/9/11"
---

# Deterministic Encryption Performance

## Overview

AES-SIV (RFC 5297) is a two-pass construction: CMAC-based S2V derives a synthetic
IV, then AES-CTR encrypts. This inherently costs ~2x a single-pass AEAD like
AES-GCM. In practice the gap is larger (~4x measured in jlsm: 66K vs 252K ops/s)
because the JVM's AES-NI intrinsics coverage differs by mode. This entry analyzes
optimization paths and alternative deterministic schemes to narrow the gap.

Theoretical minimum SIV overhead vs single-pass AEAD: 2x encryption (two data
passes), ~1.5x decryption. The observed 4x gap indicates room for optimization
-- excess comes from JCE overhead, key schedule recomputation, and uneven HW accel.

## AES-SIV Optimization Approaches

### Shared Key Schedule / Pre-Expanded Round Keys

AES-SIV uses two independent 256-bit keys (K1 for CMAC, K2 for CTR). Key
schedule expansion happens inside `Cipher.init()` / `Mac.init()`. **Optimization**:
call `init()` once per key, reuse across `doFinal()` calls. Cache initialized
`Cipher` and `Mac` instances as `ThreadLocal`. For AES-256, key expansion produces
15 round keys (240 bytes) — significant overhead for short field values (8-64
bytes). Expected improvement: 10-20% for small payloads.

### JCE Provider Selection and AES-NI Exploitation

| Mode | AES-NI Intrinsic | JDK Version | Notes |
|------|-------------------|-------------|-------|
| AES/CTR | Yes (`-XX:+UseAESIntrinsics`) | 8+ | Full hardware acceleration |
| AES/GCM | Yes (AES-NI + CLMUL) | 9+ | pclmulqdq for GHASH |
| AES-CMAC | **Partial** — AES block encrypt is accelerated, but CMAC chaining logic runs in Java | 8+ | The S2V loop is not intrinsified |
| AES/ECB | Yes | 8+ | Single-block, full AES-NI |

The CMAC pass is the bottleneck. AES-NI accelerates the individual AES block
encryptions within CMAC, but the S2V chaining (XOR, double, padding) runs in
pure Java. For short fields (1-2 AES blocks), chaining overhead is proportionally
large. The CTR pass is fully hardware-accelerated.

**Provider selection**: `SunJCE` (default) exploits AES-NI via HotSpot intrinsics.
Amazon Corretto's provider delegates to SunJCE for AES operations — no additional
acceleration. Bouncy Castle's Java provider does NOT use AES-NI (pure Java AES).
Always verify with `-XX:+PrintCompilation` that AES stubs are compiled.

### Practical Optimization Checklist for jlsm

1. **Reuse Cipher/Mac instances** — init once, doFinal many times (thread-local)
2. **Batch S2V computation** — for multi-field documents, pipeline CMAC state
3. **Avoid Bouncy Castle for AES** — stick with SunJCE for AES-NI intrinsics
4. **Verify AES-NI active** — JVM flags: `-XX:+UseAES -XX:+UseAESIntrinsics`
5. **Pre-allocate output buffers** — avoid per-call `byte[]` allocation

Expected improvement from all optimizations combined: 1.5-2x over naive
implementation, bringing AES-SIV to ~100-130K ops/s (gap narrows to ~2x).

## Alternative Deterministic Schemes

### AES-GCM-SIV (RFC 8452)

**Construction**: POLYVAL (GF multiplication) replaces CMAC for tag derivation,
then AES-CTR encrypts. POLYVAL is the little-endian variant of GHASH used in
AES-GCM, enabling hardware CLMUL acceleration.

**Deterministic usage**: with a fixed nonce, AES-GCM-SIV produces identical
ciphertext for identical plaintext — same deterministic property as AES-SIV.
Security degrades gracefully on nonce reuse (reveals only plaintext equality).

**Performance**: encryption at ~67% of AES-GCM (two passes), POLYVAL faster
than CMAC due to CLMUL acceleration. Decryption within 5% of AES-GCM.

**Java feasibility**: no JDK-native support. Pure Java impl (~400 lines) requires
hand-rolled GF(2^128) multiply without CLMUL intrinsics. `codahale/aes-gcm-siv`
benchmarks at ~6-21 us/op on Java 11.

**Verdict**: faster than AES-SIV in native code, but in pure Java without CLMUL
intrinsics the advantage disappears. Not worthwhile unless JDK exposes CLMUL.

### AES-ECB for Single-Block Values

**Construction**: for values <= 16 bytes, a single AES-ECB block encryption is
deterministic and fully hardware-accelerated. No IV, no chaining, no tag.

**Performance**: ~5-10x faster than AES-SIV for single-block values. One AES
operation vs. the full S2V + CTR pipeline.

**Security**: no authentication (no integrity check). Leaks equality like DET.
For values shorter than 16 bytes, padding must be deterministic (e.g., zero-pad
with length prefix). **Not an AEAD** — no tamper detection.

**Java feasibility**: trivial — `Cipher.getInstance("AES/ECB/NoPadding")`.

**Verdict**: suitable as a fast path for short, low-sensitivity indexed fields
(enum values, boolean flags, fixed-width IDs) where authentication is provided
at a higher level (e.g., document-level MAC). Not a general replacement.

### HMAC-CTR (HMAC for IV, then AES-CTR)

**Construction**: IV = HMAC-SHA-256(key, plaintext || AD) truncated to 128 bits,
then AES-CTR(key2, IV, plaintext). Functionally equivalent to SIV but using
HMAC instead of CMAC for the PRF pass.

**Performance**: HMAC-SHA-256 has SHA-NI intrinsics (Intel Goldmont+, AMD Zen).
SHA-256 processes 64-byte blocks vs CMAC's 16-byte — fewer rounds for short
payloads. May outperform CMAC for fields under 64 bytes.

**Security**: provably secure as deterministic AEAD with independent HMAC/CTR
keys. Same leakage as AES-SIV. Not standardized — custom construction.

**Java feasibility**: `Mac("HmacSHA256")` + `Cipher("AES/CTR/NoPadding")`, ~100
lines. **Verdict**: promising, worth benchmarking. Downside: non-standard.

## Performance Comparison

| Scheme | Passes | HW Accel (Java) | Est. ops/s | Deterministic | Auth | Standard |
|--------|--------|------------------|------------|---------------|------|----------|
| AES-GCM | 1 | Full (AES-NI+CLMUL) | 252K (measured) | No | Yes | RFC 5116 |
| AES-SIV (current) | 2 | Partial (CTR yes, CMAC partial) | 66K (measured) | Yes | Yes | RFC 5297 |
| AES-SIV (optimized) | 2 | Partial | ~100-130K (est.) | Yes | Yes | RFC 5297 |
| AES-GCM-SIV (pure Java) | 2 | No CLMUL in Java | ~80-120K (est.) | Yes* | Yes | RFC 8452 |
| AES-ECB (single block) | 1 | Full (AES-NI) | ~500K+ (est.) | Yes | No | FIPS 197 |
| HMAC-CTR | 2 | SHA-NI + AES-NI | ~120-160K (est.) | Yes | Yes | Non-std |

*AES-GCM-SIV is deterministic only when the same nonce is reused deliberately.

Estimates: optimized Java on x86-64 with AES-NI/SHA-NI, short payloads (16-64B).

## Implementation Notes (Java 25)

### javax.crypto Coverage

| Primitive | JCE API | HW Accel |
|-----------|---------|----------|
| `AES/CTR/NoPadding` | Native | AES-NI (JDK 8+) |
| `AES/ECB/NoPadding` | Native | AES-NI (JDK 8+) |
| `AES/GCM/NoPadding` | Native | AES-NI + CLMUL (JDK 9+) |
| `HmacSHA256` | Native | SHA-NI (JDK 9+, CPU-dep) |
| `AESCMAC` | Native | Partial (JDK 17+) |
| AES-SIV S2V | Custom (~80 lines) | AES-NI for blocks only |
| AES-GCM-SIV POLYVAL | Custom (~150 lines) | No CLMUL in Java |
| HMAC-CTR SIV | Trivial composition | SHA-NI + AES-NI |

### Thread Safety and Allocation

`Cipher`/`Mac` instances are NOT thread-safe — use `ThreadLocal` or per-thread
instances. Avoid `Cipher.doFinal()` (allocates new `byte[]`); prefer the
buffer-accepting overload `doFinal(in, inOff, inLen, out, outOff)`.

## Recommendation for jlsm

**Phase 1 — Optimize existing AES-SIV** (low risk, ~2x improvement):
1. Cache `Cipher` and `Mac` instances per thread via `ThreadLocal`
2. Call `init()` once per key, reuse across `doFinal()` calls
3. Use buffer-accepting `doFinal` overloads to avoid allocation
4. Verify AES-NI is active (`-XX:+UseAES -XX:+UseAESIntrinsics`)
5. Target: ~100-130K ops/s (gap narrows from 4x to ~2x)

**Phase 2 — Add AES-ECB fast path** (low risk, big win for short fields):
1. For fields <= 16 bytes with a document-level MAC providing integrity
2. Single AES-ECB block encrypt — fully hardware-accelerated
3. Target: ~500K+ ops/s for eligible fields (faster than AES-GCM)

**Phase 3 — Evaluate HMAC-CTR** (medium risk, non-standard):
1. Benchmark HMAC-SHA-256 + AES-CTR vs optimized AES-SIV
2. If HMAC-CTR is >= 1.5x faster, consider as an alternative mode
3. Document the non-standard construction and security argument

**Do NOT pursue** AES-GCM-SIV in pure Java — without CLMUL intrinsics, POLYVAL
will not outperform CMAC. ~400 lines + GF math not justified for zero gain.

**Bottom line**: 2x gap between DET and randomized AEAD is inherent (two passes
vs one). Optimizing to ~2x is achievable. Below 2x requires AES-ECB (no auth)
or native code (no pure Java).

## sources

1. [RFC 8452 — AES-GCM-SIV](https://datatracker.ietf.org/doc/html/rfc8452) — nonce-misuse-resistant AEAD specification
2. [LINE Engineering — AES-GCM-SIV Optimization](https://engineering.linecorp.com/en/blog/AES-GCM-SIV-optimization) — hardware acceleration optimization achieving 7-30x speedups
3. [codahale/aes-gcm-siv](https://github.com/codahale/aes-gcm-siv) — pure Java AES-GCM-SIV with benchmarks (6-21 us/op Java 11)

---
*Researched: 2026-03-19 | Next review: 2026-09-19*
