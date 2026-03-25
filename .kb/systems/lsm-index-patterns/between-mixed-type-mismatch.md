---
title: "Between predicate mixed-type mismatch"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/FieldIndex.java"
  - "modules/jlsm-*/src/main/**"
research_status: active
last_researched: "2026-03-25"
---

# Between predicate mixed-type mismatch

## What happens
A Between predicate accepts `Comparable<?>` for both low and high bounds. When the
caller supplies mixed numeric types (e.g., `Integer` low and `Long` high), the
predicate constructor succeeds but the index lookup crashes downstream. In
FieldIndex, the bounds are passed to `TreeMap.subMap()` which calls `compareTo`
across the mismatched types, producing a `ClassCastException` in the codec layer
where sort-preserving byte encoding assumes a single type.

## Why implementations default to this
The `Predicate.Between` record accepts `Comparable<?>` — Java's type system cannot
enforce that low and high have the same concrete type at compile time. Tests
typically use the same literal type for both bounds (`BETWEEN 1 AND 10`, both
`Integer`). The mismatch only surfaces when low and high originate from different
sources or when auto-boxing widens one operand.

## Test guidance
- Test Between with `Integer` low and `Long` high (and vice versa)
- Test Between with `Integer` low and `Double` high
- Verify the error is caught and reported at the predicate or index layer, not as
  an unhandled `ClassCastException` deep in the codec
- After fixing, verify homogeneous types still work (regression check)

## Found in
- table-indices-and-queries (audit round 1, 2026-03-25): FieldIndex.lookupBetween crashed on Integer/Long mix; fixed with explicit type-consistency check
