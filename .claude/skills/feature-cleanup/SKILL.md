---
description: "Interactive walkthrough to clean up stale feature directories"
---

# /feature-cleanup

Interactive walkthrough of existing `.feature/<slug>/` directories. For each:
shows last activity date and stage, then asks the user to keep, archive, or delete.
Addresses abandoned feature directory clutter for long-running projects.

---

## Pre-flight guard

Check that `.feature/` exists. If not:
```
No .feature/ directory found. Nothing to clean up.
```
Stop.

Display opening header:
```
───────────────────────────────────────────────
🧹 FEATURE CLEANUP
───────────────────────────────────────────────
```

---

## Step 1 — Scan feature directories

List all directories under `.feature/` excluding:
- `_archive/`
- `project-config.md`
- `CLAUDE.md`

For each directory, read `status.md` and extract:
- Stage and substage
- Last updated timestamp
- Automation mode
- Brief description (from the `feature:` frontmatter field)

Calculate staleness: days since `last_updated` (or file modification date if
`last_updated` is missing).

Sort by staleness (most stale first).

If no feature directories found:
```
No active feature directories. Nothing to clean up.
```
Stop.

---

## Step 2 — Display summary

```
── Active feature directories ─────────────────
  <n> features found

  SLUG                     STAGE              LAST ACTIVITY    AGE
  ──────────────────────   ────────────────   ──────────────   ────
  old-auth-refactor        planning/stubs     2026-02-28       16d
  cache-layer              scoping/complete   2026-03-01       15d
  sql-query-support        domains/progress   2026-03-16       0d
  ──────────────────────────────────────────────────────────────────
```

---

## Step 3 — Process each feature

Present each feature starting from the most stale:

```
── <slug> ─────────────────────────────────────
  Stage:        <stage> / <substage>
  Last activity: <date> (<n> days ago)
  Description:  <from frontmatter or brief.md first line>
  Files:        <n> files in .feature/<slug>/

  Type: keep · archive · delete
```

Wait for user response.

**keep** — no changes. Display `  ✓ Keeping <slug>` and continue to next.

**archive** — move `.feature/<slug>/` to `.feature/_archive/<slug>/`.
Create `.feature/_archive/` if it doesn't exist.
Update `.feature/CLAUDE.md` if the feature is listed in Active Features (move
to Completed/Archived section).
Display:
```
  ✓ Archived: .feature/_archive/<slug>/
```

**delete** — confirm before deleting:
```
  ⚠ This will permanently delete .feature/<slug>/ and all its contents.
    Source code and tests in git are NOT affected — only the working state files.

    Type **yes** to delete · or: keep
```
If confirmed: `rm -rf .feature/<slug>/`. Remove from `.feature/CLAUDE.md` Active
Features table if present.
Display:
```
  ✓ Deleted: .feature/<slug>/
```

---

## Step 4 — Summary

```
───────────────────────────────────────────────
🧹 FEATURE CLEANUP complete
  Kept:     <n>
  Archived: <n>
  Deleted:  <n>
  Active features remaining: <n>
───────────────────────────────────────────────
```
