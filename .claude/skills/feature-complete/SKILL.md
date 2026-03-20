---
description: "Archive feature working files after a PR has merged"
argument-hint: "<feature-slug> [--force]"
---

# /feature-complete "<feature-slug>" [--force]

Archives the feature's working files after a PR has merged.
Moves .feature/<slug>/ to .feature/_archive/<slug>/ (which is gitignored).
The source code, tests, ADRs, and KB entries all remain — only the working
state files (brief, work plan, domains, cycle log, status) are archived.

Knowledge files, index updates, and the archive manifest are committed by
`/feature-pr` before the PR is created — this command is just the post-merge
directory move.

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

  Type **yes**  ·  or: stop
```
Wait for explicit confirmation before continuing.

---

Display opening header:
```
───────────────────────────────────────────────
📦 FEATURE COMPLETE · <slug>
───────────────────────────────────────────────
```

## Step 2 — Archive the working directory

Move `.feature/<slug>/` to `.feature/_archive/<slug>/`.
(If `units/` subdirectory exists inside the slug directory, it moves with it
automatically — no special handling needed.)

`.feature/_archive/` is gitignored — the archive stays on the local machine
as a short-term reference but does not go into the repository.

If `.feature/_archive/` does not exist, create it (it should have been created
by /setup-vallorcine, but create it if missing).

---

## Step 3 — Report

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
