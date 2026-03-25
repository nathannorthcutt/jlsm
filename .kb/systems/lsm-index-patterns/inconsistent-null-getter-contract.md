---
title: "Inconsistent null getter contract"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/JlsmDocument.java"
research_status: active
last_researched: "2026-03-25"
---

# Inconsistent null getter contract

## What happens

Typed getter methods on a document/record class have inconsistent behavior when the field value
is null. Some getters (e.g., `getString()`, `getInt()`) throw `NullPointerException` for null
fields, while others (e.g., `getArray()`, `getObject()`) silently return `null`. Callers
relying on the NPE convention to detect unset fields will miss nulls from the inconsistent
getters, leading to downstream `NullPointerException` at an unrelated call site.

## Why implementations default to this

Complex getters that validate the field type via `instanceof` before accessing the value
naturally skip the null check because `instanceof null` is always false — the cast
`(Object[]) null` succeeds silently in Java. Simpler getters that use a shared `requireValue()`
helper get the null check for free. The inconsistency arises when new getters bypass the helper.

## Test guidance

- For every typed getter, test the null-field case and assert it throws `NullPointerException`.
- When adding a new getter, verify it uses the shared `requireValue()` pattern or includes an
  explicit null check before the cast.

## Found in

- optimize-document-serializer (audit round 1, 2026-03-25): `getArray()` and `getObject()`
  returned null silently — fixed with explicit null check + NPE.
