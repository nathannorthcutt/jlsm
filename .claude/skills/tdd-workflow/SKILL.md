---
name: tdd-workflow
description: >
  This skill should be used when the user asks to implement a new feature, add new
  functionality, create a new class or method, or otherwise build something new in
  the codebase. It orchestrates a four-stage Test-Driven Development cycle: Architect
  → Test Designer → Code Writer → Code Cleanup. Each stage is run by a dedicated
  sub-agent with a constrained role. Use this skill whenever the user says things
  like "implement X", "add X feature", "create a new X", "build X", or "I need X
  to work".
version: 1.0.0
---

You are orchestrating a four-stage TDD cycle. Follow these steps precisely. Do not skip stages. Do not combine stages.

---

## Step 0 — Gather context

Before launching any agent, confirm:
1. The **feature description** — what exactly needs to be built.
2. The **relevant module(s)** — which Gradle subproject(s) are affected (e.g., `modules/jlsm-core`, `modules/jlsm-vector`).
3. The **relevant existing source files** — read the interfaces, existing implementations, and `module-info.java` files the architect and test designer will need.

If the feature description is ambiguous, ask the user one focused clarifying question before proceeding.

---

## Stage 1 — Architect

Launch the `tdd-architect` agent with:
- The feature description
- Paths and contents of relevant existing source files

**Evaluate the result:**
- If `ARCHITECT_RESULT: NO_CHANGES_REQUIRED` — note the reason, record that no stubs were created, and continue to Stage 2.
- If `ARCHITECT_RESULT: CHANGES_MADE` — record the list of created/modified files. These stubs are the inputs to Stage 2.
- If the architect created any test files, halt and report an error: the architect violated its constraints.

---

## Stage 2 — Test Designer

Launch the `tdd-test-designer` agent with:
- The feature description
- The architect's result (either the stub list or the `NO_CHANGES_REQUIRED` note)
- Paths and contents of relevant source files (including the new stubs if any)

**Evaluate the result:**
- If `TEST_DESIGNER_RESULT: TESTS_WRITTEN` and the failing test output confirms failures — record the test file(s) and proceed to Stage 3.
- If any test **passed** before implementation — halt and report to the user. The test designer must fix this before the cycle can continue.
- If the test designer could not compile — investigate whether the architect's stubs are missing something, and if so re-run Stage 1 with additional context.

---

## Stage 3 — Code Writer (with feedback loop)

Launch the `tdd-code-writer` agent with:
- The feature description
- The architect's stub files
- The test designer's test files
- The failing test output from Stage 2

**Evaluate the result:**

### If `CODE_WRITER_RESULT: SUCCESS`
Record the list of files changed and the passing test output. Proceed to Stage 4.

### If `CODE_WRITER_RESULT: TEST_ERROR`
This triggers a feedback loop between the test designer and code writer:

1. Launch `tdd-test-designer` again, providing:
   - The original feature description
   - The code writer's `TEST_ERROR` report (affected test, reason, suggested correction)
   - Ask it to revise only the affected test(s)
2. Once revised tests are confirmed failing, re-launch `tdd-code-writer`.
3. **Maximum 3 iterations** of this loop (3 × code-writer launches total). If tests are still unresolved after 3 iterations, halt and report to the user with the full context: feature description, all agent outputs, and a summary of what went wrong.

---

## Stage 4 — Code Cleanup

Launch the `tdd-code-cleanup` agent with:
- The feature description
- All files created or modified across Stages 1–3
- The passing test output from Stage 3

**Evaluate the result:**
- `CLEANUP_RESULT: NO_CHANGES_REQUIRED` — note the reason.
- `CLEANUP_RESULT: CHANGES_MADE` — record the files changed and confirm tests still pass.

---

## Final Report

After all stages complete, summarise for the user:

```
TDD cycle complete for: <feature name>

Stage 1 — Architect:   <N files created | no changes required>
Stage 2 — Test Designer: <N tests written across M files>
Stage 3 — Code Writer:  <N files implemented | iterations: K>
Stage 4 — Code Cleanup: <N files refactored | no changes required>

All tests passing. Final test count: <N>
```

If any stage was halted due to an error, replace the summary with a full error report including all agent outputs so the user can diagnose and continue manually.
