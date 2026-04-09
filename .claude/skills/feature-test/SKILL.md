---
description: "Write failing tests from work-plan contracts and acceptance criteria"
argument-hint: "<feature-slug> [--unit <WU-N>] [--add-missing] [--escalation]"
---

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

```

Use AskUserQuestion with two options:
- "Proceed to implementation"
- "Stop"

If "Proceed to implementation": invoke /feature-implement "<slug>"<  --unit WU-<n>> as a sub-agent immediately.
If "Stop": display `Next: /feature-implement "<slug>"` and stop.

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

## Step 0a — Progress tracking

**Skip TodoWrite if `execution_strategy` is `balanced` or `speed`.** In
parallel mode, the coordinator owns TodoWrite — subagents must not call it
or they will overwrite the coordinator's checklist.

**In sequential/cost mode:** use TodoWrite to show progress in the Claude Code
UI (visible via Ctrl+T). Each TodoWrite call replaces the full list — always
include all items.

**Pipeline context:** Include the full feature lifecycle as top-level items.
Mark earlier stages `completed`, current `in_progress`, later `pending`.

**Per-test granularity:** After the test plan is confirmed (Step 2), add an
item for each test case from the plan. Update each to `in_progress` while
writing and `completed` when done. Use `activeForm` to show which file is
being written.

Example checklist during test writing:
```json
[
  {"id": "pipeline-scoping", "content": "Scoping", "status": "completed", "priority": "medium"},
  {"id": "pipeline-domains", "content": "Domain analysis", "status": "completed", "priority": "medium"},
  {"id": "pipeline-planning", "content": "Work planning", "status": "completed", "priority": "medium"},
  {"id": "pipeline-testing", "content": "Test writing", "status": "in_progress", "priority": "high",
   "activeForm": "Writing test 3 of 8"},
  {"id": "pipeline-implementation", "content": "Implementation", "status": "pending", "priority": "medium"},
  {"id": "pipeline-refactor", "content": "Refactor & review", "status": "pending", "priority": "medium"},
  {"id": "pipeline-pr", "content": "PR draft", "status": "pending", "priority": "medium"},
  {"id": "test-1", "content": "test_returns_token_on_valid_input", "status": "completed", "priority": "high"},
  {"id": "test-2", "content": "test_rejects_empty_input", "status": "completed", "priority": "high"},
  {"id": "test-3", "content": "test_rate_limit_exceeded", "status": "in_progress", "priority": "high",
   "activeForm": "Writing to tests/test_rate_limit.py"},
  {"id": "test-4", "content": "test_concurrent_access", "status": "pending", "priority": "high"},
  {"id": "verify-fail", "content": "Verify all tests fail (not-implemented)", "status": "pending", "priority": "high"},
  {"id": "handoff", "content": "Hand off to implementation", "status": "pending", "priority": "medium"}
]
```

---

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

### Step 1a — Resolve hardened specs (if available)

Check whether the project has a `.spec/` directory with a manifest:

```bash
bash .claude/scripts/spec-resolve.sh "<feature brief title or description>" 8000 2>/dev/null
```

If the script succeeds and produces a non-empty bundle, store the output as
SPEC_BUNDLE. This bundle contains behavioral requirements (R1, R2, ...) that
the spec system has already hardened.

If the script fails, `.spec/` does not exist, or the bundle is empty: set
SPEC_BUNDLE to empty. All subsequent spec-aware steps fall back to their
original behavior.

**Do not read `.spec/` files directly.** The resolver handles file discovery,
domain matching, transitive dependency expansion, and token budgeting.

---

## Step 1b — Construct analysis (derive tests from interfaces)

Read the stub files for each construct in the work plan. The stubs define
the public interface — method signatures, parameter types, return types, and
any `throws`/`raises` declarations. Derive structural test cases that the
brief and acceptance criteria don't mention but the interface implies.

**For each construct, scan its stub for these patterns:**

| Interface pattern | What it implies | Test to derive |
|---|---|---|
| **Paired methods** (encode/decode, serialize/deserialize, write/read, open/close, add/remove) | Round-trip: operation then inverse preserves data | `test_<X>_then_<Y>_is_identity` |
| **Closeable / AutoCloseable / resource lifecycle** | Cleanup on all paths, use-after-close safety | `test_close_releases_resources`, `test_use_after_close_throws` |
| **Mutable state** (add, put, insert, update, delete on a collection/store) | Interaction: sequences of mutations produce expected state | `test_add_then_remove_restores_original`, `test_multiple_adds_accumulate` |
| **Iterator / stream / cursor** | Exhaustion, empty iteration, concurrent modification | `test_empty_iteration`, `test_iterator_exhaustion` |
| **Factory / builder** | Invalid configuration, required fields, build order | `test_build_without_required_field_throws` |
| **Comparable / ordering** | Symmetry, transitivity, consistency with equals | `test_ordering_symmetry`, `test_ordering_transitivity` |
| **Numeric parameters** (capacity, size, count, limit, offset) | Zero, negative, overflow | `test_zero_capacity`, `test_negative_throws` |
| **Byte buffers / arrays** | Empty, single byte, exact capacity, overflow | `test_empty_buffer`, `test_buffer_at_capacity` |
| **Type hierarchies** (sealed types, enum switches, visitor patterns) | All variants handled | One test per variant/subtype |

**How to apply:** For each pattern found, add 1-2 test cases to the plan.
These are structural tests — they test properties of the interface, not
specific business scenarios. They go in a "Structural" section of the test
plan alongside the existing Happy path, Error, and Boundary sections.

**Do NOT over-generate.** Only add tests for patterns actually present in the
stubs. A construct with no paired methods gets no round-trip tests. The goal
is to catch the 3-5 tests the refactor agent would flag, not to double the
test count.

---

## Step 1c — Spec analysis pre-pass (both lenses)

Analyze the work-plan contracts and stubs across two complementary lenses to
identify risks BEFORE writing tests. This step prevents bugs from being written
rather than finding them after implementation.

### KB integration

If `.kb/CLAUDE.md` exists, scan the Topic Map for categories relevant to this
feature's domain (e.g., encryption, indexing, serialization, compression).
Read any `type: adversarial-finding` entries in matching categories — they
contain bug patterns discovered in prior features that may recur here. Add
matching patterns to the Lens B checklist below.

### Project rules as audit vectors

Check for project-specific rules that define what "correct" means beyond
general best practices. Rules in `.claude/rules/`, `CONTRIBUTING.md`, and
accepted ADRs in `.decisions/` all constrain implementation. Violations of
project rules are bugs — add them to Lens B. Examples: memory discipline
rules, architectural constraints, testing conventions, coding standards.

### Lens A — Requirement operationalization / Contract gaps

**Conflict pre-check:** Before operationalizing requirements, check if
SPEC_BUNDLE contains a `## Conflicts` section. If it does, extract all
CONFLICT and INVALIDATES lines. For each conflicting requirement pair
(e.g., F03.R8 and F07.R56), mark both requirements as:

```
UNTESTABLE: spec conflict — requirements <R_X> and <R_Y> contradict
```

Do NOT write tests for these requirements. Contradictory specs would produce
contradictory tests — one asserting "must accept" while another asserts
"must reject" for the same behavior. Include the UNTESTABLE entries in the
test plan's defensive section so the conflict is visible but not acted upon.

Proceed with operationalizing all non-conflicting requirements as normal.

**If SPEC_BUNDLE is non-empty (hardened specs available):**

The bundle contains behavioral requirements (R1, R2, ...) from hardened specs.
Operationalize each requirement into test cases:

1. Extract every requirement ID (R1, R2, ...) and its behavioral description
   from the `## Feature Requirements` section of the bundle.
2. For each requirement, ask: **"Can I write a test that verifies this
   requirement against the constructs in the work plan?"**
   - If yes: create one or more test cases that exercise the requirement.
     Tag each test with the requirement ID it covers (e.g., `covers: R3`).
   - If the requirement is abstract or cross-cutting (e.g., "the system must
     be resilient to X"): identify which construct is responsible for that
     behavior and write a concrete test against it.
   - If the requirement cannot be tested at the unit/integration level (e.g.,
     deployment constraints): note it as `UNTESTABLE: <reason>` in the
     defensive section. Do not silently drop it.
3. After operationalizing all spec requirements, apply the contract-gap table
   below as a **supplement** — the spec may not cover every edge case the
   interface implies. Any gap found that isn't already covered by a spec
   requirement becomes a CONTRACT-GAP finding.
4. Check for open obligations in the bundle's `## Open Obligations` section.
   Each obligation is a spec-level TODO that must be addressed. Convert
   applicable obligations into test cases.

**If SPEC_BUNDLE is empty (no hardened specs — fallback):**

For each contract in the work plan, apply the contract-gap table below as the
primary analysis tool. This is the original Lens A behavior.

**Contract-gap table (primary in fallback mode, supplement in spec mode):**

| Question | What to test |
|----------|-------------|
| What happens at boundary values? | Empty inputs, zero-length, max capacity, single element |
| What happens with null at every layer? | Constructor args, method params, stored fields, return values |
| Are error cases exhaustive? | Invalid combinations, inverted ranges, type mismatches |
| Are composite operations atomic? | If step A succeeds but step B fails, what state? |
| Are mutable inputs defensively copied? | Arrays, collections crossing trust boundaries |
| What equality semantics do keys use? | Identity vs content (especially byte[], arrays) |

### Lens B — Implementation risk patterns (what code typically gets wrong)

**Spec-aware scoping:** If SPEC_BUNDLE is non-empty, use the spec requirements
as the boundary of what is "specified behavior." Lens B then focuses on:
- Behaviors the spec **did not anticipate** — interactions, edge cases, and
  failure modes that fall outside any R_N requirement
- Gaps **between** requirements — where two requirements interact but neither
  fully specifies the combined behavior
- Implementation assumptions the spec takes for granted (e.g., thread safety,
  ordering, resource cleanup) without an explicit requirement

When specs are available, tag each Lens B finding with whether it is:
- `SPEC-BOUNDARY` — the spec has a relevant requirement but doesn't cover
  this specific edge case
- `SPEC-BLIND-SPOT` — no spec requirement addresses this area at all
- `IMPL-RISK` — standard implementation risk (same as no-spec mode)

If SPEC_BUNDLE is empty, proceed with the standard implementation risk analysis
below without spec-boundary tagging.

For each construct in scope, trace the full data flow — not just the construct
itself but its inputs, outputs, and data carriers. This prevents multi-pass
discovery where each audit finds the next layer.

**Level 1 — The construct itself:**
- `byte[]` or arrays used as map/set keys — identity equality, not content
- Mutable arrays/collections stored by reference without defensive copying
- Float/double encoding — sign-bit handling differences between integer and IEEE 754
- Multi-step mutations that aren't atomic — delete-then-insert, check-then-act
- Switch/instanceof that don't cover all sealed interface subtypes
- Silent truncation or Math.min instead of fail-fast on mismatched dimensions/sizes
- Not-equals predicates interacting with null field values
- Resource lifecycle — double-close, use-after-close, deferred exception aggregation
- Validation that should happen at construction but is deferred to usage
- Any patterns from adversarial KB entries loaded above

**Level 2 — Inputs (who calls this construct, what do they pass?):**
- Are callers validated at the trust boundary, or is invalid input silently accepted?
- Per project rules, should out-of-range values be rejected at entry rather than
  handled downstream? (fail-fast principle)
- Can callers pass values that are technically valid but semantically wrong?
  (e.g., NaN as a score, negative capacity, inverted range bounds)

**Level 3 — Outputs (what does this construct return, can consumers misuse it?):**
- Do returned references expose mutable internal state? (check accessors, not just constructors)
- Are returned collections unmodifiable, or can consumers corrupt internal state?
- Can the return value be in a state the consumer doesn't expect? (null, empty, partial)

**Level 4 — Data carriers (records, DTOs, result types):**
- Do data carrier types enforce their own invariants at construction?
  (null fields, NaN scores, negative counts, empty required fields)
- Do records with mutable fields (arrays, collections, MemorySegment) have
  correct equals/hashCode? (identity vs content semantics)
- Are carriers immutable once constructed, or can state leak through accessors?

**Scoping:** Trace all 4 levels on every construct in the current work unit.
If the work unit has many constructs, prioritize depth on constructs flagged
by Lens A or Level 1 findings, but do not skip levels entirely.

### Output

For each finding, note:
- The construct and contract section it applies to
- The finding type:
  - `SPEC-REQ` — directly operationalized from a spec requirement (Lens A, spec mode)
  - `CONTRACT-GAP` — gap not covered by any spec requirement (Lens A)
  - `SPEC-BOUNDARY` — spec-adjacent edge case (Lens B, spec mode)
  - `SPEC-BLIND-SPOT` — no spec coverage at all (Lens B, spec mode)
  - `IMPL-RISK` — standard implementation risk (Lens B)
- A specific defensive test case to add to the test plan
- If from a spec requirement: the requirement ID (e.g., `R3`)

These findings feed directly into the test plan as a "Defensive (from spec analysis)"
section. Do NOT write a separate spec-analysis.md file — the findings are integrated
into the test plan in the next step.

---

## Step 2 — Write the test plan (in chat first)

Update status.md substage → `confirming-plan`.

### Coverage checklist (internal — apply before presenting the plan)

Before presenting the test plan, systematically check each construct against
these categories. The refactor agent (step 2e) will check these exact categories
later — gaps found there trigger an escalation cycle. Catching them here is
much cheaper.

For each construct in the work plan:

| Category | What to test | Common gaps |
|----------|-------------|-------------|
| **Happy path** | Normal operation with valid inputs | Rarely missed |
| **Error conditions** | Every error case in the brief + contract | Missing: errors not in brief but implied by types |
| **Boundary values** | Empty/zero, single element, max capacity, null/nil | Most commonly missed — add at least one per construct |
| **State transitions** | Invalid state (e.g., use after close, double init) | Missed when construct has lifecycle |
| **Concurrency** | Thread safety, ordering dependencies, race conditions | Only if construct is shared/concurrent — skip if single-threaded |
| **Type boundaries** | Overflow, underflow, precision loss, encoding limits | Missed for numeric types, byte buffers, serialization |

**The most commonly missed category is boundary values.** For every construct,
ask: "what happens at empty, at one, and at max?" If the answer isn't obvious
from the contract, write a test for it.

### Present the plan

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
Boundary values
  N. test_<name> — <scenario>
  ...
Structural (from interface analysis)
  N. test_<name> — <scenario> — pattern: <round-trip | lifecycle | interaction | ...>
  ...
Spec requirements (from hardened specs)          ← only when specs loaded
  N. test_<name> — <scenario> — covers: R<N>
  ...
Defensive (from spec analysis)
  N. test_<name> — <scenario> — finding: <CONTRACT-GAP | SPEC-BOUNDARY | SPEC-BLIND-SPOT | IMPL-RISK>: <description>
  ...
───────────────────────────────────────────────
Spec analysis: <N> SPEC-REQ + <N> CONTRACT-GAP + <N> IMPL-RISK findings → <N> defensive tests
              [if specs loaded: <N> requirements operationalized, <N> untestable]
Does this cover the acceptance criteria? Any to add or remove?
───────────────────────────────────────────────
```

The plan MUST include "Boundary values", "Structural", and "Defensive" sections.
If any section has no applicable tests (rare), state why explicitly. The structural
section should list which interface patterns were checked and found not applicable.
The defensive section should summarize how many findings came from each lens and
any adversarial KB patterns that were checked.

**When specs were loaded:** The defensive section should additionally report:
- How many spec requirements were operationalized (SPEC-REQ count)
- How many requirements were marked UNTESTABLE and why
- How many SPEC-BOUNDARY and SPEC-BLIND-SPOT findings were identified
- Which requirement IDs each defensive test covers

Wait for confirmation. Update status.md substage → `writing-tests` after confirm.

---

## Step 3 — Write test files

Write to the test directory from project-config.md.

**Idempotent:** check whether each test already exists before writing.
Append to existing test files rather than overwriting; do not duplicate tests.
Before editing an existing test file, re-read it to pick up any additions from
prior test-writing passes — stale reads cause Edit old_string mismatches or
silent overwrites. After writing each test method, re-read the file to verify
the method is present.

Rules:
- Test names describe behaviour: `test_returns_error_when_input_is_empty`
- Public interface only — no reaching into implementation details
- Mocks/fakes for all external dependencies
- Every test: arrange / act / assert clearly separated
- Every construct MUST have at least one boundary value test (empty, zero,
  max, null/nil, single element) — the refactor agent checks for these
  and will escalate if missing

Update status.md substage → `verifying-failures` after writing.

---

## Step 4 — Verify tests fail

Run the test suite (5-minute Bash timeout per tdd-protocol). If the suite times
out: run individual test methods to isolate which test is hanging. For hanging
tests, add a @Timeout annotation or rewrite with a non-blocking approach. Do
not retry the full suite without isolating first.

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

### Step E1a — Check for spec conflict

If the escalation entry's conflict description contains "SPEC CONFLICT" or
the substage in status.md is `spec-conflict-detected`, this is a requirement
contradiction — not a test or contract problem.

**Do NOT rewrite the test.** Instead:

1. Mark both the passing and failing tests as BLOCKED in cycle-log.md:
   ```markdown
   ## <YYYY-MM-DD> — tests-blocked-spec-conflict
   **Agent:** 🧪 Test Writer
   **Cycle:** <n>
   **Blocked tests:** `<passing test>`, `<failing test>`
   **Reason:** Contradictory spec requirements — <R_N> vs <R_N>
   **Resolution:** Requires /spec-author to reconcile conflicting requirements
   ---
   ```

2. Display:
   ```
   🧪 TEST WRITER · spec conflict · <slug>
   ───────────────────────────────────────────────
   This escalation is a spec conflict, not a test or contract problem.
   Both tests are correct given their respective requirements — the
   requirements themselves contradict each other.

   Blocked tests:
     - <passing test> (covers: <R_N>)
     - <failing test> (covers: <R_N>)

   Run /spec-author to resolve the conflicting requirements, then
   re-run /feature-test "<slug>" to unblock.
   ```

3. Stop. Do not proceed to Step E2.

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
- Spec requirements operationalized: <n>/<total> (omit if no specs loaded)
- Untestable requirements: <list or none> (omit if no specs loaded)
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

### Determine next stage: hardening or implement

Check the work-plan contracts for domain lens signals to decide whether
hardening is needed. Count constructs with any of these contract properties:
- Closeable/AutoCloseable, close/cleanup mentions → resource_lifecycle
- Cross-module dependencies → contract_boundaries
- Thread-safety mentions, `shares_state` edges → concurrency
- Encode/decode, serialize/deserialize → data_transformation
- Mutable state shared by 2+ constructs → shared_state

**If zero lens signals OR only 1 construct in the work plan:**
- Skip hardening — chain directly to `/feature-implement`.

**If 1-2 lens signals AND 2-5 constructs:**
- Chain to `/feature-harden "<slug>" --lite<  --unit WU-<n>>`.

**If 3+ lens signals OR 6+ constructs:**
- Chain to `/feature-harden "<slug>"<  --unit WU-<n>>`.

### Chain execution

**If `automation_mode: autonomous`:**

Display the summary then chain immediately without prompting:
```
───────────────────────────────────────────────
🧪 TEST WRITER complete · <slug> · Cycle <n><  · WU-<n>>
  Tokens : <TOKEN_USAGE>
───────────────────────────────────────────────
Tests written and verified failing. Cycle <n><  · WU-<n>>.

Starting <hardening | implementation>  ·  type stop to pause
───────────────────────────────────────────────
```

Then invoke the next stage as a sub-agent immediately.

**If `automation_mode: manual` (or not set):**

Display:
```
───────────────────────────────────────────────
🧪 TEST WRITER complete · <slug> · Cycle <n><  · WU-<n>>
  Tokens : <TOKEN_USAGE>
───────────────────────────────────────────────
Tests written and verified failing. Cycle <n><  · WU-<n>>.

```

Use AskUserQuestion with two options:
- "Continue"
- "Stop"

If "Continue": invoke the next stage as a sub-agent immediately.
If "Stop":
```
When you're ready:
  /feature-harden "<slug>"<  --unit WU-<n>>   (or /feature-implement if skipping)
```
