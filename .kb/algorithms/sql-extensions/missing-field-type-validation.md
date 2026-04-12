---
title: "Missing field-type validation"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-sql/src/main/java/**"
  - "modules/jlsm-table/src/main/java/**"
research_status: active
last_researched: "2026-03-25"
---

# Missing field-type validation

## What happens
Translation or query-building methods validate that a field name exists in the schema but do not check whether the literal's Java type is compatible with the field's declared `FieldType`. For example, `WHERE age = 'Alice'` on an INT32 field creates a predicate with a String value that will fail at execution time with a confusing type error instead of a clear validation error at translation time.

## Why implementations default to this
Field existence checks (`schema.fieldIndex(name) >= 0`) are the obvious first validation step and are easy to implement. Type compatibility requires resolving the `FieldType`, mapping each AST value type to compatible field types, and handling edge cases (bind parameters, timestamps). The extra validation code is substantial and tests typically use matching types, so the gap is invisible.

## Test guidance
- For every comparison operator and field type, test with a mismatched literal type (string against numeric, boolean against string, numeric against boolean)
- Test BETWEEN with mismatched bound types against the field type
- Test function calls (MATCH, VECTOR_DISTANCE) with fields of the wrong type
- Verify that bind parameters are exempt from type checking (resolved at execution time)
- After adding type validation, regression-test all correct type combinations

## Found in
- sql-query-support (audit round 2, 2026-03-25): translateComparison, translateBetween, translateFunctionCall (MATCH), translateVectorDistance all accepted any literal type for any field

## Updates 2026-04-05

- **Integer range validation (sql-query-support audit run-001):** Even after
  type compatibility was added, integer types accepted any Number without range
  checking. INT8 field accepted 999999 (F-R1.cb.2.6). Fixed by splitting
  combined INT8/INT16/INT32/INT64 case into individual range-checked cases.
- **ArrayType as vector field (F-R1.cb.2.7):** ArrayType accepted as vector
  field without element-type check. Fixed by requiring FLOAT16/FLOAT32 element type.
- Field-type validation has layers: existence → compatibility → range. All must
  be tested independently.
