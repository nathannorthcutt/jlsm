# /feature-plan "<feature-slug>"

Reads the brief, domain analysis, and governing ADRs to produce a work plan
and stub implementations. Idempotent — if planning is complete, reports and stops.

---

## Idempotency pre-flight (ALWAYS FIRST)

Read `.feature/<slug>/status.md`.

**If Planning stage is `complete`:**

Check status.md for substage `escalated-to-work-planner`. Also check
cycle-log.md for a recent `test-to-planner-escalation` entry.

If an escalation is pending: proceed to the Contract Revision section below
instead of stopping.

Otherwise:
```
🏗️  WORK PLANNER · <slug>
───────────────────────────────────────────────
Work planning is already complete for '<slug>'.
Work plan: .feature/<slug>/work-plan.md

  Type **yes**  to proceed to test writing  ·  or: stop
```
If "yes": invoke /feature-test "<slug>" as a sub-agent immediately.
If "stop": display `Next: /feature-test "<slug>"` and stop.

**If Planning stage is `in-progress`:**
Display opening header, then:
- Read work-plan.md if it exists (partial content may be present)
- Say: "Planning was in progress — resuming. Checking what stubs were already written."
- Check which stub files in the work plan already exist vs. are missing
- Write only missing stubs; do not overwrite existing ones
- Jump to Step 4 (write work-plan.md) if stubs are already complete

**If Planning stage is `not-started`:**
- Verify `.feature/<slug>/domains.md` exists. If not: "Run /feature-domains first."
- Set status.md: Planning → `in-progress`, substage → `loading-context`
- Display opening header and proceed to Step 1

Display opening header:
```
───────────────────────────────────────────────
🏗️  WORK PLANNER · <slug>
───────────────────────────────────────────────
```

---

## Step 0b — Token tracking

Run silently: `bash -c 'source .claude/scripts/token-usage.sh && token_checkpoint ".feature/<slug>" "planning"'`

---

## Step 1 — Load context

Read in order:
1. `.feature/project-config.md`
2. `PROJECT-CONTEXT.md` — if it exists, read Active entries (global + scoped to
   relevant modules). Constraints from context entries should be reflected in
   work plan contracts.
3. `.feature/<slug>/brief.md`
4. `.feature/<slug>/domains.md`
5. All ADR files linked in domains.md
6. Key sections of linked KB subject files (`#key-parameters`, `#implementation-notes`)

Scan the source directory structure (names and module layout only — don't read
every file). Read only files likely relevant to this feature.

Update status.md substage → `surveying-codebase`.

---

## Step 2 — Identify existing vs. new constructs

Produce a catalogue and display:
```
── Design ──────────────────────────────────────
Existing constructs to USE or EXTEND:
  ✓ <Name> at <src/path>  —  <how this feature uses it>

New constructs to CREATE:
  + <Name>  —  <purpose>  →  <src/path>  —  depends on: <list>
```

Display:
```
  Type **yes**  ·  or: describe corrections
```
Wait for the user to type "continue" or describe corrections. Update status.md substage → `confirmed-design`
after confirmation.

---

## Step 2b — Work unit analysis

After design is confirmed, evaluate whether to split into work units.

### Token estimation

For each new construct estimate:
- Stub file: ~1K tokens
- Test file: ~2K tokens
- Work-plan contract section: ~0.5K tokens

**Single-unit total load** (what the Code Writer loads in one session):
```
N constructs × 3.5K = estimated Code Writer session size
```

**Split-unit total load** (per unit session):
```
(constructs in unit × 3.5K) + 0.5K dependency interfaces + 0.5K status overhead
```

**Split saves tokens when:** single-unit load > 15K AND at least one clean
dependency boundary exists (a group of constructs with no intra-feature deps).

### Decision rules

| Constructs | Clean dep boundary? | Recommendation |
|------------|--------------------|--------------  |
| 1–3 | any | Never split — overhead exceeds savings |
| 4–5 | no | Single unit |
| 4–5 | yes | Propose split — marginal but clean |
| 6+ | no | Single unit unless one group is fully independent |
| 6+ | yes | Split — savings are real (~15K+ vs ~6-8K per session) |

A **clean dependency boundary** means: group A constructs do not depend on
group B constructs within this feature (they may depend on existing code).

### Natural split signals (lower the threshold)
- Two constructs have zero intra-feature dependencies → free split, no interface load cost
- Constructs span different source modules or packages → natural seam
- One construct group is a pure data/type layer (no logic) → always a good WU-1

### Override rules (never split regardless of size)
- Feature has a single acceptance criterion that requires all constructs together
- User explicitly said "implement as single unit" during scoping
- All constructs are tightly coupled with circular dependencies

### Display the analysis

Always show the analysis, even when recommending no split:

```
── Work unit analysis ──────────────────────────────
Constructs: <n> new  |  Estimated single-unit load: ~<N>K

<If not splitting:>
Recommendation: single unit
Reason: <"only <n> constructs — split overhead not worth it" |
         "no clean dependency boundary" |
         "all constructs are tightly coupled">

Proceeding as single unit.

<If proposing split:>
Recommendation: split into <n> work units
Estimated savings: ~<N>K per session vs ~<N>K single unit

  WU-1: <name>  (<n> constructs, no intra-feature deps)
        Constructs: <list>
        Est. session load: ~<N>K

  WU-2: <name>  (depends on WU-1)
        Constructs: <list>
        Dependency interface loaded: <WU-1 public API> ~0.5K
        Est. session load: ~<N>K

  WU-3: <name>  (depends on WU-1 + WU-2)  [if applicable]
        ...
```

### Execution strategy prompt

After the work unit analysis display, determine the execution strategy.

**If feature doesn't qualify for splitting** (1–3 constructs, no boundaries):
set `execution_strategy: cost` implicitly in status.md, skip the prompt.

**If feature qualifies for splitting**, show the analysis then ask:

```
── Execution strategy ─────────────────────────────
  cost      — sequential execution. Splits only when a single session
              exceeds ~15K tokens.

  balanced  — split at clean boundaries, independent units run in parallel.
              Moderate token overhead.

  speed     — split at every clean boundary, maximum parallelism.
              Fastest completion, highest token cost.

Type: cost, balanced, or speed
```

Record `execution_strategy: <choice>` in status.md and in the
`<!-- execution_strategy: -->` comment under `## Work Units`.

**Strategy behaviour:**

- `cost`: use current split thresholds (>15K). If under threshold, `work_units: none`.
  If over, split but units run sequentially. No per-unit dirs.
- `balanced`: lower threshold to >8K OR 2+ independent groups. Split and create
  per-unit dirs (see below).
- `speed`: split at every clean boundary (still never split 1–3 total constructs).
  Create per-unit dirs.

**If "cost" and under threshold:** record `work_units: none` in status.md, proceed to Step 3.
**If "cost" and over threshold:** proceed with unit structure (sequential execution).
**If "balanced" or "speed":** proceed with unit structure and parallel setup.

### Dependency graph (balanced/speed only)

After the Work Units table is confirmed, display:
```
── Dependency graph ───────────────────────────
  Batch 1: WU-1, WU-2  (parallel — no mutual deps)
  Batch 2: WU-3         (depends on WU-1)

  Critical path: <n> sequential batches
  Estimated time: ~<n> sessions (vs ~<n> sequential)
```

### Per-unit directory creation (balanced/speed only)

Create `.feature/<slug>/units/WU-N/` for each unit with:

Per-unit `status.md`:
```markdown
---
feature: "<slug>"
unit: "WU-<n>"
unit_name: "<name>"
---

## Current Position
**Stage:** not-started
**Substage:** —
**Last successful checkpoint:** unit directory created
**Cycle:** 0

## TDD Cycle Tracker
| Cycle | Tests written | Tests passing | Refactor done | Missing tests |
```

Per-unit `cycle-log.md`:
```markdown
# Cycle Log — <slug> / WU-<n>
<!-- Append-only. Each agent appends entries. -->
---
```

### Write Work Units table

After confirmation, write the Work Units table to status.md:
```markdown
## Work Units

| Unit | Name | Constructs | Depends On | Status | Cycle |
|------|------|------------|------------|--------|-------|
| WU-1 | <name> | <list> | — | not-started | — |
| WU-2 | <name> | <list> | WU-1 | blocked | — |
```

Units with unmet dependencies start as `blocked`. The first unit with no
dependencies starts as `not-started` (ready to begin).

---

## Step 3 — Write stubs

Update status.md substage → `writing-stubs`.

For each new or extended construct, write the stub. Check whether the file
already exists first — if it does and contains a stub for this construct,
skip it (idempotent). Only write missing stubs.

Every stub must have:
- Correct signature with types (where the language supports them)
- Docstring/comment block: what it receives, returns, side effects, governing ADR
- NotImplementedError or language equivalent for unimplemented body

**Python:**
```python
def function_name(param: Type) -> ReturnType:
    """
    Contract: <what this does>
    Params: param — <description>
    Returns: <description>
    Side effects: <or "none">
    Governed by: <ADR path or KB section>
    """
    raise NotImplementedError
```

**TypeScript:**
```typescript
function functionName(param: Type): ReturnType {
    // Contract: <what this does>
    // Governed by: <ADR path>
    throw new Error("Not implemented");
}
```

**Go:**
```go
// FunctionName does <what>. Governed by: <ADR path>
func FunctionName(param Type) (ReturnType, error) {
    return nil, errors.New("not implemented")
}
```

---

## Step 4 — Write work-plan.md

Write `.feature/<slug>/work-plan.md` (Work Plan Template below).

Update status.md: Planning → `complete`, last checkpoint → "work-plan.md written,
<n> stubs created".
Update the Stage Completion table: Planning row → Est. Tokens `~<N>K` (sum of files
loaded: brief ~2K + domains ~3K + ADRs + source scan).
Append `planned` entry to cycle-log.md:
```markdown
## <YYYY-MM-DD> — planned
**Agent:** 🏗️ Work Planner
**Summary:** <n> new constructs, <n> extensions. Stubs written.
**Files read:** brief ~2K, domains ~3K, <ADR files>, <source files scanned>
**Token estimate:** ~<N>K
---
```
Update `.feature/CLAUDE.md`.

---

## Step 5 — Hand off

**Token tracking:** run `bash -c 'source .claude/scripts/token-usage.sh && token_summary ".feature/<slug>" "planning"'`
and capture the output as TOKEN_USAGE. Update the Stage Completion table: Planning
row → Actual Tokens from TOKEN_USAGE.

Display:
```
───────────────────────────────────────────────
🏗️  WORK PLANNER complete · <slug>
  Tokens : <TOKEN_USAGE>
───────────────────────────────────────────────
Work plan: .feature/<slug>/work-plan.md
Stubs written: <list of files>

Review the stub contracts above — the Test Writer works from these contracts
and changing them later requires re-running tests.
```

### Step 5a — Choose automation mode

Ask the user how they want to run the TDD loop. This choice is recorded now and
persists for the lifetime of this feature — it will not be asked again.

**When `execution_strategy` is `balanced` or `speed`:**

Display:
```
── How would you like to run the TDD loop? ─────
  autonomous  — independent units run their full test → implement → refactor
               cycles in parallel. Checkpoints happen at batch boundaries.

  manual      — I'll pause between batches and wait for your go-ahead.

Type: autonomous  or  manual
```

**When `execution_strategy` is `cost` (or not set):**

Display:
```
── How would you like to run the TDD loop? ─────
  autonomous  — test → implement → refactor cycles run without stopping.
               I'll pause if I find something that needs your input.

  manual      — I'll stop after each stage and wait for your command.

Type: autonomous  or  manual
```

Wait for input:
- "autonomous": set `automation_mode: autonomous` in status.md
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

### Step 5b — Start test writing or coordinator

**If `execution_strategy` is `balanced` or `speed`:**

```
───────────────────────────────────────────────
Launching parallel coordinator.
Run /feature-resume "<slug>" at any point to see batch status.
───────────────────────────────────────────────
```
Invoke `/feature-coordinate "<slug>"` as a sub-agent immediately.

**If `execution_strategy` is `cost` (or not set):**

If work units are defined:
```
───────────────────────────────────────────────
Start with the first unit:
  /feature-test "<slug>" --unit WU-1

Each unit runs its own test → implement → refactor cycle.
Run /feature-resume "<slug>" at any point to see which unit is next.
───────────────────────────────────────────────
```
Invoke `/feature-test "<slug>" --unit WU-1` as a sub-agent immediately.

If single unit (no work units):
Invoke `/feature-test "<slug>"` as a sub-agent immediately.

If the user types stop before test writing begins:
```
When you're ready:
  /feature-test "<slug>"
```

---

## Contract Revision (escalation entry point)

Entered when status.md substage is `escalated-to-work-planner` and cycle-log.md
contains a `test-to-planner-escalation` entry.

### Step R1 — Load the escalation

Read the most recent `test-to-planner-escalation` entry from cycle-log.md. Extract:
- The contract/construct name
- The work plan section reference
- The conflict description
- The brief acceptance criterion that contradicts the contract
- The escalation count (N of 3)

Read the relevant contract section from work-plan.md, the referenced acceptance
criterion from brief.md, and any governing ADRs linked in the contract.

### Step R2 — Diagnose and revise

Determine the root cause:

1. **Contract contradicts brief** — the work plan constraint does not satisfy
   the acceptance criterion. Revise the contract to match the brief.

2. **Contract contradicts ADR** — the constraint conflicts with a governing
   architecture decision. Revise the contract to align with the ADR.

3. **Contract is internally inconsistent** — the signature, return type, or
   error conditions conflict with each other. Fix the inconsistency.

4. **Brief is ambiguous** — the acceptance criterion can be read multiple ways,
   and the contract chose the wrong reading. Revise the contract to match the
   intended reading. If the intended reading is unclear, ask the user.

For each case, make the minimal change to the contract that resolves the conflict.
Do NOT rewrite unrelated contracts.

Update the contract section in work-plan.md:
- Revise the Contract Definition for the affected construct
- Update the stub signature if it changed
- Add a revision note: `<!-- Revised <YYYY-MM-DD>: <one-line reason> -->`

If the stub signature changed, update the stub file to match. Preserve any
implementation code the Code Writer has already written — change only the
signature and contract docstring/comment.

### Step R3 — Log and hand off

Append `contract-revised` to cycle-log.md:
```markdown
## <YYYY-MM-DD> — contract-revised
**Agent:** 🏗️ Work Planner
**Contract:** <construct name>
**Change:** <what was wrong → what it is now>
**Root cause:** <contradicts brief | contradicts ADR | internally inconsistent | brief ambiguous>
**Escalation count:** <N> of 3
---
```

Update status.md substage → `contract-revised`.

Display:
```
🏗️  WORK PLANNER · contract revision · <slug>
───────────────────────────────────────────────
Revised: <construct name>
Root cause: <one sentence>
Change: <what changed in the contract>

Contract updated — resuming test → implement cycle.
```

Invoke `/feature-test "<slug>"<  --unit WU-<n>>` as a sub-agent immediately.
Do not wait for user input — the contract is revised and the cycle can resume.

---

## Work Plan Template

```markdown
---
feature: "<slug>"
created: "<YYYY-MM-DD>"
language: "<language>"
---

# Work Plan — <slug>

## References
- Brief: [brief.md](brief.md)
- Domains: [domains.md](domains.md)
- Governing ADRs: <links>

## Existing Constructs

| Construct | File | Usage |
|-----------|------|-------|
| <n> | <path> | use / extend — <one line> |

## New Constructs

| Construct | File | Contract summary |
|-----------|------|-----------------|
| <n> | <path> | <one line> |

## Stub Files Written

| File | Status |
|------|--------|
| <path> | stubbed |

## Contract Definitions

### <ConstructName>
**File:** `<path>`
**Governed by:** [<ADR or KB>](<link>)
**Signature:** `<stub signature>`
**Contract:**
- Receives: <params>
- Returns: <return>
- Side effects: <or "none">
- Error conditions: <what is raised/returned on failure>

---

## Work Units
<!-- Omit this section if work_units: none in status.md -->

### WU-1: <name>
**Constructs:** <list>
**Depends on:** none
**Est. session load:** ~<N>K

### WU-2: <name>
**Constructs:** <list>
**Depends on:** WU-1 public interface
**Est. session load:** ~<N>K

## Implementation Order
<!-- Single-unit features: list constructs in dependency order -->
<!-- Multi-unit features: list units, then constructs within each unit -->
1. <construct or WU-N> — no dependencies
2. <construct or WU-N> — depends on: <1>
```
