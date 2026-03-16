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
If "yes": skip to Step 5 — PR creation (attempt to create the PR from the existing draft).

---

Display opening header:
```
───────────────────────────────────────────────
📋 PR DRAFT · <slug>
───────────────────────────────────────────────
```

## Step 0b — Token tracking

Run silently: `bash -c 'source .claude/scripts/token-usage.sh && token_checkpoint ".feature/<slug>" "pr-draft"'`

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
Looks good, or anything to adjust?
─────────────────────────────────────────────────────────────
```

Iterate on feedback. When confirmed, write to disk.

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

## Step 5 — Create the PR

**Token tracking:** run `bash -c 'source .claude/scripts/token-usage.sh && token_summary ".feature/<slug>" "pr-draft"'`
and capture the output as TOKEN_USAGE.

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
```
Stop.

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
While the feature is fresh, consider running a retrospective:
  /feature-retro "<slug>"

When the PR merges:
  /feature-complete "<slug>"
```

If `gh pr create` fails (e.g. branch not pushed, no remote): display the error
and fall back to the manual instructions above. Do not retry automatically.
