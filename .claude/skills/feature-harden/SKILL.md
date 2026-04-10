---
description: "Adversarial test hardening — domain-lens behavioral attacks on contracts before implementation"
argument-hint: "<feature-slug> [--unit <WU-N>] [--lite]"
---

# /feature-harden "<feature-slug>" [--unit <WU-N>] [--lite]

Applies adversarial domain-lens analysis to work-plan contracts and writes
defensive tests BEFORE implementation. Tests define behavioral requirements
the spec didn't anticipate — the implementation must satisfy them.

This is NOT an audit. There is no implementation to analyze. Instead, it
asks: "Given these contracts, what behaviors can go wrong that the spec
doesn't address?" — through the same domain lenses the audit uses.

Runs between `/feature-test` and `/feature-implement`. Idempotent — if
hardening is already complete for the current cycle, reports and stops.
With `--lite`, reduces scope to 2 lenses and defers all ABSENT findings.

---

## Idempotency pre-flight (ALWAYS FIRST)

Read `.feature/<slug>/status.md`.

### Work unit resolution

If `work_units: none` in status.md: ignore `--unit` flag, proceed as normal.

If work units are defined:
- If `--unit` flag provided: scope all steps to that unit only
- If no `--unit` flag: find the next unit in testing-complete state

### Stage check

Find the Hardening row in the Stage Completion table.

**If Hardening is `complete`:**
- Say: "Hardening already complete for cycle <n>. <N> adversarial tests written."
- Display `Next: /feature-implement "<slug>"` and stop.

**If Hardening is `in-progress`:**
- Say: "Hardening was in progress — resuming from substage <substage>."
- Read `hardening-report.md` for progress, jump to the appropriate step.

**If Hardening is `not-started`:**
- Set status.md: Hardening → `in-progress`, substage → `loading-context`
- Proceed to Step 0.

Display opening header:
```
───────────────────────────────────────────────
🛡  HARDENING · <slug> · Cycle <n><  · WU-<n>>
───────────────────────────────────────────────
```

---

## Step 0 — Load context

Read:
1. `.feature/project-config.md` — language, conventions, test commands
2. `.feature/<slug>/work-plan.md` — contract definitions, construct graph
   edges (depends_on, shares_state, produces/consumes). **If work units
   defined:** read only the active unit's section and Contract Definitions.
3. `.feature/<slug>/test-plan.md` — already-written tests (dedup boundary)
4. `.feature/<slug>/domains.md` — technical domains, KB entries, constraints
5. Resolved SPEC_BUNDLE (if `.spec/` exists) — behavioral requirements

If `.kb/CLAUDE.md` exists, scan the Topic Map for categories relevant to
this feature's domain. Read any `type: adversarial-finding` entries in
matching categories — these are bug patterns from prior audits that the
domain lens analysis should check.

Update status.md substage → `lens-selection`.

---

## Step 1 — Lens selection

Derive which domain lenses apply from the work-plan contracts. This is a
mechanical check — read the contracts and match against the activation
signals below. Do NOT read source code (there is none yet).

| Lens | Activates when ANY contract has... |
|------|-------------------------------------|
| resource_lifecycle | Implements Closeable/AutoCloseable, mentions close/release/cleanup/dispose, acquires resources (files, connections, locks, memory) |
| contract_boundaries | Depends on constructs in a different package/module, has external callee contracts, crosses trust boundaries |
| concurrency | Mentions thread safety, has `shares_state` edges in construct graph, spec requirements mention concurrent access, multiple constructs access same mutable state |
| data_transformation | Describes encode/decode, serialize/deserialize, compress/decompress, format conversion, has produces/consumes edges where data changes form |
| shared_state | Mutable state accessed by 2+ constructs (check `shares_state` edges), co-mutator relationships in construct graph |
| dispatch_routing | Routing/dispatch/switching on type/enum, fan-out to 3+ constructs, handler registration patterns |

**Count qualifying signals per lens.** A lens activates if at least one
construct qualifies.

**Lite mode (`--lite`):** Activate only the 2 lenses with the most
qualifying constructs. If tied, prefer resource_lifecycle > concurrency >
contract_boundaries > data_transformation > shared_state > dispatch_routing.

**Skip threshold:** If zero lenses activate, skip hardening entirely:
- Update status.md: Hardening → `complete`, substage → `skipped-no-signals`
- Display: "No domain lens signals in contracts — skipping hardening."
- Chain to `/feature-implement`.

Display lens selection:
```
── Lens selection ─────────────────────────────
Active lenses (from contract signals):
  ✓ resource_lifecycle — <n> constructs (close/cleanup contracts)
  ✓ concurrency — <n> constructs (shared state edges)
  ✗ contract_boundaries — no cross-module deps
  ✗ data_transformation — no encode/decode contracts
  ✗ shared_state — no co-mutator edges
  ✗ dispatch_routing — no dispatch patterns

Proceeding with <n> active lenses, <n> constructs in scope.
```

Update status.md substage → `behavioral-attack`.

---

## Step 2 — Behavioral attack (per lens, per construct)

For each active lens, analyze every qualifying construct. The goal is to
find behaviors the spec and existing tests DON'T cover — gaps where the
implementation will make silent assumptions.

**Read test-plan.md first.** For each finding below, check if an existing
test already covers the scenario. If it does, verdict is TEST-COVERED.

### Per-construct protocol

For each construct under the current lens:

1. Read its contract definition from work-plan.md (signature, receives,
   returns, side effects, error conditions, shared state).

2. Apply the lens-specific behavioral attack questions below.

3. For each question, produce a verdict:
   - `SPEC-COVERED` — a spec requirement addresses this. Cite R_N.
   - `TEST-COVERED` — test-plan.md has a test for this. Cite test name.
   - `HARDENING-TEST` — the behavior is implied by the contract but no
     test exists. Write an adversarial test.
   - `CONTRACT-SILENT` — the contract doesn't address this behavior.
     A design decision is needed before a test can be written.

### Lens: resource_lifecycle

For each construct with lifecycle contracts:
- **Double-close:** What happens if close/release is called twice? Is it
  idempotent (no-op) or does it throw? The contract must specify this.
- **Use-after-close:** What happens if any operation is called after close?
  Must throw, must no-op, or undefined?
- **Partial construction failure:** If the constructor/builder acquires
  resource A then fails acquiring resource B, is A cleaned up?
- **Error path cleanup:** For operations that acquire temporary resources
  (locks, buffers, intermediate state), is cleanup guaranteed on exception?
- **Close ordering:** If this construct owns other closeable resources,
  does close() close them in safe order? Does it aggregate exceptions?

### Lens: contract_boundaries

For each construct with cross-module dependencies:
- **Input trust:** Does the contract validate all inputs, or does it trust
  the caller? What happens if the caller passes null, empty, out-of-range?
- **Error propagation:** If a callee throws, does the caller's contract
  specify how that error surfaces? Are all callee error types handled?
- **Assumption match:** Do the caller's documented assumptions about return
  values match the callee's documented guarantees?
- **Builder validation:** If a Builder, does it validate all configuration
  at build() time? Can contradictory configurations pass validation?

### Lens: concurrency

For each construct with thread-sharing signals:
- **Concurrent access:** What happens if this method is called from two
  threads simultaneously? Is the outcome defined by the contract?
- **Atomicity:** Are multi-step operations (check-then-act, read-modify-
  write) specified as atomic? Can callers observe intermediate state?
- **Close under contention:** What happens if close() races with an
  active operation? Is the outcome specified?
- **Ordering:** If operations must happen in a specific order, is that
  order enforceable? What if events arrive out of order?

### Lens: data_transformation

For each construct with encode/decode contracts:
- **Round-trip fidelity:** Does encode → decode produce the original input?
  What about edge values (empty, max-size, special characters)?
- **Encoding limits:** What happens at type limits? Float overflow,
  integer overflow, precision loss, max array/string length?
- **Format agreement:** Do producer and consumer agree on encoding? What
  if the format version changes?
- **Invalid encoded data:** What happens if decode receives malformed input?
  Truncated, corrupted, wrong version?

### Lens: shared_state

For each construct pair that shares mutable state:
- **Write ordering:** If two constructs both write the same state, is the
  ordering defined? Can a write from A be lost by a concurrent write from B?
- **Partial visibility:** Can a reader see partially-updated state from a
  writer? Is there an atomicity boundary?
- **Stale reads:** Can a read return data that was valid when read but
  invalidated by a concurrent operation?

### Lens: dispatch_routing

For each construct with dispatch/routing patterns:
- **Case coverage:** Are all variants/types handled? What happens with
  an unknown or future variant?
- **Handler consistency:** Do all dispatch targets have compatible
  contracts with the dispatcher?
- **Default behavior:** Is there an explicit default/fallback? Is "do
  nothing" intentional or a bug?

### Recording findings

Write findings to `.feature/<slug>/hardening-report.md` incrementally
(per lens section). This ensures progress survives a crash.

```markdown
# Hardening Report — <slug>

## Lens: resource_lifecycle

| # | Construct | Question | Verdict | Detail |
|---|-----------|----------|---------|--------|
| H-RL-1 | HandleTracker | double-close | CONTRACT-SILENT | contract says "releases resources" but not what happens on 2nd call |
| H-RL-2 | HandleTracker | use-after-close | HARDENING-TEST | contract implies IllegalStateException — writing test |
| H-RL-3 | LocalTable | partial construction | TEST-COVERED | test_builder_cleanup_on_failure |
```

Update status.md substage → `absent-resolution` after all lenses complete.

---

## Step 3 — Gap resolution (CONTRACT-SILENT findings)

Collect all CONTRACT-SILENT findings from the hardening report. These are
behaviors the contract doesn't address but the domain lens analysis shows
are necessary design decisions.

If there are zero CONTRACT-SILENT findings, skip to Step 4.

### Format each finding as an ABSENT proposal

```markdown
### ABSENT: <one-line description>
- **Lens:** <domain lens>
- **Construct:** <construct name>
- **Gap:** <what the contract doesn't specify>
- **Why it matters:** <concrete failure scenario>
- **Proposed requirement:** <behavioral requirement text>
```

### Present to user

Group ABSENT proposals by lens. Display:

```
── Contract gaps found ────────────────────────
<n> behaviors your contracts don't specify.
These need design decisions before tests can be written.

resource_lifecycle (<n>):
  1. HandleTracker.close() — idempotency unspecified
     Proposed: "close() is idempotent — second call is a no-op"

  2. LocalEngine constructor — partial failure cleanup unspecified
     Proposed: "if HandleTracker build fails, close already-opened TableCatalog"

concurrency (<n>):
  3. HandleTracker.register() — concurrent access behavior unspecified
     Proposed: "register() is thread-safe — concurrent calls must not lose handles"
```

**Lite mode:** Display the findings but defer all by default:
- "Lite mode — <n> gaps logged in hardening-report.md, deferred for later resolution."
- Skip to Step 4.

**Full mode:** Use AskUserQuestion with options:
- "Promote all" — add all proposed requirements, write tests for all
- "Review" — show full details, decide per-finding
- "Defer all" — log all, write no tests

If "Review": present each finding individually with options:
- "Promote" — add requirement to spec (or contract in work-plan.md), write test
- "Preserve as negative" — document as intentional non-behavior, write negative test
- "Defer" — log, no test

### Apply promotions

For each promoted finding:
1. **If `.spec/` exists:** Add the proposed requirement to the appropriate spec
   file using the [ABSENT] → promote flow. Run `spec-validate.sh` to verify.
   Run `spec-resolve.sh` to check for conflicts with existing requirements.
   If conflict found: present conflict and ask user to resolve before continuing.

2. **If no spec system:** Append the requirement to the construct's contract
   definition in work-plan.md with a `<!-- Hardening: <date> -->` annotation.

Record resolutions in hardening-report.md:

```markdown
## ABSENT Resolutions

| # | Finding | Decision | Rationale |
|---|---------|----------|-----------|
| 1 | H-RL-1: close() idempotency | promoted | double-close from error recovery is common |
| 2 | H-CC-3: concurrent register() | promoted | spec says "thread-safe" but test was missing |
| 3 | H-DT-2: decode malformed input | deferred | low priority, encode is internal-only |
```

Update status.md substage → `writing-tests`.

---

## Step 4 — Write adversarial tests

Write tests for all HARDENING-TEST findings and promoted ABSENT findings.
These tests:
- Compile against existing stubs (no implementation yet)
- Fail with the expected behavior (TDD — implementation will make them pass)
- Are tagged with the hardening finding ID for traceability

### Test organization

Append adversarial test methods to existing test files from the test plan.
Do NOT create separate `*_adversarial` test files — the implementation
agent needs to see all tests for a construct in one place.

Each adversarial test method gets an intent comment block (same format
used by audit prove-fix, enabling the coverage check gate on re-audits):

```java
// Finding: H-RL-2
// Bug: operations succeed after close() — no closed flag check
// Correct behavior: register() after close() must throw IllegalStateException
// Fix location: HandleTracker.register()
// Regression watch: register() before close() must still work
@Test
void test_HandleTracker_register_afterClose_throwsIllegalState() {
    // ... test body
}
```

### Test writing principles

- **Behavioral, not implementation-prescriptive.** Test what must be true,
  not how it should be implemented. `assertThrows(IllegalStateException)`
  is behavioral. `assertTrue(field instanceof AtomicBoolean)` is not.
- **Concrete attack scenarios.** Each test has a specific input/sequence
  that triggers the behavior. "Call close(), then call register()" — not
  "verify thread safety."
- **Concurrency tests use controlled scheduling.** Use barriers, latches,
  or blocking stubs to widen race windows. Don't rely on sleep() or
  hope for interleaving. Add `@Timeout(10)` to prevent hangs.
- **One concern per test.** Don't combine double-close + use-after-close
  in one test method. Separate assertions make failures diagnostic.

### Verify tests fail

Run the test suite after writing. All new adversarial tests should fail
(stubs aren't implemented yet). If any adversarial test passes against
stubs, it's testing something that's already true — either the stub is
more complete than expected, or the test doesn't actually exercise the
adversarial scenario. Review and fix.

Update status.md substage → `verifying-failures`.

---

## Step 5 — Update artifacts and hand off

Update `.feature/<slug>/test-plan.md` — append a Hardening section:

```markdown
## Hardening (adversarial, Cycle <n>)

### Lenses activated: <list>

| Test name | File | Construct | Lens | Finding |
|-----------|------|-----------|------|---------|
| test_close_idempotent | <path> | HandleTracker | resource_lifecycle | H-RL-1 |
| test_register_afterClose | <path> | HandleTracker | resource_lifecycle | H-RL-2 |

### Coverage delta
- Tests before hardening: <N>
- Tests after hardening: <N + M>
- New adversarial tests: <M>
- ABSENT promoted: <P>
- ABSENT preserved: <Q>
- ABSENT deferred: <D>
```

Update status.md:
- Hardening → `complete`
- substage → `all-tests-verified-failing`
- Stage Completion table: Hardening row → Est. Tokens `~<N>K`

Append `hardening-complete` entry to cycle-log.md.

### Hand off

Read `automation_mode` from status.md.

**If `automation_mode: autonomous`:**

```
───────────────────────────────────────────────
🛡  HARDENING complete · <slug> · Cycle <n><  · WU-<n>>
  Tokens : <TOKEN_USAGE>
  Lenses : <active lenses>
  Tests  : <M> adversarial tests written
  Gaps   : <P> promoted, <Q> preserved, <D> deferred
───────────────────────────────────────────────
Starting implementation  ·  type stop to pause
───────────────────────────────────────────────
```

Then invoke `/feature-implement "<slug>"<  --unit WU-<n>>` as a sub-agent
immediately.

**If `automation_mode: manual` (or not set):**

```
───────────────────────────────────────────────
🛡  HARDENING complete · <slug> · Cycle <n><  · WU-<n>>
  Tokens : <TOKEN_USAGE>
  Lenses : <active lenses>
  Tests  : <M> adversarial tests written
  Gaps   : <P> promoted, <Q> preserved, <D> deferred
───────────────────────────────────────────────
```

Use AskUserQuestion with two options:
- "Proceed to implement"
- "Stop"

If "Proceed": invoke `/feature-implement "<slug>"<  --unit WU-<n>>`.
If "Stop": display `Next: /feature-implement "<slug>"` and stop.

---

## Hardening must NOT

- Read source code (there is no implementation yet)
- Modify existing tests from feature-test (only append new ones)
- Make implementation decisions (tests define requirements, not solutions)
- Write tests that prescribe implementation details (no reflection,
  no checking field types, no asserting specific synchronization primitives)
- Skip findings without a verdict (every question gets SPEC-COVERED,
  TEST-COVERED, HARDENING-TEST, or CONTRACT-SILENT)
- Write tests that can only pass with a specific implementation strategy
  (tests must be satisfiable by any correct implementation)
