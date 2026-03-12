# /quick "<description>"

A lightweight entry point for small, well-understood changes that don't need
scoping interviews, domain analysis, or work planning. Same TDD discipline as
the full pipeline — test → implement → refactor — with minimal setup.

Use this when:
- You already know exactly what needs to be built
- The change follows an established pattern in the codebase
- No architectural decisions are needed
- A full /feature pipeline would take longer than the work itself

Use /feature instead when:
- The change touches multiple systems or domains
- You need to decide between approaches
- The scope isn't clear yet
- The feature will take more than a session to implement

---

## Pre-flight guard

Check that `.feature/project-config.md` exists.
If not: "Run /feature-init to set up the project profile first."

---

## Idempotency pre-flight

Generate `quick-slug` from the description in kebab-case, prefixed with `q-`:
e.g. "add isActive to User" → `q-add-isactive-to-user`

Check if `.feature/<quick-slug>/status.md` exists.

**If it exists:**
Read it and report current state exactly as /feature-resume would.
Say: "This quick task was already started. Resuming." and jump to the
appropriate stage. Do not restart.

**If it does not exist:**
Create `.feature/<quick-slug>/` and write initial status.md.
Proceed to Step 1.

---

## Step 1 — Understand the task (quick clarification if needed)

Read the description. Make a judgment call:

**If the description is unambiguous** (clear construct, clear behaviour, clear
location in the codebase): display your understanding and proceed immediately.

```
I'll implement: <one sentence restatement>
Construct:      <what will be created or changed>
Location:       <where in the codebase>
Tests will verify: <the key behaviour>

Proceeding to tests. Say "stop" if this isn't right.
```

**If the description is ambiguous** (unclear scope, unclear expected behaviour,
or could mean multiple things): ask at most 2 targeted questions. Do not ask
about anything the implementation itself can decide.

Good questions:
- "Should this replace the existing X or sit alongside it?"
- "What should happen when the input is empty/null?"
- "Does this need to be backwards compatible with Y?"

Bad questions (don't ask these — decide them yourself):
- "What should I name the method?"
- "Should I add error handling?" (yes, always)
- "Should I write tests for edge cases?" (yes, always)

After at most one round of clarification, proceed. Do not interview.

---

## Step 2 — Write status.md

Write `.feature/<quick-slug>/status.md`:

```markdown
---
feature: "<quick-slug>"
created: "<YYYY-MM-DD HH:MM>"
last_updated: "<YYYY-MM-DD HH:MM>"
mode: "quick"
---

# Quick Task Status — <quick-slug>

## Description
<one-sentence restatement of the task>

## Construct
<what is being created or changed, and where>

## Current Position
**Stage:** testing
**Substage:** not-started
**Last successful checkpoint:** task described

## Stage Completion

| Stage | Status | Completed | Notes |
|-------|--------|-----------|-------|
| Testing | not-started | — | cycle 1 |
| Implementation | not-started | — | cycle 1 |
| Refactor | not-started | — | cycle 1 |

## TDD Cycle Tracker

| Cycle | Tests written | Tests passing | Refactor done | Missing tests |
|-------|--------------|---------------|---------------|---------------|
```

Write `.feature/<quick-slug>/cycle-log.md`:

```markdown
---
feature: "<quick-slug>"
created: "<YYYY-MM-DD>"
mode: "quick"
---
# Cycle Log — <quick-slug>
---
## <YYYY-MM-DD> — started
**Agent:** Quick Command
**Summary:** Task started. <description>
---
```

Update `.feature/CLAUDE.md` Active Features table (mode: quick).

---

## Step 3 — Scan the codebase

Read project-config.md for source directory, test directory, and conventions.

Scan the relevant part of the codebase — the module or file the change touches.
Do not do a broad scan. Read only what is needed to:
- Understand the existing interface the change extends or touches
- Know what test file to write into (or whether to create a new one)
- Confirm the change is as small as described (if it's larger, say so)

If the codebase scan reveals the change is larger than the description suggests:
```
This looks bigger than a /quick task.
I found: <what makes it larger>

Options:
  1. Continue as /quick — I'll implement what you described and note the larger
     context for later
  2. Upgrade to /feature — run /feature "<description>" for full pipeline support
```
Wait for response before continuing.

---

## Step 4 — Write tests (failing)

Write tests for the described behaviour. Same rules as /feature-test:
- Against the public interface only
- Test names describe behaviour
- Must be verified to fail before proceeding

Display the tests in chat before writing to disk:
```
Tests I'll write:
  1. test_<n> — <scenario>
  2. test_<n> — <scenario>
Proceed?
```
Write on confirmation (or after 10 seconds with no response — don't wait forever
for trivial changes).

Run the test suite. Confirm all new tests fail.
Update status.md: Testing cycle 1 → `complete`, substage → "tests verified failing".
Append `tests-written` to cycle-log.md.

---

## Step 5 — Implement

Implement the change. Same rules as /feature-implement:
- Minimum correct implementation
- Follow project-config.md conventions
- Never modify tests
- Escalate genuine contract conflicts (don't work around them)

Run tests after each logical unit. When all pass:
Update status.md: Implementation cycle 1 → `complete`.
Append `implemented` to cycle-log.md.

---

## Step 6 — Refactor

Same checklist as /feature-refactor, same cycle limits (warn at 3, stop at 5),
same missing-test escalation rules.

For a /quick task the refactor is usually fast — most of the checklist will be
"nothing to do here" for a small change. Work through it anyway; security and
missing-test checks are the ones most likely to find something even on small changes.

Run full test suite after refactor. Must be all passing.
Update status.md: Refactor cycle 1 → `complete`, Refactor stage → `complete`.
Append `refactor-complete` to cycle-log.md.

---

## Step 7 — Close

Update `.feature/CLAUDE.md` — move to Completed / Archived if the change is
committed and no further work is planned. Or leave in Active if it is part of
a larger piece of work still in progress.

```
Done. <n> tests passing.

Changes:
  <bullet list of what was written or changed>

<If the task revealed larger scope:>
Note: this touched <area> which may be worth a full /feature pass later.
```

There is no /feature-pr or /feature-complete for quick tasks — the change is
small enough that the PR description can be written inline. If you want a PR
description: /feature-pr "<quick-slug>" works the same way.
