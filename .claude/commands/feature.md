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
     Scoping is already complete for '<slug>'.
     Brief: .feature/<slug>/brief.md
     Next: /feature-domains "<slug>"
     Run /feature-resume "<slug>" to see full status.
     ```
     Stop unless the user says "redo" or "update brief".
   - If stage is `scoping` and substage is `in-progress`:
     Say "Scoping was in progress — resuming from last checkpoint."
     Re-display the last saved brief draft if it exists in status.md, then
     continue from Step 3 (confirm brief).
4. If status.md does not exist: proceed to Step 0.

---

## Step 0 — Parse and slug

- Extract the description
- Generate `feature-slug` in kebab-case
- Create `.feature/<slug>/` directory
- Write initial `status.md` (see Status File Template below) with stage `scoping`,
  substage `interviewing`

---

## Step 1 — Read project config

Read `.feature/project-config.md`. If missing: stop and say
  "Run /feature-init to set up the project profile first."

---

## Step 2 — The scoping interview

Display opening:
```
I'll help scope this out. Here's what I understood:
  "<one-sentence restatement>"

Before I write the brief, I have some questions.
```

### Core questions (adapt to context — not all apply)

**Scope boundaries**
- What is explicitly OUT of scope for this piece of work?
- Are there related features that should NOT be affected?

**Users and actors**
- Who uses this feature? (end user, admin, another service, cron job, etc.)
- Are there different roles or permissions involved?

**Inputs and outputs**
- What does the user/caller provide?
- What does the system return or produce?
- Any formats, schemas, or protocols that must be respected?

**Behaviour and rules**
- What are the key business rules or constraints?
- What should happen in error cases or edge cases?
- Any performance expectations (latency, throughput, batch size)?

**Dependencies and integration**
- Does this call any external services or APIs?
- Does it depend on existing parts of the codebase?
- Does it write to or read from storage (DB, cache, file system, queue)?

**Success criteria**
- How will we know this feature is complete and correct?
- Are there acceptance criteria you're working from?
- Anything you're particularly worried about getting wrong?

Maximum two rounds of follow-up. If still unclear after two rounds, record as
an assumption in the brief.

---

## Step 3 — Present the brief for confirmation

Update `status.md`: substage → `confirming-brief`. Save the draft brief text
into status.md under `## Draft Brief` so it survives a crash.

Display in chat:
```
─────────────────────────────────────────────────────────────
FEATURE BRIEF — <feature-slug>
─────────────────────────────────────────────────────────────
SUMMARY
<2–3 sentences>

ACTORS / <INPUTS / OUTPUTS & SIDE EFFECTS / BUSINESS RULES /
ERROR CASES / EXPLICIT OUT OF SCOPE / ACCEPTANCE CRITERIA /
OPEN ASSUMPTIONS / PERFORMANCE EXPECTATIONS
─────────────────────────────────────────────────────────────
Does this capture it correctly? Confirm or tell me what to change.
─────────────────────────────────────────────────────────────
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
**Agent:** Scoping Agent
**Summary:** Feature brief confirmed by user.
**Brief:** [brief.md](brief.md)
---
```

Update `status.md`: stage → `scoping`, substage → `complete`.
Remove the `## Draft Brief` section from status.md now that brief.md is written.
Update `.feature/CLAUDE.md` Active Features table.

---

## Step 5 — Hand off

```
Brief written to .feature/<slug>/brief.md

Next step:
  /feature-domains "<slug>"
```

---

## Status File Template

Written at Step 0, updated in-place by every agent throughout the pipeline.

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

## Stage Completion

| Stage | Status | Completed | Notes |
|-------|--------|-----------|-------|
| Scoping | in-progress | — | |
| Domains | not-started | — | |
| Planning | not-started | — | |
| Testing | not-started | — | cycle 0 |
| Implementation | not-started | — | cycle 0 |
| Refactor | not-started | — | cycle 0 |

## Domain Resolution Tracker
<!-- Populated by /feature-domains -->

| Domain | Status | ADR | KB entries | Commissioned | Resolved |
|--------|--------|-----|------------|--------------|----------|

## TDD Cycle Tracker
<!-- Populated by /feature-test, /feature-implement, /feature-refactor -->

| Cycle | Tests written | Tests passing | Refactor done | Missing tests |
|-------|--------------|---------------|---------------|---------------|
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
