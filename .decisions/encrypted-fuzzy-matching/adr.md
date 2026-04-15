---
problem: "encrypted-fuzzy-matching"
date: "2026-03-30"
version: 1
status: "deferred"
depends_on: ["encryption-key-rotation", "per-field-key-binding"]
---

# Encrypted Fuzzy Matching — Deferred

## Problem
Fuzzy matching (edit distance) on encrypted text fields. Edit distance does not work on ciphertexts — requires an alternative approach.

## Why Deferred
KB research is complete (`prefix-fuzzy-searchable-encryption.md`) and recommends LSH + Bloom filter — hash terms into locality-sensitive buckets for approximate matching. This is implementable (~200 lines) using existing `BlockedBloomFilter`. However:
1. Core encryption features must be implemented first
2. Accuracy depends heavily on n-gram size and LSH tuning — needs empirical evaluation
3. False positives from Bloom FPR + LSH collisions and false negatives from edit distance exceeding overlap threshold make this inherently approximate
4. Per the project convention "defer half-baked/leaky ones" — accuracy guarantees are hard to provide without empirical validation

## Resume When
Core encryption features are implemented AND prefix query support is evaluated (fuzzy builds on similar infrastructure).

## What Is Known So Far
From `.kb/algorithms/encryption/prefix-fuzzy-searchable-encryption.md`:
- **Recommended approach**: LSH + Bloom filter. Extract character n-gram shingles (e.g., bigrams), compute k LSH hash signatures, insert into per-document Bloom filter, encrypt filter with AES-GCM. Query: compute query n-gram signatures, decrypt candidate filters, check if >= threshold signatures present.
- **Threshold**: `(len - max_edit_distance * ngram_size + 1)` — controls precision/recall tradeoff.
- **Leakage**: minimal at rest (AES-GCM encrypted Bloom filter). Access pattern leakage on query.
- **Alternative**: FHIPE (Function-Hiding Inner Product Encryption) is theoretically applicable via cosine/Jaccard on n-gram vectors but requires bilinear pairings (not in javax.crypto). Not pure-Java viable.
- **No production encrypted DB supports fuzzy matching**: this is novel territory.

## Next Step
After core encryption + prefix query evaluation: `/architect "encrypted-fuzzy-matching"` — evaluate LSH + Bloom filter with empirical accuracy benchmarks.
