# /feature-plan "<feature-slug>"

Reads the brief, domain analysis, and governing ADRs to produce a work plan
and stub implementations. Idempotent — if planning is complete, reports and stops.

---

## Idempotency pre-flight (ALWAYS FIRST)

Read `.feature/<slug>/status.md`.

**If Planning stage is `complete`:**
```
🏗️  WORK PLANNER · <slug>
───────────────────────────────────────────────
Work planning is already complete for '<slug>'.
Work plan: .feature/<slug>/work-plan.md
Next: /feature-test "<slug>"
Run /feature-resume "<slug>" to see full status.
```
Stop.

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

## Step 1 — Load context

Read in order:
1. `.feature/project-config.md`
2. `.feature/<slug>/brief.md`
3. `.feature/<slug>/domains.md`
4. All ADR files linked in domains.md
5. Key sections of linked KB subject files (`#key-parameters`, `#implementation-notes`)

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
  ↵  looks good, write the stubs  ·  or type: corrections
```
Wait for the user to press Enter or describe corrections. Update status.md substage → `confirmed-design`
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

  ↵  split into work units  ·  or type: single
```

If user chooses single: record `work_units: none` in status.md, proceed to Step 3.
If user chooses split (or no split was proposed): proceed with unit structure.

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

Display:
```
───────────────────────────────────────────────
🏗️  WORK PLANNER complete · <slug>
⏱  Token estimate: ~<N>K
   Loaded: brief ~2K, domains ~3K<, ADR files ~2-4K each, source files scanned>
   Wrote:  work-plan ~4K, <n> stub files ~<N>K total
───────────────────────────────────────────────
Work plan: .feature/<slug>/work-plan.md
Stubs written: <list of files>

Review the stub contracts above — the Test Writer works from these contracts
and changing them later requires re-running tests.

───────────────────────────────────────────────
↵  continue to test writing  ·  or type: stop
───────────────────────────────────────────────
```

If work units are defined:
```
───────────────────────────────────────────────
Start with the first unit:
  /feature-test "<slug>" --unit WU-1

Each unit runs its own test → implement → refactor cycle.
Run /feature-resume "<slug>" at any point to see which unit is next.
───────────────────────────────────────────────
```
If yes: invoke /feature-test "<slug>" --unit WU-1 as a sub-agent immediately.
If no: print the command above and stop.

If single unit (no work units):
If the user presses Enter or says yes: invoke /feature-test "<slug>" as a sub-agent immediately.
If the user types stop or no:
```
When you're ready:
  /feature-test "<slug>"
```

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
