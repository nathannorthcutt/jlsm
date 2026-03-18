---
description: "Open a new feature session with scoping interview and brief"
argument-hint: "<description>"
---

# /feature "<description>"

Opens a new feature session. Interviews the user, confirms a brief, writes
brief.md, and initialises status.md as the restart checkpoint.

---

## Idempotency pre-flight (ALWAYS FIRST)

1. Generate the `feature-slug` from the description (kebab-case)
2. Check if `.feature/<slug>/status.md` exists
3. If it exists, read it:
   - If stage is `scoping` and substage is `complete` or later:
     ```
     🔍 SCOPING AGENT · <slug>
     ───────────────────────────────────────────────
     Scoping is already complete for '<slug>'.
     Brief: .feature/<slug>/brief.md

       Type **yes**  to proceed to domain analysis  ·  or: stop
     ```
     If "yes": invoke /feature-domains "<slug>" as a sub-agent immediately.
     If "stop": display `Next: /feature-domains "<slug>"` and stop.
     Stop if the user says "redo" or "update brief" — proceed with re-scoping.
   - If stage is `scoping` and substage is `in-progress`:
     Display the opening header, then say "Scoping was in progress — resuming
     from last checkpoint." Re-display the last saved brief draft if it exists
     in status.md, then continue from Step 3 (confirm brief).
4. If status.md does not exist: proceed to Step 0.

---

## Step 0 — Parse and slug

- Extract the description
- Generate `feature-slug` in kebab-case
- Create `.feature/<slug>/` directory
- Write initial `status.md` (see Status File Template below) with stage `scoping`,
  substage `interviewing`
Display opening header immediately:
```
───────────────────────────────────────────────
🔍 SCOPING AGENT · <slug>
───────────────────────────────────────────────
```

---

## Step 1 — Read project config

Read `.feature/project-config.md`. If missing: stop and say
  "Run /feature-init to set up the project profile first."

If `PROJECT-CONTEXT.md` exists in the project root: read the `## Active` section.
Use global entries and any scoped entries matching the feature description to
inform the scoping interview. Do not ask questions that active context entries
already answer.

---

## Step 2 — The scoping interview

### Pre-interview analysis (internal — do not display)

Before asking anything, read the description and project-config.md and privately
build a list of unknowns across these six dimensions:

| Dimension | What to resolve |
|-----------|----------------|
| Scope | What is in and explicitly out of scope |
| Actors | Who or what initiates and receives this |
| Interface | Inputs, outputs, formats, protocols |
| Behaviour | Business rules, error cases, edge cases, performance |
| Integration | External services, storage, existing codebase dependencies |
| Success | Acceptance criteria, definition of done, concerns |

For each dimension, mark it as:
- **known** — the description or project-config.md answers it clearly
- **inferable** — you can make a reasonable assumption; record it, don't ask
- **unknown** — genuinely unclear and affects how downstream agents work

Only unknowns become questions. Inferable items become assumptions in the brief.
Simple features may have 1–2 unknowns. Complex ones may have 5–6. Either is fine.

Rank unknowns by impact: questions whose answers would change the most about
the brief come first. Scope and interface questions almost always rank highest.

### Opening display

```
── Scoping · <slug> ────────────────────────────
<one-sentence restatement of what you understood>

I have <n> question<s> before I can write the brief.
I'll go one at a time.
```

If n is 0 (description is fully specified): skip directly to Step 3, noting
"Description is complete — no clarification needed." in the brief's assumptions.

### Question loop (one question per turn, always)

For each unknown in ranked order:

Display:
```
── Question <i> of <n> ─────────────────────────
<The question — one focused question only>

<One sentence of context explaining why this matters for the brief.>
```

Rules for good questions:
- One question per turn. Never combine two questions into one turn.
- Ask about things that would change the brief if answered differently.
- Frame the question with the specific tradeoff or consequence so the user
  understands why you're asking: "This affects whether we need a migration
  path for existing data."
- If a yes/no framing helps, offer it: "Does X need to support Y? (yes / no /
  it depends — <elaboration welcome>"

After the user responds:
- Absorb the answer into your internal model
- Check if the answer resolved any other unknowns (skip those questions)
- If the answer raises a new unknown that ranks higher than remaining questions,
  insert it next
- If you have remaining questions: ask the next one
- If all unknowns are resolved: move directly to Step 3 — do not announce it,
  just transition: "Got it — let me draft the brief."

### What NOT to ask

Never ask questions the implementation can answer:
- Naming ("What should I call the method?")
- Standard practice ("Should I add error handling?" — yes, always)
- Obvious defaults ("Should I write tests?" — yes, always)
- Things project-config.md already answers (language, test framework, conventions)

Never ask for information just to be thorough. If a dimension is inferable,
infer it and record the assumption. Ask only when the answer genuinely changes
what gets built.

### If a question opens a deeper conversation

The user may give a long or complex answer that warrants a follow-up.
That is fine — stay in the conversation. The question count is a guide,
not a strict limit. If the user's answer to Q2 requires a follow-up, ask it
before moving to Q3. Depth on one question is better than breadth across all.

Record any discussion points that affect the brief as you go — don't rely
on reconstructing them at brief-writing time.

---

## Step 3 — Present the brief for confirmation

Update `status.md`: substage → `confirming-brief`. Save the draft brief text
into status.md under `## Draft Brief` so it survives a crash.

Display:
```
── Brief ───────────────────────────────────────
🔍 FEATURE BRIEF · <slug>
───────────────────────────────────────────────
SUMMARY
<2–3 sentences>

ACTORS / INPUTS / OUTPUTS & SIDE EFFECTS / BUSINESS RULES /
ERROR CASES / EXPLICIT OUT OF SCOPE / ACCEPTANCE CRITERIA /
OPEN ASSUMPTIONS / PERFORMANCE EXPECTATIONS
───────────────────────────────────────────────
Does this capture it correctly? Confirm or tell me what to change.
```

Iterate until confirmed. Do not write brief.md until confirmed.

---

## Step 4 — Write brief.md and initialise cycle-log.md

Write `.feature/<slug>/brief.md` (Brief File Template below).

Write `.feature/<slug>/cycle-log.md`:
```markdown
---
feature: "<feature-slug>"
created: "<YYYY-MM-DD>"
---
# Cycle Log — <feature-slug>
This file is append-only. Each agent appends entries. Nothing is edited or deleted.
---
## <YYYY-MM-DD> — scoped
**Agent:** 🔍 Scoping Agent
**Summary:** Feature brief confirmed by user.
**Brief:** [brief.md](brief.md)
**Token estimate:** ~<N>K (loaded: project-config ~1K / wrote: brief ~2K, status ~1K, cycle-log ~1K)
---
```

Update `status.md`: stage → `scoping`, substage → `complete`.
Update the Stage Completion table: Scoping row → Est. Tokens `~5K`, status → `complete`.
Remove the `## Draft Brief` section from status.md now that brief.md is written.
Update `.feature/CLAUDE.md` Active Features table.

---

## Step 5 — Hand off

Display:
```
───────────────────────────────────────────────
🔍 SCOPING AGENT complete · <slug>
  Tokens : <TOKEN_USAGE>
───────────────────────────────────────────────
Brief written to .feature/<slug>/brief.md

Take a moment to review the brief before continuing — domain analysis builds
directly on it and fixing scope issues now is much cheaper than later.
```

### Step 5a — Feature branch

Read `branch_naming` from `.feature/project-config.md`.

**If `branch_naming: none` or project-config.md does not exist:** skip this step.

**If a branch naming convention is defined:**

Expand the convention by substituting `<slug>` with the feature slug.
Check the current git branch (`git branch --show-current`). If already on a
branch that matches the convention, skip silently — branch already created.

Display:
```
── Feature branch ──────────────────────────────
  Suggested branch: <expanded branch name>

  Type: create  to checkout a new branch now  ·  or: skip
```

If "create": run `git checkout -b <branch-name>`. Display the result.
If the branch already exists locally, run `git checkout <branch-name>` instead.
If "skip": continue without creating a branch.

### Step 5b — Continue

```
───────────────────────────────────────────────
  Type **yes**  ·  or: stop
───────────────────────────────────────────────
```

If "yes": invoke /feature-domains "<slug>" as a sub-agent immediately.
If "stop":
```
When you're ready:
  /feature-domains "<slug>"
```

---

## Status File Template

Written at Step 0, updated in-place by every agent throughout the pipeline.

### Token tracking in Stage Completion table

Every pipeline agent updates the Stage Completion table with token data:

**Est. Tokens** — written at stage start. The agent's estimate of context window
load for this stage, derived from the construct count and file sizes. Format:
`~<N>K` (e.g., `~8K`, `~15K`). Based on:
- Scoping: project-config (~1K) + brief writing (~2K) + status (~1K)
- Domains: brief (~2K) + KB/decisions indexes (~2K) + ADR files loaded
- Planning: brief (~2K) + domains (~3K) + ADRs + source scan
- Testing: project-config (~1K) + brief (~2K) + work-plan section (~2K)
- Implementation: work-plan section (~2K) + test files (~3K) + stubs (~1K)
- Refactor: project-config (~1K) + work-plan (~2K) + impl files + test files

**Actual Tokens** — written automatically by the token tracking Stop hook when
a stage transition is detected. The hook reads the session transcript and logs
usage to `token-log.md`. Format: `<N>K in / <N>K out` (e.g., `12K in / 8K out`).
Agents do not need to run any bash commands for token tracking.

```markdown
---
feature: "<feature-slug>"
created: "<YYYY-MM-DD HH:MM>"
last_updated: "<YYYY-MM-DD HH:MM>"
---

# Feature Status — <feature-slug>

## Current Position
**Stage:** scoping
**Substage:** interviewing
**Last successful checkpoint:** feature directory created
**Automation mode:** not-set
**Execution strategy:** not-set

## Stage Completion

| Stage | Status | Completed | Est. Tokens | Actual Tokens | Notes |
|-------|--------|-----------|-------------|---------------|-------|
| Scoping | in-progress | — | — | — | |
| Domains | not-started | — | — | — | |
| Planning | not-started | — | — | — | |
| Testing | not-started | — | — | — | cycle 0 |
| Implementation | not-started | — | — | — | cycle 0 |
| Refactor | not-started | — | — | — | cycle 0 |

## Domain Resolution Tracker
<!-- Populated by /feature-domains -->

| Domain | Status | ADR | KB entries | Commissioned | Resolved |
|--------|--------|-----|------------|--------------|----------|

## Work Units
<!-- Populated by /feature-plan if feature is split into units -->
<!-- work_units: none  ← set this if no split was done -->
<!-- execution_strategy: not-set -->
<!-- current_batch: 0 -->

| Unit | Name | Constructs | Depends On | Status | Cycle |
|------|------|------------|------------|--------|-------|

## TDD Cycle Tracker
<!-- Single-unit: one row per cycle -->
<!-- Multi-unit: rows labelled "Cycle N · WU-N" -->

| Cycle | Unit | Tests written | Tests passing | Refactor done | Missing tests |
|-------|------|--------------|---------------|---------------|---------------|
```

---

## Brief File Template

```markdown
---
feature: "<description>"
slug: "<slug>"
created: "<YYYY-MM-DD>"
status: "scoped"
---

# Feature Brief — <slug>

## Summary
## Actors
## Inputs
## Outputs / Side Effects
## Business Rules
## Error Cases
## Explicit Out of Scope
## Acceptance Criteria
## Open Assumptions
## Performance Expectations

## Project Context
- Language: <from project-config.md>
- Framework: <from project-config.md>
- Test framework: <from project-config.md>
```
