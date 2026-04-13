---
type: adversarial-finding
domain: validation
severity: confirmed
tags: [overflow, truncation, integer-arithmetic, user-controlled]
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/"
sources:
  - json-only-simd-jsonl audit run-001, 2026-04-12
---

# Integer Overflow and Silent Narrowing in User-Controlled Arithmetic

## Pattern

Integer arithmetic that multiplies or narrows user-controlled values (nesting
depth, indentation level, type width) can silently overflow or produce wrong
results. Two variants:

1. **Multiplication overflow** — `level * spacesPerLevel` wraps to a small or
   negative value via two's-complement overflow, producing incorrect output
   (e.g., wrong indentation) instead of failing with an error.
2. **Silent narrowing** — `Integer.parseInt` on a value that fits in `long` but
   not `int` throws a generic `NumberFormatException` instead of a specific
   overflow error, making diagnostics harder and conflating format errors with
   range errors.

## Why It Happens

Java integer multiplication does not throw on overflow — it silently wraps. When
both operands come from user input (configuration values, parsed data), the
product can exceed `Integer.MAX_VALUE` without any signal. Narrowing via
`Integer.parseInt` conflates "not a number" with "number too large for int",
losing diagnostic information.

## Fix

Use `Math.multiplyExact` for overflow detection in multiplication:

```java
// Wrong — silent wrap on overflow
int indent = level * spacesPerLevel;

// Correct — throws ArithmeticException on overflow
int indent = Math.multiplyExact(level, spacesPerLevel);
```

For narrowing, parse to the wider type first and range-check:

```java
// Wrong — conflates format error and overflow
int value = Integer.parseInt(text);

// Correct — explicit range check with clear error
long wide = Long.parseLong(text);
if (wide < Integer.MIN_VALUE || wide > Integer.MAX_VALUE) {
    throw new ArithmeticException(
        "Value " + wide + " exceeds int range");
}
int value = (int) wide;
```

## Detection

- Contract-boundaries lens: identify integer arithmetic on user-controllable
  inputs
- Adversarial test: pass `Integer.MAX_VALUE` for both level and spacesPerLevel;
  verify ArithmeticException instead of silent wrap
- Adversarial test: pass a string representing `Long.MAX_VALUE` to an int
  parsing path; verify a specific overflow error

## Scope

Applies to any code path where user-controlled integers are multiplied or
narrowed: indentation computation, buffer size calculation, array index
arithmetic, parsed numeric literals.

Affected constructs in json-only-simd-jsonl: JsonWriter.appendIndent
(F-R1.cb.4.6), JsonValueAdapter INT32 path (F-R1.cb.1.5).
