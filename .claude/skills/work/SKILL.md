---
description: "Create a work group for coordinating multi-feature work"
argument-hint: "<goal>"
---

# /work "<goal>"

Creates a work group — a named collection of work definitions that share a
larger goal. Work groups coordinate multi-feature work with artifact-based
dependencies and computed readiness.

Use this when work spans multiple features, requires ordering, or benefits
from interface contracts between work definitions.

For standalone single-feature work, use `/feature` instead — no work group
needed.

---

## Idempotency pre-flight (ALWAYS FIRST)

1. Generate `group-slug` from the goal (kebab-case, max 40 chars)
2. Check if `.work/<group-slug>/` exists
3. If it exists and `work.md` is present:
   ```
   ───────────────────────────────────────────────
   📋 WORK GROUP · <group-slug>
   ───────────────────────────────────────────────
   Work group '<group-slug>' already exists.
   ```
   Use AskUserQuestion with options:
     - "View status" → invoke `/work-status "<group-slug>"`
     - "Decompose into work definitions" → invoke `/work-decompose "<group-slug>"`
     - "Stop"
4. If directory does not exist: proceed to Step 0.

---

## Step 0 — Parse and create directory

- Extract the goal description
- Generate `group-slug` in kebab-case
- Create `.work/<group-slug>/` directory

Display opening header:
```
───────────────────────────────────────────────
📋 WORK GROUP · <group-slug>
───────────────────────────────────────────────
```

---

## Step 1 — Read existing context

Read silently before asking anything:
1. `.work/CLAUDE.md` — check for related active work groups
2. `.kb/CLAUDE.md` — scan topic map for relevant domains
3. `.decisions/CLAUDE.md` — check for related active/deferred decisions
4. `.spec/CLAUDE.md` — check for existing specs in relevant domains

---

## Step 2 — Scoping interview

The goal is to establish clear boundaries for the work group. Ask questions
across these dimensions using AskUserQuestion for each:

### Pre-interview analysis (internal — do not display)

Read the goal and privately identify unknowns across:

| Dimension | What to resolve |
|-----------|----------------|
| Scope | What is the full extent of the change? What is explicitly excluded? |
| Boundaries | Where are the natural seams between independent work units? |
| Ordering | Are there hard ordering constraints (A must complete before B)? |
| Shared surfaces | Will multiple work definitions need to agree on interfaces? |
| Dependencies | What existing specs, ADRs, or KB entries does this work assume? |
| Success criteria | How do we know the work group is complete? |

### Questioning rules

- Collapse knowns: if the goal description answers a dimension, skip it
- Maximum 4 questions total — batch related dimensions into compound questions
- Use AskUserQuestion with typed options where choices are enumerable
- One question at a time, wait for the answer before the next
- After each answer, update your internal model — skip questions the answer
  already covers

---

## Step 3 — Confirm work group scope

Present the scope summary:

```
## Work Group: <group-slug>

**Goal:** <one sentence>

**In scope:**
- <bullet points>

**Out of scope:**
- <bullet points>

**Known ordering constraints:**
- <bullet points, or "none identified yet">

**Shared interfaces expected:**
- <bullet points, or "none identified yet">

**Success criteria:**
- <bullet points>
```

Use AskUserQuestion with options:
  - "Looks good"
  - "Revise" (with Other for specific changes)

If "Revise": apply changes and re-present.

---

## Step 4 — Write work group files

### 4a — Write work.md

Write `.work/<group-slug>/work.md`:

```yaml
---
group: <group-slug>
goal: <one sentence goal>
status: active
created: <YYYY-MM-DD>
---

## Goal
<goal description>

## Scope
### In scope
<bullet points>

### Out of scope
<bullet points>

## Ordering Constraints
<bullet points or "None identified.">

## Shared Interfaces
<bullet points or "None identified yet — will be discovered during decomposition.">

## Success Criteria
<bullet points>
```

### 4b — Write manifest.md

Write `.work/<group-slug>/manifest.md`:

```markdown
# Work Group Manifest: <group-slug>

**Goal:** <one sentence>
**Status:** active
**Created:** <YYYY-MM-DD>
**Work definitions:** 0

## Work Definitions

| WD | Title | Status | Domains | Deps | Produces |
|----|-------|--------|---------|------|----------|

## Dependency Graph

(empty — populate with /work-decompose)
```

### 4c — Update .work/CLAUDE.md index

Add a row to the "Active Work Groups" table:

```
| <group-slug> | .work/<group-slug>/ | <goal> | 0 | 0 | 0 | <YYYY-MM-DD> |
```

Add a row to the "Recently Added" table:

```
| <YYYY-MM-DD> | <group-slug> | — | — | active |
```

---

## Step 5 — Next steps

```
Work group '<group-slug>' created.

Files:
  .work/<group-slug>/work.md       — scope and success criteria
  .work/<group-slug>/manifest.md   — work definition registry

Next: decompose this into work definitions:

  /work-decompose "<group-slug>"

This will identify the individual work units, their dependencies,
and any shared interface contracts needed between them.
```

Stop.
