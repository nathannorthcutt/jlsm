# /release

Creates a versioned release of vallorcine.

Bumps VERSION, updates CHANGELOG.md, builds the release zip, commits,
tags, and pushes. Run from the vallorcine repo root.

---

## Pre-flight checks

Run silently before displaying anything:

1. Check that `VERSION` exists in the current directory.
   If not: "This command must be run from the vallorcine repo root."

2. Check that git is available: `git --version`
   If not: "git is not available. Install git and retry."

3. Check that the working tree is clean: `git status --porcelain`
   If there are uncommitted changes:
   ```
   🚀 RELEASE
   ───────────────────────────────────────────────
   ⚠  Uncommitted changes detected:
   <list of changed files from git status>

   A release should be cut from a clean working tree.

     ↵  commit changes first  ·  or type: force
   ```
   If Enter: stop and remind the user to commit or stash changes first.
   If "force": proceed with a warning note in the release commit message.

4. Read `VERSION` — store as CURRENT_VERSION.
   Parse into MAJOR.MINOR.PATCH integers.

Display opening header:
```
───────────────────────────────────────────────
🚀 RELEASE · current: v<CURRENT_VERSION>
───────────────────────────────────────────────
```

---

## Step 1 — Version bump

Display:
```
── Version bump ─────────────────────────────────
Current: <CURRENT_VERSION>

  ↵  patch  (<MAJOR>.<MINOR>.<PATCH+1>)  ·  or type: minor  /  major
```

Wait for input:
- Enter or "patch": increment PATCH, reset nothing
- "minor": increment MINOR, reset PATCH to 0
- "major": increment MAJOR, reset MINOR and PATCH to 0

Compute NEW_VERSION. Display:
```
New version: <NEW_VERSION>

  ↵  confirm  ·  or type: stop
```

Wait. If stop: exit cleanly.

---

## Step 2 — Draft release notes

Run: `git log v<CURRENT_VERSION>..HEAD --oneline 2>/dev/null || git log --oneline -20`

Display the commits found:
```
── Commits since v<CURRENT_VERSION> ─────────────────
<list of commits, one per line>
─────────────────────────────────────────────────
```

Display:
```
── Release notes ────────────────────────────────
I'll draft CHANGELOG.md entries from the commits above.

Describe what changed in this release, or press Enter and I'll
generate a draft from the commit log.
```

Wait for input.

**If the user provides notes:** use them as the base for the CHANGELOG entry.
**If Enter:** draft the CHANGELOG entry yourself from the commit messages.
  Group commits by type if they follow conventional commit format (feat/fix/chore).
  Otherwise summarise them plainly. Keep it factual — what changed, not why.

Show the drafted entry:
```
── Draft CHANGELOG entry ────────────────────────
## [<NEW_VERSION>] — <YYYY-MM-DD>

<drafted content>
─────────────────────────────────────────────────
  ↵  looks good  ·  or type: edit
```

If "edit": ask for corrections and incorporate them, then show again.
If Enter: proceed.

---

## Step 3 — Build release zip

```
── Building release zip ─────────────────────────
```

Run the following, capturing any errors:

```bash
cd <repo_root>
zip -r "vallorcine-v<NEW_VERSION>.zip" . \
  --exclude "*.git*" \
  --exclude ".claude/*" \
  --exclude ".feature/*" \
  --exclude ".kb/*" \
  --exclude ".decisions/*" \
  --exclude ".DS_Store" \
  --exclude "*.zip" \
  --exclude ".env*"
```

If the zip command fails: display the error and stop.

Display:
```
  Built: vallorcine-v<NEW_VERSION>.zip  (<size>)
```

Write `.vallorcine-source` in the repo root:

```
repo=<REMOTE_URL>
api=https://api.github.com/repos/<OWNER>/<REPO>/releases
```

Derive OWNER and REPO by parsing REMOTE_URL:
- Strip `https://github.com/` prefix or `git@github.com:` prefix
- Strip `.git` suffix
- Split on `/` to get OWNER and REPO

If the remote URL cannot be parsed as a GitHub URL: write the raw URL to
`repo=` and leave `api=` blank with a comment `# non-GitHub remote`.

This file is included in the zip (not gitignored) so consumers get it on
install. install.sh copies it to `.claude/.vallorcine-source`.

Display:
```
  Wrote: .vallorcine-source  (repo + API endpoint)
```

---

## Step 4 — Apply version changes

Make the following file changes:

1. **Write `VERSION`**: single line containing `<NEW_VERSION>`

2. **Prepend to `CHANGELOG.md`**: insert the new entry after the first `---`
   separator (after the header section), before the previous latest entry.
   The entry format:
   ```
   ## [<NEW_VERSION>] — <YYYY-MM-DD>

   <release notes>

   ---
   ```

Display:
```
── Files updated ────────────────────────────────
  VERSION        <CURRENT_VERSION> → <NEW_VERSION>
  CHANGELOG.md   entry added for v<NEW_VERSION>
```

---

## Step 5 — Commit and tag

Run the following git commands in sequence, displaying each before running:

```bash
git add VERSION CHANGELOG.md .vallorcine-source
git commit -m "release: v<NEW_VERSION>"
git tag -a "v<NEW_VERSION>" -m "Release v<NEW_VERSION>"
```

If any command fails: display the error, show what succeeded, and stop.
Do not push until commit and tag both succeed.

Display:
```
── Git ──────────────────────────────────────────
  Committed: release: v<NEW_VERSION>
  Tagged:    v<NEW_VERSION>
```

---

## Step 6 — Push

Check if a remote named `origin` exists: `git remote get-url origin`

If no remote:
```
── No remote configured ─────────────────────────
No git remote named 'origin' found.
Commit and tag are local. To push manually:

  git remote add origin <url>
  git push origin main --tags
```
Stop.

If remote exists, display:
```
── Push ─────────────────────────────────────────
Remote: <remote URL>

  ↵  push main + tag  ·  or type: skip
```

If skip: show the manual push commands and stop.
If Enter: run:

```bash
git push origin main
git push origin "v<NEW_VERSION>"
```

If push fails (e.g. branch protection, auth): display the error and the
manual commands. Do not retry automatically.

---

## Step 7 — GitHub Release (if gh CLI available)

Check: `gh --version 2>/dev/null`

If `gh` is not available: skip to summary.

If available, check auth: `gh auth status 2>/dev/null`

If not authenticated: skip to summary with a note.

If authenticated:
```
── GitHub Release ───────────────────────────────
gh CLI detected. Create a GitHub Release with the zip attached?

  ↵  create release  ·  or type: skip
```

If Enter: run:
```bash
gh release create "v<NEW_VERSION>" \
  "vallorcine-v<NEW_VERSION>.zip" \
  --title "v<NEW_VERSION>" \
  --notes "<release notes — first paragraph only>"
```

Display the release URL on success.

If skip or gh fails: display instructions for creating manually:
```
To create a GitHub Release manually:
  1. Go to: <remote URL>/releases/new
  2. Tag: v<NEW_VERSION>
  3. Title: v<NEW_VERSION>
  4. Attach: vallorcine-v<NEW_VERSION>.zip
  5. Paste the CHANGELOG entry as the release description
```

---

## Step 8 — Summary

```
───────────────────────────────────────────────
🚀 RELEASE complete · v<NEW_VERSION>
───────────────────────────────────────────────
Version  : <CURRENT_VERSION> → <NEW_VERSION>
Tag      : v<NEW_VERSION>
Zip      : vallorcine-v<NEW_VERSION>.zip  (<size>)
Pushed   : <yes / no — skipped / no — no remote>
GH Release: <URL / not created>
───────────────────────────────────────────────
Install command for users:
  bash install.sh /path/to/project
  (after cloning or downloading the zip)
───────────────────────────────────────────────
```

Note: `vallorcine-v<NEW_VERSION>.zip` is in the repo root but gitignored.
It is the distributable artifact — attach to GitHub Release or share directly.
It is not committed to git history.

`.vallorcine-source` IS committed — it tells consumers where to check
for upgrades. install.sh copies it to `.claude/.vallorcine-source` in
the target project.
