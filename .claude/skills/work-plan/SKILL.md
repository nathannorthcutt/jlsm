---
description: "Specify a work definition — produce specs and ADRs without implementation"
argument-hint: "<group-slug> [WD-nn | next]"
---

# /work-plan "<group-slug>" [WD-nn | next]

Specification-only pipeline for a work definition. Creates a feature directory,
runs domain analysis and spec authoring, then stops. No implementation stages.

Use this when:
- A WD produces specification artifacts (ADRs, specs, interface contracts)
- You want to plan and specify before implementing
- You're running parallel specification across multiple terminal sessions

For implementation after specification is complete, use `/work-start`.

**Arguments:**
- `<group-slug>` — the work group to draw from
- `WD-nn` — specify a particular work definition (e.g., WD-01)
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
📝 WORK PLAN · <group-slug>
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

If SPECIFYING: check if a feature directory already exists for this WD:
```
WD-<nn> is already being specified.
Feature directory: .feature/<group>--<wd-slug>/

Resume with: /feature-resume "<group>--<wd-slug>"
```
Stop.

If SPECIFIED:
```
WD-<nn> is already specified. Ready for implementation:
  /work-start "<group-slug>" WD-<nn>
```
Stop.

If IMPLEMENTING or COMPLETE:
```
WD-<nn> is already past the specification phase. Nothing to do.
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
  - "Plan WD-<nn>"
  - "Pick a different WD"
  - "Stop"

---

## Step 4 — Create feature directory

Generate a feature slug from the work group and WD:
```
<group-slug>--<wd-id-lowercase>
```
Example: `decisions-backlog--wd-01`

Create `.feature/<slug>/` directory.

### 4a — Generate brief.md

Read the WD file (`.work/<group-slug>/WD-<nn>.md`). Build `brief.md` from:

```markdown
# Feature Brief: <WD title>

**Source:** Work group '<group-slug>', WD-<nn>
**Generated:** <YYYY-MM-DD>
**Pipeline mode:** specification

## Description
<WD Summary section content>

## Acceptance Criteria
<WD Acceptance Criteria section content>

## Constraints
<WD Implementation Notes section content>

## Group Envelope (AUTHORITATIVE — from work group Phase B)

Group-level artifacts settled during `/work-decompose` Phase B. **These are
authoritative for WD-local analysis** — domain analysis must defer to them
rather than re-deciding. If WD-local work would contradict an envelope item,
that's an escalation back to `/work-decompose`, not a local override.

For each artifact in the WD's `artifact_deps:`, write one entry:
- **spec `<domain>/<name>`** — state: APPROVED — <title from spec frontmatter>
  <one-sentence summary of what the spec settles>
- **adr `<slug>`** — status: accepted — <title from adr.md H1>
  <one-sentence summary of the decision>
- **kb `<path>`** — <title from entry frontmatter>

Also list explicit scope declarations from `work.md`:
- **out_of_scope:** <items from work.md's out_of_scope frontmatter, if any>
- **external_deps:** <entries from work.md's external_deps frontmatter, if any>

If the WD has no artifact_deps and no group-level scope declarations, write:
"No group envelope — this WD operates standalone within the work group."

## Produced Artifacts (expected outputs)
<List each produces entry>

## Work Group Context
<Run work-context.sh --group "<group-slug>" and include relevant excerpt>
```

### 4b — Write status.md

Write `.feature/<slug>/status.md` with the standard format plus work group
metadata:

```yaml
work_group: <group-slug>
work_definition: WD-<nn>
pipeline_mode: specification
```

Set stage = `scoping`, substage = `complete`.

Stage Completion table — specification mode only:

| Stage | Status |
|-------|--------|
| Scoping | complete |
| Domains | pending |
| Spec Authoring | pending |

### 4c — Update WD status

Edit `.work/<group-slug>/WD-<nn>.md` — set `status: SPECIFYING`.

The manifest table is automatically synced by `work-resolve.sh` — do not
update it manually.

---

## Step 5 — Domain analysis

```
Feature directory created: .feature/<slug>/
Pipeline mode: specification (produce artifacts only)

Scoping is pre-populated from the work definition — proceeding to domain
analysis.
```
Invoke `/feature-domains "<slug>"`.

In specification mode, `/feature-domains` returns after domain analysis
without chaining into spec authoring. It produces `domains.md` which
identifies the specs to write.

---

## Step 5b — Sequential spec authoring

**Spec authoring is mandatory.** Even for decisions-focused WDs, the
architectural choices made in ADRs have behavioral implications that must
be captured as specs. ADRs describe WHY a decision was made; specs define
WHAT the system must do as a result. Without specs, the adversarial
hardening and audit pipeline have nothing to falsify. Do not skip or
bypass spec authoring for any WD type.

Read `domains.md` to identify the specs that need to be authored.

**Dedupe against the group envelope first.** Read the WD file's
`artifact_deps:` from `.work/<group-slug>/WD-<nn>.md`. For any spec in
`domains.md` whose identity matches an `artifact_deps` spec entry in
APPROVED state, do NOT author it — the group already owns that spec and
WD-local re-authoring would create divergent copies. Log the skip:

```
  ⊘ <spec-id> — deferred to group-level spec <ref> (APPROVED)
```

Authoring proceeds only for specs not already covered by the envelope.
For each remaining spec to produce, **in sequence**:

1. Invoke `/spec-author "<feature-id>" "<title>"` as a separate subagent.
   Each invocation gets a clean context but reads previously registered
   specs via the resolver — so spec 2 sees spec 1's requirements, spec 3
   sees both. This is the compounding loop: each spec's falsification
   catches contradictions with prior specs.

2. After `/spec-author` completes, verify the spec is registered and in
   APPROVED state via `.spec/registry/manifest.json`. If still DRAFT,
   falsification was incomplete — stop and report the error.

3. Proceed to the next spec.

Display progress between specs:
```
Spec authoring: <completed>/<total>
  ✓ F24 — Pool-Aware Block Size Configuration (APPROVED)
  → F25 — Byte-Budget Block Cache (authoring...)
```

If every spec in `domains.md` is covered by the envelope, Step 5b emits a
no-op log line and Step 6 proceeds to finalize the WD — this is legitimate
when Phase B settled the full spec surface for this WD.

---

## Step 6 — Verify specs and finalize WD status

After all specs are authored, verify that every spec produced by this WD
is in APPROVED state. Check `.spec/registry/manifest.json`.

**If any spec is still DRAFT:** do NOT mark the WD as SPECIFIED.
```
⚠ Spec <ID> is still in DRAFT state — falsification incomplete.
Run /spec-author "<feature-id>" "<title>" to complete adversarial review.
```
Stop and wait for the user to resolve.

**If all specs are APPROVED:** update the WD:

1. Edit `.work/<group-slug>/WD-<nn>.md` — set `status: SPECIFIED`

The manifest table is automatically synced by `work-resolve.sh` — do not
update it manually.

Do NOT proceed to `/feature-retro` or `/feature-complete` — those run
after implementation.

Display:
```
───────────────────────────────────────────────
📝 WORK PLAN complete · <group-slug> / WD-<nn>
───────────────────────────────────────────────
Specifications produced:
  <list of spec files written or updated>

The WD is ready for implementation:
  /work-start "<group-slug>" WD-<nn>
───────────────────────────────────────────────
```

---

## Notes

- The double-dash convention (`<group>--<wd>`) in the feature slug allows
  feature-resume, feature-retro, and feature-complete to auto-detect work
  group association without needing to read status.md.
- The brief.md includes work group context so domain analysis and spec
  authoring have visibility into the broader initiative.
- Status.md starts at scoping/complete because the WD's Summary and
  Acceptance Criteria serve as the pre-approved brief.
- After specification is complete, use `/work-start` for implementation.
- Do NOT run `/feature-retro` or `/feature-complete` after `/work-plan`.
  Retro and completion happen after implementation, not after spec authoring.
