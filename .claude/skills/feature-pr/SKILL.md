---
description: "Draft a pull request title, description, and review checklist"
argument-hint: "<feature-slug>"
---

# /feature-pr "<feature-slug>"

Drafts a pull request title, description, and review checklist by reading the
feature's working files. Works for both /feature and /feature-quick slugs.

Run this after /feature-refactor completes and before opening the PR.

---

## Pre-flight guard

Check that `.feature/<slug>/` exists. If not:
"No feature found for '<slug>'. Check the slug or run /feature-resume to see active features."

Check that cycle-log.md has at least one `refactor-complete` or `implemented` entry.
If not: "Feature '<slug>' has not been implemented yet. Complete the pipeline first."

---

## Idempotency pre-flight

Check status.md for a `pr-draft` substage entry.
If a previous PR draft exists in `.feature/<slug>/pr-draft.md`:
```
📋 PR DRAFT · <slug>
───────────────────────────────────────────────
A PR draft already exists for '<slug>'.
Draft: .feature/<slug>/pr-draft.md

  Type **yes**  to proceed to PR creation  ·  or: regenerate
```
If "regenerate": proceed to regenerate the draft.
If "yes": skip to Step 6 — PR creation (attempt to create the PR from the existing draft).

---

Display opening header:
```
───────────────────────────────────────────────
📋 PR DRAFT · <slug>
───────────────────────────────────────────────
```

---

## Step 0.5 — Pre-PR commit verification

Before drafting, check for uncommitted files that should be part of this PR.
These are files created by the pipeline (research, architect, test writer,
code writer) that may not have been staged — especially after crashes or
session interruptions.

### Scan for uncommitted files

Run `git status --short` and check for untracked or modified files in:

1. **`.kb/`** — KB entries created during `/feature-domains` or `/feature-retro`
2. **`.decisions/`** — ADRs, index updates, and history.md from `/feature-domains`
   or `/feature-retro`. This includes `CLAUDE.md` (active decisions index) and
   `history.md` (archived rows) — both are updated by the architect agent during
   domain analysis. These are feature-produced, not upgrade artifacts.
3. **Source files** listed in `work-plan.md` (if it exists)
4. **Test files** listed in `work-plan.md` or referenced in `cycle-log.md`

### Separating feature changes from upgrade changes

If `.claude/` files are also modified (skills, scripts, settings, manifest),
those are likely from a vallorcine upgrade, not the feature pipeline. Present
them separately:

```
── Pre-PR check ────────────────────────────────
I found uncommitted changes in two categories:

Feature-produced (should be in this PR):
  ? .decisions/compression-codec-api-design/adr.md
  M .decisions/CLAUDE.md
  M .decisions/history.md
  ? .kb/systems/lsm-index-patterns/index-scan-patterns.md
  M src/storage/block.go

Vallorcine upgrade (separate commit recommended):
  M .claude/skills/research/SKILL.md
  M .claude/settings.json
  M .claude/.vallorcine-manifest
```

Recommend committing upgrade changes first (`chore: upgrade vallorcine to vX.X.X`)
before staging feature changes. This keeps the feature PR clean.

Filter out:
- `.feature/` — gitignored working files, not committed
- `.curate/` — gitignored runtime files
- Files already staged

### If uncommitted files found

```
── Pre-PR check ────────────────────────────────
I found files created during this feature's pipeline that haven't been
committed yet:

Knowledge & decisions:
  ? .decisions/session-storage/adr.md
  ? .decisions/session-storage/constraints.md
  ? .decisions/session-storage/evaluation.md
  ? .decisions/session-storage/log.md
  ? .kb/systems/payments/stripe-integration.md

Source & tests:
  M src/auth/session.ts
  ? tests/auth/test-session.ts

These should be included in your PR. Want me to stage them?
  yes  — stage all listed files
  pick — let me choose which to stage
  skip — proceed without staging (I'll handle it manually)
```

**If "yes":** run `git add` for all listed files. Report what was staged.
**If "pick":** present numbered list, user picks by number. Stage selected.
**If "skip":** proceed to Step 1. Note in the PR description that uncommitted
files were detected (so the reviewer knows to check).

### If no uncommitted files found

Proceed silently to Step 1. No message needed.

---

## Step 1 — Load context

Read in order:
1. `.feature/<slug>/status.md` — current state, cycle count
2. `.feature/<slug>/brief.md` if it exists (full /feature pipeline)
   OR the Description field from status.md (quick task)
3. `.feature/<slug>/work-plan.md` if it exists
4. `.feature/<slug>/cycle-log.md` — full history
   (If `units/` directory exists, the coordinator has already merged per-unit
   logs into the feature-level cycle-log.md — read that merged log.)
5. `.feature/<slug>/domains.md` if it exists — for ADR links

Do NOT read implementation or test files — the PR description should describe
intent and behaviour, not implementation details.

---

## Step 2 — Draft the PR

Construct the PR draft in this order:

### Title
One line. Format: `<type>(<scope>): <what it does>`
Types: feat / fix / refactor / test / chore
Examples:
- `feat(auth): add rate limiting to login endpoint`
- `fix(user): handle null email in isActive check`
- `refactor(cache): extract TTL logic into shared utility`

### Description

```markdown
## What
<2–3 sentences describing what this PR does and why. Written for a reviewer
who has not seen the feature work. No implementation details.>

## Changes
<Bullet list of the meaningful changes — constructs added, behaviour changed,
files modified. One line each. Sourced from work-plan.md and cycle-log.md.>

<If units/ directory exists (parallel feature), group changes by work unit:>
### WU-1: <name>
- <changes from WU-1 cycle-log entries>

### WU-2: <name>
- <changes from WU-2 cycle-log entries>

## Tests
<How the change is tested. Number of tests written, what they cover at a high
level. Note any edge cases or security scenarios specifically tested.>

## Decisions
<Only if domains.md exists and ADRs were consulted. Brief note on any
architectural decisions that influenced this implementation, with links.
Omit this section entirely for quick tasks with no ADR involvement.>

## Notes for reviewer
<Anything the reviewer should pay particular attention to — a tricky edge case,
a known limitation, a follow-up task that was deferred, a dependency on another PR.
If nothing notable: omit this section.>
```

### Review checklist

Generated from the refactor cycle log and project-config.md:

```markdown
## Review checklist
- [ ] Tests pass locally (`<run tests command from project-config>`)
- [ ] Linter passes (`<lint command>`)
<If type check exists:>
- [ ] Type check passes (`<type check command>`)
<If security findings were noted in refactor cycle:>
- [ ] Security: <specific item to verify>
<If performance findings were noted:>
- [ ] Performance: <specific item to verify>
<If integration tests exist:>
- [ ] Integration tests pass (`<integration test command>`)
- [ ] No unintended side effects on <related areas from domains.md>
```

---

## Step 3 — Display and confirm

Display the full draft in chat:
```
─────────────────────────────────────────────────────────────
PR DRAFT — <slug>
─────────────────────────────────────────────────────────────
Title: <title>

<description>

<checklist>
─────────────────────────────────────────────────────────────
  Type **yes**  to finalize this draft  ·  or: describe adjustments
─────────────────────────────────────────────────────────────
```

Iterate on feedback. When the user types **yes**, write to disk.

---

## Step 4 — Write pr-draft.md

Write `.feature/<slug>/pr-draft.md`:

```markdown
---
feature: "<slug>"
created: "<YYYY-MM-DD>"
status: "draft"
---

# PR Draft — <slug>

## Title
<title>

## Description
<full description>

## Review checklist
<checklist>

---
*Generated from brief.md, work-plan.md, and cycle-log.md on <date>.*
*Copy the Title and Description sections directly into your PR.*
```

Update status.md substage → `pr-draft-written`.
Append `pr-drafted` entry to cycle-log.md:
```markdown
## <YYYY-MM-DD> — pr-drafted
**Agent:** 📋 PR Draft
**Summary:** PR draft written and confirmed by user.
**Token estimate:** ~<N>K (loaded: brief ~2K, work-plan ~4K, cycle-log ~<N>K)
---
```

---

## Step 5 — Finalize feature records

Before creating the PR, finalize the feature's knowledge and index records so
they are included in the PR. This work used to happen post-merge in
`/feature-complete`, but leaving it until after the PR risks losing information
— users may not run the cleanup step, or the uncommitted changes get ignored.

### 5a — Write archive manifest

Write `.feature/<slug>/ARCHIVE.md`:

```markdown
---
feature: "<slug>"
archived: "<YYYY-MM-DD>"
---

# Archive Manifest — <slug>

## Feature Summary
<Summary from brief.md>

## What Was Built
<Bullet list of constructs from work-plan.md>

## Files Created / Modified
<From work-plan.md and cycle-log.md>

## TDD Summary
- Cycles completed: <n>
- Tests written: <n>
- Missing tests found during refactor: <total across all cycles>

## Decisions Made
<Links to ADRs created or used — these remain in .decisions/, this is just a reference>

## KB Entries Used
<Links to KB entries — these remain in .kb/>
```

### 5b — Update .feature/CLAUDE.md

Move the feature row from Active Features to Completed / Archived:

```
| <feature> | <slug> | <YYYY-MM-DD> | .feature/_archive/<slug>/ |
```

### 5c — Commit feature records

Stage and commit all feature-produced files that aren't yet committed:

```bash
git add .feature/CLAUDE.md
```

Also check for and include any uncommitted `.decisions/` and `.kb/` files
(indexes, history, ADRs, KB entries created during this feature's pipeline):

```bash
# Only add files that are actually modified or untracked
git add .decisions/CLAUDE.md .decisions/history.md .kb/CLAUDE.md 2>/dev/null
# Add any ADR directories created for this feature
git add .decisions/*/adr.md .decisions/*/constraints.md .decisions/*/evaluation.md .decisions/*/log.md 2>/dev/null
# Add any KB entries created for this feature
git add .kb/ 2>/dev/null
git commit -m "chore: finalize <slug> — archive manifest, index updates, knowledge files"
```

If nothing to commit (all already staged), continue silently.

---

## Step 6 — Create the PR

Check if `gh` CLI is available: run `gh auth status` silently.

**If `gh` is not available or not authenticated:**
```
───────────────────────────────────────────────
📋 PR DRAFT complete · <slug>
  Tokens : <TOKEN_USAGE>
───────────────────────────────────────────────
PR draft written to .feature/<slug>/pr-draft.md

gh CLI not found or not authenticated. To create the PR manually:
  1. Copy the title from pr-draft.md
  2. Copy the description from pr-draft.md
  3. Open a PR in your repo's UI or with: gh pr create

When the PR merges, run:
  /feature-complete "<slug>"

── Retrospective ──────────────────────────────
A retrospective captures what worked and what didn't while the feature is fresh.
It writes back to the KB and decisions store — making the next feature better.

  Type **yes**  to run /feature-retro now  ·  or: skip
───────────────────────────────────────────────
```

If "yes": invoke `/feature-retro "<slug>"` as a sub-agent immediately.
If "skip": stop.

**If `gh` is available:**

Display:
```
───────────────────────────────────────────────
📋 PR DRAFT complete · <slug>
  Tokens : <TOKEN_USAGE>
Draft: .feature/<slug>/pr-draft.md

  Type: create  to open the PR now via gh  ·  or: skip
───────────────────────────────────────────────
```

If "skip":
```
When you're ready:
  gh pr create --title "<title>" --body-file .feature/<slug>/pr-draft.md

When the PR merges, run:
  /feature-complete "<slug>"
```
Stop.

If "create":

Run:
```
gh pr create --title "<title from pr-draft.md>" --body-file ".feature/<slug>/pr-draft.md"
```

If the command succeeds, capture the PR URL from `gh` output. Update status.md
substage → `pr-created`. Append to cycle-log.md:
```markdown
## <YYYY-MM-DD> — pr-created
**Agent:** 📋 PR Draft
**PR:** <URL>
---
```

Display:
```
───────────────────────────────────────────────
✓ PR opened: <URL>
───────────────────────────────────────────────
When the PR merges:
  /feature-complete "<slug>"

── Retrospective ──────────────────────────────
A retrospective captures what worked and what didn't while the feature is fresh.
It writes back to the KB and decisions store — making the next feature better.

  Type **yes**  to run /feature-retro now  ·  or: skip
───────────────────────────────────────────────
```

If "yes": invoke `/feature-retro "<slug>"` as a sub-agent immediately.
If "skip": stop.

If `gh pr create` fails (e.g. branch not pushed, no remote): display the error
and fall back to the manual instructions above. Do not retry automatically.
