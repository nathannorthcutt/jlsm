---
title: "Mutable array getter return"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/JlsmDocument.java"
research_status: active
last_researched: "2026-03-25"
---

# Mutable array getter return

## What happens

Public getter methods (`getArray()`) return the internal `Object[]` reference directly without
defensive copying. Callers that mutate the returned array corrupt the document's internal state,
causing subsequent reads to return incorrect values. This violates the immutability expectation
of `JlsmDocument`.

## Why implementations default to this

Array cloning adds allocation overhead on every access. When the getter is used on hot paths
(e.g., during serialization or query evaluation), the performance cost of `.clone()` is visible.
The typical shortcut is to return the raw reference and rely on callers not to mutate it, but
this breaks when the public API surface expands beyond trusted internal callers.

## Test guidance

- Mutate a returned array element and verify a subsequent `get*()` call still returns the
  original value.
- Test both `JlsmDocument.of()` creation path and deserialized documents (different array
  origins).
- Check all getters returning reference types: `Object[]`, `JlsmDocument` (nested), `float[]`,
  `short[]`.

## Found in

- optimize-document-serializer (audit round 1, 2026-03-25): `getArray()` returned raw `Object[]`
  reference — fixed with `.clone()`.
