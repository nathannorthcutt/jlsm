---
title: "Runtime type inference ambiguity"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/FieldIndex.java"
  - "modules/jlsm-*/src/main/**"
research_status: active
last_researched: "2026-03-25"
---

# Runtime type inference ambiguity

## What happens
When a secondary index infers the FieldType from the Java runtime type of a value
(e.g., `value instanceof Short` → INT16), it produces the wrong encoding for types
that share a Java class but have different sort-preserving encodings. Short maps to
both INT16 (sign-bit-flip) and FLOAT16 (IEEE 754 sort-preserving). Long maps to
both INT64 and TIMESTAMP (same encoding today, but semantically distinct).

For FLOAT16, negative values are misordered: INT16 sign-bit-flip puts -0.5 before
-2.0, while IEEE 754 encoding correctly puts -2.0 before -0.5. Range queries on
FLOAT16 fields silently return incorrect results.

## Why implementations default to this
Runtime type inference is the simplest approach — no schema plumbing needed. It
works correctly for all types where the Java class uniquely identifies the encoding
(String, Integer, Float, Double, Boolean, Byte). The ambiguity only surfaces for
Short (INT16 vs FLOAT16) and is invisible in tests that don't use negative values.

## Test guidance
- Always test negative-value sort order when a FieldType shares its Java class with
  another FieldType (Short for INT16/FLOAT16, Long for INT64/TIMESTAMP)
- Verify range queries return correct results for negative values, not just positive
- When building a new index or codec, prefer schema-derived FieldType over inference

## Found in
- table-indices-and-queries (audit round 1, 2026-03-25): FieldIndex.inferFieldType mapped Short→INT16, breaking FLOAT16 negative sort order
