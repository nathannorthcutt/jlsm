---
description: "Review and refactor implemented code with quality checklist"
argument-hint: "<feature-slug> [--unit <WU-N>]"
---

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
  2e missing-tests → 2f integration-tests → 2g documentation →
  2h security-review → Step 4 final-lint

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

## Step 0a — Progress tracking

**Skip TodoWrite if `execution_strategy` is `balanced` or `speed`.** In
parallel mode, the coordinator owns TodoWrite — subagents must not call it
or they will overwrite the coordinator's checklist.

**In sequential/cost mode:** use TodoWrite to show progress in the Claude Code
UI (visible via Ctrl+T). Each TodoWrite call replaces the full list — always
include all items.

**Pipeline context:** Include the full feature lifecycle as top-level items.
Mark earlier stages `completed`, current `in_progress`, later `pending`.

**Per-check granularity:** Each refactor checklist item (2a–2h) gets its own
TodoWrite item. Use `activeForm` to show what is being reviewed or fixed
(e.g., "Fixing: unused import in src/rate.py").

```json
[
  {"id": "pipeline-scoping", "content": "Scoping", "status": "completed", "priority": "medium"},
  {"id": "pipeline-domains", "content": "Domain analysis", "status": "completed", "priority": "medium"},
  {"id": "pipeline-planning", "content": "Work planning", "status": "completed", "priority": "medium"},
  {"id": "pipeline-testing", "content": "Test writing", "status": "completed", "priority": "medium"},
  {"id": "pipeline-implementation", "content": "Implementation", "status": "completed", "priority": "medium"},
  {"id": "pipeline-refactor", "content": "Refactor & review", "status": "in_progress", "priority": "high",
   "activeForm": "2a — Coding standards"},
  {"id": "pipeline-pr", "content": "PR draft", "status": "pending", "priority": "medium"},
  {"id": "coding-standards", "content": "2a — Coding standards", "status": "in_progress", "priority": "medium",
   "activeForm": "Fixing: unused import in src/rate.py"},
  {"id": "duplication", "content": "2b — Duplication check", "status": "pending", "priority": "medium"},
  {"id": "security", "content": "2c — Security fixes", "status": "pending", "priority": "high"},
  {"id": "performance", "content": "2d — Performance review", "status": "pending", "priority": "medium"},
  {"id": "missing-tests", "content": "2e — Missing test coverage", "status": "pending", "priority": "high"},
  {"id": "integration", "content": "2f — Integration tests", "status": "pending", "priority": "medium"},
  {"id": "documentation", "content": "2g — Documentation check", "status": "pending", "priority": "medium"},
  {"id": "security-review", "content": "2h — Security review", "status": "pending", "priority": "high"},
  {"id": "final-lint", "content": "Final lint and test run", "status": "pending", "priority": "medium"},
  {"id": "handoff", "content": "Log and hand off", "status": "pending", "priority": "medium"}
]
```

---

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

  Type **yes**  ·  or: stop  to review before continuing
```
Wait for input. If "yes": continue the refactor and resume chaining if autonomous.
If "stop": complete the current checklist item, write the log entry, then stop.

### 2e — Missing tests
`status.md substage → "refactor: missing-tests"`
`Display: ── Missing tests ───────────────────────────`

For each construct, check these categories (same checklist the Test Writer uses):

**Scenario-based:**
- Happy path tested? ✓/✗
- All documented error conditions tested? ✓/✗
- Boundary values (empty, zero, max, null/nil, single element)? ✓/✗
- Concurrency / ordering dependencies (if applicable)? ✓/✗

**Structural (derived from interface):**
- Paired methods have round-trip tests (encode/decode, write/read)? ✓/✗
- Resource lifecycle tested (close, use-after-close)? ✓/✗
- Method interaction tested (add+remove, sequences of mutations)? ✓/✗
- Type boundaries (overflow, precision loss, encoding limits)? ✓/✗

**Implementation-discovered:**
- Cases the implementation handles that the Work Planner didn't anticipate? ✓/✗

If missing tests are found → see Step 3 (escalate, do not continue).

### 2f — Integration tests
`status.md substage → "refactor: integration-tests"`
`Display: ── Integration tests ───────────────────────`

**Parallel mode (`execution_strategy` is `balanced` or `speed`):** skip 2f entirely.
Display: "Integration tests deferred to coordinator." Continue to Step 4.

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

### 2g — Documentation
`status.md substage → "refactor: documentation"`
`Display: ── Documentation ────────────────────────────`

Check whether this feature added, renamed, or changed constructs that are
covered by existing project documentation. Scan for:

- **README.md** / **CONTRIBUTING.md** — do they reference modules, APIs, or
  patterns that this feature changed?
- **docs/ directory** (if it exists) — any files that describe the area this
  feature touches?
- **Module-level documentation** — package-info.java, module docstrings,
  `__init__.py` module docs, Go package comments
- **API documentation** — if the feature changed public API signatures that are
  documented (OpenAPI specs, generated docs, etc.)
- **Inline architecture notes** — README files in subdirectories that describe
  the module's purpose or structure

For each documentation file that references something this feature changed:

1. Read the doc file
2. Check whether the documentation is still accurate given the implementation
3. If inaccurate or incomplete: update it to reflect the current state
4. If a new module/package was created and the project has documentation
   conventions (e.g., README per module): create the expected documentation

**What NOT to update:**
- Changelog entries (handled by `/feature-pr` and `/feature-retro`)
- vallorcine's own working files (status.md, cycle-log.md, etc.)
- Test documentation (tests are self-documenting)
- Comments in code that were already addressed in 2a

Display findings:
```
  Documentation updates:
    ✓ README.md — updated API section for new <construct>
    ✓ docs/architecture.md — added <module> description
    · No documentation found for <new module> (no convention detected)
```

Run tests after changes (documentation changes should not break tests, but
verify anyway).

### 2h — Security review
`status.md substage → "refactor: security-review"`
`Display: ── Security review ──────────────────────────`

This is a holistic audit of the feature's security posture — distinct from 2c
which fixes implementation-level vulnerabilities inline. 2h steps back and
assesses what this feature changes about the project's threat surface.

Scope to only the constructs and files changed by this feature (read work-plan.md
for the list). Do not audit the entire codebase.

**Authentication & Authorization:**
- Do new endpoints/APIs enforce authentication?
- Do access control checks match the feature's intended audience?
- Are there privilege escalation paths (user action → admin effect)?

**Data handling:**
- Is sensitive data (PII, credentials, tokens) encrypted at rest and in transit?
- Are there new logging statements that might log sensitive data?
- Is data sanitized before display (XSS) and before storage (injection)?
- Are temporary files / caches cleaned up?

**Trust boundaries:**
- Does this feature introduce new trust boundaries (user input, external API,
  file upload, deserialization)?
- Are all inputs from untrusted sources validated at the boundary?
- Are error responses safe (no stack traces, internal paths, or version info)?

**Dependency & configuration:**
- Do new dependencies have known vulnerabilities? (advisory check, not a scanner)
- Are there new configuration options with insecure defaults?
- Are feature flags / admin toggles protected?

**Threat surface delta:**
- What attack surface does this feature add that didn't exist before?
- One sentence summary of the security posture change

**If no findings:**
```
  Security review: no issues found ✓
  Threat surface delta: <one sentence>
```
Continue to Step 4.

**If findings exist — always pause:**
```
── Security review — findings ─────────────────
<n> issue(s) found:

  1. [HIGH] <description> — <file:line>
  2. [MEDIUM] <description> — <file:line>
  3. [LOW/INFO] <description>

  Fix now?  Type **yes**  ·  or: stop  to review first
```

Severity levels:
- **HIGH** — exploitable vulnerability, must fix before PR (injection, auth
  bypass, secret exposure)
- **MEDIUM** — defense-in-depth gap, should fix (missing rate limiting, overly
  broad permissions)
- **LOW/INFO** — observation worth noting in PR (new attack surface, recommended
  future hardening)

If "yes": fix HIGH and MEDIUM inline, note LOW/INFO in the cycle-log. Re-run
tests after fixes.

If "stop": write all findings to cycle-log, do not fix anything.

Append to cycle-log.md:
```markdown
## <YYYY-MM-DD> — security-review
**Agent:** ✨ Refactor Agent
**Cycle:** <n>
**Findings:** <n total — n HIGH, n MEDIUM, n LOW>
**Fixed:** <list of fixed issues, or "none">
**Noted:** <list of LOW/INFO items for PR review, or "none">
**Threat surface delta:** <one sentence>
---
```

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
- Stage Completion table: Refactor row → Est. Tokens `~<N>K` (project-config ~1K +
  work-plan ~2K + all impl files + all test files)

If no further cycles needed: update status.md Refactor stage → `complete`.

Append `refactor-complete` to cycle-log.md:
```markdown
## <YYYY-MM-DD> — refactor-complete
**Agent:** ✨ Refactor Agent
**Cycle:** <n>
**Changes:** <bullet list>
**Security findings (2c):** <none | list>
**Security review (2h):** <n findings — n HIGH, n MEDIUM, n LOW | clean>
**Threat surface delta:** <one sentence>
**Performance findings:** <none | list>
**Missing tests found:** <n>
**Unit tests:** <n> passing, 0 failing
**Integration tests:** <n passing | skipped — not configured | pre-existing failures noted>
**Token estimate:** ~<N>K
---
```

Update `.feature/CLAUDE.md`.

Read `automation_mode` from status.md.

**If `execution_strategy` is `balanced` or `speed` (parallel mode):**

Mark unit complete in per-unit `unit_status`.
Mark unit complete in feature-level Work Units table.
Do NOT chain to next unit or PR — the coordinator handles that.

```
───────────────────────────────────────────────
✨ REFACTOR AGENT complete · <slug> · WU-<n>
  Tokens : <TOKEN_USAGE>
───────────────────────────────────────────────
Unit complete. Returning to coordinator.
```
STOP. (Coordinator launched this as subagent; returning is sufficient.)

**If more work units remain (not the final unit) — sequential/cost mode only:**

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
  Type **yes**  ·  or: stop
```
If "yes": invoke `/feature-test "<slug>" --unit WU-<next>`.
If "stop": display the manual command and stop.

**If this is the final (or only) unit and refactor is clean — sequential/cost mode only:**

Read the Stage Completion table from status.md and display a token summary:

```
───────────────────────────────────────────────
✨ REFACTOR AGENT complete · <slug> · Cycle <n>
───────────────────────────────────────────────
Refactor cycle <n> complete. <n> tests passing.

── Token Summary ──────────────────────────────
  | Stage          | Est.   | Actual           | Δ      |
  |----------------|--------|------------------|--------|
  | Scoping        | ~5K    | 4.2K in / 3K out | -16%   |
  | Domains        | ~6K    | 8.1K in / 2K out | +35%   |
  | Planning       | ~8K    | 7.5K in / 5K out | -6%    |
  | Testing        | ~5K    | 6.0K in / 4K out | +20%   |
  | Implementation | ~8K    | 9.2K in / 7K out | +15%   |
  | Refactor       | ~6K    | 5.8K in / 3K out | -3%    |
  |----------------|--------|------------------|--------|
  | Total          | ~38K   | 40.8K in / 24K out       |
───────────────────────────────────────────────
```

The Δ column compares estimated tokens to actual input tokens:
`((actual_input - estimate) / estimate × 100)`, rounded to nearest integer.
Only show for stages with both values present. Parse actual input tokens from
the "Actual Tokens" column (the number before "in").

— Autonomous:
```
All units complete. Starting PR draft  ·  type stop to pause
───────────────────────────────────────────────
```
Invoke `/feature-pr "<slug>"` immediately.

— Manual:
```
Feature is ready for review.

  Type **yes**  ·  or: stop
───────────────────────────────────────────────
```
If "yes": invoke `/feature-pr "<slug>"`.
If "stop": display manual commands and stop.

**If missing tests escalation triggered (either mode):**

```
───────────────────────────────────────────────
✨ REFACTOR AGENT complete · <slug> · Cycle <n>
───────────────────────────────────────────────
Missing tests found — handing to Test Writer.
<If autonomous:> Pausing — missing tests require your review.

  Type **yes**  ·  or: stop
```
Wait for input regardless of automation_mode — missing tests are always a
human checkpoint. If "yes": invoke `/feature-test "<slug>" --add-missing`.
If "stop": display the manual sequence and stop.
