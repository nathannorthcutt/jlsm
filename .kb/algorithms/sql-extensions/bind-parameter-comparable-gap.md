---
title: "Bind parameter Comparable gap"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-sql/src/main/java/jlsm/sql/SqlTranslator.java"
  - "modules/jlsm-*/src/main/**"
research_status: active
last_researched: "2026-03-25"
---

# Bind parameter Comparable gap

## What happens
When bind parameters (`?`) are used with range comparison operators (`>`, `>=`,
`<`, `<=`) or `BETWEEN`, the translator extracts the parameter as a raw AST node
that does not implement `Comparable<?>`. Range predicate constructors (Gt, Gte,
Lt, Lte, Between) require `Comparable<?>` values. The `toComparable()` check
rejects the parameter with "Value is not comparable", making bind parameters
usable only with equality operators (`=`, `!=`).

## Why implementations default to this
Equality predicates accept `Object` values, so the raw AST parameter node passes
through. Range predicates narrow the type to `Comparable<?>`. The translator's
`extractValue()` method returns the AST node directly for parameters without
wrapping it in a Comparable-compatible marker. Tests typically use literal values
for range comparisons and bind parameters only for equality.

## Test guidance
- Test bind parameters with every comparison operator: `=`, `!=`, `>`, `>=`, `<`, `<=`
- Test bind parameters in BETWEEN: `WHERE field BETWEEN ? AND ?`
- When introducing a new value wrapper type, verify it implements all interfaces
  required by every predicate constructor it could be passed to
- If using a marker/placeholder pattern, ensure the marker implements `Comparable`

## Found in
- sql-query-support (audit round 1, 2026-03-25): `toComparable()` rejected `SqlAst.Expression.Parameter`; fixed by introducing `SqlQuery.BindMarker` implementing `Comparable`
