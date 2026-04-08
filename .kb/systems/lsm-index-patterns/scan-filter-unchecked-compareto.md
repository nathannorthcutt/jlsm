---
title: "Scan-and-filter unchecked compareTo"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/QueryExecutor.java"
research_status: active
last_researched: "2026-03-25"
---

# Scan-and-filter unchecked compareTo

## What happens

When a query falls back to scan-and-filter (no matching index), comparison
predicates (Gt, Gte, Lt, Lte, Between) call `Comparable.compareTo()` without
checking that the field value and predicate value are the same Java type.
`Integer.compareTo(Long)` throws ClassCastException because Integer's compareTo
expects Integer, not Object. The exception propagates as an unhandled crash
rather than a meaningful error.

This is specific to the scan-and-filter path — the index-backed path catches
type mismatches earlier in FieldValueCodec.encode with a clear IAE.

## Why implementations default to this

The scan-and-filter path uses raw Java `Comparable.compareTo()` which is
generic but not type-safe at runtime. The `@SuppressWarnings("unchecked")`
annotation suppresses the compiler warning. Tests typically use matching types
(Integer predicate on Integer field), so the cross-type case is never exercised.

## Test guidance

- For every comparison predicate test, include a cross-type variant: use Long
  predicate value on an INT32 field, Integer value on INT64 field, etc.
- Verify the result is empty (no match) rather than ClassCastException
- Test both index-backed and scan-and-filter paths (the latter by omitting
  the index definition)

## Found in

- table-indices-and-queries (audit round 2, 2026-03-25): QueryExecutor.matchesPredicate crashed with ClassCastException on Integer/Long mismatch. Fixed by adding `fieldValue.getClass() == predicateValue.getClass()` check before compareTo in all comparison branches.
