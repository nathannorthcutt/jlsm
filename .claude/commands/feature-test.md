# /feature-test "<feature-slug>" [--unit <WU-N>] [--add-missing]

Writes failing tests from work-plan contracts and brief acceptance criteria.
Idempotent — if testing is complete for the current cycle, reports and stops.
With --unit, scopes to a single work unit. With --add-missing, adds tests for
cases found by the Refactor Agent.

---

## Idempotency pre-flight (ALWAYS FIRST)

Read `.feature/<slug>/status.md`.

### Work unit resolution (check before anything else)

If `work_units: none` in status.md: ignore `--unit` flag, proceed as normal.

If work units are defined:
- If `--unit` flag provided: scope all steps to that unit only
- If no `--unit` flag: find the next unit where status is `not-started`
  (units are `blocked` until all dependencies are `complete`)
  - If all units complete: report and stop
  - If a unit is `in-progress`: resume that unit
  - If next unit is `blocked`: display:
    ```
    🧪 TEST WRITER · <slug>
    ───────────────────────────────────────────────
    WU-<n> is blocked — waiting on: <dependency units>
    Complete those units first, then run:
      /feature-test "<slug>" --unit WU-<n>
    Current unit statuses:
      <table from Work Units status>
    ```
    Stop.

Update the Work Units table in status.md: active unit → `in-progress`.

Display opening header with unit if applicable:
```
───────────────────────────────────────────────
🧪 TEST WRITER · <slug> · Cycle <n><  · WU-<n>>
───────────────────────────────────────────────
```

Determine the current TDD cycle number from the TDD Cycle Tracker.

**Without --add-missing:**

If Testing status for the current cycle is `complete` (for this unit if --unit provided):
```
🧪 TEST WRITER · <slug>
───────────────────────────────────────────────
Tests are already written for cycle <n><  · WU-<n>>.
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
- Display opening header and proceed to Step 1

Display opening header:
```
───────────────────────────────────────────────
🧪 TEST WRITER · <slug> · Cycle <n>
───────────────────────────────────────────────
```

---

## Step 1 — Load context

If work unit is active, load only what is needed for that unit:

1. `.feature/project-config.md` — always
2. `.feature/<slug>/brief.md` — acceptance criteria and error cases
3. `.feature/<slug>/work-plan.md` — **if work units defined:** read only the
   section for the active unit plus the Contract Definitions for its constructs.
   Do NOT load contract sections for other units.

**Do NOT read implementation files.**

If --add-missing: load only the missing test cases from cycle-log.md.

---

## Step 2 — Write the test plan (in chat first)

Update status.md substage → `confirming-plan`.

Display:
```
── Test plan ───────────────────────────────────
TEST PLAN — <slug> (Cycle <n>)
─────────────────────────────────────────────────────────────
Happy path
  1. test_<name> — <scenario> — covers: <acceptance criterion>
  ...
Error and edge cases
  N. test_<name> — <scenario>
  ...
───────────────────────────────────────────────
Does this cover the acceptance criteria? Any to add or remove?
───────────────────────────────────────────────
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

Display:
```
───────────────────────────────────────────────
🧪 TEST WRITER complete · <slug> · Cycle <n><  · WU-<n>>
⏱  Token estimate: ~<N>K
   Loaded: brief ~2K, work-plan (unit section) ~<N>K, project-config ~1K
   Wrote:  <n> test files ~<N>K total
───────────────────────────────────────────────
Tests written and verified failing. Cycle <n><  · WU-<n>>.

───────────────────────────────────────────────
  ↵  continue to implementation  ·  or type: stop
───────────────────────────────────────────────
```

If the user presses Enter or says yes: invoke /feature-implement "<slug>"<  --unit WU-<n>> as a sub-agent immediately.
If the user types stop or no:
```
When you're ready:
  /feature-implement "<slug>"<  --unit WU-<n>>
```
