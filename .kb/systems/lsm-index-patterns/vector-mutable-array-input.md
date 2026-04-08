---
title: "Mutable vector array stored by reference at document construction"
type: adversarial-finding
domain: data-integrity
severity: confirmed
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/JlsmDocument.java"
research_status: active
last_researched: "2026-03-25"
---

# Mutable vector array stored by reference at document construction

## What happens
When `JlsmDocument.of()` accepts a `float[]` or `short[]` for a VectorType field, the caller's array reference is stored directly without cloning. After construction, the caller can mutate the original array and the document's internal state changes. This breaks the immutability contract and can corrupt data during serialization or index operations that happen after construction.

## Why implementations default to this
Java records and value holders commonly store references directly because cloning arrays has a measurable cost. The validation step (`validateType`) checks type and dimensions but doesn't address ownership transfer. Developers focus on "is the data valid?" without considering "is the data still mine?".

## Test guidance
- Pass a float[]/short[] to JlsmDocument.of(), then mutate the original array. Verify the document's values are unchanged.
- Also check the output side: if `values()` returns the raw reference, mutation of the returned array affects the document. For package-private accessors used by the serializer, this may be acceptable for performance — document the trade-off.
- Apply the same pattern to any construct accepting mutable collections or arrays across a trust boundary.

## Found in
- vector-field-type (audit round 1, 2026-03-25): float[] and short[] vectors stored by reference in JlsmDocument.of()
