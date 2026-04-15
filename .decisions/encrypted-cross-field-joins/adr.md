---
problem: "encrypted-cross-field-joins"
date: "2026-03-30"
version: 1
status: "deferred"
depends_on: ["encryption-key-rotation", "per-field-key-binding", "distributed-join-execution"]
---

# Encrypted Cross-Field Joins — Deferred

## Problem
Cross-field joins on encrypted values from different tables. Standard hash/sort-merge joins work only when both columns use the same DET key.

## Why Deferred
KB research is complete (`encrypted-cross-field-joins.md`) and covers five strategies. However:
1. Same-key DET equi-joins work automatically with zero special handling — this is already supported by the confirmed `encrypted-index-strategy` ADR
2. Cross-key joins require proxy re-encryption or per-query token generation — both need client-side key material at query time
3. **Leakage amplification is the critical risk**: Hoover et al. (USENIX Security 2024) showed >15% plaintext recovery from cross-table equality patterns, vs <5% from single-table attacks
4. Distributed join execution (just confirmed 2026-04-14) must be implemented before cross-table encrypted joins
5. Per the project convention "defer half-baked/leaky ones" — cross-key encrypted joins amplify leakage multiplicatively

## Resume When
Distributed join execution is implemented AND core encryption features are stable AND a concrete use case for cross-key encrypted joins emerges.

## What Is Known So Far
From `.kb/algorithms/encryption/encrypted-cross-field-joins.md`:
- **Same-key DET**: zero-cost equi-join on ciphertexts. Already works.
- **Cross-key DET**: proxy re-encryption (CryptDB JOIN-ADJ). Server transforms ciphertexts without decrypting. O(m) re-encryptions, cached for repeated joins. Risk: delta_AB is a long-lived secret.
- **OPE range joins**: both columns must share the same OPE key and order space. No cross-key OPE path.
- **SSE join tokens**: per-query tokens prevent cross-query linkage. Best leakage profile. Requires client-side token generation.
- **PSI**: out of scope for single-process library. Recommended for federated/multi-tenant scenarios.
- **Critical**: leakage amplification — joining two encrypted tables reveals strictly more than either table alone. Any encrypted join must expose a leakage profile.

## Next Step
After distributed join execution + core encryption: `/architect "encrypted-cross-field-joins"` — evaluate same-key DET (already works) vs cross-key strategies with leakage analysis.
