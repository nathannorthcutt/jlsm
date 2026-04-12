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
and fails with a confusing error instead of detecting the reversal and flipping
the operator.

## Why implementations default to this
The simplest translation path is left=field, right=value. Most SQL examples use
this order. The gap only surfaces when users write idiomatic reversed comparisons
— valid SQL but uncommon in tests.

## Test guidance
- Always test comparisons with the literal on the left side (`WHERE 5 < field`)
- When adding new expression types, verify both operand orderings
- See also: [bind-parameter-comparable-gap](bind-parameter-comparable-gap.md) for
  the related issue of value wrappers lacking required interfaces

## Found in
- sql-query-support (audit round 1, 2026-03-25): `translateComparison` assumed left=ColumnRef, right=value; reversed comparisons threw confusing error

## Updates 2026-04-05

- **Dispatch completeness gap (sql-query-support audit run-001):** Only 2 branches
  existed (field-left/value-right and reversed) for 4 possible operand cases.
  Both-values and both-fields cases fell through to the reversed-comparison
  handler, producing misleading errors (F-R1.dispatch_routing.1.2). Fixed by
  adding explicit dispatch for all 4 cases (field-field, field-value,
  value-field, value-value).
