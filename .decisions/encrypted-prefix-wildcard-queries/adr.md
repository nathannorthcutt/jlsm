---
problem: "encrypted-prefix-wildcard-queries"
date: "2026-03-30"
version: 1
status: "deferred"
depends_on: ["encryption-key-rotation", "per-field-key-binding"]
---

# Encrypted Prefix/Wildcard Queries — Deferred

## Problem
Prefix/wildcard queries (`LIKE 'foo%'`) on encrypted text fields. Standard DET/OPE ciphertexts do not support prefix matching.

## Why Deferred
KB research is complete (`prefix-fuzzy-searchable-encryption.md`) and recommends prefix tokenization + DET — encrypt every prefix of each term, store in inverted index. This is implementable (~100 lines) and composes with `LsmInvertedIndex`. However:
1. Core encryption features (per-field-key-binding, encryption-key-rotation) must be implemented first
2. Prefix tokenization multiplies encrypted tokens per term (O(L * N) index entries) — storage overhead needs benchmarking
3. Leakage is strictly more than DET equality (prefix frequency exposed) — per the project convention "defer half-baked/leaky ones," this should be shipped only with per-field keys and documented leakage profiles

## Resume When
Core encryption features are implemented AND index-access-pattern-leakage mitigations (per-field keys, leakage documentation) are in place.

## What Is Known So Far
From `.kb/algorithms/encryption/prefix-fuzzy-searchable-encryption.md`:
- **Recommended approach**: Prefix tokenization + DET. Generate all prefixes of each term (down to min length), encrypt each with AES-SIV, store in `LsmInvertedIndex` composite key format. Query: encrypt query prefix, exact lookup.
- **Alternative**: ORE (Lewi-Wu) enables `LIKE 'foo%'` as range query (`>= 'foo' AND < 'fop'`). Stronger than OPE but leaks order and common-prefix lengths. No production pure-Java ORE library. ~800-1000 lines.
- **Alternative**: SSE + prefix index. Opaque at rest (unlike DET) but leaks search/access pattern on query. ~600 lines.
- **Leakage**: prefix tokenization + DET leaks prefix frequency and equality at rest. Per-field keys mitigate cross-field correlation.
- **No production encrypted DB supports LIKE**: CryptDB, Arx, Acra, StealthDB all lack prefix query support.

## Next Step
After core encryption implementation + leakage mitigations: `/architect "encrypted-prefix-wildcard-queries"` — evaluate prefix tokenization vs ORE vs SSE+prefix.
