---
title: "Parallel TDD subagents must run `:check` (not just `:test`) before declaring complete"
type: adversarial-finding
domain: "process"
severity: "confirmed"
tags: [parallel-tdd, coordinator, feature-coordinate, check-vs-test, checkstyle, spotless, sealed-classes, integration-frontier, OOM, balanced-mode, speed-mode]
applies_to:
  - ".claude/skills/feature-coordinate/**"
  - ".claude/rules/tdd-protocol.md"
  - "any project running balanced or speed mode via /feature-coordinate"
related:
  - ".kb/architecture/feature-footprints/implement-encryption-lifecycle--wd-03.md"
  - ".kb/patterns/adversarial-review/integration-frontier-blind-spot.md"
  - ".kb/patterns/testing/stale-test-after-exception-type-tightening.md"
decision_refs: []
research_status: active
last_researched: "2026-04-27"
sources:
  - url: ".feature/implement-encryption-lifecycle--wd-03/cycle-log.md"
    title: "WD-03 cycle-log — coordinator-integration-fix entry (2026-04-27)"
    accessed: "2026-04-27"
    type: "docs"
  - url: ".feature/implement-encryption-lifecycle--wd-03/cycle-log.md"
    title: "WD-03 cycle-log — coordinator-batch-4-complete entry (2026-04-27, OOM lesson)"
    accessed: "2026-04-27"
    type: "docs"
---

# Parallel TDD subagents must run `:check` (not just `:test`) before declaring complete

## Pattern

In balanced or speed mode, the coordinator (`/feature-coordinate`)
dispatches 2+ TDD subagents concurrently. Each subagent autonomously
runs `/feature-test` → `/feature-implement` → `/feature-refactor` and
returns a status. If the subagent dispatch prompt says **"run tests
then declare complete"**, the subagent runs `:test` only — which
compiles + runs the test suite but **skips checkstyle, spotless format
check, and (for sealed-class-anonymous-subclass cases) the full compile
pipeline**. Per-WU `:test` is a fast feedback loop; the coordinator's
`:check` is the truth. Without the discipline, integration breaks land
in the merged tree and surface only at the coordinator's post-batch
verification — by which point fix attribution to a specific subagent is
murky.

## What happens

WD-03 (`implement-encryption-lifecycle--wd-03`) Batch 2 dispatched two
parallel TDD subagents (WU-2 + WU-4). Both reported COMPLETE with all
their `:test` runs green. Coordinator's post-batch `./gradlew
:modules:jlsm-core:check` then surfaced 4 integration breaks:

1. **`KmsPermanentException` was relaxed to `non-sealed`** by the
   planner subagent — looser than spec R83-1 P4-30 mandates. The drift
   compiled and tested cleanly in isolation; only the cross-WU coupling
   to the WD-01-shipped `KmsExceptionHierarchyTest.permanent_isFinal()`
   surfaced it.
2. **Pre-existing test `KmsExceptionHierarchyTest.permanent_isFinal()`
   asserted the now-incorrect shape** — a STALE assertion that needed
   to be updated to `permanent_isSealed()` once the planner's relaxation
   was tightened to `sealed permits KekRevokedException`.
3. **`KmsErrorClassifierTest` had `new KmsPermanentException(...) { }`**
   anonymous subclass. Anonymous subclasses are **illegal under sealing**;
   this is a compile-time error visible only when the full check
   pipeline runs against the sealed-tightened type.
4. **`DekPrunerTest` had 2 unused imports** (`HashSet`,
   `CompactionInputRegistry`) that failed checkstyle but were ignored
   by `:test`.

A separate failure mode showed in Batch 4: running **3 TDD subagents +
their full test suites simultaneously OOM-killed** the larger
adversarial stress tests (e.g.,
`jlsm.core.io.SharedStateAdversarialTest` 50K-thread races, exit 137).
Two subagents reported their final `:check` as failed; coordinator's
single-threaded `./gradlew :modules:jlsm-core:check --rerun` was green.
The OOMs were environmental — JVM count × test memory exceeded the host
budget — not real regressions. But the conservative subagent ESCALATED
on a non-clean check, forcing a coordinator override to confirm
underlying state was clean.

## Why per-`:test` misses these

`:test` is a narrow target: it compiles test sources and runs the test
runner. It deliberately skips the auxiliary verification tasks aggregated
under `check`:

- **checkstyle** (unused imports, formatting violations, sealed-permit
  hygiene)
- **spotless format check** (whitespace, import order, license headers)
- **full main-source compile invariants** that surface only when a sealed
  class's permits set is fully resolved against all consumers (a sibling
  test using `new SealedType() { }` is a compile error, but `:test` may
  short-circuit if test compilation cache is warm)

The subagent's TDD pipeline naturally optimises for `:test` because it's
the inner loop of the test/implement/refactor cycle. The discipline gap
is between the inner-loop verification and the outer-loop merge gate.

## Root cause

Subagent dispatch prompts said **"run tests then declare complete"** —
incomplete coverage of the verify step. The phrasing leaks an
implementation detail (`:test`) into the contract, when the contract
should be **"the merged tree must pass `:check`"**. A subagent reading
"tests pass" satisfies that, runs `:test`, and returns COMPLETE on the
narrow proof.

A second contributing factor: subagents work in their own directory
(`.feature/<slug>/units/WU-N/`) but commit to the shared module tree.
Their `:test` run sees only the WU's tests; the coordinator's `:check`
sees the **merged** tree, where pre-existing tests from sibling WUs
(WD-01 tests in this case) interact with the new construct shapes.

## Mitigation pattern

1. **Coordinator dispatch prompts MUST require
   `./gradlew :modules:<m>:check`** (full check) before COMPLETE return.
   Use the explicit task name, not the abbreviation `:test`.
2. **Coordinator MUST run a single-threaded
   `./gradlew :modules:<m>:check`** post-batch as a backstop, regardless
   of subagent self-reports. This is the load-bearing gate; subagent
   self-reports are advisory.
3. **Subagent verification section** in dispatch prompts should reference
   **checkstyle + spotless explicitly**, not just "tests pass":
   ```
   Verification before COMPLETE return:
   - ./gradlew :modules:jlsm-core:check passes (compile + test +
     checkstyle + spotless)
   - VSCode diagnostics show no errors
   ```
4. **Concurrent-OOM mitigation** — running 3+ concurrent JVMs each
   invoking the full test suite can OOM-kill adversarial stress tests
   (50K-thread races, shared-memory pressure). The coordinator should:
   - **Cap concurrent units to ≤ 2** in heavy projects (jlsm-core falls
     into this bucket — `SharedStateAdversarialTest`,
     `DekVersionRegistry` 50K-iteration races).
   - **Or** dispatch subagents with `:test` only (lighter), and the
     coordinator runs single-threaded `:check` post-batch as the truth.
     This trades subagent self-determination for coordinator-level
     resource control.
5. **Coordinator-level integration verification is the load-bearing
   gate.** Per-subagent `:check` self-reports under concurrent JVM load
   are inherently noisy (OOM-flake risk). A green coordinator-level
   `:check --rerun` post-batch supersedes subagent ESCALATED returns
   when the underlying cause is environmental, not regression.

## Test guidance

When writing dispatch prompts in `/feature-coordinate` (or any analogous
parallel-TDD coordinator):

- The "complete" criterion in the subagent contract must read
  **`:check` passes**, not "tests pass".
- The coordinator's post-batch verification must **always** run
  `:check --rerun` single-threaded, not skip on the strength of subagent
  self-reports.
- For projects with adversarial stress tests that allocate large
  thread/memory budgets, document the **per-batch concurrency cap**
  (`max_parallel_units`) in the project's feature pipeline config.
- Treat any subagent OOM-flake on the final `:check` as a coordinator
  rerun trigger, not a feature failure. The coordinator's serialized
  rerun is the truth.

## Found in

- **`implement-encryption-lifecycle--wd-03`** (Batch 2 + Batch 4,
  2026-04-27): 4 integration breaks slipped past per-WU `:test`
  (sealed-class regression, stale assertion, anonymous-subclass compile
  error, unused imports); 3-way parallel batches OOM-killed adversarial
  stress tests. Both lessons recorded in
  `.feature/implement-encryption-lifecycle--wd-03/cycle-log.md`. See the
  [WD-03 feature footprint](../../architecture/feature-footprints/implement-encryption-lifecycle--wd-03.md)
  for full context.

## Applies to

- Any project running parallel TDD via `/feature-coordinate` in balanced
  or speed mode.
- Any coordinator skill that dispatches autonomous subagents into a
  shared module tree where pre-existing tests from sibling WUs may
  interact with newly-introduced construct shapes.
- Any project where the test target (`:test`) is narrower than the
  merge-gate target (`:check`) — i.e., almost all Gradle/Maven projects
  with checkstyle, spotless, or auxiliary verification tasks.
