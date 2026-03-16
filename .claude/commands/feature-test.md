# /feature-test "<feature-slug>" [--unit <WU-N>] [--add-missing] [--escalation]

Writes failing tests from work-plan contracts and brief acceptance criteria.
Idempotent — if testing is complete for the current cycle, reports and stops.
With --unit, scopes to a single work unit. With --add-missing, adds tests for
cases found by the Refactor Agent. With --escalation, reviews a specific test
flagged by the Code Writer as having a contract conflict.

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

### Per-unit path resolution (parallel mode)

If `execution_strategy` is `balanced` or `speed` in feature-level status.md:
```
unit_status = .feature/<slug>/units/WU-<n>/status.md
unit_log = .feature/<slug>/units/WU-<n>/cycle-log.md
```
Else:
```
unit_status = .feature/<slug>/status.md
unit_log = .feature/<slug>/cycle-log.md
```

All status.md reads/writes for stage, substage, cycle tracker → use `unit_status`.
All cycle-log.md appends → use `unit_log`.
Feature-level status.md Work Units table → still update unit status there too.

Display opening header with unit if applicable:
```
───────────────────────────────────────────────
🧪 TEST WRITER · <slug> · Cycle <n><  · WU-<n>>
───────────────────────────────────────────────
```

Determine the current TDD cycle number from the TDD Cycle Tracker.

**Without --add-missing or --escalation:**

First, check status.md substage for `contract-revised`. Also check cycle-log.md
for a recent `contract-revised` entry.

If a contract revision is pending: the Work Planner has revised a contract and
its stub. Tests may now be misaligned with the updated contract.
- Say: "Contract revised by Work Planner — re-verifying tests against updated contract."
- Read the most recent `contract-revised` entry from cycle-log.md to understand
  what changed
- Read the revised contract section from work-plan.md
- Read the affected test file(s)
- If any test assertions no longer match the revised contract: update the tests
  to align with the new contract. Run them to confirm they fail for the right
  reason (not-implemented, not wrong-assertion).
- If tests already match: no changes needed.
- Append `tests-reverified` to cycle-log.md:
```markdown
## <YYYY-MM-DD> — tests-reverified
**Agent:** 🧪 Test Writer
**Cycle:** <n>
**Contract:** <construct name>
**Tests changed:** <list of changed tests, or "none — already aligned">
---
```
- Set status.md substage back to `complete` (testing is done).
- Display:
```
🧪 TEST WRITER · <slug> · contract re-verified
───────────────────────────────────────────────
Contract: <construct name>
Tests changed: <list or "none — already aligned">

Re-run implementation:
  /feature-implement "<slug>"<  --unit WU-<n>>
```
Stop.

If Testing status for the current cycle is `complete` (for this unit if --unit provided):

Read `automation_mode` from status.md.

**If `automation_mode: autonomous`:**
```
🧪 TEST WRITER · <slug>
───────────────────────────────────────────────
Tests are already written for cycle <n><  · WU-<n>>.
Starting implementation  ·  type stop to pause
───────────────────────────────────────────────
```
Invoke /feature-implement "<slug>"<  --unit WU-<n>> as a sub-agent immediately.

**If `automation_mode: manual` (or not set):**
```
🧪 TEST WRITER · <slug>
───────────────────────────────────────────────
Tests are already written for cycle <n><  · WU-<n>>.
Test plan: .feature/<slug>/test-plan.md
All tests verified failing.

  Type **yes**  to proceed to implementation  ·  or: stop
```
If "yes": invoke /feature-implement "<slug>"<  --unit WU-<n>> as a sub-agent immediately.
If "stop": display `Next: /feature-implement "<slug>"` and stop.

If Testing is `in-progress`:
- Check which test files already exist
- Say: "Test writing was in progress for cycle <n> — resuming."
- Skip tests already written; write only missing ones
- If all tests are written but failure verification hasn't run: jump to Step 4

**With --add-missing:**

Find the most recent `missing-tests-found` entry in cycle-log.md.
If none exists: "No missing tests have been reported. Nothing to add."
If found: load only the listed missing test cases and proceed directly to Step 3.

**With --escalation:**

Read the most recent `code-escalation` entry in cycle-log.md.
If none exists: "No escalation has been logged. Nothing to review." Stop.
If found: proceed to the Escalation Review section below.

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

## Step 0b — Token tracking

Run silently: `bash -c 'source .claude/scripts/token-usage.sh && token_checkpoint ".feature/<slug>" "testing"'`

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

## Escalation Review (--escalation only)

Entered when the Code Writer escalates a contract conflict via `--escalation`.

### Step E1 — Load the escalation

Read the most recent `code-escalation` entry from cycle-log.md. Extract:
- The test name and file
- What the test expects
- The constraint from the work plan
- The conflict description
- The escalation count (N of 3)

Read the test file and the relevant contract section from work-plan.md.

### Step E2 — Diagnose

Determine which of three cases applies:

1. **Test is wrong** — the test asserts something not implied by the contract.
   Fix the test to match the contract. Run it to confirm it fails for the right
   reason (not-implemented, not wrong-assertion).

2. **Test is right, contract is ambiguous** — the contract can be read multiple
   ways. Clarify the test (add a comment explaining intent) and adjust the
   assertion if needed. The Code Writer will re-read the test on next run.

3. **Contract itself is wrong** — the work plan constraint contradicts the brief
   or an ADR, or is internally inconsistent. The Test Writer cannot fix this.
   Escalate to the Work Planner — see Step E3.

For cases 1 and 2, after fixing:

Append `test-escalation-resolved` to cycle-log.md:
```markdown
## <YYYY-MM-DD> — test-escalation-resolved
**Agent:** 🧪 Test Writer
**Cycle:** <n>
**Test:** `<test name>` in `<file>`
**Diagnosis:** <test was wrong | contract was ambiguous>
**Fix:** <what changed>
**Escalation count:** <N> of 3
---
```

Update status.md substage → `escalation-resolved`.

Display:
```
🧪 TEST WRITER · escalation resolved · <slug>
───────────────────────────────────────────────
Test: <test name>
Diagnosis: <test was wrong | contract was ambiguous>
Fix: <what changed>

Re-run implementation:
  /feature-implement "<slug>"<  --unit WU-<n>>
```
Stop.

### Step E3 — Escalate to Work Planner

Before escalating, check the escalation counter.

Read cycle-log.md and count `test-to-planner-escalation` entries for the same
contract/construct.

**3rd escalation on the same contract:** hard stop. Do NOT escalate to the
Work Planner again. Instead:
```
🛑  ESCALATION LIMIT · Test Writer → Manual Resolution
───────────────────────────────────────────────
The same contract issue has been escalated 3 times without resolution.
Contract: <construct name>
Work plan section: <reference>

Automatic resolution is not working. Please review the conflict manually:
  1. Check the contract in work-plan.md
  2. Check the acceptance criteria in brief.md
  3. Check any governing ADRs
  4. Fix the work plan, then re-run /feature-test "<slug>"

If the brief itself is wrong, revisit /feature "<slug>".
```
Update status.md substage → `escalation-limit-reached`. Stop.

**Under the limit:** proceed with escalation.

Append `test-to-planner-escalation` to cycle-log.md:
```markdown
## <YYYY-MM-DD> — test-to-planner-escalation
**Agent:** 🧪 Test Writer
**Cycle:** <n>
**Contract:** <construct name>
**Work plan section:** <reference>
**Conflict:** <what the contract says vs. what it should say>
**Brief reference:** <acceptance criterion that contradicts the contract>
**Escalation count:** <N> of 3
---
```

Update status.md substage → `escalated-to-work-planner`.

Display:
```
⚠️  ESCALATION · Test Writer → Work Planner  (<N>/3)
───────────────────────────────────────────────
Contract conflict — the work plan constraint cannot satisfy the brief.
Contract: <construct name>
Problem: <paragraph>
Brief reference: <acceptance criterion>

The Work Planner needs to revise this contract. Run:
  /feature-plan "<slug>"
Then re-run the test → implement cycle for this construct.
```
Stop.

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
- Stage Completion table: Testing row → Est. Tokens `~<N>K` (project-config ~1K +
  brief ~2K + work-plan section ~2K + test files written)

Append `tests-written` entry to cycle-log.md.
Update `.feature/CLAUDE.md`.

---

## Step 6 — Hand off

Read `automation_mode` from status.md.

**Token tracking:** run `bash -c 'source .claude/scripts/token-usage.sh && token_summary ".feature/<slug>" "testing"'`
and capture the output as TOKEN_USAGE. Update the Stage Completion table: Testing
row → Actual Tokens from TOKEN_USAGE.

**If `automation_mode: autonomous`:**

Display the summary then chain immediately without prompting:
```
───────────────────────────────────────────────
🧪 TEST WRITER complete · <slug> · Cycle <n><  · WU-<n>>
  Tokens : <TOKEN_USAGE>
───────────────────────────────────────────────
Tests written and verified failing. Cycle <n><  · WU-<n>>.

Starting implementation  ·  type stop to pause
───────────────────────────────────────────────
```

Then invoke `/feature-implement "<slug>"<  --unit WU-<n>>` as a sub-agent
immediately. Do not wait for user input.

**If `automation_mode: manual` (or not set):**

Display:
```
───────────────────────────────────────────────
🧪 TEST WRITER complete · <slug> · Cycle <n><  · WU-<n>>
  Tokens : <TOKEN_USAGE>
───────────────────────────────────────────────
Tests written and verified failing. Cycle <n><  · WU-<n>>.

───────────────────────────────────────────────
  Type **yes**  ·  or: stop
───────────────────────────────────────────────
```

If "yes": invoke /feature-implement "<slug>"<  --unit WU-<n>> as a sub-agent immediately.
If "stop":
```
When you're ready:
  /feature-implement "<slug>"<  --unit WU-<n>>
```
