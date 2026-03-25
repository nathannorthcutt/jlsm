---
title: "Precision overflow silent data loss"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-vector/src/main/java/jlsm/vector/LsmVectorIndex.java"
research_status: active
last_researched: "2026-03-25"
---

# Precision overflow silent data loss

## What happens
When quantizing float32 values to a lower-precision format (e.g., float16), values
exceeding the target format's finite range overflow to Infinity. With cosine similarity,
`cosine(query, [Inf,...])` = `Inf / Inf` = NaN. NaN scores are filtered by search
methods (to prevent heap corruption — see `nan-score-ordering-corruption`), making the
indexed vector silently invisible in search results. The vector is stored but can never
be found.

## Why implementations default to this
The encoding helpers (`encodeFloat16s`) follow IEEE 754 conversion rules faithfully —
overflow to Infinity is the correct IEEE 754 behavior. But the index contract ("index
then search finds it") is silently violated. Developers implement the encoding correctly
per IEEE 754 but forget that downstream scoring depends on finite values.

## Test guidance
When any precision quantization is added to a vector index:
1. Test with values at the target format's max finite boundary (e.g., ±65504 for float16)
   — should be accepted
2. Test with values just above the boundary (e.g., ±100000 for float16) — should either
   throw `IllegalArgumentException` or be findable in search
3. Test with each similarity function (cosine, dot product, euclidean) — only cosine
   produces NaN from Infinity, but all are affected by precision loss
4. Verify the validation is precision-specific (float32 path should NOT reject large values)

## Found in
- float16-vector-support (round 3, 2026-03-25): `IvfFlat.index()` and `Hnsw.index()`
  accepted float32 values > 65504 that overflow to Infinity in float16, making vectors
  invisible in cosine search. Fixed by adding `validateFloat16Components()` with eager
  `IllegalArgumentException`.
