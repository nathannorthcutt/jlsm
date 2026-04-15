---
problem: "encrypted-cross-field-joins"
date: "2026-04-15"
version: 2
status: "deferred"
depends_on: ["encrypted-index-strategy", "distributed-join-execution"]
---

# Encrypted Cross-Field Joins -- Re-Deferred

## Problem
Cross-field joins on encrypted values from different tables. Standard hash/sort-merge joins work only when both columns use the same DET key.

## Why Re-Deferred

Same-key DET equi-joins already work with zero special handling -- this is
confirmed by the encrypted-index-strategy ADR and requires no new machinery.
Cross-key joins have been thoroughly researched (see KB below) and every
viable strategy carries severe leakage amplification:

1. **Leakage amplification is multiplicative**: Hoover et al. (USENIX Security
   2024) showed >15% plaintext recovery from cross-table equality patterns vs
   <5% from single-table attacks. This is not a theoretical concern -- it was
   demonstrated on real datasets (Chicago taxi/crime/rideshare).

2. **All cross-key strategies require client-side key material at query time**:
   proxy re-encryption needs a re-encryption key delta, SSE join tokens need
   per-query token generation, PSI needs EC exponentiations. None can be
   executed server-side without leaking.

3. **No concrete use case identified**: No consumer of jlsm has requested
   cross-key encrypted joins. Same-key DET covers the common case (declared
   column groups sharing a key).

4. **Per project convention "defer half-baked/leaky ones"**: Cross-key encrypted
   joins amplify leakage multiplicatively and should not be shipped without a
   concrete threat model analysis.

## What Is Already Supported

Same-key DET equi-joins work today. When both join columns use the same DET
key (same key derivation path via F41 HKDF), `Enc_K(a) == Enc_K(b)` iff
`a == b`. Standard hash or sort-merge join applies to ciphertexts directly.
This is the zero-cost option per the encrypted-cross-field-joins KB article.

## What Would Be Needed to Resume

1. A concrete use case for cross-key encrypted joins in jlsm
2. Threat model analysis specific to jlsm's leakage profile (F41 R44-R50)
3. Evaluation of SSE join tokens (Shafieinejad et al., 2021) as the
   lowest-leakage server-executable option
4. Decision on whether cross-key join support belongs in the library or in
   the client-side encryption SDK (F45)

## Resume When

A concrete use case for cross-key encrypted joins emerges AND leakage
mitigation research (SSE join tokens per Shafieinejad et al.) is evaluated
against jlsm's threat model.
