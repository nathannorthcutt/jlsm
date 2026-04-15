---
problem: "block-cache-block-size-interaction"
evaluated: "2026-04-14"
candidates:
  - name: "True byte-budget rewrite"
    source: "RocksDB/Caffeine model"
  - name: "Byte-budget builder with entry-count derivation"
    source: "Arithmetic derivation"
  - name: "Pool-aware byte-budget derivation"
    source: "automatic-backend-detection ADR pattern"
  - name: "Document only (status quo)"
    source: "backend-optimal-block-size ADR"
  - name: "Per-entry byte accounting with eviction loop"
    source: "Falsification agent (missing candidate)"
constraint_weights:
  scale: 2
  resources: 1
  complexity: 3
  accuracy: 3
  operational: 3
  fit: 2
---

# Evaluation — block-cache-block-size-interaction

## References
- Constraints: [constraints.md](constraints.md)
- Related ADRs: [backend-optimal-block-size](../backend-optimal-block-size/adr.md),
  [automatic-backend-detection](../automatic-backend-detection/adr.md),
  [cross-stripe-eviction](../cross-stripe-eviction/adr.md)
- KB: [concurrent-cache-eviction-strategies](../../.kb/data-structures/caching/concurrent-cache-eviction-strategies.md)

## Constraint Summary
The cache holds blocks from SSTables with potentially different block sizes (4 KiB
to 8 MiB). Memory usage must be predictable from a byte budget. The LinkedHashMap
structure should be preserved. Operators need a bytes API, not entry count.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | Mixed block sizes are the core problem but not the most narrowing |
| Resources | 1 | Minimal new state needed |
| Complexity | 3 | Cache internals are well-tested; changes must be surgical |
| Accuracy | 3 | Byte budget must be respected even with mixed block sizes |
| Operational | 3 | Operators must reason about bytes, not entries |
| Fit | 2 | Must work with existing striped architecture |

---

## Candidate: True Byte-Budget Rewrite

**Source:** RocksDB block cache model, Caffeine weigher-based eviction

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 2 | 5 | 10 | Handles mixed block sizes perfectly |
| Resources | 1 | 3 | 3 | Adds per-entry size tracking, atomic counter, new data structure |
| Complexity | 3 | 2 | 6 | Replaces removeEldestEntry pattern; fundamentally changes cache internals |
| Accuracy | 3 | 5 | 15 | Exact byte tracking |
| Operational | 3 | 5 | 15 | Operators set byte budget directly |
| Fit | 2 | 2 | 4 | Breaks LinkedHashMap pattern; significant internal rewrite |
| **Total** | | | **53** | |

**Hard disqualifiers:** Excessive complexity for the problem at hand.

---

## Candidate: Byte-Budget Builder with Entry-Count Derivation

**Source:** Arithmetic derivation: maxEntries = byteBudget / blockSize

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 2 | 4 | 8 | Works for uniform blocks; breaks on mixed block sizes |
|       |   |   |   | **Would be a 2 if:** cache holds blocks from SSTables with different sizes |
| Resources | 1 | 5 | 5 | Zero internal changes |
| Complexity | 3 | 5 | 15 | Builder-only change |
| Accuracy | 3 | 4 | 12 | Accurate for uniform blocks only |
|          |   |   |    | **Would be a 2 if:** mixed block sizes after compaction/transfer |
| Operational | 3 | 5 | 15 | Operators set byte budget |
| Fit | 2 | 5 | 10 | LinkedHashMap untouched |
| **Total** | | | **65** | |

**Key weakness:** Assumes uniform block sizes — breaks on mixed workloads.

---

## Candidate: Pool-Aware Byte-Budget Derivation

**Source:** automatic-backend-detection ADR pattern

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 2 | 4 | 8 | Same as entry-count derivation |
| Resources | 1 | 5 | 5 | Same |
| Complexity | 3 | 5 | 15 | Same |
| Accuracy | 3 | 4 | 12 | Same derivation problem; pool buffer size ≠ cache entry size |
|          |   |   |    | **Would be a 2 if:** pool is write-path, cache is read-path with different block sizes |
| Operational | 3 | 5 | 15 | Same |
| Fit | 2 | 5 | 10 | Same |
| **Total** | | | **65** | |

**Key weakness:** Pool buffer size (write-path) conflated with cache entry size
(read-path). In heterogeneous deployments these differ.

---

## Candidate: Document Only (Status Quo)

**Source:** No change; document that capacity is entry-count

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 2 | 3 | 6 | Works but requires manual calculation |
| Resources | 1 | 5 | 5 | No changes |
| Complexity | 3 | 5 | 15 | Zero complexity |
| Accuracy | 3 | 3 | 9 | Correct only if operator does the math right |
| Operational | 3 | 2 | 6 | Operators must know block size AND do division |
| Fit | 2 | 5 | 10 | Nothing changes |
| **Total** | | | **51** | |

---

## Candidate: Per-Entry Byte Accounting with Eviction Loop

**Source:** Falsification agent (identified as missing candidate)

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 2 | 5 | 10 | Handles mixed block sizes — no uniform-size assumption |
|       |   |   |    | **Would be a 2 if:** blocks had no accessible size metadata |
| Resources | 1 | 4 | 4 | Adds long counter and per-put byteSize() call (O(1)) |
| Complexity | 3 | 4 | 12 | Replace removeEldestEntry with post-put eviction loop; lock serializes |
|            |   |   |    | **Would be a 2 if:** eviction loop needed to handle concurrent multi-put |
| Accuracy | 3 | 5 | 15 | Exact byte tracking via MemorySegment.byteSize() |
| Operational | 3 | 5 | 15 | Operators set byte budget directly |
| Fit | 2 | 4 | 8 | Keeps LinkedHashMap structure; changes eviction trigger only |
|     |   |   |   | **Would be a 2 if:** removeEldestEntry was used for other purposes |
| **Total** | | | **64** | |

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Total |
|-----------|-------|-----------|------------|----------|-------------|-----|-------|
| True byte-budget rewrite | 10 | 3 | 6 | 15 | 15 | 4 | **53** |
| Byte-budget builder (derivation) | 8 | 5 | 15 | 12 | 15 | 10 | **65** |
| Pool-aware derivation | 8 | 5 | 15 | 12 | 15 | 10 | **65** |
| Document only | 6 | 5 | 15 | 9 | 6 | 10 | **51** |
| **Per-entry byte accounting** | **10** | **4** | **12** | **15** | **15** | **8** | **64** |

## Recommendation
Per-entry byte accounting (64) trails derivation candidates (65) by 1 point but
handles mixed block sizes correctly — a real scenario that derivation candidates
fail on. The 1-point deficit comes from slightly more internal change, which is
justified by the accuracy gain.

## Risks and Open Questions
- Risk: eviction loop under lock may evict many entries on a single large put — but
  this is bounded and correct behavior
- Open: whether StripedBlockCache should have a global byte counter or per-stripe
  byte budgets (per-stripe is simpler and aligns with existing architecture)
