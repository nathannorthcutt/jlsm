# /feature-complete "<feature-slug>" [--force]

Archives the feature's working files after a PR has merged.
Moves .feature/<slug>/ to .feature/_archive/<slug>/ (which is gitignored).
The source code, tests, ADRs, and KB entries all remain — only the working
state files (brief, work plan, domains, cycle log, status) are archived.

Use --force to archive even if status.md does not show refactor complete
(e.g. the feature was simple enough that full TDD wasn't used).

---

## Step 1 — Verify readiness

Read `.feature/<slug>/status.md`.

**Without --force:**
If Refactor stage is not `complete`:
```
Feature '<slug>' is not yet marked as refactor-complete in status.md.

Current stage: <stage>

If the feature is genuinely done (e.g. PR merged, all tests green), run:
  /feature-complete "<slug>" --force

If work is still in progress, continue with:
  <next command from status.md>
```
Stop.

**With --force:**
Show a warning:
```
⚠ Archiving '<slug>' before full pipeline completion.
Status shows: <stage> / <substage>

This is fine if:
  - The PR has already merged
  - You intentionally skipped stages (e.g. no refactor needed for small changes)

  ↵  proceed  ·  or type: stop
```
Wait for explicit yes before continuing.

---

Display opening header:
```
───────────────────────────────────────────────
📦 FEATURE COMPLETE · <slug>
───────────────────────────────────────────────
```

## Step 2 — Confirm PR is merged

Ask (skip if --force was already confirmed):
```
Has the PR for this feature been merged?
  yes — proceed with archive
  no  — archive anyway (working files will be preserved locally in _archive/)
```

Record the response in the archive manifest.

---

## Step 3 — Verify source and tests are committed

Check git status for the implementation and test files listed in work-plan.md.
If any are untracked or have uncommitted changes:
```
⚠ The following files have uncommitted changes:
  <list>

Archive them anyway? Their content will be lost from git history if you proceed
without committing.
  ↵  archive anyway  ·  or type: stop
```
Wait for response.

---

## Step 4 — Write archive manifest

Before moving anything, write `.feature/<slug>/ARCHIVE.md`:

```markdown
---
feature: "<slug>"
archived: "<YYYY-MM-DD>"
pr_merged: "<yes | no | not-asked>"
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

## Why Archived
PR merged — working state no longer needed locally.
Source code and tests are in git history.
ADRs and KB entries are permanent and remain in place.
```

---

## Step 5 — Archive the working directory

Move `.feature/<slug>/` to `.feature/_archive/<slug>/`.

`.feature/_archive/` is gitignored — the archive stays on the local machine
as a short-term reference but does not go into the repository.

If `.feature/_archive/` does not exist, create it (it should have been created
by /feature-init, but create it if missing).

---

## Step 6 — Update .feature/CLAUDE.md

Move the feature row from Active Features to Completed / Archived:

```
| <feature> | <slug> | <YYYY-MM-DD> | .feature/_archive/<slug>/ |
```

---

## Step 7 — Report

```
───────────────────────────────────────────────
📦 FEATURE COMPLETE · <slug>
───────────────────────────────────────────────
Feature '<slug>' archived.

Working files moved to: .feature/_archive/<slug>/
  (gitignored — local reference only)

Permanent records (not moved):
  ADRs:       .decisions/ (all decisions remain)
  KB entries: .kb/ (all research remains)
  Source:     in git history
  Tests:      in git history

To recover working files if needed:
  cp -r .feature/_archive/<slug>/ .feature/<slug>/
```
