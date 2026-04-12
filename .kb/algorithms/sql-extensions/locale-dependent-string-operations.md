---
type: adversarial-finding
domain: internationalization
severity: confirmed
tags: [locale, string, case-conversion, i18n]
applies_to: ["modules/jlsm-sql/src/main/java/jlsm/sql/SqlLexer.java"]
sources:
  - sql-query-support audit run-001, 2026-04-05
---

# Locale-Dependent String Operations

## Pattern

`String.toUpperCase()` without `Locale.ROOT` produces locale-dependent results.
In Turkish locale, `"in".toUpperCase()` yields `"\u0130N"` (dotted capital I),
breaking keyword lookup maps that expect ASCII uppercase.

## Why It Happens

The no-argument `toUpperCase()` uses `Locale.getDefault()`. On most developer
machines this is English, so the bug is invisible until deployed in a
Turkish/Azerbaijani/Lithuanian locale.

## Fix

Use `Locale.ROOT` for all programmatic case conversion:
```java
token.toUpperCase(Locale.ROOT)
```

## Test Guidance

- Set `Locale.setDefault(Locale.of("tr", "TR"))` before keyword/enum lookup tests
- Verify against Turkish, Azerbaijani, Lithuanian locales (all have non-ASCII case rules)
- Restore original locale in `@AfterAll`

## Scope

Any module doing case-insensitive string matching: bloom filter key normalization,
field name resolution, enum parsing, SQL keyword lookup.

## Found In

- sql-query-support (audit run-001, 2026-04-05): `SqlLexer.readIdentifierOrKeyword`
  (F-R1.shared_state.2.1)
