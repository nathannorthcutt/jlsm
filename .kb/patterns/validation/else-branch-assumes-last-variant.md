---
title: "Else Branch Assumes Last Variant"
aliases: ["implicit else dispatch", "two-branch sealed dispatch"]
topic: "patterns"
category: "validation"
tags: ["validation", "dispatch", "sealed-types", "exhaustiveness"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-06"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/JlsmDocument.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/JsonWriter.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/YamlWriter.java"
related:
  - "assert-only-public-api-validation"
decision_refs: []
sources: []
---

# Else Branch Assumes Last Variant

## Summary

When a sealed type or enum has exactly two variants, code uses `if`/`else`
where the else branch implicitly assumes the second variant without checking.
If a third variant is added later, the else branch silently handles it with the
wrong logic. The fix is to use explicit `else if` with the expected variant and
add a defensive `AssertionError` or `IllegalStateException` in a final else,
or use exhaustive `switch` expressions.

## Problem

A two-variant dispatch written as `if`/`else`:

```java
if (elementType == VectorType.FLOAT32) {
    // handle FLOAT32
} else {
    // assumes FLOAT16 — but what if BFLOAT16 is added?
    encodeAsFloat16(vector);
}
```

The else branch silently handles any future variant with FLOAT16 logic. When a
third variant is added, the compiler gives no warning and the code silently
produces wrong results.

## Symptoms

- New enum/sealed variant is added and existing code silently mishandles it
- No compilation error or test failure when a variant is added
- Subtle data corruption or wrong encoding discovered much later

## Root Cause

`if`/`else` is not exhaustive — the compiler does not verify that all variants
are covered. The else branch is a catch-all that swallows unknown variants.

## Fix Pattern

Use exhaustive `switch` expressions (preferred) or explicit `else if`:

```java
// Preferred: exhaustive switch
return switch (elementType) {
    case FLOAT32 -> encodeAsFloat32(vector);
    case FLOAT16 -> encodeAsFloat16(vector);
    // Compiler error if a new variant is added without a case
};

// Alternative: explicit else if with defensive final else
if (elementType == VectorType.FLOAT32) {
    encodeAsFloat32(vector);
} else if (elementType == VectorType.FLOAT16) {
    encodeAsFloat16(vector);
} else {
    throw new AssertionError("Unhandled vector type: " + elementType);
}
```

## Detection

- Search for `if`/`else` blocks dispatching on enum or sealed type values
- Check whether the else branch explicitly names the expected variant
- dispatch_routing lens: check two-branch dispatches for implicit assumptions
  about the last variant

## Audit Findings

Identified in vector-field-type audit run-001:
- `JlsmDocument.validateType` — VectorType elementType dispatch assumes FLOAT16
- `JsonWriter.writeVector` — else branch assumes FLOAT16 encoding
- `YamlWriter.writeVector` — else branch assumes FLOAT16 encoding
