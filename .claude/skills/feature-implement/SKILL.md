---
description: "Implement stubs until all tests pass"
argument-hint: "<feature-slug> [--unit <WU-N>]"
---

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

Display opening header:
```
───────────────────────────────────────────────
⚙️  CODE WRITER · <slug> · Cycle <n><  · WU-<n>>
───────────────────────────────────────────────
```

Determine the current TDD cycle from the TDD Cycle Tracker.

**If substage is `escalation-resolved`:**
The Test Writer has resolved a contract conflict. Resume implementation.
- Say: "Escalation resolved by Test Writer — checking current test state."
- Read the most recent `test-escalation-resolved` entry from cycle-log.md to
  understand what changed
- Run the test suite to see what is passing and what is failing
- Set status.md substage → `resuming-after-escalation`
- Jump to Step 2 (implement in order, skipping constructs whose tests already pass)

**If Implementation for this cycle is `complete`:**

Read `automation_mode` from status.md.

**If `automation_mode: autonomous`:**
```
⚙️  CODE WRITER · <slug> · Cycle <n>
───────────────────────────────────────────────
Implementation is already complete for cycle <n>.
Starting refactor  ·  type stop to pause
───────────────────────────────────────────────
```
Invoke /feature-refactor "<slug>"<  --unit WU-<n>> as a sub-agent immediately.

**If `automation_mode: manual` (or not set):**
```
⚙️  CODE WRITER · <slug> · Cycle <n>
───────────────────────────────────────────────
Implementation is already complete for cycle <n>.
All tests were passing as of: <date from status.md>

```

**In parallel mode (`execution_strategy: balanced | speed`):** do NOT call
AskUserQuestion — chain to `/feature-refactor "<slug>" --unit WU-<n>`
immediately.

**Sequential/cost mode:**
Use AskUserQuestion with two options:
- "Proceed to refactor"
- "Stop"

If "Proceed to refactor": invoke /feature-refactor "<slug>"<  --unit WU-<n>> as a sub-agent immediately.
If "Stop": display `Next: /feature-refactor "<slug>"` and stop.

**If Implementation is `in-progress`:**
- Say: "Implementation was in progress for cycle <n> — checking status."
- Read status.md's substage field. It records `"implemented: <construct name>"`
  after each successful construct. Use this to determine which constructs are
  already done vs remaining — this is cheaper than running the full test suite.
- Run tests ONLY for constructs not yet marked `implemented` in status.md.
  If only 1-2 constructs remain, run their tests individually instead of the
  full suite.
- Report: "<n> constructs complete, <n> remaining — resuming from <next construct>"
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

Read `automation_mode` from status.md. The mode is set during `/feature-plan`
and persists for the lifetime of this feature.

**If `automation_mode` is `autonomous` or `manual`:** continue — no prompt needed.

**If `automation_mode` is `not-set`** (fallback — should not occur if `/feature-plan`
ran normally, but handle it gracefully):

Display:
```
── Automation mode was not set during planning ─
  auto    — test → implement → refactor cycles run without stopping.
            I'll pause if I find something that needs your input.

  manual  — I'll stop after each stage and wait for your command.

```

Use AskUserQuestion with two options:
- "Auto" (description: cycles run without stopping, pauses only if input needed)
- "Manual" (description: stop after each stage, wait for command)

- If "Auto": set `automation_mode: autonomous` in status.md
- If "Manual": set `automation_mode: manual` in status.md

---

## Step 0a — Progress tracking

**Skip TodoWrite if `execution_strategy` is `balanced` or `speed`.** In
parallel mode, the coordinator owns TodoWrite — subagents must not call it
or they will overwrite the coordinator's checklist.

**In sequential/cost mode:** use TodoWrite to show progress in the Claude Code
UI (visible via Ctrl+T). Each TodoWrite call replaces the full list — always
include all items.

**Pipeline context:** Include the full feature lifecycle as top-level items.
Mark earlier stages `completed`, current `in_progress`, later `pending`.

**Per-construct granularity:** After loading context (Step 1), add an item for
each construct from the work plan's implementation order. Use `activeForm` to
show which file is being edited and test pass status.

Example checklist during implementation:
```json
[
  {"id": "pipeline-scoping", "content": "Scoping", "status": "completed", "priority": "medium"},
  {"id": "pipeline-domains", "content": "Domain analysis", "status": "completed", "priority": "medium"},
  {"id": "pipeline-planning", "content": "Work planning", "status": "completed", "priority": "medium"},
  {"id": "pipeline-testing", "content": "Test writing", "status": "completed", "priority": "medium"},
  {"id": "pipeline-implementation", "content": "Implementation", "status": "in_progress", "priority": "high",
   "activeForm": "Implementing 2 of 4 constructs"},
  {"id": "pipeline-refactor", "content": "Refactor & review", "status": "pending", "priority": "medium"},
  {"id": "pipeline-pr", "content": "PR draft", "status": "pending", "priority": "medium"},
  {"id": "impl-token-bucket", "content": "TokenBucket — src/rate.py", "status": "completed", "priority": "high"},
  {"id": "impl-rate-middleware", "content": "RateLimitMiddleware — src/middleware.py", "status": "in_progress",
   "priority": "high", "activeForm": "Editing src/middleware.py — 2/3 tests passing"},
  {"id": "impl-config-loader", "content": "ConfigLoader — src/config.py", "status": "pending", "priority": "high"},
  {"id": "verify", "content": "Final verification — all tests passing", "status": "pending", "priority": "high"},
  {"id": "handoff", "content": "Hand off to refactor", "status": "pending", "priority": "medium"}
]
```

Update the checklist after each construct's tests pass — mark the construct
`completed` and move to the next.

---

---

## Step 1 — Load context and baseline

**Displacement awareness:** If `work-plan.md` contains a `## Removal Work`
section, this feature includes code removal alongside new implementation.
Display in the opening header:
```
⚠️  This feature includes removal work (<N> constructs to remove).
    Removal tests must pass alongside addition tests.
```
Removal work units (RW-1, RW-2, ...) are sequenced in the Implementation
Order after the addition work that replaces them. When implementing a removal
work unit, delete the code that implements the displaced behavior and verify
the removal tests pass.

Read:
1. `.feature/project-config.md` — always
2. `.feature/<slug>/work-plan.md` — **if work units defined:** read only the
   active unit's section and Contract Definitions. Do NOT load other units.
3. `.feature/<slug>/test-plan.md` — the test summary (test names, files,
   constructs, acceptance criteria, expected failures). Use this to understand
   what each test expects. Do NOT read individual test files for context —
   test-plan.md has everything needed to understand intent.
4. Stub files for the active unit only
5. **Dependency interfaces only** (not implementations): for each completed
   dependency unit, read only the public interface (signatures + contracts from
   work-plan.md). Do not read their implementation files or test files.

Run the test suite (5-minute Bash timeout per tdd-protocol). If the suite times
out: run individual test methods to isolate which test is hanging. For hanging
tests, check for missing @Timeout, blocking waits without duration, or deadlocks
in test setup. If a test from a prior work unit hangs, skip it for regression
checking and note it in the output. If the current work unit's test hangs, the
implementation may have introduced a deadlock — check lock ordering before
retrying. Do not retry the full suite without isolating first.
Confirm all new tests are currently failing.
Update status.md substage → `implementing`.

---

## Step 2 — Implement in order

Follow the implementation order from work-plan.md.
Skip any construct whose tests are already passing (idempotent re-entry).

For each construct:
1. Read its contract (docstring/comment in the stub)
2. Check test-plan.md for this construct's acceptance criteria (already loaded
   in Step 1). Only read the actual test file if a test fails unexpectedly and
   you need to understand the assertion details for debugging.
3. Before any Edit, re-read the target file at the lines being changed — prior
   constructs may have modified it. Do not rely on earlier reads.
4. Implement only what the contract specifies
5. Run the tests for this construct — confirm they pass before moving on
6. After a successful compile, re-read the edited lines to verify the edit
   persisted (earlier work unit edits can silently revert if old_string was stale).
7. Update status.md substage → "implemented: <construct name>" after each passing unit
8. **Tendency scan (MANDATORY)** — after this construct's tests pass, check
   for known anti-patterns before moving to the next construct. This step
   is not optional: the compounding-KB claim depends on it firing for every
   construct. Skipping it is a silent correctness loss.

   a. If `.feature/<slug>/known_issues.md` exists, read TENDENCY entries that
      apply to this construct's domain. Do NOT introduce these patterns.
   b. If `.kb/CLAUDE.md` exists, run:
      `bash .claude/scripts/kb-search.sh "<domain> <construct-type>" --kb-root .kb --top 5`
      For each result with `type: adversarial-finding`, read the entry's
      `applies_to` patterns and `## Test guidance` section.
   c. Scan your just-implemented code for matches against these patterns.
      If found: fix proactively, re-run tests, confirm still green.
      If not found: continue to next construct.
   d. **Checkpoint writes (MANDATORY).** After the scan — even if `.kb/` is
      empty or produces zero matches — write both:
      - `status.md` substage →
        `tendency-scan-complete: <construct> — <n> patterns checked, <n> applied`
      - `cycle-log.md` append →
        `tendency-scan (<construct>): <n> results / <n> applied / <n> skipped`

      Zero-result scans still write the checkpoint (`0 / 0 / 0`). The absence
      of the checkpoint is the signal that the scan was skipped, so it must
      always be present.
   e. Budget: <30 seconds per construct, ≤5 KB entries deep-read. The goal is
      catching known anti-patterns, not a full audit.

If a test fails unexpectedly after implementation: see Escalation Protocol.

**Implementation principles:**
- Minimum correct implementation — no gold-plating
- Follow project-config.md conventions exactly
- No public methods beyond what contracts define
- If typed language: use the types from stub signatures

---

## Escalation protocol

If a test cannot be satisfied given the work plan's constraints:

**Step 0a — Check for spec conflict.** Before escalating, check whether the
failure is caused by contradictory requirements rather than a bad test or
bad contract.

1. Check the failing test for a `covers: R<N>` or `Finding:` comment that
   links it to a specific requirement or spec.
2. Check all PASSING tests in the same test class/file for `covers:` or
   `Finding:` comments referencing different requirements or specs.
3. If a passing test and the failing test reference requirements from
   different specs, AND those requirements describe contradictory behavior
   for the same construct or method (e.g., one requires null return, the
   other requires an exception for the same input condition):

   **Do NOT escalate to the Test Writer.** This is a requirement
   contradiction, not an implementation bug or a wrong test.

   Append `spec-conflict` to cycle-log.md:
   ```markdown
   ## <YYYY-MM-DD> — spec-conflict
   **Agent:** ⚙️ Code Writer
   **Cycle:** <n>
   **Passing test:** `<test name>` — covers: <requirement ID> from <spec/source>
   **Failing test:** `<test name>` — covers: <requirement ID> from <spec/source>
   **Construct:** <construct or method under test>
   **Conflict:** <what the two requirements demand and why they contradict>
   ---
   ```

   Update status.md substage → `spec-conflict-detected`.

   Display:
   ```
   🛑  SPEC CONFLICT DETECTED
   ───────────────────────────────────────────────
   Passing: <test name> (covers: <R_N> from <spec/source>)
   Failing: <test name> (covers: <R_N> from <spec/source>)
   Both test the same construct/method but expect contradictory behavior.
   This is a requirement contradiction, not an implementation bug.

   Escalate to /spec-author to resolve <R_N> vs <R_N>.
   ```

   Stop implementation for this work unit until the conflict is resolved.
   Do NOT proceed to the escalation steps below.

4. If no spec conflict is found, continue with the normal escalation flow.

**Step 0b — Check escalation count.** Read cycle-log.md and count
`code-escalation` entries for the same test name.

- **3rd escalation on the same test:** hard stop. Do NOT escalate to the
  Test Writer again. Instead say:
```
🛑  ESCALATION LIMIT · Code Writer → Manual Resolution
───────────────────────────────────────────────
The same contract conflict has been escalated 3 times without resolution.
Test: <test name>
File: <file>

Automatic resolution is not working. Please review the conflict manually:
  1. Check the test expectation in the test file
  2. Check the constraint in the work plan
  3. Resolve the mismatch directly, then re-run /feature-implement "<slug>"

If the work plan itself is wrong, update it before continuing.
```
  Update status.md substage → `escalation-limit-reached`. Stop.

- **Under the limit:** proceed with escalation below.

1. Append `code-escalation` to cycle-log.md:
```markdown
## <YYYY-MM-DD> — code-escalation
**Agent:** ⚙️ Code Writer
**Cycle:** <n>
**Test:** `<test name>` in `<file>`
**What the test expects:** <exact assertion>
**Constraint from work plan:** <section reference>
**Conflict:** <paragraph>
**Escalation count:** <N> of 3
---
```

2. Update status.md substage → `escalated-to-test-writer`

3. Say:
```
⚠️  ESCALATION · Code Writer → Test Writer  (<N>/3)
───────────────────────────────────────────────
Contract conflict — handing to Test Writer now.
Test: <test name>
Problem: <paragraph>
Work plan reference: <section>
```

Invoke `/feature-test "<slug>" --escalation` as a sub-agent immediately.
Do not wait for user input — the escalation is already logged.

---

## Step 3 — Final verification

Run the full test suite (5-minute Bash timeout). If the suite times out: run
individual test methods to isolate the hanger. For hanging tests from a prior
work unit, skip and note them. For the current work unit's test, check for
deadlocks or lock ordering issues before retrying. Confirm:
- All new tests pass
- No previously passing tests broken
- No test files were modified

---

## Step 4 — Log and hand off

Update status.md:
- Implementation cycle N → `complete`
- TDD Cycle Tracker: Tests passing → today
- substage → "all tests passing"
- Stage Completion table: Implementation row → Est. Tokens `~<N>K` (work-plan
  section ~2K + test files ~3K + stub files ~1K + dependency interfaces)

Append `implemented` entry to cycle-log.md.

Write `.feature/<slug>/implement-summary.md` (or append cycle section):

```markdown
## Cycle <n> — <YYYY-MM-DD>

### Files Modified
| File | Constructs | Action |
|------|-----------|--------|
| <path> | <construct1, construct2> | created / modified |

### Implementation Status
| Construct | Tests | Status |
|-----------|-------|--------|
| <name> | <n>/<n> passing | complete |

### Notes
- <any escalations, workarounds, or design choices made during implementation>
```

This summary is consumed by `/feature-refactor` — it avoids reloading all
implementation and test files from scratch.

If work units are defined:
- Mark the active unit as `complete` in the Work Units table in status.md
- Check if any blocked units are now unblocked (all their deps are complete)
  → update those units from `blocked` → `not-started`
- **Parallel mode (`execution_strategy` is `balanced` or `speed`):** mark unit
  complete in feature-level Work Units table and unblock dependent units, but do
  NOT invoke the next unit — the coordinator handles unit sequencing. Chain to
  `/feature-refactor` for the current unit as normal.

Update `.feature/CLAUDE.md`.

Read `automation_mode` from status.md.

**If `automation_mode: autonomous`:**

Display the unit progress summary then chain immediately without prompting:
```
───────────────────────────────────────────────
⚙️  CODE WRITER complete · <slug> · Cycle <n><  · WU-<n>>
  Tokens : <TOKEN_USAGE>
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
  Tokens : <TOKEN_USAGE>
───────────────────────────────────────────────
All tests passing. Cycle <n><  · WU-<n>>.
<n> constructs implemented across <list of files>.

<If work units and more units remain:>
Work unit progress:
  ✓ WU-1: <n> — complete
  → WU-2: <n> — ready (unblocked)
  ○ WU-3: <n> — blocked (waiting on WU-2)

```

Use AskUserQuestion with two options:
- "Continue"
- "Stop"

If "Continue": invoke `/feature-refactor "<slug>"<  --unit WU-<n>>` as a sub-agent.
If "Stop":
```
When you're ready:
  /feature-refactor "<slug>"<  --unit WU-<n>>
```
