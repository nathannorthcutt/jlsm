---
type: adversarial-finding
domain: validation
severity: confirmed
tags: [builder, reflection, validation-bypass, constructor-guard, defense-in-depth]
applies_to:
  - "modules/jlsm-*/src/main/**"
sources:
  - striped-block-cache audit round-001, 2026-04-21
---

# Reflective bypass of builder validation

## Pattern

A Builder centralizes validation in `build()` but the constructor
`<init>(Builder)` trusts the Builder's internal state without re-validating.
Reflective callers (or test fixtures) can set fields directly, bypass `build()`,
and invoke the constructor with out-of-range or sentinel values. The result is
corrupted invariants, leaked sentinels in error messages, or unchecked
exceptions from downstream code paths.

## What happens

A reflective test or malicious caller creates a Builder, uses reflection to
set a private field to an invalid value (negative, `Integer.MAX_VALUE + 1`,
sentinel like `-1L`), then invokes `<init>(Builder)` directly (e.g., via
`Constructor.setAccessible(true)`). The constructor trusts that `build()`
already validated, so it proceeds and either crashes with the wrong exception
type (e.g., `NegativeArraySizeException` instead of `IllegalArgumentException`)
or silently corrupts a data structure. Sentinel values intended for internal
use escape into error messages exposed to callers.

## Fix pattern

Defend at the constructor. Every constraint that `build()` enforces must also
be enforced in the constructor, OR the constructor must be `private` with only
`build()` as the single legitimate caller. Prefer constructor-side duplication
over `build()`-only validation — it is redundant but catches reflective bypass
and guarantees the same exception class and message regardless of entry path.

```java
MyThing(Builder b) {
    if (b.capacity <= 0)
        throw new IllegalArgumentException("capacity must be positive");
    if (b.stripeCount > MAX_STRIPE_COUNT)
        throw new IllegalArgumentException(
            "stripeCount must be <= " + MAX_STRIPE_COUNT);
    // ... same checks build() performs ...
}
```

## Test guidance

Write a reflective-bypass test that uses `java.lang.reflect` to construct the
instance with each out-of-range sentinel value. Assert the same exception
class and error message as the legitimate `build()` path. Cover every
parameter that `build()` validates — missing one means a gap survives.

```java
Field f = Builder.class.getDeclaredField("stripeCount");
f.setAccessible(true);
Builder b = new Builder();
f.setInt(b, MAX_STRIPE_COUNT + 1);
Constructor<?> ctor = MyThing.class.getDeclaredConstructor(Builder.class);
ctor.setAccessible(true);
assertThrows(IllegalArgumentException.class, () -> ctor.newInstance(b));
```

## Seen in

- striped-block-cache audit round-001, 2026-04-21:
  - F-R1.contract_boundaries.4.1 — `StripedBlockCache.Builder.build` enforces
    `MAX_STRIPE_COUNT` but `<init>(Builder)` does not re-check
  - F-R1.contract_boundaries.4.2 — `LruBlockCache`/`StripedBlockCache` `<init>`
    leaks the `-1L` sentinel in `IllegalArgumentException` messages when
    reached via reflection
  - F-R1.dispatch_routing.1.2 — `StripedBlockCache` `<init>` does not
    re-validate `stripeCount`, causing `NegativeArraySizeException` from
    `roundUpToPowerOfTwo` overflow instead of an `IllegalArgumentException`
