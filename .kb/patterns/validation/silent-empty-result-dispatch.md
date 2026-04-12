---
type: adversarial-finding
domain: correctness
severity: confirmed
tags: [dispatch, empty-result, silent-failure, data-loss]
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/QueryExecutor.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/IndexRegistry.java"
sources:
  - table-indices-and-queries audit R1, 2026-04-03
---

# Silent Empty Result in Dispatch

## Pattern

Code paths return an empty iterator or null instead of throwing when encountering
an unsupported operation. The caller interprets the empty result as "no matches"
rather than "this operation is not supported," leading to silent data loss.

## Why It Happens

Default/fallback branches in dispatch code (switch expressions, if-else chains)
often return a safe empty value to satisfy the return type. This looks correct —
an empty result is a valid query outcome. But when the empty result means
"unsupported operation" rather than "no matches," the caller cannot distinguish
the two cases.

## Fix

Unsupported operations must throw, not return empty:
```java
// Wrong — silent data loss
default -> Collections.emptyIterator();

// Correct — explicit failure
default -> throw new UnsupportedOperationException(
    "Index type " + index.type() + " does not support " + op);
```

## Test Guidance

- For every dispatch/routing method, test with each enum value and verify:
  - Supported operations return correct results
  - Unsupported operations throw UnsupportedOperationException
- Use sealed types where possible so the compiler catches missing cases

## Scope

Any dispatch/routing code: query planners, compaction strategy selectors, WAL
format selectors, field type handlers.

## Found In

- table-indices-and-queries (audit R1, 2026-04-03): 6 findings
  (F-R1.cb.2.1, dispatch_routing.1.1, 1.3, 1.4, 1.5, CB.4.6)
