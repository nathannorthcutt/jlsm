---
type: adversarial-finding
domain: validation
severity: confirmed
tags: [assert, validation, production, anti-pattern]
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/"
  - "modules/jlsm-sql/src/main/java/jlsm/sql/"
sources:
  - table-indices-and-queries audit R1, 2026-04-03
  - sql-query-support audit run-001, 2026-04-05
---

# Assert-Only Guard Anti-Pattern

## Pattern

Public API boundaries (constructors, public methods) use `assert` as the sole
validation guard. Java assertions are disabled by default in production (`-ea`
not set), so these guards silently pass null, invalid types, or post-close calls
through to downstream code where they manifest as obscure exceptions (NPE,
ArrayIndexOutOfBoundsException) or silent data corruption.

## Why It Happens

The project coding guidelines correctly distinguish assert (internal invariant
validation) from runtime guards (public API boundaries), but the distinction is
easy to miss in record compact constructors. Records look like value types where
validation feels optional. `assert x != null` reads as "I checked this" but does
nothing in production.

## Fix

Replace `assert X` with runtime checks at public API boundaries:
```java
// Wrong — disabled in production
public record Foo(String name) {
    public Foo { assert name != null; }
}

// Correct — always enforced
public record Foo(String name) {
    public Foo { Objects.requireNonNull(name, "name"); }
}
```

## When Assert IS Correct

- Private methods called after a public-API runtime check
- Internal state transitions between cooperating methods
- Post-condition verification (the result of a computation)
- Development-time visibility into assumptions

## Test Guidance

- For every public record, verify compact constructor guards use runtime checks
- Run tests with `-ea` disabled (`-da`) to catch assert-only guards
- In adversarial audits, systematically test null/invalid inputs to every public
  constructor and method

## Scope

This is the single most common bug category across audits:
- table-indices-and-queries: 9 of 39 confirmed bugs (F-R1.conc.2.2, 2.5,
  cb.1.1, cb.2.3, cb.2.6, CB.4.3, resource_lifecycle.1.7, shared_state.1.1, 1.3)
- sql-query-support: 3 findings across 2 lenses (F-R1.cb.1.2, cb.1.3)

Applies to any jlsm module with public records or public constructors.
