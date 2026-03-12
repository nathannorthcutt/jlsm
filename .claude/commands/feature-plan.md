# /feature-plan "<feature-slug>"

Reads the brief, domain analysis, and governing ADRs to produce a work plan
and stub implementations. Idempotent — if planning is complete, reports and stops.

---

## Idempotency pre-flight (ALWAYS FIRST)

Read `.feature/<slug>/status.md`.

**If Planning stage is `complete`:**
```
Work planning is already complete for '<slug>'.
Work plan: .feature/<slug>/work-plan.md
Next: /feature-test "<slug>"
Run /feature-resume "<slug>" to see full status.
```
Stop.

**If Planning stage is `in-progress`:**
- Read work-plan.md if it exists (partial content may be present)
- Say: "Planning was in progress — resuming. Checking what stubs were already written."
- Check which stub files in the work plan already exist vs. are missing
- Write only missing stubs; do not overwrite existing ones
- Jump to Step 4 (write work-plan.md) if stubs are already complete

**If Planning stage is `not-started`:**
- Verify `.feature/<slug>/domains.md` exists. If not: "Run /feature-domains first."
- Set status.md: Planning → `in-progress`, substage → `loading-context`
- Proceed to Step 1

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

Produce a catalogue and display in chat:
```
Existing constructs to USE or EXTEND:
  ✓ <Name> at <src/path>  —  <how this feature uses it>

New constructs to CREATE:
  + <Name>  —  <purpose>  →  <src/path>  —  depends on: <list>
```

Ask: "Does this match your expectations before I write the stubs?" Wait for
confirmation or corrections. Update status.md substage → `confirmed-design`
after confirmation.

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
Append `planned` entry to cycle-log.md.
Update `.feature/CLAUDE.md`.

---

## Step 5 — Hand off

```
Work plan: .feature/<slug>/work-plan.md
Stubs written: <list of files>

Next: /feature-test "<slug>"
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

## Implementation Order
1. <construct> — no dependencies
2. <construct> — depends on: <1>
```
