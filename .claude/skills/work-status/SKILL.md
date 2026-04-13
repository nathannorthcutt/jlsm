---
description: "Show work group readiness — what is ready, blocked, or complete"
argument-hint: "<group-slug> [--all]"
---

# /work-status "<group-slug>" [--all]

Shows the readiness state of a work group. Reports which work definitions are
READY (all artifact dependencies satisfied), BLOCKED (with specific missing
artifacts), IN_PROGRESS, or COMPLETE.

This is the "what can I work on next?" entry point.

**Flags:**
- (no flag) — show readiness for a specific work group
- `--all` — show readiness summary across all active work groups

---

## Step 0 — List mode (--all)

If `--all` flag is set:

1. Read `.work/CLAUDE.md` — get all active work groups
2. For each group directory in `.work/`:
   - Run `bash .claude/scripts/work-resolve.sh <group-slug>` (capture stdout)
   - Extract the summary line (Total/Ready/Blocked/In Progress/Complete)
3. Display:

```
───────────────────────────────────────────────
📊 WORK STATUS · all groups
───────────────────────────────────────────────

| Group | WDs | Ready | Blocked | In Progress | Complete |
|-------|-----|-------|---------|-------------|----------|
| <slug> | <n> | <n> | <n> | <n> | <n> |
...

Ready to work on:
  /work-plan "<slug>" next    — specify the first ready WD (produce specs/ADRs)
  /work-start "<slug>" next   — implement a fully specified WD
```

If no work groups exist:
```
No active work groups. Create one with:
  /work "<goal>"
```

Stop. Do not continue to other steps.

---

## Step 1 — Validate group

Check `.work/<group-slug>/` exists. If not:
```
Work group '<group-slug>' not found.

Available groups:
  <list from .work/CLAUDE.md>

Or create a new one:
  /work "<goal>"
```
Stop.

Read `.work/<group-slug>/work.md` for the goal description.

---

## Step 2 — Run readiness resolver

Run:
```bash
bash .claude/scripts/work-resolve.sh "<group-slug>"
```

Capture stdout (the readiness report) and stderr (diagnostics).

---

## Step 3 — Present results

Display opening header:
```
───────────────────────────────────────────────
📊 WORK STATUS · <group-slug>
───────────────────────────────────────────────
Goal: <goal from work.md>
```

Then present the resolver output, reformatted for readability:

### Summary line
```
<total> work definitions: <ready> ready · <blocked> blocked · <in_progress> in progress · <complete> complete
```

### Status table

From the resolver's `## Status` section. Present as-is — it's already a
markdown table sorted by status priority (READY first).

### Blockers (if any)

For each BLOCKED WD, list the specific missing artifacts:
```
Blocked:
  WD-03 — Auth middleware rewrite
    - spec:auth/jwt-token-contract — spec not found (need APPROVED)
    - adr:token-format — ADR not found

  WD-05 — Session migration
    - spec:auth/middleware-interface — spec is DRAFT (need APPROVED)
```

### Scope signals (if any)

From the resolver's `## Scope Signal` section, if present.

---

## Step 4 — Actionable next steps

Based on the readiness state, present specific next actions:

### If READY WDs exist:
```
Ready to work on:
  /work-plan "<group-slug>" WD-<nn>    — specify a WD (produce specs/ADRs)
  /work-plan "<group-slug>" next       — specify the highest-value ready WD
  /work-start "<group-slug>" WD-<nn>   — implement a specific WD
  /work-start "<group-slug>" next      — implement the highest-value ready WD
```

### If all remaining WDs are BLOCKED:
```
All remaining WDs are blocked. Unblock by:
```
Then for each unique blocker, suggest the command to resolve it:
- Missing spec → `/spec-author "<id>" "<title>"`
- Missing ADR → `/architect "<problem>"`
- Missing KB entry → `/research "<subject>"`
- Spec in wrong state → `/spec-verify "<id>"`

### If all WDs are COMPLETE:
```
All work definitions complete! Work group '<group-slug>' is finished.

Consider running a retrospective on the overall effort:
  /feature-retro for individual features created from this group
```

### If WDs are IN_PROGRESS:
```
In progress:
  WD-<nn> (<title>) — resume with /feature-resume "<group>--<wd-slug>"
```

Stop.
