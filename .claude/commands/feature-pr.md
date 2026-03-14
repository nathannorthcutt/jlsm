# /feature-pr "<feature-slug>"

Drafts a pull request title, description, and review checklist by reading the
feature's working files. Works for both /feature and /quick slugs.

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

  ↵  use existing draft  ·  or type: regenerate
```
If the user presses Enter: display existing draft and stop. If user types regenerate: proceed.

---

Display opening header:
```
───────────────────────────────────────────────
📋 PR DRAFT · <slug>
───────────────────────────────────────────────
```

## Step 1 — Load context

Read in order:
1. `.feature/<slug>/status.md` — current state, cycle count
2. `.feature/<slug>/brief.md` if it exists (full /feature pipeline)
   OR the Description field from status.md (quick task)
3. `.feature/<slug>/work-plan.md` if it exists
4. `.feature/<slug>/cycle-log.md` — full history
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

## Step 5 — Report

Display:
```
───────────────────────────────────────────────
📋 PR DRAFT complete · <slug>
───────────────────────────────────────────────
PR draft written to .feature/<slug>/pr-draft.md

Copy the title and description into your PR. The review checklist
can be pasted into the PR description or used as a reviewer guide.

When the PR merges, run:
  /feature-complete "<slug>"
```
