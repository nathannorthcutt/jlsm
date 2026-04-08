---
title: "NaN score ordering corruption"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-*/src/main/**"
research_status: active
last_researched: "2026-03-25"
---

# NaN score ordering corruption

## What happens
When scored results (vector similarity, full-text relevance) contain NaN scores, `Double.compare` treats NaN as greater than all finite values. A max-heap comparator using `Comparator.comparingDouble(...).reversed()` will poll NaN entries first, ranking them above legitimate high-scoring results.

## Why implementations default to this
`Double.compare` is the standard comparison method and handles NaN consistently (NaN > everything), so it appears correct. The problem only manifests when NaN scores enter the system — often from edge cases like zero-norm vectors, missing fields, or division by zero in scoring functions. Tests typically use well-formed scores.

## Test guidance
- For any scored result merge: test with NaN scores mixed among finite scores and verify NaN entries are ranked last (below all finite scores) or filtered out entirely
- Test with `Double.POSITIVE_INFINITY` and `Double.NEGATIVE_INFINITY` to verify correct handling of infinity scores
- Consider whether the scoring entry point should reject NaN at construction or at merge time

## Found in
- table-partitioning (audit round 1, 2026-03-25): ResultMerger.mergeTopK ranked NaN-scored entries above all finite scores
- float16-vector-support (audit round 2, 2026-03-25): IvfFlat.search() and Hnsw.searchLayer() min-heaps never evicted NaN-scored entries, corrupting topK results
