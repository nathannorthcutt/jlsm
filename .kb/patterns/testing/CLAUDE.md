# patterns / testing
*Topic: patterns*
*Tags: testing, test-pollution, isolation, determinism, clock-injection, assertThrows, exception-types, audit-cleanup, test-triage, STALE, REGRESSION*

> Anti-patterns and fix patterns for test reliability, determinism, and audit-driven test triage.

## Contents

| Subject | File | Description | Added |
|---------|------|-------------|-------|
| wall-clock-dependency-in-duration-logic | [wall-clock-dependency-in-duration-logic.md](wall-clock-dependency-in-duration-logic.md) | Direct `Instant.now()` in duration logic; fix with injectable `Clock` and single-capture | 2026-04-06 |
| static-mutable-state-test-pollution | [static-mutable-state-test-pollution.md](static-mutable-state-test-pollution.md) | Static `ConcurrentHashMap` registries leaking across tests; fix with `AutoCloseable` or instance-scoped | 2026-04-06 |
| stale-test-after-exception-type-tightening | [stale-test-after-exception-type-tightening.md](stale-test-after-exception-type-tightening.md) | Pre-existing test asserts old broad exception type after audit narrowed it; classify STALE vs REGRESSION, update assertion + cite finding ID | 2026-04-23 |

## Comparison Summary

The three entries cover three distinct causes of test-suite drift:
- **Wall-clock dependency** — flakiness from non-deterministic inputs (time).
- **Static mutable state pollution** — cross-test contamination from shared JVM state.
- **Stale assertion after exception-type tightening** — audit-driven contract
  narrowing leaves pre-existing tests asserting the old broad type. Triage
  cost is small but classification discipline (STALE vs REGRESSION) is
  load-bearing: misclassify a REGRESSION as STALE and you silence a real bug.

## Recommended Reading Order
1. [wall-clock-dependency-in-duration-logic.md](wall-clock-dependency-in-duration-logic.md) — start here if you have flaky tests at time boundaries
2. [static-mutable-state-test-pollution.md](static-mutable-state-test-pollution.md) — next if tests pass individually but fail in suite runs
3. [stale-test-after-exception-type-tightening.md](stale-test-after-exception-type-tightening.md) — consult during post-audit test-cleanup phases
