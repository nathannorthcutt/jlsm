---
description: "Start implementing a specified work definition — implementation pipeline only"
argument-hint: "<group-slug> [WD-nn | next]"
---

# /work-start "<group-slug>" [WD-nn | next]

Bridges a work definition from a work group into the implementation pipeline.
Creates a `.feature/` directory and hands off to planning, testing, and
implementation. For specification-only work (producing specs, ADRs, or
interface contracts), use `/work-plan` instead.

**Arguments:**
- `<group-slug>` — the work group to draw from
- `WD-nn` — start a specific work definition (e.g., WD-01)
- `next` — auto-select the highest-value READY work definition

If no WD argument is provided, defaults to `next`.

---

## Step 1 — Validate work group

Check `.work/<group-slug>/` exists. If not:
```
Work group '<group-slug>' not found.

Available groups:
```
List directories in `.work/` (excluding `_archive`, `_refs`).
Stop.

---

## Step 2 — Run readiness resolver

```bash
bash .claude/scripts/work-resolve.sh "<group-slug>"
```

Parse the output to determine the readiness state of all WDs.

Display opening header:
```
───────────────────────────────────────────────
🚀 WORK START · <group-slug>
───────────────────────────────────────────────
```

---

## Step 3 — Select work definition

### If specific WD (e.g., WD-01):

Check the WD's status in the resolver output.

If SPECIFIED: proceed to Step 4.

If READY or BLOCKED or SPECIFYING: the WD has not been through `/work-plan` yet.
```
WD-<nn> (<title>) needs specification before implementation.
Run /work-plan "<group-slug>" WD-<nn> first.
```
Use AskUserQuestion with options:
  - "Run /work-plan first" (description: "Specify this WD to produce its required artifacts")
  - "Start anyway (skip specification)" (description: "Proceed without specs — not recommended")
  - "Stop"

If "Run /work-plan first": invoke `/work-plan "<group-slug>" WD-<nn>`.
If "Start anyway": proceed to Step 4 with a warning logged.

If IMPLEMENTING: check if a feature directory already exists for this WD:
```
WD-<nn> is already being implemented.
Feature directory: .feature/<group>--<wd-slug>/

Resume with: /feature-resume "<group>--<wd-slug>"
```
Stop.

If COMPLETE:
```
WD-<nn> is already COMPLETE. Nothing to do.
```
Stop.

### If "next" (or no WD specified):

From the SPECIFIED WDs, select the one with the most downstream dependents
(i.e., the WD whose completion would unblock the most other WDs). This
maximizes unblocking value.

If multiple WDs tie on unblocking value, prefer the one with fewer
artifact dependencies (simpler work first).

If no WDs are SPECIFIED:
```
No work definitions are SPECIFIED in '<group-slug>'.

Status:
  <ready>  ready (needs /work-plan first)
  <specifying> specifying
  <implementing> implementing
  <complete> complete

Run /work-plan "<group-slug>" to specify a ready WD first.
```
Stop.

Display the selected WD:
```
Selected: WD-<nn> — <title>
  Domains: <domains>
  Deps: <dep count> (all satisfied)
  Unblocks: <list of WDs this will unblock, or "none">
```

Use AskUserQuestion with options:
  - "Start WD-<nn>"
  - "Pick a different WD"
  - "Stop"

---

## Step 4 — Create feature directory

Generate a feature slug from the work group and WD:
```
<group-slug>--<wd-id-lowercase>
```
Example: `auth-migration--wd-01`

Create `.feature/<slug>/` directory.

### 4a — Generate brief.md

Read the WD file (`.work/<group-slug>/WD-<nn>.md`). Build `brief.md` from:

```markdown
# Feature Brief: <WD title>

**Source:** Work group '<group-slug>', WD-<nn>
**Generated:** <YYYY-MM-DD>

## Description
<WD Summary section content>

## Acceptance Criteria
<WD Acceptance Criteria section content>

## Constraints
<WD Implementation Notes section content>

## Artifact Dependencies (from work group)
<List each artifact_dep with its current state>

## Produced Artifacts (expected outputs)
<List each produces entry>

## Work Group Context
<Run work-context.sh --group "<group-slug>" and include relevant excerpt>
```

### 4b — Verify implementation readiness

If the WD's `artifact_deps` include unresolved items (the WD was started
with "Start anyway" in Step 3), display a warning:
```
  Warning: This WD has unresolved artifact dependencies.
  Consider running /work-plan "<group-slug>" WD-<nn> first to produce
  the required specifications.
```
Proceed regardless — the user explicitly chose to start.

### 4c — Write status.md

Write `.feature/<slug>/status.md` with the standard format plus work group
metadata:

```yaml
work_group: <group-slug>
work_definition: WD-<nn>
pipeline_mode: implementation
```

Set stage = `planning`, substage = `loading-context`.

Stage Completion table — implementation stages only:

| Stage | Status |
|-------|--------|
| Planning | pending |
| Testing | pending |
| Hardening | pending |
| Implementation | pending |
| Refactor | pending |

### 4d — Update WD status

Edit `.work/<group-slug>/WD-<nn>.md` — set `status: IMPLEMENTING`.
Update `.work/<group-slug>/manifest.md` — update the WD's status in the table.

---

## Step 5 — Hand off to pipeline

```
Feature directory created: .feature/<slug>/
Pipeline mode: implementation (specifications already exist)

Proceeding directly to work planning — specs and ADRs will be loaded
from the resolved context.
```
Invoke `/feature-plan "<slug>"`.

---

## Notes

- The double-dash convention (`<group>--<wd>`) in the feature slug allows
  feature-resume, feature-retro, and feature-complete to auto-detect work
  group association without needing to read status.md.
- The brief.md includes work group context so domain analysis and planning
  have visibility into the broader initiative.
- Status.md starts at planning/loading-context because specifications
  should already exist from a prior `/work-plan` run or manual authoring.
