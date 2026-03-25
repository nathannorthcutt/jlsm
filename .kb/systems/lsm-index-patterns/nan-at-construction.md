---
title: "NaN score accepted at construction"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/ScoredEntry.java"
research_status: active
last_researched: "2026-03-25"
---

# NaN score accepted at construction

## What happens
Value types that carry numeric scores used for ordering (e.g., relevance scores, distances, priorities) accept Double.NaN at construction without validation. NaN has no defined ordering — Double.compare treats it as greater than all values, Comparator.comparingDouble places it inconsistently, and direct < > comparisons always return false. If NaN reaches a PriorityQueue or sorted collection, it corrupts ordering and produces incorrect results.

## Why implementations default to this
Record constructors typically validate reference nullness but not numeric edge cases. NaN is a valid IEEE 754 double, so it passes type checking. The assumption is that callers produce valid scores, but NaN can arise from 0.0/0.0, sqrt(-1), or failed computation. Defense-in-depth requires rejecting NaN at the construction boundary rather than trusting all callers.

## Test guidance
- Pass Double.NaN to any score/distance/priority constructor — should throw IAE
- Verify Double.POSITIVE_INFINITY and NEGATIVE_INFINITY are accepted (they have valid ordering)
- If NaN was previously handled downstream (e.g., in a comparator), update downstream tests to verify NaN can no longer reach them

## Found in
- table-partitioning (round 2, 2026-03-25): ScoredEntry allowed NaN score — downstream ResultMerger had NaN handling but callers outside ResultMerger saw undefined ordering; fixed with IAE at construction
