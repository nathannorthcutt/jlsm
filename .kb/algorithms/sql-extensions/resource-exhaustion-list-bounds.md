---
type: adversarial-finding
domain: security
severity: confirmed
tags: [parser, resource-exhaustion, bounds, DoS]
applies_to: ["modules/jlsm-sql/src/main/java/jlsm/sql/SqlParser.java"]
sources:
  - sql-query-support audit run-001, 2026-04-05
---

# Resource Exhaustion via Unbounded List Accumulation

## Pattern

Parser accumulator lists (column lists, ORDER BY clauses, function arguments,
bind parameters) grow without bound. Crafted input with thousands of elements
causes memory exhaustion before any semantic check runs.

## Why It Happens

Parsers naturally use `while (match(COMMA))` loops to collect elements. Without
an explicit size limit, the loop runs until input is exhausted or OOM kills the
process. Semantic validation happens after parsing, too late to prevent the
allocation.

## Fix

Declare maximum sizes and check during accumulation:
```java
private static final int MAX_LIST_SIZE = 1000;
private static final int MAX_PARAMETERS = 10_000;

columns.add(parseColumn());
if (columns.size() > MAX_LIST_SIZE)
    throw new SqlParseException("column list exceeds maximum", pos);
```

## Test Guidance

- For every parser that accumulates elements, test with count exceeding the
  declared maximum
- Verify the checked exception is thrown, not OutOfMemoryError
- Regression-test moderate sizes (50-100 elements) to confirm normal usage works

## Found In

- sql-query-support (audit run-001, 2026-04-05): SqlParser — parseColumnList,
  parseOrderBy, parseFunctionCall, parameterIndex (F-R1.shared_state.1.3, 1.7)
