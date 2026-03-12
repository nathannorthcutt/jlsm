# /feature-implement "<feature-slug>"

Implements stubs until all tests pass. Idempotent — checks current test state
on startup and resumes from first failing test rather than starting over.

---

## Idempotency pre-flight (ALWAYS FIRST)

Read `.feature/<slug>/status.md`.

Determine the current TDD cycle from the TDD Cycle Tracker.

**If Implementation for this cycle is `complete`:**
```
Implementation is already complete for cycle <n>.
All tests were passing as of: <date from status.md>
Next: /feature-refactor "<slug>"
Run /feature-resume "<slug>" to see full status.
```
Stop.

**If Implementation is `in-progress`:**
- Say: "Implementation was in progress for cycle <n> — checking current test state."
- Run the test suite immediately to see what is passing and what is failing
- Report: "<n> passing, <n> failing — resuming from first failing test"
- Jump to Step 2 (implement in order, skipping constructs whose tests already pass)

**If Implementation is `not-started`:**
- Verify `.feature/<slug>/test-plan.md` exists. If not: "Run /feature-test first."
- Set status.md: Implementation cycle N → `in-progress`, substage → `loading-context`
- Proceed to Step 1

---

## Step 1 — Load context and baseline

Read:
1. `.feature/project-config.md`
2. `.feature/<slug>/work-plan.md` — contracts and implementation order
3. `.feature/<slug>/test-plan.md` — test names and constructs
4. Stub files listed in work-plan.md
5. Test files (to understand structure, not to anticipate implementation)

Run the test suite. Confirm all new tests are currently failing.
Update status.md substage → `implementing`.

---

## Step 2 — Implement in order

Follow the implementation order from work-plan.md.
Skip any construct whose tests are already passing (idempotent re-entry).

For each construct:
1. Read its contract (docstring/comment in the stub)
2. Read the relevant test(s) to understand what is expected
3. Implement only what the contract specifies
4. Run the tests for this construct — confirm they pass before moving on
5. Update status.md substage → "implemented: <construct name>" after each passing unit

If a test fails unexpectedly after implementation: see Escalation Protocol.

**Implementation principles:**
- Minimum correct implementation — no gold-plating
- Follow project-config.md conventions exactly
- No public methods beyond what contracts define
- If typed language: use the types from stub signatures

---

## Escalation protocol

If a test cannot be satisfied given the work plan's constraints:

1. Append `code-escalation` to cycle-log.md:
```markdown
## <YYYY-MM-DD> — code-escalation
**Agent:** Code Writer
**Cycle:** <n>
**Test:** `<test name>` in `<file>`
**What the test expects:** <exact assertion>
**Constraint from work plan:** <section reference>
**Conflict:** <paragraph>
---
```

2. Update status.md substage → `escalated-to-test-writer`

3. Say:
```
Contract conflict — cannot proceed without Test Writer input.
Test: <test name>
Problem: <paragraph>
Work plan reference: <section>

Run /feature-test "<slug>" — the Test Writer will review and either
adjust the test or escalate the contract change to the Work Planner.
```
Stop.

---

## Step 3 — Final verification

Run the full test suite. Confirm:
- All new tests pass
- No previously passing tests broken
- No test files were modified

---

## Step 4 — Log and hand off

Update status.md:
- Implementation cycle N → `complete`
- TDD Cycle Tracker: Tests passing → today
- substage → "all tests passing"

Append `implemented` entry to cycle-log.md.
Update `.feature/CLAUDE.md`.

Say:
```
All tests passing. Cycle <n>.
<n> constructs implemented across <list of files>.

Next: /feature-refactor "<slug>"
```
