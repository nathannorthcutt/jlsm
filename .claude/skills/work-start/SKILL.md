---
description: "Start implementing a ready work definition from a work group"
argument-hint: "<group-slug> [WD-nn | next]"
---

# /work-start "<group-slug>" [WD-nn | next]

Bridges a work definition from a work group into the feature pipeline.
Creates a `.feature/` directory from the WD's specification, then hands off
to the standard pipeline stages.

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

Check if the specified WD is READY in the resolver output.

If READY: proceed to Step 4.

If BLOCKED: display the blockers from the resolver output:
```
WD-<nn> (<title>) is BLOCKED:
  - <blocker 1>
  - <blocker 2>

Unblock by resolving these dependencies first.
```
Use AskUserQuestion with options:
  - "Pick a different WD"
  - "Start anyway (skip readiness check)"
  - "Stop"

If "Pick a different WD": show READY WDs and let user choose.
If "Start anyway": proceed to Step 4 with a warning logged.

If IN_PROGRESS: check if a feature directory already exists for this WD:
```
WD-<nn> is already IN_PROGRESS.
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

From the READY WDs, select the one with the most downstream dependents
(i.e., the WD whose completion would unblock the most other WDs). This
maximizes unblocking value.

If multiple WDs tie on unblocking value, prefer the one with fewer
artifact dependencies (simpler work first).

If no WDs are READY:
```
No work definitions are READY in '<group-slug>'.

Status:
  <blocked>  blocked
  <in_progress> in progress
  <complete> complete

Unblock by resolving the artifact dependencies shown in:
  /work-status "<group-slug>"
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

### 4b — Detect pipeline mode

Determine the pipeline mode based on the WD's `produces` list:

- **specification** — if the WD's `produces` list contains only specification
  artifacts (specs, ADRs, interface contracts) and no implementation is expected.
  Heuristic: the WD's Acceptance Criteria describe artifact outputs, not
  behavioral outcomes.
- **implementation** — if the WD's `artifact_deps` are all satisfied (it's READY)
  and its Summary/Acceptance Criteria describe behavioral outcomes (tests passing,
  code changes, system behavior).
- **full** — default when the mode is ambiguous.

Present the detected mode to the user:
```
Pipeline mode: <mode>
  <one sentence explanation>
```

Use AskUserQuestion with options:
  - "Proceed with <mode> mode"
  - "Use full mode instead"
  - "Use specification mode"
  - "Use implementation mode"

### 4c — Write status.md

Write `.feature/<slug>/status.md` with the standard format plus work group
metadata:

```yaml
work_group: <group-slug>
work_definition: WD-<nn>
```

Set `Pipeline mode` based on Step 4b:

- **specification mode:** stage = `scoping`, substage = `complete`
- **implementation mode:** stage = `planning`, substage = `loading-context`
- **full mode:** stage = `scoping`, substage = `complete`

Adjust the Stage Completion table to match the mode:
- **specification:** only Scoping, Domains, Spec Authoring rows
- **implementation:** only Planning, Testing, Hardening, Implementation, Refactor rows
- **full:** all rows (standard template)

### 4d — Update WD status

Edit `.work/<group-slug>/WD-<nn>.md` — set `status: IN_PROGRESS`.
Update `.work/<group-slug>/manifest.md` — update the WD's status in the table.

---

## Step 5 — Hand off to pipeline

The handoff depends on the pipeline mode:

**specification mode:**
```
Feature directory created: .feature/<slug>/
Pipeline mode: specification (produce artifacts only)

Scoping is pre-populated from the work definition — proceeding to domain
analysis and spec authoring.
```
Invoke `/feature-domains "<slug>"`.

**implementation mode:**
```
Feature directory created: .feature/<slug>/
Pipeline mode: implementation (specifications already exist)

Proceeding directly to work planning — specs and ADRs will be loaded
from the resolved context.
```
Invoke `/feature-plan "<slug>"`.

**full mode:**
```
Feature directory created: .feature/<slug>/

Scoping is pre-populated from the work definition — proceeding to domain
analysis.
```
Invoke `/feature-domains "<slug>"`.

Invoke `/feature-domains "<slug>"` as a sub-agent. The standard pipeline
takes over from here.

---

## Notes

- The double-dash convention (`<group>--<wd>`) in the feature slug allows
  feature-resume, feature-retro, and feature-complete to auto-detect work
  group association without needing to read status.md.
- The brief.md includes work group context so domain analysis and planning
  have visibility into the broader initiative.
- Status.md starts at scoping/complete because the WD's Summary and
  Acceptance Criteria serve as the pre-approved brief.
