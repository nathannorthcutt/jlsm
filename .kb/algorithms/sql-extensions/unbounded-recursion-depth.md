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
