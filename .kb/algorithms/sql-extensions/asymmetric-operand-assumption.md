---
title: "Asymmetric operand assumption in SQL translation"
type: adversarial-finding
domain: "data-integrity"
severity: "tendency"
applies_to:
  - "modules/jlsm-sql/src/main/java/jlsm/sql/SqlTranslator.java"
  - "modules/jlsm-*/src/main/**"
research_status: active
last_researched: "2026-03-25"
---

# Asymmetric operand assumption in SQL translation

## What happens
Translation methods assume a fixed operand layout — field on the left, value on
the right — without checking. When a user writes a reversed comparison like
`WHERE 30 < age`, the translator tries to extract a field name from the literal
and fails with a confusing error. Additionally, bind parameters stored as AST nodes
may not implement interfaces required by downstream predicate constructors (e.g.,
`Comparable` for range predicates).

## Why implementations default to this
The simplest translation path is left=field, right=value. Most SQL examples use
this order. The gap only surfaces when users write idiomatic reversed comparisons
or use bind parameters in range positions — both are valid SQL but uncommon in
tests.

## Test guidance
- Always test comparisons with the literal on the left side (`WHERE 5 < field`)
- Test bind parameters (`?`) with every comparison operator, not just equality
- When adding new expression types, verify both operand orderings
- Check that all value wrapper types implement required interfaces (`Comparable`)

## Found in
- sql-query-support (audit round 1, 2026-03-25): `translateComparison` assumed left=ColumnRef, right=value; bind parameters lacked Comparable for range ops
