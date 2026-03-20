---
description: "Review codebase quality — find stale decisions, knowledge gaps, and implicit dependencies"
argument-hint: "[--init] [--deeper]"
---

# /curate [--init] [--deeper]

Correlation engine that combines vallorcine's structured history with git data
to find things that individual features, decisions, and research sessions
couldn't see because they each had a narrower scope.

**What it finds:**
1. ADR pressure — decisions under concentrated change (scope being actively modified)
2. ADR gravity — files implicitly related to decisions but not in their scope
3. Hub files — shared dependencies across 3+ decisions (fragility/test concerns)
4. ADR drift — code diverging from architectural decisions
5. Stale KB — research that may have better approaches now given what's been built
6. Implicit dependencies — gaps between independently-designed features
7. Orphaned areas — high-churn files with no structured knowledge behind them

**Flags:**
- `--init` — first-time scan (ignores last-scanned SHA, good for new installs)
- `--deeper` — scan 6 months instead of default 3

This command feels like a colleague who noticed something and is offering to help,
not a task manager assigning work.

---

## Step 0 — Pre-flight

Check that `.curate/` directory exists. If not, create it.

Read `.curate/curation-state.md` if it exists — extract last-scanned SHA and
any previously deferred items from the review log.

Display opening header:
```
───────────────────────────────────────────────
🔍 CURATION · scanning for quality signals
───────────────────────────────────────────────
```

---

## Step 0.5 — Index verification (self-healing)

Before scanning, run the index verification script to catch and repair any
index inconsistencies from previous crashes:

```bash
bash .claude/scripts/index-verify.sh --both 2>&1
```

If repairs are made, the script outputs what was fixed. Note these for the
findings presentation — they're bookkeeping fixes the user should know about
but don't need to act on.

If the script doesn't exist (older install), skip silently.

---

## Step 1 — Run the scan script

Build the scan command:

```bash
bash .claude/scripts/curate-scan.sh [--init] [--window <months>]
```

- Default: `--window 3` (3 months, capped at 500 commits)
- If `--init` flag: pass `--init`
- If `--deeper` flag: pass `--window 6`

Run the script. If it exits with "No new commits since last scan," report that
and ask if the user wants to force a rescan with `--init`.

---

## Step 2 — Read and correlate

Read `.curate/scan-summary.md` (the script's output).

Also read (if they exist):
- `.decisions/CLAUDE.md` — active decisions index
- `.kb/CLAUDE.md` — KB root index
- `.feature/CLAUDE.md` — active and archived features

### 2a — ADR drift detection

**ADR Pressure** (from "ADR Pressure" in scan summary):
1. ADRs with 2+ constrained files changed in the scan window
2. Higher pressure % = more of the decision's scope is actively changing
3. Read the ADR and assess: is the code evolving within the decision, or away from it?
4. High pressure (>60%) → strong signal for re-evaluation

**ADR Gravity** (from "ADR Gravity" in scan summary):
1. Files that co-change with ADR-constrained files but aren't in the ADR's scope
2. These are implicit relationships — the decision's influence is wider than documented
3. Assess: should these files be added to the ADR's `files:` field, or is the
   co-change coincidental?
4. High gravity (5+ unconstrained files for one ADR) → potential **isolation problem**.
   The decision may have drawn the boundary in the wrong place. Flag for `/architect`
   review with framing: "This decision's actual dependency footprint is larger than
   its documented scope — worth re-evaluating the boundary."

**Hub Files** (from "Hub Files" in scan summary):
1. Files co-changing with 3+ ADRs' constrained areas
2. These are fragility points — changes here ripple across multiple decisions
3. Flag as test coverage concerns: "This file is a shared dependency across
   <N> architectural decisions. Worth ensuring test coverage is solid."

**Flat artifact correlations** (from "Artifact Correlations" where Type is ADR):
1. Individual ADR file references not captured by pressure (single-file changes)
2. Read the referenced ADR, compare stated approach against changed files
3. Check if "Conditions for Revision" have been met by recent changes

### 2b — KB + hindsight review

For each entry in "Stale KB Entries":
1. Note the KB file and how long since last research
2. Cross-reference with "Churn Hotspots" — is the area the KB covers actively changing?
3. Check if any ADRs were made since the KB entry was written that might change
   which options are viable

For each entry in "Artifact Correlations" where Type is KB:
1. Note that implementation has changed since research was done
2. Flag if the changes suggest the research conclusions may need updating

### 2c — Implicit dependency detection

Using "Co-change Clusters" and "Artifact Correlations" where Type is FEATURE:
1. Identify file pairs that co-change but were designed in separate features
2. Check if cross-feature test coverage exists for the shared files
3. Flag gaps where independently-designed features share files without
   cross-coverage

### 2d — Orphaned areas

From "Orphaned Areas" in the scan summary:
1. Identify high-churn files with no KB, ADR, or feature coverage
2. These are backfill candidates — areas the codebase is actively changing
   but that have no structured knowledge behind them

### 2e — Test-source drift

From "Test-Source Drift" in the scan summary:
1. Source files that changed but their corresponding tests didn't
2. Cross-reference with feature archives — were these files part of features
   that should have had test updates?
3. Flag files where the drift is significant (3+ source commits with no test change)
4. This catches within-feature drift where implementation evolved but tests
   didn't keep pace

### 2f — Backfill candidates (implicit decisions)

From "Backfill Candidates" in the scan summary:
1. Archived feature domains that made implicit decisions (no governing ADR)
2. For each candidate, assess whether the decision is significant enough to
   warrant formal documentation
3. Present as items the user can decide, draft as ADR, defer, or dismiss
4. This subsumes the standalone `/decisions backfill` command — curate is the
   single entry point for finding undocumented decisions

### 2g — Out-of-scope items (deferred work in accepted ADRs)

From "Out-of-Scope Items" in the scan summary:
1. Items from "What This Decision Does NOT Solve" sections of confirmed ADRs
   that have no corresponding deferred decision stub
2. These are architectural concerns the team explicitly scoped out when making
   a decision — they are effectively deferred work invisible to `/decisions triage`
3. Group items by parent ADR for presentation
4. For each item, the user can: create a deferred stub, skip, or create all
   stubs from that parent ADR at once

---

## Step 3 — Present findings as a numbered pick list

Present findings as a numbered list, grouped by priority (highest first).
Each item gets: the problem, why it matters, and what you'll do if they pick it.
Lead with the most actionable items. Tone: offering help, not assigning tasks.

### Cold start (no existing KB/ADRs/features)

When there are no artifact correlations (everything is orphaned), present as
a prioritized bootstrapping guide:

```
I scanned the last <N> months of changes (<N> commits) and found <N> areas
worth exploring:

  1. <Area> (<files>) — <N> commits, <observation>.
     → I'll research how this is structured and write a KB entry

  2. <Area> (<files>) — <observation>.
     → I'll run an architecture review to document the current approach

  3. <Area> (<files>) — <observation>.
     → I'll explore this area and surface anything worth documenting

Pick a number to start, or:
  all   — work through each item in order
  done  — note remaining items for next /curate run

Items you don't address are saved automatically — run /curate anytime to pick them up.
```

### Warm repo (has existing artifacts)

Present correlations first (numbered), then orphaned areas:

```
I scanned <N> commits since last review and found <N> items:

  1. <Index/integrity issue> — <what's broken and impact>
     → I'll fix this now (no confirmation needed, it's bookkeeping)

  2. <ADR slug> — <N>% pressure (<M> of <T> constrained files changed)
     → I'll compare the current code against this decision

  3. <ADR slug> — <N> unconstrained files co-changing with its scope
     → This decision's boundary may not match the actual dependencies.
       I'll review the isolation.

  4. <Hub file> — shared across <N> decisions (<slugs>)
     → I'll check test coverage for this shared dependency

  5. <ADR slug> — <what changed and why the decision may not fit>
     → I'll re-evaluate this decision against the current codebase

  6. <KB entry> — last researched <date>, <what's changed since>
     → I'll refresh this research with current implementation context

  7. <Shared files> — touched by <feature A> and <feature B>, no cross-coverage
     → I'll explore the interaction and flag anything missed

  8. <Orphaned files> — <N> commits, no KB or decision coverage
     → I'll research this area so future work has context

  9. <parent-adr-slug> — <N> out-of-scope items with no deferred stubs
     → I'll show them and you can choose which to track as deferred decisions

Pick a number to start, or:
  all   — work through each item in order
  done  — note remaining items for next /curate run

Items you don't address are saved automatically — run /curate anytime to pick them up.
```

### After completing an item

After resolving an item, re-present the remaining list (renumbered) so the
user can pick the next one without having to remember what was left:

```
Done. <N> items remaining:

  1. <next item> — <description>
     → <action>

  2. ...

Pick a number, or: done
```

### Nothing found

```
I scanned <N> commits since last review — nothing flagged.

Your KB entries are current, ADRs align with the code, and there are no
obvious coverage gaps. Nice.

Next scan will pick up from here automatically.
```

---

## Step 4 — Handle user response (LOOP — always return to pick list)

**CRITICAL: After completing ANY item, ALWAYS return to the pick list with
remaining items. NEVER go to the closing report until the user explicitly
says "done" or all items are resolved. The user controls when curation ends,
not the agent.**

The flow is a loop:
```
Present numbered list → user picks → execute action → mark resolved →
re-present remaining items → user picks again → ... → user says "done" → close
```

### User picks a number

Execute the action for that item:

**Index/integrity fixes:** Fix directly — rebuild indexes, clean up stale
entries. These are bookkeeping and don't need architectural judgment.

**ADR pressure:** Read the ADR file, then compare the decision's stated approach
against the changed constrained files. Present a summary: "This decision
constrains <N> files and <M> have changed. Here's what shifted: <brief
description>." Offer: "Want me to run a full re-evaluation via /architect?"

**ADR gravity (low, <5 files):** Read the ADR and the unconstrained files.
Assess whether the relationship is meaningful or coincidental. If meaningful:
"These files appear to be implicitly part of this decision's scope. Want me
to add them to the ADR's files: field?" If coincidental: note and move on.

**ADR gravity (high, 5+ files) — isolation concern:** This is a boundary
problem, not just missing file tags. Invoke `/architect "<ADR-slug> boundary
review"`. Provide context: "This decision's actual dependency footprint is
significantly wider than documented — <N> unconstrained files co-change with
its scope. The boundary may need redrawing."

**Hub files:** Read the hub file and the ADRs it's connected to. Assess test
coverage: does the file have tests that cover its interaction with each
decision's constrained area? Flag gaps. This is not an `/architect` issue —
it's a test coverage concern. Present: "This file is shared across <N>
decisions. Current test coverage: <assessment>."

**ADR drift:** Invoke `/architect "<problem>"` as a review session.
Provide context: "This was originally decided on <date> because <reason>. The
codebase has shifted — want me to run a full re-evaluation?"

**Stale KB:** Invoke `/research <topic> <category> "<subject>"`.
Provide context: "This was researched on <date>. Since then, <what changed>.
Want me to refresh this with current information?"

**Implicit dependencies:** Investigate directly within the curation session.
Read the shared files, review the feature briefs that touched them, and assess
whether there's a real gap. Present findings and suggest next steps (which
might be "this is fine" or "worth adding tests for X interaction").

**Orphaned areas:** Offer `/research` to build understanding, or `/architect`
if the area seems to need a decision.

**Test-source drift:** Investigate the specific files — read the source changes
and the existing tests. Assess whether the tests are genuinely stale (need
updating) or whether the source changes were internal refactoring that doesn't
affect the test contracts. Present findings: "tests need updating because X
changed" or "tests are still valid — the changes were internal."

**Backfill candidates (implicit decisions):** Present the candidate with context
from the archived feature. Offer the same actions as the old `/decisions backfill`:
- **decide** → invoke `/architect` with the problem statement
- **draft** → write a draft ADR (status: draft, source: backfill)
- **defer** → write a deferred stub
- **dismiss** → append to `.decisions/.backfill-dismissed`, won't resurface

**Out-of-scope items (deferred work in accepted ADRs):** Present items grouped
by parent ADR:

```
── Out-of-scope items from <parent-slug> ──────
This ADR (accepted <date>) scoped out these items:

  [1] <item text> — <reason>
  [2] <item text> — <reason>
  ...

For each: create-stub · skip
Or: create-all · skip-all
```

- **create-stub** → Write a deferred decision stub using the Step 0D template
  from `/architect`:
  - Slugify the concern (first ~5 words, kebab-case)
  - Problem: the concern text
  - Why Deferred: "Scoped out during `<parent-slug>` decision. `<reason>`."
  - Resume When: "When `<parent-slug>` implementation is stable and this
    concern becomes blocking."
  - What Is Known So Far: "See `.decisions/<parent-slug>/adr.md` for the
    architectural context that excluded this concern."
  - Next Step: "Run `/architect "<concern>"` when ready to evaluate."
  - Add a row to the Deferred section of `.decisions/CLAUDE.md`
  - Append an `out-of-scope-promoted` event to the parent ADR's `log.md`
- **create-all** → Apply create-stub to all items from that parent ADR
- **skip** → Items resurface on next `/curate` run (no dismiss needed — once
  a stub exists, the scan deduplicates automatically)

After completing the action, mark it `resolved` in the review log. Then
**ALWAYS re-present the remaining items** (renumbered) so the user can
continue. Only proceed to Step 5 when the user says "done" or all items
are resolved.

### User says "all"

Work through items in order, starting with #1. After each item completes,
proceed to the next. After ALL items are resolved, show the closing report.

### User says "done"

Note all remaining unaddressed items as `suggested` in the review log.
Display:
```
Noted <N> remaining items. They'll resurface next time you run /curate,
deprioritized below any new findings.

Run /curate anytime — incremental scans are fast.
```
Proceed to Step 5.

### Automatic persistence

All findings are written to the review log regardless of user action:
- Items the user acts on → `resolved`
- Items the user explicitly defers → `deferred`
- Items the user doesn't address (says "done" or session ends) → `suggested`

Nothing gets lost. The next `/curate` run resurfaces `suggested` and `deferred`
items, deprioritized below new findings.

---

## Step 5 — Update curation state

After the user is done (explored items, deferred, or noted for later):

Update `.curate/curation-state.md`:

```markdown
# Curation State

## Scan State
Last scanned: <current HEAD SHA>
Last scanned date: <YYYY-MM-DD>
Window: <months used>
Commits scanned: <N>

## Review Log
| Date | Item | Status | Notes |
|------|------|--------|-------|
| <date> | <item> | <explored / deferred / suggested / resolved> | <notes> |
```

**Status values:**
- `explored` — user investigated, no further action needed
- `deferred` — user chose to defer, resurface next time (deprioritized)
- `suggested` — flagged but user noted for later
- `resolved` — was flagged, user took action (ran /architect, /research, etc.)

---

## Step 6 — Closing report

```
───────────────────────────────────────────────
🔍 CURATION complete
───────────────────────────────────────────────
Scanned: <N> commits (<scan mode>)
Found: <N> items across <N> categories
Explored: <N>  Deferred: <N>  Noted: <N>

Next scan will pick up from <current SHA short>.
Run /curate anytime — incremental scans are fast.
───────────────────────────────────────────────
```

---

## Quality checklist (self-verify before ending session)

- [ ] Scan script ran successfully and produced scan-summary.md
- [ ] Findings presented conversationally, not as a raw dump
- [ ] Each finding includes why it matters and an offer to help
- [ ] User responses recorded in curation-state.md review log
- [ ] Last-scanned SHA updated in curation-state.md
- [ ] No commands assigned — only offers made
- [ ] Cold start findings are prioritized bootstrapping suggestions, not a wall of "everything is orphaned"
