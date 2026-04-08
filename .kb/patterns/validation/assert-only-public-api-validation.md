---
title: "Assert-Only Public API Validation"
aliases: ["assert-as-guard", "assert-only precondition"]
topic: "patterns"
category: "validation"
tags: ["validation", "assert", "public-api", "preconditions"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-06"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/FieldType.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/DocumentSerializer.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/JsonWriter.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/YamlWriter.java"
related:
  - "non-finite-float-bypass"
  - "else-branch-assumes-last-variant"
decision_refs: []
sources:
  - url: "https://docs.oracle.com/javase/8/docs/technotes/guides/language/assert.html"
    title: "Programming With Assertions"
    accessed: "2026-04-06"
    type: "docs"
---

# Assert-Only Public API Validation

## Summary

Public API methods (factory methods, entry points) use `assert` as the sole
validation mechanism for preconditions like null checks or range checks. Since
assertions are disabled in production (`-ea` not set), these methods accept
invalid input silently, leading to downstream failures (AIOOBE, NPE,
ClassCastException) with no diagnostic context. The fix is to use
`Objects.requireNonNull` or explicit `if`/`throw` for all public API boundary
validation, reserving `assert` for internal invariant checks only.

## Problem

A public method guards a precondition with `assert` only:

```java
public static FieldType arrayOf(FieldType elementType) {
    assert elementType != null;
    // ... construct ...
}
```

When `-ea` is not set (the default in production), the assert is a no-op.
Callers passing `null` proceed silently, and the failure surfaces later as an
unrelated NPE or corrupted data structure — far from the root cause.

## Symptoms

- NPE or AIOOBE in production with no clear origin
- Tests pass (because `-ea` is enabled) but production fails on the same input
- Error messages reference internal implementation state, not the invalid input

## Root Cause

`assert` is a development-time tool controlled by the `-ea` JVM flag. It is
not a runtime enforcement mechanism. Using it as the sole guard at a public API
boundary means the contract is unenforced in production.

## Fix Pattern

Replace `assert` with runtime checks at public API boundaries:

```java
public static FieldType arrayOf(FieldType elementType) {
    Objects.requireNonNull(elementType, "elementType");
    // ... construct ...
}
```

Reserve `assert` for internal invariant checks — conditions that should be
impossible if the code is correct, not conditions that depend on external input.

## Detection

- Search for `assert` statements in public methods of exported packages
- Check whether the asserted condition validates external input (parameters,
  user data) vs internal state
- contract_boundaries lens: verify that every public API entry point enforces
  its stated contracts with runtime checks

## Audit Findings

Identified in vector-field-type audit run-001:
- `FieldType.arrayOf()` — null elementType passed through silently
- `FieldType.objectOf()` — null fields passed through silently
- `DocumentSerializer.encodeVector` — invalid dimension unchecked in production
- `JsonWriter.write` — null document accepted silently
- `YamlWriter.write` — null document accepted silently
