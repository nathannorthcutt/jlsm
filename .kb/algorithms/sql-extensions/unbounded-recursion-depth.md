---
title: "Unbounded recursion depth in parsers"
type: adversarial-finding
domain: "security"
severity: "tendency"
applies_to:
  - "modules/jlsm-sql/src/main/java/**"
  - "modules/jlsm-*/src/main/java/**"
research_status: active
last_researched: "2026-03-25"
---

# Unbounded recursion depth in parsers

## What happens
Recursive descent parsers that handle nested expressions (parenthesized groups, nested function calls, nested objects) recurse without tracking depth. Crafted input with hundreds of nesting levels causes `StackOverflowError`, which is an unrecoverable JVM error rather than a handleable exception. This is a denial-of-service vector for any parser that accepts untrusted input.

## Why implementations default to this
Recursive descent is the natural and correct approach for expression grammars with nested precedence. Depth limits add complexity and an arbitrary constant. Standard SQL test inputs rarely nest beyond 5-10 levels, so the gap is invisible in normal testing.

## Test guidance
- For any recursive descent parser, test with nesting depth of 500+ levels
- Verify the parser throws the declared checked exception (e.g., SqlParseException), not StackOverflowError
- Verify moderate nesting (10-20 levels) still works as a regression check
- The depth limit should be a named constant (not magic number) and documented

## Found in
- sql-query-support (audit round 2, 2026-03-25): SqlParser.parsePrimary → parseExpression via LPAREN had no depth limit; fixed with MAX_EXPRESSION_DEPTH=128

## Updates 2026-04-05

- **Three additional unbounded recursion paths (sql-query-support audit run-001):**
  - `parseNot()` self-recursion on NOT chains (F-R1.shared_state.1.1)
  - `parseOr()`/`parseAnd()` while-loop chains (F-R1.shared_state.1.2)
  - `parseFunctionCall()` mutual recursion with `parsePrimary()` (F-R1.shared_state.1.6)
  All three now enforce MAX_EXPRESSION_DEPTH=128 consistently.
- The original fix was incomplete — only `parsePrimary()` LPAREN depth was
  bounded. Three other entry points to the recursive descent were unbounded.
