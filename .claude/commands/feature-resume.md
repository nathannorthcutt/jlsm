# /feature-resume "<feature-slug>" [--status] [--share] [--list]

Tells you exactly where a feature is and what to run next.
Use this after any interruption, crash, or context switch.

**Flags:**
- (no flag) — navigation mode: current position, stage progress, next command
- `--status` — session briefing mode: what was done, current blockers, next agenda
- `--share` — condensed standup/team format (implies --status)
- `--list` — list all active features with their current stage (ignores slug argument)

Works for both `/feature` and `/feature-quick` slugs.

---

## Step 0 — List mode (--list)

If `--list` flag is set:

1. List all directories under `.feature/` (excluding `_archive/` and `project-config.md`)
2. For each directory, read `status.md` and extract: stage, substage, last updated timestamp
3. Display:

```
───────────────────────────────────────────────
📋 ACTIVE FEATURES
───────────────────────────────────────────────
  <slug>            <stage> · <substage>            <last updated>
  <slug>            <stage> · <substage>            <last updated>
  <slug>            <stage> · <substage>            <last updated>
───────────────────────────────────────────────
```

Sort by last updated (most recent first).
If no features exist, display: `No active features. Start one with /feature "<description>" or /feature-quick "<description>"`

Stop. Do not continue to other steps.

---

## Step 1 — Read status

Check `.feature/<slug>/status.md`. If it does not exist:
```
No status file found for '<slug>'.

Options:
  - If this is a new feature: /feature "<slug description>"
  - If status.md was accidentally deleted: run each stage command — they will
    check for their prerequisite files and tell you what is missing.
```
Stop.

---

## Step 1b — Mode dispatch

If `--share` flag: run --status mode, then condense output to share format (see Step 5b).
If `--status` flag: skip to Step 5 — Session briefing.
Otherwise: continue to Step 2 — Navigation mode.

---

## Step 2 — Display the full status report

```
───────────────────────────────────────────────
🔄 FEATURE RESUME · <slug>
───────────────────────────────────────────────

CURRENT POSITION
Stage:      <stage>
Substage:   <substage>
Last checkpoint: <last successful checkpoint>
Last updated:    <timestamp>
Automation: <autonomous | manual | not-set (will ask on next /feature-implement)>
<If execution_strategy is balanced or speed:>
EXECUTION: parallel (<balanced|speed>)

STAGE PROGRESS
  ✓ Scoping        complete    <date>
  ✓ Domains        complete    <date>
  ✓ Planning       complete    <date>
  ↻ Testing        in-progress  cycle 1 — tests written, verifying failures
  · Implementation not-started
  · Refactor       not-started

TOKEN USAGE (from Stage Completion table in status.md)
  Read the Stage Completion table and display estimated vs actual:

  | Stage          | Est.   | Actual         | Δ       |
  |----------------|--------|----------------|---------|
  | Scoping        | ~5K    | 4.2K in / 3K out | -16%  |
  | Domains        | ~6K    | 8.1K in / 2K out | +35%  |
  | Planning       | ~8K    | 7.5K in / 5K out | -6%   |
  | Testing        | ~5K    | —              | —       |
  | Implementation | —      | —              | —       |
  | Refactor       | —      | —              | —       |
  | **Total**      | **~24K** | **19.8K in / 10K out** | |

  The Δ column compares estimated tokens to actual input tokens.
  Calculate as: ((actual_input - estimate) / estimate × 100), rounded.
  Only show for completed stages with both values present.

  If token-log.md also exists, additionally run:
  `bash -c 'source .claude/scripts/token-usage.sh && token_report ".feature/<slug>"'`
  and display below the comparison table.

DOMAIN STATUS (if domains stage was reached)
  ✓ <domain>   resolved    ADR: .decisions/<adr>/adr.md
  ✓ <domain>   resolved    KB: .kb/<topic>/<cat>/<subject>.md
  ✗ <domain>   pending     commissioned <date> — not yet complete

TDD CYCLES (if testing was reached)
  Cycle 1: tests <date> | passing <date or "—"> | refactor <date or "—"> | missing: <n>
  Cycle 2: ...

<If .decisions/.decision-candidates has status: new entries:>
  ℹ <n> decision candidates pending review (/decisions candidates)
───────────────────────────────────────────────
```

---

## Step 2b — Work unit next-command resolution

If `work_units: none` in status.md: skip this section.

If work units are defined, determine the precise next command:

1. Find the first unit that is `in-progress` → that unit needs its current
   stage continued (check substage in status.md)
2. If no in-progress unit, find the first `not-started` unit → that unit
   needs `/feature-test "<slug>" --unit WU-<n>`
3. If all units are `complete` → feature is ready for `/feature-pr`
4. If a unit is `blocked` → its dependencies need to complete first

**If `execution_strategy` is `balanced` or `speed`:** read per-unit
`units/WU-N/status.md` for each unit's current stage/substage and display
with batch grouping:

```
Work units:
  Batch 1 (complete):
    ✓ WU-1: <name>
  Batch 2 (in-progress):
    ↻ WU-2: <name> — implementing (cycle 1)
    ↻ WU-3: <name> — testing (cycle 1)
  Batch 3 (waiting):
    ○ WU-4: <name> — blocked (waiting on WU-2, WU-3)
```

**Otherwise (sequential/cost mode):** display the work unit table with dependency
info as part of the resume output:
```
Work units:
  ✓ WU-1: <name> — complete
  → WU-2: <name> — in-progress (implementing, cycle 1)   ← active
    └─ depends on: WU-1
  ○ WU-3: <name> — blocked (waiting on WU-2)
    └─ depends on: WU-1, WU-2

  Progress: <n complete> / <n total>
```

The "Next command" line must include the `--unit` flag (sequential mode) or
use the coordinator (parallel mode):
```
<sequential:> Next: /feature-implement "<slug>" --unit WU-2
<parallel:>   Next: /feature-coordinate "<slug>"
```

## Step 3 — Determine and display what to run next

Based on the current stage and substage:

| Stage + Substage | What to run next |
|-----------------|-----------------|
| scoping / interviewing | `/feature "<slug description>"` — scoping in progress |
| scoping / confirming-brief | `/feature "<slug description>"` — confirm the brief |
| scoping / complete | `/feature-domains "<slug>"` |
| domains / in-progress (pending commissions) | See pending domains below |
| domains / in-progress (all commissioned) | `/feature-domains "<slug>"` — verify commissions |
| domains / complete | `/feature-plan "<slug>"` |
| planning / in-progress | `/feature-plan "<slug>"` — will resume writing stubs |
| planning / complete | `/feature-test "<slug>"` |
| testing / in-progress | `/feature-test "<slug>"` — will resume writing tests |
| testing / complete (cycle N) | `/feature-implement "<slug>"` |
| implementation / in-progress | `/feature-implement "<slug>"` — will resume from failing tests |
| implementation / escalated | `/feature-test "<slug>"` — contract conflict needs Test Writer |
| implementation / complete (cycle N) | `/feature-refactor "<slug>"` |
| refactor / in-progress | `/feature-refactor "<slug>"` — will resume from checklist item |
| refactor / escalated-missing-tests | `/feature-test "<slug>" --add-missing` |
| refactor / complete | `/feature-complete "<slug>"` — when PR has merged |

If `automation_mode: autonomous` and the feature is mid-implementation or
mid-refactor: note this in the Next Step display:
```
NOTE: This feature is in autonomous mode. Stages will chain automatically.
Type stop at any time during a run to pause.
```

Display the specific next command clearly:

```
NEXT STEP
  <command to run>
  <one sentence explaining why>
```

**Auto-invoke rules** (apply after displaying the NEXT STEP block):

- If the next step resolves to `/feature-domains` (stage is `scoping/complete`):
  invoke `/feature-domains "<slug>"` as a sub-agent immediately. Domain analysis
  requires no external action to proceed.

- If the next step resolves to `/feature-plan` (stage is `domains/complete` or
  `planning/in-progress`): invoke `/feature-plan "<slug>"` as a sub-agent
  immediately. Planning requires no external action to proceed.

- If `automation_mode: autonomous` AND `execution_strategy` is `balanced` or
  `speed` AND work units remain incomplete: invoke `/feature-coordinate "<slug>"`
  as a sub-agent immediately.

- If `automation_mode: autonomous` AND the next step resolves to
  `/feature-implement` or `/feature-refactor` (stages `testing/complete`,
  `implementation/in-progress`, or `implementation/complete`): invoke the
  appropriate command as a sub-agent immediately. A crash should not break
  the autonomous loop — resuming after a crash is equivalent to re-entering
  the same stage that was interrupted.

For pending domain commissions, list each:
```
PENDING DOMAINS — complete these before re-running /feature-domains:
  1. <domain> — Run: /research <topic> <category> "<subject>"
  2. <domain> — Run: /architect "<problem statement>"
```

---

## Step 4 — Show recent cycle-log entries (last 3)

If cycle-log.md exists, display the last three entries to provide context
on what actually happened most recently. This is especially useful after a crash
where the substage in status.md might be stale.

```
RECENT ACTIVITY (from cycle-log.md)
<last 3 entries>
```

---

## Step 5 — Session briefing (--status mode)

Load additional context beyond status.md:
1. `.feature/<slug>/brief.md` or status.md Description (quick tasks)
2. `.feature/<slug>/cycle-log.md` — full history
3. `.feature/<slug>/work-plan.md` if it exists — construct completion
4. `.feature/<slug>/test-plan.md` if it exists — test coverage

Display:
```
───────────────────────────────────────────────
📊 FEATURE STATUS · <slug>
<YYYY-MM-DD> | <full pipeline / quick task>
───────────────────────────────────────────────

WHAT THIS IS
<2–3 sentences from brief summary.>

── Progress ───────────────────────────────────
  ✓ Scoping        <date>
  ✓ Domains        <date> — <n> domains resolved, <n> ADRs consulted
  ✓ Planning       <date> — <n> constructs
  ↻ Testing        in progress — cycle <n>
  · Implementation not started
  · Refactor       not started

<If work units:>
── Work Units ─────────────────────────────────
  Read the Work Units table from status.md. For each unit, read the
  Depends On column to build the dependency graph.

  Display units grouped by dependency layer (topological order):

  Layer 0 (no dependencies):
    ✓ WU-1: <name> — complete
    ✓ WU-4: <name> — complete

  Layer 1 (depends on layer 0):
    → WU-2: <name> — implementing (cycle 1, 4/7 tests passing)
      └─ depends on: WU-1

  Layer 2 (depends on layer 1):
    ○ WU-3: <name> — blocked (waiting on WU-2)
      └─ depends on: WU-1, WU-2

  If execution_strategy is balanced or speed, also show batch info:
    Batch 1 (complete): WU-1, WU-4
    Batch 2 (in-progress): WU-2
    Batch 3 (waiting): WU-3
    Critical path: <n> sequential batches

  Progress: <n complete> / <n total> units
  Est. remaining sessions: <n>

── What Was Done Last Session ─────────────────
<Synthesise the most recent 3–5 cycle-log entries into plain English.
Not a raw dump — a readable summary of what actually happened.
Example: "The Work Planner identified 4 constructs and wrote stubs.
The Test Writer wrote 12 tests covering the happy path and 3 error
conditions — all verified failing.">

── Current Blocker ────────────────────────────
<If active escalation in cycle-log.md: describe in one paragraph.
If no blocker: "None — ready to continue.">

── Next Session Agenda ────────────────────────
<3–5 concrete bullet points. Not "continue implementation" but
"implement TokenBucket.consume() and RateLimitMiddleware.handle() —
these are the two remaining failing tests.">
  1. <specific task>
  2. <specific task>
  3. ...

TO CONTINUE: <exact command to run>
───────────────────────────────────────────────
```

<If work-plan.md exists, append construct completion table:>
```
CONSTRUCTS
  ✓ <ConstructName>   <src/path>   tests passing
  ↻ <ConstructName>   <src/path>   <n> tests failing
  · <ConstructName>   <src/path>   not started
```

Determine construct status from: work-plan.md (planned), cycle-log.md
`implemented` entries (completed), test-plan.md (test mapping).
If results aren't in the log, mark "status unknown — run tests to confirm".

---

## Step 5b — Share format (--share)

Condense the session briefing to standup/team format:

```
📋 <slug> — <one-line summary>
Stage: <stage> | Cycle: <n><  | Units: <n>/<total>>

Done: <bullet points of completed stages>
Next: <1–2 sentences on what's next>
Blocker: <one sentence or "none">

Full details: .feature/<slug>/
```
