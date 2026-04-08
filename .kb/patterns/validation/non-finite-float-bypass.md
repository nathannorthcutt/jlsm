---
title: "Non-Finite Float Bypass"
aliases: ["NaN bypass", "Infinity bypass", "parser validation gap"]
topic: "patterns"
category: "validation"
tags: ["validation", "float", "NaN", "Infinity", "parser", "bypass"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-06"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/JsonParser.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/YamlParser.java"
related:
  - "assert-only-public-api-validation"
decision_refs: []
sources: []
---

# Non-Finite Float Bypass

## Summary

Parser code paths that construct documents via internal APIs (e.g.,
`DocumentAccess.create()`) bypass the finiteness validation enforced by the
public `JlsmDocument.of()` factory. NaN and Infinity values enter the system
through these alternative construction paths, corrupting distance computations
for vectors and causing lossy round-trips through serializers (which output
"null" for non-finite values). The fix is to enforce finiteness at the parser
level before document construction, regardless of which construction path is
used.

## Problem

The public factory enforces finiteness:

```java
public static JlsmDocument of(Map<String, Object> fields) {
    // validates no NaN or Infinity in float fields
}
```

But internal construction paths skip this validation:

```java
// Parser builds document via internal API — no finiteness check
DocumentAccess.create(parsedFields);
```

Any float parsed from JSON or YAML that is NaN or Infinity passes through
unchecked, corrupting downstream consumers.

## Symptoms

- Vector distance computations return NaN (any arithmetic with NaN propagates)
- Serialized output contains "null" instead of a float value
- Silent data corruption on round-trip (deserialize → serialize → deserialize)
- Tests pass because test data uses finite values

## Root Cause

Validation is placed at the public API boundary but not at internal construction
paths. When a new construction path is added (parser, bulk loader, migration
tool), it bypasses the validation unless the implementer remembers to add it.

## Fix Pattern

Enforce finiteness at the parser level, before any document construction:

```java
private float parseFloat(String text) {
    float value = Float.parseFloat(text);
    if (!Float.isFinite(value)) {
        throw new IllegalArgumentException("Non-finite float: " + text);
    }
    return value;
}
```

Alternatively, enforce the invariant in the internal construction path itself,
so no bypass is possible regardless of the caller.

## Detection

- Trace all construction paths for the target type — public factories, internal
  factories, builders, parsers, deserializers
- Check whether each path enforces the same validation as the public API
- contract_boundaries lens: follow data flow from external input to internal
  construction, identify validation gaps

## Audit Findings

Identified in vector-field-type audit run-001:
- `JsonParser.parseFloat` — no finiteness check
- `JsonParser.parsePrimitive` (FLOAT32, FLOAT16) — no finiteness check
- `YamlParser.parseVectorSequence` — no finiteness check on vector elements
- `YamlParser.parsePrimitiveValue` — no finiteness check on float values
