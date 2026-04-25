---
description: "Show work group readiness — what is ready, blocked, or complete"
argument-hint: "<group-slug> [--all]"
---

# /work-status "<group-slug>" [--all]

Shows the readiness state of a work group. Reports which work definitions are
READY (all artifact dependencies satisfied), BLOCKED (with specific missing
artifacts), SPECIFYING, SPECIFIED, IMPLEMENTING, or COMPLETE.

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

| Group | WDs | Ready | Blocked | Specifying | Specified | Implementing | Complete |
|-------|-----|-------|---------|------------|-----------|--------------|----------|
| <slug> | <n> | <n> | <n> | <n> | <n> | <n> | <n> |
...
```

**Next-step commands must route by state — not suggest both commands for
the same WD.** `/work-start` rejects READY/BLOCKED WDs with "needs
planning first"; `/work-plan` rejects SPECIFIED WDs with "already
specified." Mixing them in suggestions creates a round-trip where the
user runs the wrong one, gets bounced, and guesses again.

Build the suggestion block by scanning the status of WDs across all groups:

- **If any group has READY WDs** → show `/work-plan "<slug>" next` for
  those groups. These WDs need planning before implementation can start,
  even when "Ready" sounds like "ready to work."
- **If any group has SPECIFIED WDs** → show `/work-start "<slug>" next`
  for those groups. These are planned and ready to implement.
- **If any group has SPECIFYING or IMPLEMENTING WDs** → show
  `/feature-resume "<group>--<wd-slug>"` for those specific in-progress
  units.
- **If all remaining WDs in a group are BLOCKED** → do not suggest either
  `/work-plan` or `/work-start` for that group; instead, route to the
  specific unblock command (`/spec-author`, `/architect`, `/research`, or
  upstream WD).

Present suggestions grouped by action type, not jumbled together:

```
Ready to specify:
  /work-plan "<group-a>" next    — <N> READY WD(s) need planning
  /work-plan "<group-b>" next    — <N> READY WD(s) need planning

Ready to implement:
  /work-start "<group-c>" next   — <N> SPECIFIED WD(s) planned and ready

Resume in progress:
  /feature-resume "<group-d>--wd-02"   — SPECIFYING
  /feature-resume "<group-e>--wd-01"   — IMPLEMENTING

Blocked (unblock to proceed):
  /spec-author "<id>" "<title>"   — for group-f (missing spec)
  /architect "<problem>"          — for group-g (missing ADR)
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

Based on the readiness state, present specific next actions. **Route
suggestions strictly by state.** `/work-start` rejects READY/BLOCKED WDs
with "needs planning first"; `/work-plan` rejects SPECIFIED WDs with
"already specified." Never list both commands as options for the same
WD — it creates a wrong-command round-trip.

### If READY WDs exist (planning not yet run):
```
Ready to plan:
  /work-plan "<group-slug>" WD-<nn>    — plan a specific WD
  /work-plan "<group-slug>" next       — plan the highest-unblocking WD
```

Do NOT list `/work-start` here — READY means DRAFT with deps met, and
`/work-start` requires SPECIFIED. `/work-plan` is mandatory before
`/work-start`, even when planning has nothing to add (no-op planning
still transitions DRAFT → SPECIFIED).

### If SPECIFIED WDs exist (planning done, ready to implement):
```
Ready to implement:
  /work-start "<group-slug>" WD-<nn>   — implement a specific WD
  /work-start "<group-slug>" next      — implement the highest-unblocking WD
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

### If WDs are SPECIFYING or IMPLEMENTING:
```
Active:
  WD-<nn> (<title>) — SPECIFYING — resume with /feature-resume "<group>--<wd-slug>"
  WD-<nn> (<title>) — IMPLEMENTING — resume with /feature-resume "<group>--<wd-slug>"
```

Stop.
