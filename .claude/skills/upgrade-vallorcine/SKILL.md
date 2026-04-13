---
description: "Check for and apply updates from the vallorcine source repository"
---

# /upgrade-vallorcine

Checks the source repository for a newer release of vallorcine and,
with confirmation, downloads and applies it to this project.

Safe to run at any time. Never touches .kb/, .decisions/, or .feature/.
User-populated index files are preserved even when kit files are updated.

---

## Pre-flight checks

Run silently before displaying anything:

1. Check that `.claude/upgrade.sh` exists.
   If not:
   ```
   ⬆️  UPGRADE
   ───────────────────────────────────────────────
   upgrade.sh not found in .claude/.

   This project was installed from an older version of vallorcine
   that did not include the upgrade command.

   To upgrade manually:
     1. Download the latest release zip from the repository
     2. Run: FORCE_UPDATE=1 bash install.sh <project-path>
   ```
   Stop.

2. Check that `.claude/.vallorcine-source` exists.
   If not:
   ```
   ⬆️  UPGRADE
   ───────────────────────────────────────────────
   .vallorcine-source not found.

   This file tells the upgrade command where to check for new releases.
   It is written by install.sh from the release package.

   Re-install from a release zip to restore it:
     bash install.sh <project-path>
   ```
   Stop.

3. Check that `bash` and either `curl` or `gh` are available.
   If neither: stop with message explaining what's needed.

Display opening header:
```
───────────────────────────────────────────────
⬆️  UPGRADE
───────────────────────────────────────────────
```

---

## Step 1 — Check for new release

Run:
```bash
bash .claude/upgrade.sh --check
```

Capture the full output. Parse for:
- "Already up to date" → installed version IS the latest
- "Available : v<X>" → a newer version exists
- Any error output → fetch failed

**If already up to date:**
```
⬆️  UPGRADE
───────────────────────────────────────────────
Already up to date. v<VERSION> is the latest release.
───────────────────────────────────────────────
```
Stop.

**If fetch failed:** display the error output from upgrade.sh and stop.
Do not proceed to apply if the version check itself failed.

**If a new version is available:** display:
```
── New release available ────────────────────────
  Installed : v<INSTALLED>
  Available : v<LATEST>

── What's new ───────────────────────────────────
<release notes from upgrade.sh output>
─────────────────────────────────────────────────
```

---

## Step 2 — Explain what will change

Display:
```
── What upgrade does ────────────────────────────
Updates (overwritten with new version):
  .claude/commands/    all slash commands
  .claude/agents/      all agent definitions
  .claude/rules/       all rules files
  .claude/upgrade.sh   this upgrade script

Preserved (never touched):
  .kb/                 your knowledge base
  .decisions/          your architecture decisions
  .feature/            your in-progress feature work
  .kb/CLAUDE.md        if you have added topics
  .decisions/CLAUDE.md if you have added decisions

Use AskUserQuestion with options:
  - "Apply upgrade"
  - "Stop"

If "Stop": exit cleanly with "Upgrade cancelled. No changes made."

---

## Step 3 — Apply

Run:
```bash
bash .claude/upgrade.sh
```

Stream the output directly to the user as it runs — do not buffer it.

If upgrade.sh exits non-zero: display the error and stop.

---

## Step 4 — Commit upgrade

Kit upgrades are a separate concern from feature work. Commit them immediately
so they don't leak into feature PRs.

First, check if there are already staged changes from other work:
```bash
git diff --cached --stat
```

If there ARE already-staged files: **stash them first** so they don't get
swept into the upgrade commit:
```bash
git stash push --staged -m "pre-upgrade: stash staged changes"
```

Then stage only kit-managed files:
```bash
git add .claude/skills/ .claude/agents/ .claude/rules/ .claude/scripts/ \
       .claude/upgrade.sh .claude/.vallorcine-version .claude/.vallorcine-manifest
```

Also stage `.gitattributes` and `.gitignore` if they were modified by the
installer (merge driver entries, runtime file entries).

Verify only kit files are staged with `git diff --cached --stat`. If non-kit
files are accidentally staged, unstage them before committing.

Commit with:
```
chore: upgrade vallorcine to v<LATEST>
```

If staged changes were stashed earlier, restore them:
```bash
git stash pop
```

Display:
```
── Committed ───────────────────────────────────
Upgrade committed as a standalone change.
This keeps kit updates out of your feature PRs.
```

If the commit fails (e.g. pre-commit hook rejects formatting on kit files):
display the error and suggest the user commit manually with the same message.
Do not block the upgrade — the files are already updated on disk.
If staged changes were stashed, restore them regardless of commit success.

---

## Step 5 — Post-upgrade notice

Display:
```
───────────────────────────────────────────────
⬆️  UPGRADE complete · v<INSTALLED> → v<LATEST>
───────────────────────────────────────────────
New commands and agents are active immediately.
Upgrade committed — won't appear in feature PRs.

If any command behaviour seems unexpected, check CHANGELOG.md
in the source repository for breaking changes:
  <REPO_URL>/blob/main/CHANGELOG.md

───────────────────────────────────────────────
```

Note: the new commands are live as soon as the files are written —
Claude Code picks up slash command changes without a restart.
