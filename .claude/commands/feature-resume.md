# /feature-resume "<feature-slug>"

Reads status.md and tells you exactly where the feature is, what happened,
and what to run next. Use this after any interruption, crash, or context switch.

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

## Step 2 — Display the full status report

```
─────────────────────────────────────────────────────────────
FEATURE RESUME — <slug>
─────────────────────────────────────────────────────────────

CURRENT POSITION
Stage:      <stage>
Substage:   <substage>
Last checkpoint: <last successful checkpoint>
Last updated:    <timestamp>

STAGE PROGRESS
  ✓ Scoping        complete    <date>
  ✓ Domains        complete    <date>
  ✓ Planning       complete    <date>
  ↻ Testing        in-progress  cycle 1 — tests written, verifying failures
  · Implementation not-started
  · Refactor       not-started

DOMAIN STATUS (if domains stage was reached)
  ✓ <domain>   resolved    ADR: .decisions/<adr>/adr.md
  ✓ <domain>   resolved    KB: .kb/<topic>/<cat>/<subject>.md
  ✗ <domain>   pending     commissioned <date> — not yet complete

TDD CYCLES (if testing was reached)
  Cycle 1: tests <date> | passing <date or "—"> | refactor <date or "—"> | missing: <n>
  Cycle 2: ...
─────────────────────────────────────────────────────────────
```

---

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

Display the specific next command clearly:

```
NEXT STEP
  <command to run>
  <one sentence explaining why>
```

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
