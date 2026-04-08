---
description: "Produce a work plan and stub implementations from the brief and domain analysis"
argument-hint: "<feature-slug>"
---

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
```
Use AskUserQuestion with options:
  - "Proceed to test writing"
  - "Stop"
If "Proceed to test writing": invoke /feature-test "<slug>" as a sub-agent immediately.
If "Stop": display `Next: /feature-test "<slug>"` and stop.

**If Planning stage is `in-progress`:**
Display opening header, then:
- Say: "Planning was in progress — resuming."
- If substage is `writing-stubs` or later:
  - Read work-plan.md if it exists (may be partial or absent — the subagent may
    have been interrupted)
  - Check which stub files from the design already exist vs. are missing
  - If all stubs present AND work-plan.md exists AND status is `complete`:
    jump to Step 4 (hand off)
  - Otherwise: re-launch the Step 3 subagent. Pass the full construct list but
    note which stubs already exist so the subagent skips them (idempotent).
    The subagent will write missing stubs AND write/rewrite work-plan.md.
- If substage is before `writing-stubs` (e.g. `loading-context`, `surveying-codebase`,
  `confirmed-design`): resume from the appropriate earlier step (re-run design
  confirmation if needed, then proceed to Step 3 subagent)

**If Planning stage is `not-started`:**
- Verify `.feature/<slug>/domains.md` exists. If not: "Run /feature-domains first."
- Check for spec infrastructure (`test -f .spec/CLAUDE.md || test -d .spec/registry`).
  If spec infrastructure exists, check whether the Spec Authoring stage in status.md
  is `complete`. If Spec Authoring is `not-started` or `in-progress`, warn:
  "Spec infrastructure exists but specs haven't been authored for this feature."
  Use AskUserQuestion with options:
    - "Run /spec-author first" (description: stop and let user run spec-author)
    - "Skip specs" (description: plan from brief and domains only)
  If "Skip specs": proceed normally (specs will be absent from context).
  If "Run /spec-author first": stop and let the user run spec-author.
- Set status.md: Planning → `in-progress`, substage → `loading-context`
- Display opening header and proceed to Step 1

Display opening header:
```
───────────────────────────────────────────────
🏗️  WORK PLANNER · <slug>
───────────────────────────────────────────────
```

---

## Step 0a — Progress tracking

Use TodoWrite to show progress in the Claude Code UI (visible via Ctrl+T).
Each TodoWrite call replaces the full list — always include all items.

**Pipeline context:** Include the full feature lifecycle as top-level items so
the user sees where this stage fits in the overall flow. Mark earlier stages as
`completed`, the current stage as `in_progress`, and later stages as `pending`.

**Stage-level detail:** Within the current stage, add granular items for each
piece of work. Use `activeForm` on in-progress items to show real-time detail
(e.g., "Reading domains.md — checking ADR links").

Example checklist at the start of planning:
```json
[
  {"id": "pipeline-scoping", "content": "Scoping", "status": "completed", "priority": "medium"},
  {"id": "pipeline-domains", "content": "Domain analysis", "status": "completed", "priority": "medium"},
  {"id": "pipeline-planning", "content": "Work planning", "status": "in_progress", "priority": "high",
   "activeForm": "Loading context files"},
  {"id": "pipeline-testing", "content": "Test writing", "status": "pending", "priority": "medium"},
  {"id": "pipeline-implementation", "content": "Implementation", "status": "pending", "priority": "medium"},
  {"id": "pipeline-refactor", "content": "Refactor & review", "status": "pending", "priority": "medium"},
  {"id": "pipeline-pr", "content": "PR draft", "status": "pending", "priority": "medium"},
  {"id": "load-context", "content": "Load context and survey codebase", "status": "in_progress", "priority": "medium"},
  {"id": "design", "content": "Design confirmation", "status": "pending", "priority": "high"},
  {"id": "work-units", "content": "Work unit analysis", "status": "pending", "priority": "medium"},
  {"id": "stubs", "content": "Write stubs and work plan", "status": "pending", "priority": "high"},
  {"id": "handoff", "content": "Hand off to test writing", "status": "pending", "priority": "medium"}
]
```

As the stage progresses, update both the stage-level items AND the pipeline
item's `activeForm` to reflect current work.

---

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
7. **Resolve specs** — check for spec infrastructure first:
   ```bash
   test -f .spec/CLAUDE.md || test -d .spec/registry
   ```
   If spec infrastructure exists, run the resolver:
   ```bash
   bash .claude/scripts/spec-resolve.sh "<feature description>" 8000
   ```
   If the resolver returns specs, these are the **primary context** for work
   planning — alongside the brief and domains, specs are the authoritative
   source for behavioral requirements. The three documents serve different
   roles:
   - **Brief** — describes *why* we're building and the user's intent
   - **Domains** — describes *what constraints* exist (ADRs, KB, gaps)
   - **Specs** — describes *what must be true* (behavioral requirements)

   When specs exist, contracts in the work plan MUST trace to spec requirements.
   The brief and domains provide context and constraints; the spec provides the
   requirements that contracts must deliver.

   If the brief contradicts a spec requirement, flag the contradiction for the
   user — the spec takes precedence unless the user explicitly overrides.

   If spec infrastructure does not exist, or the resolver returns no specs for
   this feature's domains, proceed as before — derive contracts from the brief
   and ADRs.

   **Conflict gate:** After loading the resolved bundle, check if it contains
   a `## Conflicts` section. If it does:

   Display:
   ```
   ⚠️  Spec conflicts detected in resolved bundle:
     <list each CONFLICT and INVALIDATES line from the section>

   These must be resolved before planning can proceed.
   Options: resolve via /spec-author, or type **override** to acknowledge and continue.
   ```

   Wait for user input:
   - If the user runs /spec-author: stop and let them resolve.
   - If the user types "override": proceed with planning, but record the
     conflicts in work-plan.md under a `## Open Risks` section:
     ```markdown
     ## Open Risks
     - Spec conflict override: <each conflict line>
     ```
     These are carried forward as open risks in the work plan.

8. Read project coding conventions: `CONTRIBUTING.md`, `.claude/rules/`,
   and any language-specific style guides. These constrain the design space
   for construct shapes.

Scan the source directory structure (names and module layout only — don't read
every file). Read only files likely relevant to this feature.

Update status.md substage → `surveying-codebase`.

---

## Step 2 — Map requirements to constructs

**If specs were resolved in Step 1:** translate each spec requirement into the
construct(s) that will deliver it. The spec describes behavioral contracts
(what must be true); the work planner designs the implementation structure
(which constructs deliver those behaviors). Every spec requirement must map
to at least one construct. If a requirement can't be mapped, add a construct
or extend an existing one — do not present a gap to the user.

**If no specs exist:** derive contracts from the brief and ADRs as before.

### Self-check (before presenting to the user)

After generating the construct catalogue, verify the design before presenting:

1. **Coverage:** Every spec requirement must map to at least one contract. If
   any requirement has no contract, add a construct or extend an existing one
   to cover it.

2. **Agent success shaping:** For each contract, ask "how will the implementing
   agent misunderstand this?" If a contract is ambiguous or the construct
   boundaries make it easy to implement correctly-but-wrong, reshape the
   constructs. Split a large construct into two with clearer contracts. Merge
   two constructs that are too tightly coupled to implement independently.
   Respect the project's coding conventions — don't reshape into patterns
   that violate established architecture.

3. **Dependency completeness:** For each construct, verify it has access to
   all inputs its contract needs. If a dependency is missing from the graph,
   add it — don't flag it.

4. **Shared state identification:** For each pair of constructs, check whether
   their contracts reference shared mutable state or produce/consume the same
   data. Record these edges — they determine clustering in Step 2b.

### Present the design

Produce a catalogue and display:
```
── Design ──────────────────────────────────────
Spec requirements mapped: <N>/<total>

Existing constructs to USE or EXTEND:
  ✓ <Name> at <src/path>  —  delivers: <spec requirement IDs>

New constructs to CREATE:
  + <Name>  —  <purpose>  →  <src/path>  —  delivers: <spec requirement IDs>
                                             depends on: <list>
                                             shares state with: <list or "none">
```

Use AskUserQuestion with options:
  - "Approve" (description: design looks correct, proceed)
  - "Needs changes" (description: user will describe corrections)

If "Approve": proceed. User corrections are not needed.
If "Needs changes": ask the user what to change. User corrections
become additional constraints on the design — the work planner respects them
while maintaining coverage of all spec requirements.

Update status.md substage → `confirmed-design` after confirmation.

---

## Step 2b — Work unit analysis

After design is confirmed, cluster constructs into work units using the
construct graph from Step 2.

### Clustering rules

Work units are determined by **construct relationships**, not just token cost.
The construct graph has three edge types (identified in Step 2's self-check):

- **depends_on** — construct A calls or imports construct B's interface
- **shares_state** — constructs A and B read/write the same mutable resource
- **produces/consumes** — construct A's output is construct B's input

**Clustering constraints:**
1. Constructs connected by `shares_state` edges must be in the same work unit.
   They cannot be parallel — their TDD pass must see both constructs.
2. Constructs connected by `produces/consumes` should be in the same work unit
   when possible. If split, the consuming unit must include the producing
   construct as visible context.
3. Constructs connected only by `depends_on` can be in separate work units.
   The dependent unit loads the dependency's public interface, not its internals.

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
(constructs in unit × 3.5K) + context from prior units + 0.5K status overhead
```

### Decision rules

| Constructs | Shared state? | Recommendation |
|------------|--------------|----------------|
| 1–3 | any | Never split — overhead exceeds savings |
| 4–5 | all share state | Single unit — can't split shared state |
| 4–5 | clean groups | Propose split — marginal but clean |
| 6+ | all share state | Single unit — but flag size for user awareness |
| 6+ | clean groups | Split — savings are real |

### Work unit ordering

Units are ordered by dependency. When a later unit depends on an earlier
unit's constructs (via `shares_state` or `produces/consumes`), those
constructs are included as visible context in the later unit's TDD pass.
The work plan must state this explicitly:

```
WU-2 context includes: <construct A from WU-1> (shares state with <construct C>)
```

This ensures the TDD agent has visibility into all constructs it needs.

### Natural split signals (lower the threshold)
- Two constructs have zero intra-feature dependencies → free split
- Constructs span different source modules or packages → natural seam
- One construct group is a pure data/type layer (no logic) → always a good WU-1

### Override rules (never split regardless of size)
- Feature has a single acceptance criterion that requires all constructs together
- User explicitly said "implement as single unit" during scoping
- All constructs share mutable state (no clean boundary exists)

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

Use AskUserQuestion with options:
  - "Cost" (description: sequential execution — splits only when a single session exceeds ~15K tokens)
  - "Balanced" (description: split at clean boundaries, independent units run in parallel in batches — moderate token overhead)
  - "Speed" (description: split at every clean boundary, maximum parallelism — fastest wall-clock time, highest token cost)

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

After the Work Units table is confirmed, compute the dependency graph and
critical path. Display:

```
── Dependency graph ───────────────────────────
  WU-1 ──┐
          ├── WU-3
  WU-2 ──┘

  Critical path: WU-1 → WU-3 (2 sequential stages)
  Max parallelism: 2 units simultaneously
  Speed mode: units launch on dependency resolution, no batch waits
  Balanced mode: Batch 1 {WU-1, WU-2} → Batch 2 {WU-3}
```

The critical path is the longest chain of dependent units — it determines the
minimum wall-clock time regardless of how much parallelism is available. Display
it so the user can see the theoretical minimum and compare against batch mode.

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

## Step 3 — Write stubs and work plan (subagent)

Delegate stub creation and work-plan assembly to a subagent to keep file I/O
out of the main conversation context. The subagent runs autonomously — no user
interaction is needed for the mechanical writing phase.

**Launch a subagent** with the following prompt, substituting the confirmed
design data from Step 2:

````
You are a Work Planner Agent completing the mechanical writing phase for
feature "<slug>".

## Your task

Write stub files and work-plan.md based on the confirmed design below, then
update status and cycle-log.

## Confirmed design

Feature slug: <slug>
Language: <language from project-config.md>

Existing constructs:
<paste the "Existing constructs to USE or EXTEND" list from Step 2>

New constructs:
<paste the "New constructs to CREATE" list from Step 2, including paths,
signatures, dependencies, and governing ADR references>

Work units: <"none" or paste the work unit assignments from Step 2b>

## Step A — Write stubs

Update `.feature/<slug>/status.md` substage → `writing-stubs`.

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

## Step B — Write work-plan.md

Write `.feature/<slug>/work-plan.md` using the Work Plan Template from
the feature-plan command. Include all sections: References, Existing Constructs,
New Constructs, Stub Files Written, Contract Definitions, Work Units (if any),
and Implementation Order.

## Step C — Update status and log

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

## Step D — Return summary

Return a structured summary:
```
Stubs written: <list of file paths>
Stubs skipped (already existed): <list or "none">
Work plan: .feature/<slug>/work-plan.md
Constructs: <n> new, <n> extensions
```
````

Wait for the subagent to return. Use the returned summary for the handoff
display in Step 4.

---

## Step 4 — Hand off

Use the subagent's returned summary (stubs written, stubs skipped, construct counts)
for the display below. Do not re-read stub files — the subagent already confirmed them.

Display:
```
───────────────────────────────────────────────
🏗️  WORK PLANNER complete · <slug>
  Tokens : <TOKEN_USAGE>
───────────────────────────────────────────────
Work plan: .feature/<slug>/work-plan.md
Stubs written: <list from subagent summary>

Review the stub contracts in work-plan.md — the Test Writer works from these
contracts and changing them later requires re-running tests.
```

### Step 4a — Choose automation mode

Ask the user how they want to run the TDD loop. This choice is recorded now and
persists for the lifetime of this feature — it will not be asked again.

**When `execution_strategy` is `balanced` or `speed`:**

Use AskUserQuestion with options:
  - "Auto" (description: independent units run their full test/implement/refactor cycles in parallel — checkpoints happen at batch boundaries)
  - "Manual" (description: pause between batches and wait for go-ahead)

**When `execution_strategy` is `cost` (or not set):**

Use AskUserQuestion with options:
  - "Auto" (description: cycles run without stopping, pauses only if input needed)
  - "Manual" (description: stop after each stage and wait for command)

Wait for input:
- "auto" (or "autonomous"): set `automation_mode: autonomous` in status.md
- "manual": set `automation_mode: manual` in status.md

If auto, display:
```
Running autonomously. Type stop at any time to pause.
──────────────────────────────────────────────────
```

If manual, display:
```
Manual mode. I'll prompt you at each stage boundary.
──────────────────────────────────────────────────
```

### Step 4b — Start test writing or coordinator

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

Determine the root cause. **If specs exist for this feature, the spec is the
tiebreaker.** Check the contract against the spec requirement it claims to
deliver — if the contract matches the spec, the escalation is wrong. If the
contract deviates from the spec, the contract is wrong.

1. **Contract contradicts spec** — the contract does not satisfy the spec
   requirement it maps to. Revise the contract to deliver the spec requirement.

2. **Contract contradicts brief** — the work plan constraint does not satisfy
   the acceptance criterion. Check whether the brief or the spec is authoritative.
   If the spec exists and covers this behavior, align with the spec. If no spec
   exists, revise the contract to match the brief.

3. **Contract contradicts ADR** — the constraint conflicts with a governing
   architecture decision. Revise the contract to align with the ADR.

4. **Contract is internally inconsistent** — the signature, return type, or
   error conditions conflict with each other. Fix the inconsistency.

5. **Brief is ambiguous** — the acceptance criterion can be read multiple ways,
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
- Specs: <spec IDs and paths from .spec/, or "none — contracts derived from brief">

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

## Requirement Traceability
<!-- Every spec requirement must map to at least one contract. -->

| Spec Requirement | Contract(s) | Work Unit |
|-----------------|-------------|-----------|
| <F01.R1> | <ConstructName> | WU-1 |

## Contract Definitions

### <ConstructName>
**File:** `<path>`
**Delivers:** <spec requirement IDs, e.g. F01.R5, F01.R6>
**Governed by:** [<ADR or KB>](<link>)
**Signature:** `<stub signature>`
**Contract:**
- Receives: <params>
- Returns: <return>
- Side effects: <or "none">
- Error conditions: <what is raised/returned on failure>
- Shared state: <what mutable resources this construct accesses, or "none">

---

## Work Units
<!-- Omit this section if work_units: none in status.md -->

### WU-1: <name>
**Constructs:** <list>
**Depends on:** none
**Context from prior units:** none
**Est. session load:** ~<N>K

### WU-2: <name>
**Constructs:** <list>
**Depends on:** WU-1 public interface
**Context from prior units:** <constructs from WU-1 visible in this unit's TDD pass, with reason (shares state / produces-consumes)>
**Est. session load:** ~<N>K

## Implementation Order
<!-- Single-unit features: list constructs in dependency order -->
<!-- Multi-unit features: list units, then constructs within each unit -->
1. <construct or WU-N> — no dependencies
2. <construct or WU-N> — depends on: <1>
```
