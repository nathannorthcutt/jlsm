# /feature-implement "<feature-slug>" [--unit <WU-N>]

Implements stubs until all tests pass. Idempotent — checks current test state
on startup and resumes from first failing test rather than starting over.
With --unit, scopes to a single work unit.

---

## Idempotency pre-flight (ALWAYS FIRST)

Read `.feature/<slug>/status.md`.

### Work unit resolution (check before anything else)

If `work_units: none` in status.md: ignore `--unit` flag, proceed as normal.

If work units are defined:
- If `--unit` flag provided: scope all steps to that unit only
- If no `--unit` flag: find the unit currently `in-progress`, or the next
  `not-started` unit whose dependencies are all `complete`
- Update opening header to include unit name

Update the active unit status → `in-progress` in the Work Units table.

Display opening header:
```
───────────────────────────────────────────────
⚙️  CODE WRITER · <slug> · Cycle <n><  · WU-<n>>
───────────────────────────────────────────────
```

Determine the current TDD cycle from the TDD Cycle Tracker.

**If Implementation for this cycle is `complete`:**
```
⚙️  CODE WRITER · <slug> · Cycle <n>
───────────────────────────────────────────────
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
- Display opening header and proceed to Step 0

Display opening header:
```
───────────────────────────────────────────────
⚙️  CODE WRITER · <slug> · Cycle <n>
───────────────────────────────────────────────
```

---

## Step 0 — Automation mode

Read `automation_mode` from status.md.

**If `automation_mode` is `autonomous` or `manual`:** skip this step entirely —
the user has already chosen and the choice persists for this feature.

**If `automation_mode` is `not-set`** (first implementation run only):

Display:
```
── How would you like to run the TDD loop? ─────
  ↵  autonomous  — test → implement → refactor cycles run without stopping.
                   Interrupt anytime by typing in the session.
                   I'll pause if I find something that needs your input.

  or type: manual  — I'll stop after each stage and wait for your command.
```

Wait for input:
- Enter or "autonomous": set `automation_mode: autonomous` in status.md
- "manual": set `automation_mode: manual` in status.md

If autonomous, display:
```
Running autonomously. Type stop at any time to pause.
──────────────────────────────────────────────────
```

If manual, display:
```
Manual mode. I'll prompt you at each stage boundary.
──────────────────────────────────────────────────
```

---

## Step 1 — Load context and baseline

Read:
1. `.feature/project-config.md` — always
2. `.feature/<slug>/work-plan.md` — **if work units defined:** read only the
   active unit's section and Contract Definitions. Do NOT load other units.
3. Test files for the active unit only
4. Stub files for the active unit only
5. **Dependency interfaces only** (not implementations): for each completed
   dependency unit, read only the public interface (signatures + contracts from
   work-plan.md). Do not read their implementation files or test files.

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
**Agent:** ⚙️ Code Writer
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
⚠️  ESCALATION · Code Writer → Test Writer
───────────────────────────────────────────────
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

If work units are defined:
- Mark the active unit as `complete` in the Work Units table in status.md
- Check if any blocked units are now unblocked (all their deps are complete)
  → update those units from `blocked` → `not-started`

Update `.feature/CLAUDE.md`.

Read `automation_mode` from status.md.

**If `automation_mode: autonomous`:**

Display the unit progress summary then chain immediately without prompting:
```
───────────────────────────────────────────────
⚙️  CODE WRITER complete · <slug> · Cycle <n><  · WU-<n>>
⏱  Token estimate: ~<N>K
───────────────────────────────────────────────
All tests passing. <n> constructs implemented.

<If work units and more units remain:>
Work unit progress:
  ✓ WU-1 — complete
  → WU-2 — starting refactor  ·  type stop to pause
  ○ WU-3 — blocked (waiting on WU-2)

<If single unit or final unit:>
All units complete — starting refactor  ·  type stop to pause
───────────────────────────────────────────────
```

Then invoke `/feature-refactor "<slug>"<  --unit WU-<n>>` as a sub-agent
immediately. Do not wait for user input.

**If `automation_mode: manual`:**

Display:
```
───────────────────────────────────────────────
⚙️  CODE WRITER complete · <slug> · Cycle <n><  · WU-<n>>
⏱  Token estimate: ~<N>K
   Loaded: work-plan (unit) ~<N>K, <n> test files ~<N>K, <n> stub files ~<N>K
           dependency interfaces ~<N>K
   Wrote:  <n> implementation files ~<N>K
───────────────────────────────────────────────
All tests passing. Cycle <n><  · WU-<n>>.
<n> constructs implemented across <list of files>.

<If work units and more units remain:>
Work unit progress:
  ✓ WU-1: <n> — complete
  → WU-2: <n> — ready (unblocked)
  ○ WU-3: <n> — blocked (waiting on WU-2)

───────────────────────────────────────────────
  ↵  continue to refactor  ·  or type: stop
───────────────────────────────────────────────
```

If Enter: invoke `/feature-refactor "<slug>"<  --unit WU-<n>>` as a sub-agent.
If "stop":
```
When you're ready:
  /feature-refactor "<slug>"<  --unit WU-<n>>
```
