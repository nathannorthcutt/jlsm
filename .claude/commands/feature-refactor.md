# /feature-refactor "<feature-slug>" [--unit <WU-N>]

Reviews and refactors implemented code. Tracks cycles, warns at cycle 3,
checkpoints with the user at cycle 5. Idempotent — resumes from the review
checklist item where it stopped if interrupted.
With --unit, scopes to a single work unit. Integration tests (2f) run only
after the final unit is refactored.

---

## Idempotency pre-flight (ALWAYS FIRST)

Read `.feature/<slug>/status.md`.

### Work unit resolution (check before anything else)

If `work_units: none` in status.md: ignore `--unit` flag, proceed as normal.

If work units are defined:
- If `--unit` flag provided: scope refactor to that unit only
- If no `--unit` flag: find the unit whose implementation is `complete` but
  refactor has not run (check Work Units table and cycle-log.md)
- **Integration tests (Step 2f):** run ONLY when the final work unit is being
  refactored (all other units are `complete`). Skip 2f for intermediate units
  and note: "Integration tests deferred — not the final work unit."

Display opening header with unit if applicable:
```
───────────────────────────────────────────────
✨ REFACTOR AGENT · <slug> · Cycle <n><  · WU-<n>>
───────────────────────────────────────────────
```

Determine the current TDD cycle from the TDD Cycle Tracker.
Count completed `refactor-complete` entries in cycle-log.md to get cycle number.

**If cycle number would be 6 or more:**
Stop and ask: "We've completed 5 refactor cycles and you approved continuation.
Continue with cycle 6?
  1  yes — proceed
  2  no — stop here
  3  summarise remaining issues first
  Type 1, 2, or 3."
Wait for explicit response before proceeding.

**If Refactor for the current cycle is `complete`:**
```
✨ REFACTOR AGENT · <slug> · Cycle <n>
───────────────────────────────────────────────
Refactor cycle <n> is already complete.
Run /feature-resume "<slug>" to see full status.
```
Stop.

**If Refactor is `in-progress`:**
- Read the substage from status.md (which checklist item was in progress)
- Run the full test suite to confirm current state
- Say: "Refactor was in progress at: <substage>. Resuming from there."
- Jump to the appropriate checklist item in this order:
  2a coding-standards → 2b duplication → 2c security → 2d performance →
  2e missing-tests → 2f integration-tests → Step 4 final-lint

**If Refactor is `not-started`:**
- Verify cycle-log.md has an `implemented` entry for this cycle.
  If not: "Run /feature-implement first."
- Set status.md: Refactor cycle N → `in-progress`, substage → `loading-context`
- Display opening header and proceed to Step 1

Display opening header:
```
───────────────────────────────────────────────
✨ REFACTOR AGENT · <slug> · Cycle <n>
───────────────────────────────────────────────
```

---

## Step 1 — Load context

Read:
1. `.feature/project-config.md` — standards, security requirements, run commands
2. `CONTRIBUTING.md` (or `docs/coding-standards.md` if it exists) — full standards
3. `.feature/<slug>/work-plan.md` — contracts (intent vs. implementation check)
4. All implementation files changed during this feature
5. All test files for this feature

Run the full test suite to confirm current baseline (all passing).
If tests are failing: stop and say "Tests are failing before refactor began.
Run /feature-implement to restore a passing state first."

---

## Step 2 — Review checklist (work through in order, update substage after each)

Update status.md substage as each item begins so a crash is resumable.

### 2a — Coding standards
`status.md substage → "refactor: coding-standards"`
`Display: ── Coding standards ───────────────────────`
- Naming conventions per project-config.md
- Run formatter — commit all changes
- Update docstrings on any stub that now has an implementation
- Remove dead code (unused imports, variables, params)
- Complete type annotations if the language is typed
Run tests after changes. Stop if any fail.

### 2b — Duplication
`status.md substage → "refactor: duplication"`
`Display: ── Duplication ────────────────────────────`
- Logic repeated in 3+ places → extract shared utility
- Do not over-abstract — only extract when benefit is clear
Run tests after changes.

### 2c — Security
`status.md substage → "refactor: security"`
`Display: ── Security ───────────────────────────────`
Apply project-config.md security requirements plus:
- Input validation on all public methods
- Parameterised DB queries / shell commands / templates
- No hardcoded secrets, keys, or tokens
- Error messages don't leak internal stack details
- Flag any eval, exec, unsafe pointer arithmetic
Run tests after changes.

### 2d — Performance
`status.md substage → "refactor: performance"`
`Display: ── Performance ─────────────────────────────`
- O(n²) where O(n) clearly exists → fix if behaviour-preserving
- Unnecessary allocations in loops → fix
- N+1 query patterns → flag
- No micro-optimisation — only obvious, high-impact fixes
Run tests after changes.

**Structural escalation (both modes):** If 2c or 2d reveals an issue requiring
interface changes, new dependencies, or contract renegotiation — pause and
surface it regardless of automation_mode:
```
── Refactor paused — structural issue found ─────
This issue may affect other units or require interface changes:

  <description of issue and why it can't be self-contained>

  ↵  I'll handle it, continue
  or type: stop  to review before continuing
```
Wait for input. If Enter: continue the refactor and resume chaining if autonomous.
If "stop": complete the current checklist item, write the log entry, then stop.

### 2e — Missing tests
`status.md substage → "refactor: missing-tests"`
`Display: ── Missing tests ───────────────────────────`

For each construct, ask:
- Happy path tested? ✓/✗
- All documented error conditions tested? ✓/✗
- Boundary values (empty, max, null/nil)? ✓/✗
- Concurrency / ordering dependencies? ✓/✗
- Cases the implementation handles that the Work Planner didn't anticipate? ✓/✗

If missing tests are found → see Step 3 (escalate, do not continue).

### 2f — Integration tests
`status.md substage → "refactor: integration-tests"`
`Display: ── Integration tests ───────────────────────`

Only run this step if all unit tests are passing and no missing-test escalation
was triggered in 2e.

Read project-config.md for the `Run integration tests` command.

**If an integration test command is configured:**

Run it. Three possible outcomes:

1. **All pass** — note the result and continue to Step 4.
   ```
   Integration tests: <n> passing ✓
   ```

2. **Some fail** — determine whether the failure is related to this feature's changes:
   - Read the failure output carefully
   - Check whether the failing tests touch constructs listed in work-plan.md
   - If YES (related to this feature): treat as a bug. Fix the implementation,
     re-run unit tests to confirm they still pass, re-run integration tests.
     Append `integration-test-failure` to cycle-log.md describing what failed
     and what was fixed.
   - If NO (pre-existing failure unrelated to this feature): note it but do not
     fix it. Append a warning to cycle-log.md:
     ```
     ## <YYYY-MM-DD> — integration-test-warning
     **Agent:** Refactor Agent
     **Note:** Pre-existing integration test failure unrelated to this feature.
     **Failing tests:** <list>
     **Assessment:** Not caused by changes in this feature — deferred.
     ---
     ```
     Tell the user and continue to Step 4.

3. **Cannot run** (command fails to execute, env not configured, etc.):
   Note it and continue. Do not block the refactor on environment issues.
   ```
   Integration tests could not be run: <reason>
   This will need to be verified manually before merging.
   ```

**If no integration test command is configured in project-config.md:**

Check whether the project appears to have integration tests by scanning for
directories or files commonly used for them:
- `tests/integration/`, `test/integration/`, `e2e/`, `spec/integration/`
- Files named `*_integration_test.*`, `*.e2e.*`, `*_e2e_test.*`

If found:
```
Integration test files found at <path> but no run command is configured
in project-config.md. Add one with /feature-init to enable automatic
integration test runs during refactor.

Manual check needed before merging: <path>
```

If not found: skip silently and continue.

---

## Step 3 — Missing tests escalation

If missing tests found, STOP further refactoring immediately.

Update status.md substage → `escalated-missing-tests`.

Append `missing-tests-found` to cycle-log.md:
```markdown
## <YYYY-MM-DD> — missing-tests-found
**Agent:** ✨ Refactor Agent
**Cycle:** <n>
**Missing cases:**
- `test_<name>` — <one sentence: scenario> | Construct: <name> | Why: <reason>
- ...
---
```

Display:
```
⚠️  MISSING TESTS · Refactor Agent → Test Writer
───────────────────────────────────────────────
Found <n> missing test case(s). Handing to Test Writer before continuing.

Missing:
  1. <test name> — <scenario>
  2. ...

Run: /feature-test "<slug>" --add-missing
Then: /feature-implement "<slug>"  (to make them pass)
Then: /feature-refactor "<slug>"   (to resume this review)
```
Stop.

---

## Step 4 — Run final lint and format

Update status.md substage → `refactor: final-lint`.

- Run formatter
- Run linter
- Run type checker (if applicable)
- Fix remaining issues
- Run full test suite one final time — must be all passing

---

## Step 5 — Cycle limit checkpoints

**Cycle 3:**
```
This is refactor cycle 3. Normal for non-trivial features — just flagging it.
```

**Cycle 5:**
Update status.md substage → `cycle-5-checkpoint`.
```
We've completed 5 refactor cycles. Before starting another:

Remaining concerns: <list or "none">
Missing tests added this cycle: <n>

Options:
  1. Continue — approve cycle 6
  2. Stop — mark complete with noted limitations
  3. Summarise — list remaining issues without fixing
```
Wait for explicit response. Record response in cycle-log.md.

---

## Step 6 — Log and close

Update status.md:
- Refactor cycle N → `complete`
- TDD Cycle Tracker: Refactor done → today, Missing tests → <n found>
- substage → "refactor complete"

If no further cycles needed: update status.md Refactor stage → `complete`.

Append `refactor-complete` to cycle-log.md:
```markdown
## <YYYY-MM-DD> — refactor-complete
**Agent:** ✨ Refactor Agent
**Cycle:** <n>
**Changes:** <bullet list>
**Security findings:** <none | list>
**Performance findings:** <none | list>
**Missing tests found:** <n>
**Unit tests:** <n> passing, 0 failing
**Integration tests:** <n passing | skipped — not configured | pre-existing failures noted>
**Token estimate:** ~<N>K
---
```

Update `.feature/CLAUDE.md`.

Read `automation_mode` from status.md.

**If more work units remain (not the final unit):**

— Autonomous:
```
───────────────────────────────────────────────
✨ REFACTOR AGENT complete · <slug> · Cycle <n> · WU-<n>
───────────────────────────────────────────────
Work unit progress:
  ✓ WU-1 — complete
  → WU-2 — starting tests  ·  type stop to pause
  ○ WU-3 — blocked (waiting on WU-2)
───────────────────────────────────────────────
```
Invoke `/feature-test "<slug>" --unit WU-<next>` immediately.

— Manual:
```
───────────────────────────────────────────────
✨ REFACTOR AGENT complete · <slug> · Cycle <n> · WU-<n>
───────────────────────────────────────────────
  ↵  start tests for WU-<next>  ·  or type: stop
```
If Enter: invoke `/feature-test "<slug>" --unit WU-<next>`.
If "stop": display the manual command and stop.

**If this is the final (or only) unit and refactor is clean:**

— Autonomous:
```
───────────────────────────────────────────────
✨ REFACTOR AGENT complete · <slug> · Cycle <n>
───────────────────────────────────────────────
All units complete. Starting PR draft  ·  type stop to pause
───────────────────────────────────────────────
```
Invoke `/feature-pr "<slug>"` immediately.

— Manual:
```
───────────────────────────────────────────────
✨ REFACTOR AGENT complete · <slug> · Cycle <n>
⏱  Token estimate: ~<N>K
───────────────────────────────────────────────
Refactor cycle <n> complete. <n> tests passing.
Feature is ready for review.

  ↵  draft the PR now  ·  or type: stop
```
If Enter: invoke `/feature-pr "<slug>"`.
If "stop": display manual commands and stop.

**If missing tests escalation triggered (either mode):**

```
───────────────────────────────────────────────
✨ REFACTOR AGENT complete · <slug> · Cycle <n>
───────────────────────────────────────────────
Missing tests found — handing to Test Writer.
<If autonomous:> Pausing — missing tests require your review.

  ↵  add missing tests now  ·  or type: stop
```
Wait for input regardless of automation_mode — missing tests are always a
human checkpoint. If Enter: invoke `/feature-test "<slug>" --add-missing`.
If "stop": display the manual sequence and stop.
