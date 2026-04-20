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
8. Unspecified shared types — foundational types referenced by 3+ specs with no spec
9. Spec obligations — DRAFT specs with unresolved conflicts blocking approval
10. Spec-code drift — specs whose domain code changed after the spec was written
11. Cross-reference gaps — KB entries and ADRs with missing related/source links

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

### 2h — Spec coverage analysis

**Guard:** Only run this step if `.spec/` exists. If no spec directory, skip
entirely — don't mention specs or suggest setting up specs.

From "Spec Coverage Gaps" in the scan summary (if present):

**Unspecified shared types:**
1. Types referenced by 3+ specs that have no spec of their own
2. These are foundational types with implicit contracts — multiple specs
   depend on their behavior but nobody has defined what that behavior is
3. Rank by reference count — higher count = more dependent specs = bigger risk

**Specs with open obligations:**
1. Specs with `[UNRESOLVED]` or `[CONFLICT]` markers or `open_obligations`
   in frontmatter
2. These are blocking downstream work — DRAFT specs can't be relied on until
   obligations are resolved
3. Higher obligation count = more blocking

**Obligation registry (from _obligations.json):**
1. Open obligations from the centralized registry — spec requirements where the
   code does not match the spec. These are the gap between what was specified
   and what was built.
2. Group by spec for display. Show affected requirement count and blocked_by.
3. Route to `/work-decompose "<group>" --from-obligations` to convert
   obligations into a work group with proper WD ordering. This is the primary
   action — obligations without a work group have no implementation path.
4. Higher affected-requirement count = larger implementation gap.

**Spec-code drift:**
1. Specs whose domain files have been committed since the spec was created
2. Higher commit count = more likely the spec no longer matches reality
3. Cross-reference with ADR pressure — if the same area has both ADR pressure
   and spec drift, it's a stronger signal

**Undecided absent behaviors:**
1. Specs with `[ABSENT]` requirements — behaviors that downstream specs assume
   but the implementation doesn't provide
2. These are unresolved design decisions: each `[ABSENT]` requirement needs an
   explicit promote/preserve/defer choice
3. Higher count = more implicit assumptions without backing decisions

**Orphaned specs (no matching source code):**
1. APPROVED specs whose subject tokens were not found in any source file
2. These may describe behavior that was removed without updating the spec
3. For each orphaned spec, use AskUserQuestion with options:
   - **"Verify with /spec-verify"** — run spec-verify to check if the
     behavior still exists (subject token search may have missed it)
   - **"Mark as INVALIDATED"** — the behavior was removed; mark the spec
     as INVALIDATED with `displacement_reason: "behavior removed — detected
     by curate scan"`
   - **"Skip for now"** — defer to a later curation pass

### 2i — Cross-reference repair candidates

**Guard:** Only run this step if "Cross-Reference Candidates" section exists in
the scan summary. If absent, skip entirely.

From "Cross-Reference Candidates" in the scan summary:

**KB entries with missing related links (tag overlap):**
1. Entry pairs that share 2+ tags but have no `related` link between them
2. Higher tag overlap = stronger signal that these entries should reference each other
3. Entries in different categories are more valuable links — same-category entries
   are already navigable via category indexes
4. Assess whether the overlap is meaningful: shared tags like "performance" +
   "caching" between a caching strategy and a benchmarking entry → likely related.
   Shared tags like "java" + "testing" between unrelated entries → coincidental.

**KB entries with overlapping applies_to:**
1. Entries that target the same source files/patterns but don't reference each other
2. These likely describe different aspects of the same code — a `related` link
   helps the Research Agent find all relevant context when loading one entry
3. Stronger signal than tag overlap because file paths are specific

**ADR evaluation references not in KB Sources:**
1. KB entries cited in evaluation.md scoring that don't appear in the ADR's
   KB Sources Used table
2. These are missing traceability links — the ADR used this research during
   evaluation but doesn't formally reference it
3. Fix is straightforward: add the missing row to the KB Sources table

### 2j — Deferred audit feedback

**Guard:** Only run this step if "Deferred Audit Feedback" section exists
in the scan summary. If absent, skip entirely.

From "Deferred Audit Feedback" in the scan summary:

1. Each row is a `spec-updates.md` or `kb-suggestions.md` file from a
   completed audit where the user skipped or deferred the feedback loop
2. These contain ready-made spec requirements and KB pattern suggestions
   that a prior audit produced — they don't need re-analysis, just review
   and application
3. Present as high-priority pick list items — the work is already done,
   applying it is cheap

When the user picks one of these items:
- Read the file at the path shown in the scan summary
- Present the contents using the same apply/review/skip (for specs) or
  create/select/skip (for KB) menus from the audit feedback loop
  (see audit SKILL.md Job 5a/5b for the exact flow)
- After applying: rename the file from `<name>.md` to `<name>.applied.md`
  so it won't be picked up by future curate scans or audit feedback loops

### 2k — Decisions roadmap needed

**Guard:** Only run this step if "Decisions Roadmap Needed" section exists
in the scan summary. If absent, skip entirely.

From "Decisions Roadmap Needed" in the scan summary:

1. There are 10+ deferred decisions with no current roadmap
2. Present as a high-priority pick list item: "N deferred decisions need
   planning — run `/decisions roadmap` to cluster and prioritize"
3. When the user picks this item: suggest running `/decisions roadmap` in
   a separate session (roadmap is a planning skill, not a curate action)

### 2l — Work group health

**Guard:** Only run this step if any "Work Group:" section exists in the scan
summary. If absent, skip entirely.

**Displaced dependencies:**
1. Work definitions that depend on specs now INVALIDATED
2. These WDs are effectively BLOCKED by a spec that no longer exists
3. For each, use AskUserQuestion with options:
   - **"Author replacement spec"** → suggest `/spec-author` for the missing spec
   - **"Update WD to remove dependency"** → the WD no longer needs this artifact
   - **"Skip for now"** — defer

**Stalled work groups:**
1. Work groups with no WD activity in 14+ days
2. Present the group name, total WDs, completed WDs, and days since last activity
3. For each, use AskUserQuestion with options:
   - **"Check status"** → run `/work-status "<group>"`
   - **"Skip"** — acknowledged, no action needed

**Artifact drift:**
1. WDs whose artifact dependencies were modified after the WD was written
2. The artifact still exists but its content changed — the WD's assumptions
   may be stale
3. For each, use AskUserQuestion with options:
   - **"Review WD"** → read the WD and the changed artifact, assess impact
   - **"Skip"** — the change was minor and doesn't affect the WD

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

Items you don't address are saved automatically — run /curate anytime to pick them up.
```

Use AskUserQuestion to let the user choose. Build options dynamically:
- If 4 or fewer items: one option per item (labeled with its number and
  short description), plus `All` (description: "Work through each item in
  order") and `Done` (description: "Note remaining items for next /curate run").
- If more than 4 items: use `All`, `Done`, and `Other` (description: "Type
  a number to start with"). If the user selects "Other", wait for them to
  provide the item number as free text.

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

  8. <parent-adr-slug> — <N> out-of-scope items with no deferred stubs
     → I'll show them and you can choose which to track as deferred decisions

  9. <TypeName> — referenced by <N> specs but has no spec of its own
     → I'll run spec extraction to define its contract and find cross-spec conflicts

 10. Spec <ID> (<name>) — <N> unresolved conflicts blocking APPROVED status
     → I'll show the conflicts so you can resolve them via /spec-author

 11. Spec <ID> (<name>) — <N> commits to related files since spec was written
     → I'll check if the spec still matches the implementation via /spec-verify

 12. Spec <ID> (<name>) — <N> undecided [ABSENT] requirements need explicit decisions
     → I'll show each one so you can promote, preserve, or defer

 13. <Orphaned files> — <N> commits, no KB or decision coverage
     → I'll research this area so future work has context

 14. <N> KB entries may need `related` links — <N> tag-overlap pairs, <N> applies_to overlaps
     → I'll show the most likely candidates so you can add or dismiss each link

 15. <adr-slug> — evaluation references <N> KB entries not in its Sources table
     → I'll add the missing references to the ADR

Items you don't address are saved automatically — run /curate anytime to pick them up.
```

Use AskUserQuestion to let the user choose. Build options dynamically:
- If 4 or fewer items: one option per item (labeled with its number and
  short description), plus `All` (description: "Work through each item in
  order") and `Done` (description: "Note remaining items for next /curate run").
- If more than 4 items: use `All`, `Done`, and `Other` (description: "Type
  a number to start with"). If the user selects "Other", wait for them to
  provide the item number as free text.

### After completing an item

After resolving an item, re-present the remaining list (renumbered) so the
user can pick the next one without having to remember what was left:

```
Done. <N> items remaining:

  1. <next item> — <description>
     → <action>

  2. ...

```

Use AskUserQuestion to let the user choose. Build options dynamically:
- If 4 or fewer remaining items: one option per item (labeled with its number
  and short description), plus `Done` (description: "Note remaining items for
  next /curate run").
- If more than 4 remaining items: use `Done` and `Other` (description: "Type
  a number to continue with"). If the user selects "Other", wait for them to
  provide the item number as free text.

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
description>."

Use AskUserQuestion:
  - "Re-evaluate via /architect"
  - "Skip"

**ADR gravity (low, <5 files):** Read the ADR and the unconstrained files.
Assess whether the relationship is meaningful or coincidental. If meaningful:
"These files appear to be implicitly part of this decision's scope."

Use AskUserQuestion:
  - "Add files to ADR scope"
  - "Skip (coincidental)"

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

**ADR drift:** Present: "This was originally decided on <date> because <reason>.
The codebase has shifted."

Use AskUserQuestion:
  - "Re-evaluate via /architect"
  - "Skip"

If accepted, invoke `/architect "<problem>"` as a review session.

**Stale KB:** Invoke `/research "<subject>" context: "curate: stale KB entry at <topic>/<category>, originally researched <date>. Since then, <what changed>."`.
Provide context about what changed since the original research.

**Implicit dependencies:** Investigate directly within the curation session.
Read the shared files, review the feature briefs that touched them, and assess
whether there's a real gap. Present findings and suggest next steps (which
might be "this is fine" or "worth adding tests for X interaction").

**Orphaned areas:** Offer `/research "<subject>" context: "curate: orphaned area needing coverage"` to build understanding, or `/architect`
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

**Unspecified shared types:** Read the spec files that reference this type to
understand the implicit contract. Then invoke `/spec-author extraction-mode`
with the type name — this extracts the type's behavioral contract from the
referencing specs and the source code, producing a standalone spec. Present
summary: "This type is referenced by <N> specs. Here's what each spec assumes
about it: <brief list>."

Use AskUserQuestion:
  - "Extract spec"
  - "Skip"

If accepted, invoke `/spec-author extraction-mode` with the type name.

**Specs with open obligations:** Read the spec file and display the specific
`[UNRESOLVED]` and `[CONFLICT]` markers with their surrounding context (2-3
lines each direction). Present: "This spec has <N> unresolved items. Here they
are: <list>."

Use AskUserQuestion:
  - "Resolve via /spec-resolve"
  - "Skip"

**Obligation registry:** Present the obligations grouped by spec: "Spec <ID>
has <N> open obligations affecting <M> requirements. Blocked by: <blockers>."

Use AskUserQuestion:
  - "Create work group from obligations" (description: "Run /work-decompose
    with --from-obligations to convert these into actionable work definitions")
  - "View obligation details" (description: "Read the full obligation
    descriptions from _obligations.json")
  - "Skip"

When "Create work group": guide the user to create a work group with `/work`
for the affected spec(s), then run `/work-decompose "<group>" --from-obligations`.
When "View details": read and display the full obligation entries from
_obligations.json for the selected spec.

**Spec-code drift:** Present the commit count and affected domains: "This spec
was written on <date> and <N> commits have touched its domain files since."

Use AskUserQuestion:
  - "Verify via /spec-verify"
  - "Skip"

When the user accepts, invoke `/spec-verify` with the spec file path.

**Undecided absent behaviors:** Read the spec file. Find all requirements
tagged with `[ABSENT]`. For each one, display the requirement ID, the full
requirement text, and any consuming specs that assume this behavior. Then
offer the three choices:

- **promote** — Rewrite as a positive requirement describing what the code
  SHOULD do. Remove `[ABSENT]`, add `[UNIMPLEMENTED]`. This creates an open
  obligation (implementation work needed).
- **preserve** — Rewrite as a negative requirement documenting the intentional
  absence ("X MUST NOT Y" instead of "X does not Y [ABSENT]"). Remove
  `[ABSENT]`. This locks in the design choice.
- **defer** — Leave `[ABSENT]` in place. It will resurface on the next
  `/curate` run.

Apply decisions directly to the spec file. After all `[ABSENT]` requirements
in the spec are decided, summarize what changed: how many promoted (new work),
how many preserved (documented decisions), how many deferred.

**Cross-reference repair (KB related links):** Present candidates one at a time,
highest overlap first. For each pair, show both entries' tags and the shared tags:

```
── Cross-reference candidate ───────────────────
  Entry A: .kb/<path-a>
    Tags: [tag1, tag2, tag3]
  Entry B: .kb/<path-b>
    Tags: [tag1, tag2, tag4]
  Shared: [tag1, tag2]

  add    — add related links in both entries
  skip   — not related, won't resurface
  defer  — resurface next /curate run
```

- **add**: Read both entries. Add entry B's relative path to entry A's `related`
  array and vice versa. Both entries get the bidirectional link. Use the standard
  frontmatter array format (`related: ["topic/category/subject.md"]`).
- **skip**: Record as dismissed in curation state (`xref-dismissed`). Won't
  resurface on future scans.
- **defer**: Leave for next `/curate` run.

For applies_to overlap candidates, present the same way but show the shared
file paths instead of tags. Same action options.

**Cross-reference repair (ADR KB Sources):** Present the ADR and its missing
references together:

```
── Missing KB references in ADR ────────────────
  ADR: .decisions/<slug>/adr.md
  Missing from KB Sources table:
    · .kb/<path> — referenced in evaluation.md scoring
    · .kb/<path> — referenced in evaluation.md scoring

  add-all — add all missing references to the ADR's KB Sources table
  select  — choose which to add
  skip    — these references aren't significant
```

- **add-all**: Read adr.md, add rows to the "KB Sources Used in This Decision"
  table for each missing entry. Role column: "Referenced in evaluation."
- **select**: Present each missing reference individually for add/skip.
- **skip**: Record and move on. Won't resurface (the evaluation hasn't changed).

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
