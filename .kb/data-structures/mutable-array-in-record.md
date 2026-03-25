---
title: "Mutable array in Java record"
type: adversarial-finding
domain: "data-integrity"
severity: "tendency"
applies_to:
  - "modules/jlsm-*/src/main/**"
research_status: active
last_researched: "2026-03-25"
---

# Mutable array in Java record

## What happens
Java records generate accessors that return the field reference directly. For array
fields (byte[], float[], int[]), the caller receives a reference to the record's
internal array and can mutate it, violating the record's immutability contract.
The compact constructor also stores the caller's array reference without cloning,
so the caller can mutate the record's state by modifying the original array.

## Why implementations default to this
Records are designed for immutable value types, and the language doesn't auto-clone
arrays. Developers assume record fields are immutable by default (true for
primitives and immutable objects, false for arrays). The bug is invisible in tests
that don't mutate inputs after construction.

## Test guidance
- For any record holding an array: test that mutating the original array after
  construction does not affect the record's state
- Test that mutating the array returned by the accessor does not affect the record
- Both the constructor clone AND the accessor defensive copy are needed

## Found in
- table-indices-and-queries (audit round 1, 2026-03-25): Predicate.VectorNearest stored float[] queryVector without cloning
