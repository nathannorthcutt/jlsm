---
description: "Start implementing a specified work definition — implementation pipeline only"
argument-hint: "<group-slug> [WD-nn | next | --parallel [N]]"
---

# /work-start "<group-slug>" [WD-nn | next | --parallel [N]]

Bridges a work definition from a work group into the implementation pipeline.
Creates a `.feature/` directory and hands off to planning, testing, and
implementation. For specification-only work (producing specs, ADRs, or
interface contracts), use `/work-plan` instead.

**Arguments:**
- `<group-slug>` — the work group to draw from
- `WD-nn` — start a specific work definition (e.g., WD-01)
- `next` — auto-select the highest-value READY work definition
- `--parallel [N]` — start every SPECIFIED WD concurrently (optionally
  cap at N concurrent sub-agents). See "Parallel mode" section below.

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

### If `--parallel` flag is present:

Skip the single-WD selection logic and go to the **Parallel mode**
section below. The rest of Step 3's single-WD paths (WD-nn / next) do
not apply.

### If specific WD (e.g., WD-01):

Check the WD's status in the resolver output.

If SPECIFIED: proceed to Step 4.

If BLOCKED: check the blocker detail from the resolver output.
- If blocked by `wd:` deps (predecessor WDs not complete): report which WDs
  must complete first. Do not offer to start — the ordering exists for a reason.
  ```
  WD-<nn> (<title>) is BLOCKED by predecessor work definitions:
    <list blockers from resolver>

  Complete these first, or use /work-start "<group-slug>" next to auto-select
  a WD that is ready.
  ```
  Stop.
- If blocked by unmet artifact deps (spec/adr/kb): this WD needs specification.
  ```
  WD-<nn> (<title>) needs specification before implementation.
  Run /work-plan "<group-slug>" WD-<nn> first.
  ```
  Use AskUserQuestion with options:
    - "Run /work-plan first"
    - "Start anyway (skip specification)"
    - "Stop"

If READY or SPECIFYING: the WD has not been through `/work-plan` yet.
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

The manifest table is automatically synced by `work-resolve.sh` — do not
update it manually.

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

## Parallel mode

When invoked with `--parallel [N]`, `/work-start` dispatches every
SPECIFIED work definition in the group as a concurrent sub-agent. Each
sub-agent runs the full feature pipeline
(`/feature-plan` → `/feature-test` → `/feature-implement` →
`/feature-refactor`) in an isolated context.

### When to use parallel mode

Parallel mode is a velocity multiplier when the group has multiple
WDs that are READY to implement and have no runtime cross-dependencies
(e.g., they land in separate modules or produce non-overlapping
artifacts). A wave of five SPECIFIED WDs in a group can complete in
roughly the time of one WD instead of five sequential runs.

### When NOT to use parallel mode

- **WDs share runtime state** (test DB, ports, cache). Parallel test
  execution will fight for resources. Sequential is safer.
- **WDs produce overlapping specs or KB entries.** Concurrent writes
  to the same spec file or KB entry corrupt the registry.
- **The user wants to review each step.** Parallel mode is
  fire-and-wait; individual WD progress is visible in each sub-agent's
  `.feature/<slug>/status.md` but there is no global pause point
  between stages.

### Parallel-mode flow

Replace Steps 3–5 with this block when `--parallel` is set.

1. **Enumerate startable WDs.** Parse the `work-resolve.sh` output
   table and collect every WD whose Status is `SPECIFIED`. If zero,
   report and stop:
   ```
   No SPECIFIED work definitions in '<group-slug>' — run /work-plan
   first to specify READY WDs, or check work-status for blockers.
   ```

2. **Cap concurrency.** If the user supplied `--parallel N`, take the
   first N SPECIFIED WDs (prefer those with more downstream dependents
   — same "unblocking value" heuristic as `next` mode). If `N` is
   omitted, dispatch all of them.

3. **Show the plan.** Display:
   ```
   ── Parallel start plan ─────────────────────────
   Group: <group-slug>
   SPECIFIED WDs: <N>
   Will dispatch concurrently: <dispatched count>

     WD-<nn> — <title>  (domains: <domains>, unblocks: <list>)
     WD-<nn> — ...
   ───────────────────────────────────────────────
   ```
   Use AskUserQuestion to confirm — parallel mode spawns multiple
   long-running pipelines at once and the user should be explicit:
   - "Dispatch all <N> in parallel"
   - "Cap at 2" (or a lower number)
   - "Stop — I'll start them manually"

4. **Create feature directories (all WDs, sequential).** For each
   dispatched WD, run Step 4 from the single-WD path in full: generate
   `brief.md`, verify readiness, write `status.md`, update the WD's
   status to `IMPLEMENTING`. Do this sequentially — these operations
   touch the `.work/` tree and are fast. Fanning out here adds no
   measurable benefit and risks racing on `manifest.md` regeneration.

5. **Dispatch sub-agents concurrently.** Spawn one sub-agent per WD in
   a **single message with multiple Agent tool calls**. Each sub-agent
   receives this prompt:
   ```
   You are the parallel pipeline runner for <feature-slug>.
   The feature directory exists at .feature/<feature-slug>/.
   Status.md is initialized at planning/loading-context and the WD is
   marked IMPLEMENTING.

   Invoke /feature-plan "<feature-slug>" and run the full pipeline
   through to /feature-refactor. Do not pause for user confirmation;
   treat automation_mode as autonomous. If any stage escalates (spec
   conflict, missing tests, test writer escalation), record it in
   cycle-log.md and continue with the remaining stages where possible;
   report the escalation in your final one-line summary.

   Return a single summary line of the form:
     "<feature-slug>: <COMPLETE | STOPPED_AT_<stage> | ERROR> — <detail>"
   ```

6. **Aggregate results.** When all sub-agents return, summarize:
   ```
   ── Parallel run complete · <group-slug> ───────
   Dispatched: <N>
   Complete: <n>/<N>
   Stopped mid-pipeline: <list with stage>
   Errored: <list with detail>
   ───────────────────────────────────────────────
   ```
   For stopped or errored WDs, the user's next action is usually
   `/feature-resume "<feature-slug>"` to pick up where each sub-agent
   left off.

### Concurrency caveats (documented, user-accepted)

- **Shared KB / ADR writes.** If two WDs' `/feature-retro` phases both
  want to write a new `adversarial-finding` KB entry about the same
  pattern, both writes succeed but one silently clobbers. Mitigation:
  run `/curate` after a parallel batch; it detects duplicate KB
  entries in the cross-reference analysis.
- **Shared spec writes.** If two WDs produce specs in overlapping
  domains, both `/spec-write` calls may serialize through the same
  manifest file. The manifest update uses atomic `jq` + tmp + mv, so
  the *last writer wins* on the manifest — but individual spec files
  are per-WD and don't conflict.
- **Test runner contention.** Parallel test suites can race on shared
  resources (test DB, network ports, temp files). This is a
  project-level concern. If the project's tests are isolated per WD,
  parallel is safe; if not, either cap at 1 or don't use parallel.
- **Token / cost budget.** N parallel pipelines burn roughly N× the
  tokens and dollars of a single run. Budget accordingly.

---

## Notes

- The double-dash convention (`<group>--<wd>`) in the feature slug allows
  feature-resume, feature-retro, and feature-complete to auto-detect work
  group association without needing to read status.md.
- The brief.md includes work group context so domain analysis and planning
  have visibility into the broader initiative.
- Status.md starts at planning/loading-context because specifications
  should already exist from a prior `/work-plan` run or manual authoring.
- Parallel mode (`--parallel`) is an advanced flow — see the "Parallel
  mode" section above for when it applies and the concurrency caveats.
