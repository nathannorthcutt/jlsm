---
title: "Range query inverted bounds"
type: adversarial-finding
domain: "data-integrity"
severity: "tendency"
applies_to:
  - "modules/jlsm-*/src/main/**"
research_status: active
last_researched: "2026-03-25"
---

# Range query inverted bounds

## What happens
Range query methods accepting (fromKey, toKey) pairs do not validate that fromKey < toKey. When fromKey == toKey (empty range), the method may return non-empty results because the overlap check `pLow < to AND pHigh > from` is satisfied when from == to falls within a partition. When fromKey > toKey (inverted range), behavior is implementation-dependent — may return incorrect results or empty.

## Why implementations default to this
Range query methods focus on the common case where from < to. The overlap condition `pLow < to AND pHigh > from` is mathematically correct for valid ranges but has surprising behavior for degenerate cases. Empty range (from == to) should logically return nothing but satisfies the overlap check for any partition containing that key.

## Test guidance
- Test with fromKey == toKey — should return empty result
- Test with fromKey > toKey — should return empty or throw IAE
- Apply this check to all layers: RangeMap.overlapping, PartitionedTable.getRange, and any range scan method
- Related KB entry: `between-inverted-range` covers the same pattern for Between predicates

## Found in
- table-partitioning (audit round 1, 2026-03-25): RangeMap.overlapping() returned non-empty for empty range [from, from)
