# /feature-status "<feature-slug>"

Produces a human-readable session briefing — what was done, what is in progress,
and a focused agenda for the next work session. Oriented toward picking up where
you left off, not just showing raw state.

Different from /feature-resume: resume tells you what command to run next.
Status tells you what the work actually is — useful at the start of a session,
for sharing progress with teammates, or for writing a standup update.

Works for both /feature and /quick slugs.

---

## Pre-flight

Check `.feature/<slug>/status.md` exists.
If not: "No status found for '<slug>'. Run /feature-resume to see all active features."

---

## Step 1 — Load context

Read:
1. `.feature/<slug>/status.md` — stage and substage
2. `.feature/<slug>/brief.md` or status.md Description field (quick tasks)
3. `.feature/<slug>/cycle-log.md` — full history
4. `.feature/<slug>/work-plan.md` if it exists — to report construct completion
5. `.feature/<slug>/test-plan.md` if it exists — to report test coverage

---

## Step 2 — Build the session briefing

Display:

```
─────────────────────────────────────────────────────────────
FEATURE STATUS — <slug>
<YYYY-MM-DD HH:MM> | <mode: full pipeline / quick task>
─────────────────────────────────────────────────────────────

WHAT THIS IS
<2–3 sentences from brief summary or quick task description.>

─────────────────────────────────────────────────────────────
PROGRESS
─────────────────────────────────────────────────────────────

  ✓ Scoping      <date> — brief confirmed
  ✓ Domains      <date> — <n> domains resolved, <n> ADRs consulted
  ✓ Planning     <date> — <n> constructs, <n> new / <n> extended
  ↻ Testing      in progress — cycle <n>
    · Tests written: <n> (<date>)
    · Tests passing: <n of n> as of <date or "not yet run">
  · Implementation  not started
  · Refactor        not started

(Use ✓ for complete, ↻ for in-progress, · for not started)

─────────────────────────────────────────────────────────────
WHAT WAS DONE LAST SESSION
─────────────────────────────────────────────────────────────
<Synthesise the most recent 3–5 cycle-log entries into plain English.
Not a raw dump of the log — a readable summary of what actually happened.
Example: "The Work Planner identified 4 new constructs and wrote stubs for
all of them. The Test Writer then wrote 12 tests covering the happy path and
3 error conditions — all verified failing.">

─────────────────────────────────────────────────────────────
CURRENT BLOCKER / OPEN QUESTION
─────────────────────────────────────────────────────────────
<If there is an active escalation (code-escalation, missing-tests-found,
cycle-5-checkpoint, etc.) in cycle-log.md: describe it in one paragraph.
If there is no blocker: "None — ready to continue.">

─────────────────────────────────────────────────────────────
NEXT SESSION AGENDA
─────────────────────────────────────────────────────────────
<3–5 bullet points describing what the next work session needs to accomplish.
Concrete and actionable — not "continue implementation" but "implement the
TokenBucket.consume() method and RateLimitMiddleware.handle() — these are
the two remaining failing tests." Source from work-plan.md and test-plan.md.>

  1. <specific task>
  2. <specific task>
  3. ...

TO CONTINUE:
  <exact command to run>

─────────────────────────────────────────────────────────────
```

---

## Step 3 — Construct completion table (if work-plan.md exists)

After the main briefing, if work-plan.md exists, append a compact table:

```
CONSTRUCTS

  ✓ <ConstructName>    <src/path>    tests passing
  ↻ <ConstructName>    <src/path>    <n> tests failing
  · <ConstructName>    <src/path>    not started

```

Determine status by cross-referencing:
- work-plan.md (what was planned)
- cycle-log.md `implemented` entries (what the Code Writer completed)
- test-plan.md (which tests map to which constructs)

If test results aren't in the log (e.g. mid-session crash before logging),
mark as "status unknown — run tests to confirm" rather than guessing.

---

## Step 4 — Team sharing format (optional)

If the user adds `--share` to the command, produce a condensed version
suitable for pasting into a standup update or team channel:

```
/feature-status "<slug>" --share
```

Output:

```
📋 <slug> — <one-line summary>
Stage: <current stage> | Cycle: <n>

Done: <bullet points of completed stages>
Next: <1–2 sentences on what's next>
Blocker: <one sentence or "none">

Full details: .feature/<slug>/
```
