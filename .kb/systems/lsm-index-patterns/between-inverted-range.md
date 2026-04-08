---
title: "Between predicate inverted range"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/FieldIndex.java"
  - "modules/jlsm-*/src/main/**"
research_status: active
last_researched: "2026-03-25"
---

# Between predicate inverted range

## What happens
When a Between predicate is constructed with low > high (e.g., `BETWEEN 10 AND 1`),
the index lookup passes the inverted bounds to `TreeMap.subMap(high, low)`. TreeMap
throws `IllegalArgumentException("fromKey > toKey")` which propagates as an
unhandled crash instead of returning an empty result set.

## Why implementations default to this
The `Predicate.Between` record doesn't validate that low <= high because the
comparison requires knowing the Comparable's natural order, which is type-specific.
Tests always use correctly ordered bounds. The crash only surfaces with user-supplied
or computed bounds where the ordering is not guaranteed.

## Test guidance
- Test Between with low > high for each supported type (Integer, Long, Double, String)
- Verify the result is an empty iterator, not an exception
- If the design choice is to reject inverted ranges, verify a clear
  `IllegalArgumentException` at predicate construction, not a crash in the index

## Found in
- table-indices-and-queries (audit round 1, 2026-03-25): TreeMap.subMap threw IAE on inverted bounds; fixed with compareTo guard returning empty iterator
