---
description: "Remove vallorcine from this project while preserving your knowledge base and decisions"
---

# /uninstall-vallorcine

Removes all vallorcine kit files from this project while preserving
user-created data (.kb/, .decisions/, .feature/, PROJECT-CONTEXT.md).

---

## Pre-flight

1. Check that `.claude/scripts/uninstall.sh` exists.
   If not:
   ```
   ───────────────────────────────────────────────
   🗑️  UNINSTALL
   ───────────────────────────────────────────────
   uninstall.sh not found in .claude/scripts/.

   vallorcine may not be fully installed, or the uninstall script
   was not included in your version.

   To remove manually, delete the files listed in .claude/.vallorcine-manifest.
   ```
   Stop.

Display opening header:
```
───────────────────────────────────────────────
🗑️  UNINSTALL
───────────────────────────────────────────────
```

---

## Step 1 — Preview

Run:
```bash
bash .claude/scripts/uninstall.sh --dry-run
```

Display the full output to the user.

---

## Step 2 — Show what will be preserved

Display:
```
── What uninstall preserves ────────────────────
These files and directories are NEVER removed:
  .kb/                   your knowledge base entries
  .decisions/            your architecture decisions
  .feature/              in-progress feature work
  PROJECT-CONTEXT.md     your project context
  CLAUDE.md              your root configuration
  .claude/settings.json  non-vallorcine settings

── What will be removed ────────────────────────
  All kit skills, agents, rules, and scripts
  Dashboard state and watchers
  Merge driver configuration
  Upgrade script
  Version and manifest metadata
─────────────────────────────────────────────────
```

---

## Step 3 — Confirm

Ask:
```
Type **yes** to uninstall vallorcine  ·  or: stop
```

Wait for the user's response.
- If "stop" or anything other than "yes": exit with "Uninstall cancelled. No changes made."
- If "yes": proceed.

---

## Step 4 — Execute

Run:
```bash
bash .claude/scripts/uninstall.sh --yes
```

Stream the output directly to the user.

If the script exits non-zero, display the error and stop.

---

## Step 5 — Post-uninstall notice

Display:
```
───────────────────────────────────────────────
🗑️  UNINSTALL complete
───────────────────────────────────────────────
vallorcine has been removed from this project.

Your knowledge base, decisions, and feature work are untouched.
To re-install later, run install.sh from a release zip.
───────────────────────────────────────────────
```
