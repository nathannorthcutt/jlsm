---
title: "Unchecked parse exception propagation"
type: adversarial-finding
domain: "data-integrity"
severity: "tendency"
applies_to:
  - "modules/jlsm-sql/src/main/java/jlsm/sql/SqlTranslator.java"
  - "modules/jlsm-*/src/main/**"
research_status: active
last_researched: "2026-03-25"
---

# Unchecked parse exception propagation

## What happens
Internal parsing methods (e.g., `Integer.parseInt`, `Long.parseLong`,
`Double.parseDouble`) throw unchecked `NumberFormatException` on overflow or
malformed input. When these calls are nested inside a method declared to throw
a checked exception (`SqlParseException`), the unchecked exception bypasses the
checked exception contract and propagates to the caller as an unexpected runtime
error.

## Why implementations default to this
The inner `parseInt` → `parseLong` fallback pattern correctly catches the first
overflow, creating a false sense of completeness. The outer `parseLong` overflow
is a rarer edge case (requires 20+ digit numbers) that doesn't appear in standard
test data.

## Test guidance
- For any method that parses user-supplied strings into numbers, test with values
  exceeding the maximum of every numeric type in the fallback chain
- Verify the method throws the declared checked exception, not an unchecked one
- Test with boundary values: `Long.MAX_VALUE`, `Long.MAX_VALUE + 1` (as string),
  and strings with 30+ digits

## Found in
- sql-query-support (audit round 1, 2026-03-25): `parseNumber` caught `Integer` overflow but not `Long` overflow — 20-digit numbers threw unchecked `NumberFormatException`
