# /feature-test "<feature-slug>" [--add-missing]

Writes failing tests from work-plan contracts and brief acceptance criteria.
Idempotent — if testing is complete for the current cycle, reports and stops.
With --add-missing, adds tests for cases found by the Refactor Agent.

---

## Idempotency pre-flight (ALWAYS FIRST)

Read `.feature/<slug>/status.md`.

Determine the current TDD cycle number from the TDD Cycle Tracker.

**Without --add-missing:**

If Testing status for the current cycle is `complete`:
```
Tests are already written for cycle <n>.
Test plan: .feature/<slug>/test-plan.md
All tests verified failing.
Next: /feature-implement "<slug>"
Run /feature-resume "<slug>" to see full status.
```
Stop.

If Testing is `in-progress`:
- Check which test files already exist
- Say: "Test writing was in progress for cycle <n> — resuming."
- Skip tests already written; write only missing ones
- If all tests are written but failure verification hasn't run: jump to Step 4

**With --add-missing:**

Find the most recent `missing-tests-found` entry in cycle-log.md.
If none exists: "No missing tests have been reported. Nothing to add."
If found: load only the listed missing test cases and proceed directly to Step 3.

**If Testing is `not-started`:**
- Verify `.feature/<slug>/work-plan.md` exists. If not: "Run /feature-plan first."
- Set status.md: Testing cycle N → `in-progress`, substage → `planning`
- Proceed to Step 1

---

## Step 1 — Load context

Read:
1. `.feature/project-config.md`
2. `.feature/<slug>/brief.md` — acceptance criteria and error cases
3. `.feature/<slug>/work-plan.md` — contracts

**Do NOT read implementation files.**

If --add-missing: load only the missing test cases from cycle-log.md.

---

## Step 2 — Write the test plan (in chat first)

Update status.md substage → `confirming-plan`.

Display:
```
─────────────────────────────────────────────────────────────
TEST PLAN — <slug> (Cycle <n>)
─────────────────────────────────────────────────────────────
Happy path
  1. test_<name> — <scenario> — covers: <acceptance criterion>
  ...
Error and edge cases
  N. test_<name> — <scenario>
  ...
─────────────────────────────────────────────────────────────
Does this cover the acceptance criteria? Any to add or remove?
─────────────────────────────────────────────────────────────
```

Wait for confirmation. Update status.md substage → `writing-tests` after confirm.

---

## Step 3 — Write test files

Write to the test directory from project-config.md.

**Idempotent:** check whether each test already exists before writing.
Append to existing test files rather than overwriting; do not duplicate tests.

Rules:
- Test names describe behaviour: `test_returns_error_when_input_is_empty`
- Public interface only — no reaching into implementation details
- Mocks/fakes for all external dependencies
- Every test: arrange / act / assert clearly separated

Update status.md substage → `verifying-failures` after writing.

---

## Step 4 — Verify tests fail

Run the test suite.

Expected: all new tests fail with NotImplementedError or import/compile error.

If a test PASSES unexpectedly: investigate — stub may already be implemented,
or test may not be testing the right thing. Do not hand off with passing tests
at this stage.

Capture the full test runner output.

---

## Step 5 — Write test-plan.md and log

Write `.feature/<slug>/test-plan.md` (or append cycle section if it exists):

```markdown
## Cycle <n> — <YYYY-MM-DD>

### Tests Written

| Test name | File | Construct | Acceptance criterion |
|-----------|------|-----------|---------------------|
| test_<n> | <path> | <construct> | <criterion> |

### Failure output (expected)
```
<test runner output>
```

### Coverage
- Acceptance criteria covered: <n>/<total>
- Error cases covered: <n>
- Gaps noted: <any>
```

Update status.md:
- Testing cycle N → `complete`
- TDD Cycle Tracker: Tests written → today
- substage → "tests verified failing"

Append `tests-written` entry to cycle-log.md.
Update `.feature/CLAUDE.md`.

---

## Step 6 — Hand off

```
Tests written and verified failing. Cycle <n>.

Next: /feature-implement "<slug>"
```
