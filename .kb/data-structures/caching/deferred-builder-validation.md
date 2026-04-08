---
title: "Deferred builder validation"
type: adversarial-finding
domain: "data-integrity"
severity: "tendency"
applies_to:
  - "modules/jlsm-*/src/main/**"
research_status: active
last_researched: "2026-03-26"
---

# Deferred builder validation

## What happens
Builder setter methods store values without validation, deferring all checks
to `build()`. When some setters validate eagerly (e.g., `stripeCount()` throws
on `<= 0`) while others silently accept invalid input (e.g., `capacity(-5)`),
the API becomes inconsistent. Callers get no feedback until `build()`, making
builder chains harder to debug.

## Why implementations default to this
Setters that return `this` for fluent chaining are commonly implemented as
simple field assignments. Developers add validation to `build()` as the
natural gate. Inconsistency arises when some setters are later hardened
without reviewing siblings.

## Test guidance
- For every builder: call each setter with boundary-invalid values (0, -1,
  MAX_VALUE) and assert the exception is thrown by the setter, not by `build()`
- Check consistency: if any setter in a builder validates eagerly, all setters
  must validate eagerly

## Found in
- striped-block-cache (audit round 2, 2026-03-26): `LruBlockCache.Builder.capacity()` and `StripedBlockCache.Builder.capacity()` accepted negatives/zero silently while `stripeCount()` validated eagerly
