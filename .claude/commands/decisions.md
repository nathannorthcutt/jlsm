# /decisions [subcommand] [arguments]

Single entry point for all architecture decision operations.

## Subcommands

| Invocation | What it does |
|------------|-------------|
| `/decisions "<question>"` | Query decisions in plain language |
| `/decisions review "<slug>"` | Revisit a confirmed decision with deliberation |
| `/decisions defer "<problem>" [--until <condition>]` | Park a topic for later |
| `/decisions close "<problem>" [--reason <text>]` | Rule a topic out permanently |
| `/decisions triage` | Review all deferred items and act on them |

**Default (no subcommand):** if the first argument looks like a question rather
than a subcommand name, treat it as `/decisions "<question>"`.

---

## Pre-flight guard (all subcommands)

Check that `.decisions/CLAUDE.md` exists. If not:
```
The decisions directory has not been initialised. Run /setup-vallorcine first.
```
Stop.

---

## decisions "<question>" — natural language query

Answers questions like:
  "What did we decide about caching?"
  "Have we ruled out GraphQL anywhere?"
  "What assumptions are we carrying about our database layer?"
  "Is there anything deferred that relates to auth?"

No files are written. Read-only.

Display opening header:
```
───────────────────────────────────────────────
🏛️  DECISIONS QUERY · "<question>"
───────────────────────────────────────────────
```

If no question was provided:
```
What would you like to know about your architecture decisions?
e.g. "what did we decide about the database?" or "is there anything on caching?"
```
Wait for input, then proceed.

### Step 1 — Index scan

Read `.decisions/CLAUDE.md` in full. Identify candidate slugs by matching the
question against problem descriptions in all sections (Active, Recently Accepted,
Deferred, Closed) and any keywords in Resume When or Reason columns.

Read `.decisions/<slug>/CLAUDE.md` for each candidate — one-line recommendation,
status, last activity. Do NOT load full adr.md files yet.

If no candidates found:
```
No decisions found matching "<question>".

The decisions store contains:
  <n> confirmed · <n> deferred · <n> closed

Try a broader term, or run /decisions review "<slug>" for a specific one.
```
Stop.

### Step 2 — Deep read (targeted)

For each candidate where CLAUDE.md leaves the question unanswered, read only
the relevant section of adr.md:
- "What did we decide" → `## Decision` only
- "Why did we choose X" → `## Rationale` only
- "What assumptions" → `## Key Assumptions` + `## Conditions for Revision`
- "What doesn't this solve" → `## What This Decision Does NOT Solve`
- Deferred/closed stubs → read in full (they're short by design)

Do not load `evaluation.md`, `constraints.md`, or `log.md` unless the user
explicitly asks for scoring detail or decision history.

### Step 3 — Display the answer

```
<Direct answer in 2–4 sentences. Lead with the conclusion.>

RELEVANT DECISIONS

<slug> — <confirmed | deferred | closed>
  Decision:    <one-line outcome>
  Date:        <date>
  <confirmed:> Assumes: <key assumption relevant to the question>
  <confirmed:> Revisit if: <most relevant condition>
  <deferred:>  Resume when: <condition>
  <closed:>    Reason: <why ruled out>
  Full record: .decisions/<slug>/adr.md

<If tangent-captured entries are relevant:>
TANGENTS (set aside during deliberation)
  During <parent-slug>, <date>: "<topic>" — <deferred | closed>

───────────────────────────────────────────────
<n> decision(s) found.
Want more detail? /decisions review "<most-relevant-slug>"
───────────────────────────────────────────────
```

**Quality rules:**
- Lead with the conclusion — not background or preamble
- If decisions conflict or constrain each other, note it
- If something was explicitly closed, say so: "ruled out on <date> because <reason>"
- If genuinely uncertain (deferred with no context), say so

---

## decisions review "<slug>" — deliberation review

Revisits an existing confirmed ADR. Uses the deliberation loop — no file is
written until the user confirms.

Display opening header:
```
───────────────────────────────────────────────
🏛️  DECISIONS REVIEW · <slug>
───────────────────────────────────────────────
```

If slug not found: "No decision found for '<slug>'. Use /architect to start a new one."

### Step 1 — Load

Read in order:
1. `.decisions/<slug>/adr.md` — current decision and KB links
2. `.decisions/<slug>/constraints.md` — original constraints
3. `.decisions/<slug>/log.md` — full history
4. `.decisions/<slug>/evaluation.md` — scoring and KB evidence

Append a `review-requested` log entry immediately (before any analysis).

### Step 2 — Open deliberation

```
───────────────────────────────────────────────
🏛️  DECISIONS REVIEW · <slug>
Current recommendation: <from adr.md>
Status: <from frontmatter>
Last activity: <most recent log entry>
Original decision date: <from adr.md>

What prompted this review?
  1. Constraints have changed
  2. New research is available in the KB
  3. Implementation revealed unexpected problems
  4. Scheduled review
  5. Want to change the decision regardless of scoring

Or just describe what has changed.
───────────────────────────────────────────────
```

### Step 3 — Analyse and re-evaluate

Branch based on response:

**Constraints changed:** ask for updated values, re-score changed dimensions,
determine if recommendation changes.

**New KB research:** read new subject file(s), score against current constraints,
add to candidate pool, re-run comparison.

**Implementation problems:** ask user to describe specifically, map to a
constraint dimension, determine if ADR remains valid.

**Override:** ask one question only — "Can you tell me why? I'll record it."
Accept any reason or none. Proceed with override noted.

### Step 4 — Present and confirm

Present the review outcome as a defence summary in chat (same format as
`/architect` Step 6a) with one of these headers:
- `[NO CHANGE]` — recommendation holds; explain with KB evidence
- `[REVISED]` — recommendation changes; explain what changed
- `[OVERRIDE]` — user-directed change; state what changed and the reason

Follow all deliberation chat rules from `/architect` Step 6b.

### Step 5 — Write confirmed outcome

**No change:**
- Update `adr.md` frontmatter: `last_reviewed: YYYY-MM-DD`
- Append `review-deliberation-confirmed` log entry
- Append `review-completed` log entry

**Revision:**
1. Mark current `adr.md`: `status: superseded`, `superseded_by: adr-v<N>.md`
2. Write `adr-v<N>.md` (from `/architect` ADR Template)
3. Append to `constraints.md` or `evaluation.md` as needed (`## Updates YYYY-MM-DD`)
4. Append `revision-confirmed` log entry
5. Update `.decisions/<slug>/CLAUDE.md` ADR Version History
6. Update `.decisions/CLAUDE.md` master index

Every invocation produces at minimum a `review-requested` and a
`review-deliberation-confirmed` or `review-completed` log entry.

Display on completion:
```
───────────────────────────────────────────────
🏛️  DECISIONS REVIEW complete · <slug>
⏱  Token estimate: ~<N>K
───────────────────────────────────────────────
```

---

## decisions defer "<problem>" [--until <condition>] — park for later

Writes a lightweight deferred stub without full evaluation.

Create `.decisions/<slug>/adr.md` with `status: deferred`:

```markdown
---
problem: "<slug>"
date: "<YYYY-MM-DD>"
version: 1
status: "deferred"
---

# <Problem Slug> — Deferred

## Problem
<problem statement>

## Why Deferred
<reason given, or "not specified">

## Resume When
<--until condition, or "not specified">

## What Is Known So Far
<any context already stated, or "none captured">

## Next Step
Run `/architect "<problem>"` when ready to evaluate.
```

Append to `log.md`:
```markdown
## <YYYY-MM-DD> — deferred
**Agent:** Architect Agent
**Event:** deferred
**Summary:** Marked deferred. Resume condition: <condition or "unspecified">.
---
```

Add a Deferred row to `.decisions/CLAUDE.md`.
Update `.decisions/<slug>/CLAUDE.md` with status `deferred`.

Display:
```
───────────────────────────────────────────────
🏛️  Deferred: <slug>
Resume when: <condition or "not specified">
Recorded in .decisions/<slug>/adr.md

To revisit: /architect "<problem>"
To triage all deferred: /decisions triage
───────────────────────────────────────────────
```

**Tangent capture during `/architect` deliberation:** if the user flags a topic
as out-of-scope mid-deliberation, capture it the same way — brief acknowledgement,
`tangent-captured` log entry on the parent problem, stub adr.md for the tangent,
row in the Deferred (or Closed) section. Return to deliberation without
re-presenting the full summary. See `/architect` Step 6b for full rules.

---

## decisions close "<problem>" [--reason <text>] — rule out permanently

Writes a lightweight closed stub. Won't be raised again.

Create `.decisions/<slug>/adr.md` with `status: closed`:

```markdown
---
problem: "<slug>"
date: "<YYYY-MM-DD>"
version: 1
status: "closed"
---

# <Problem Slug> — Closed (Won't Pursue)

## Problem
<problem statement>

## Decision
**Will not pursue.** Explicitly ruled out — should not be raised again.

## Reason
<reason given, or "not specified">

## Context
<any constraints or context, or "none captured">

## Conditions for Reopening
<if stated, or "none — treat as permanently closed">
```

Append to `log.md` and add a Closed row to `.decisions/CLAUDE.md`.

Display:
```
───────────────────────────────────────────────
🏛️  Closed: <slug>
Reason: <reason or "not specified">
Will not be raised again.
Recorded in .decisions/<slug>/adr.md

To reopen: /architect "<problem>"
───────────────────────────────────────────────
```

---

## decisions triage — review all deferred items

Triages everything in the Deferred section of `.decisions/CLAUDE.md`.

Display opening header:
```
───────────────────────────────────────────────
🏛️  DECISIONS TRIAGE
───────────────────────────────────────────────
```

If the Deferred section is empty:
```
No deferred topics. Nothing to triage.
```
Stop.

### Step 1 — Build the triage list

For each deferred item, read `.decisions/<slug>/adr.md` to get full context.

Display:
```
Deferred topics (<n> total)
───────────────────────────────────────────────

[1] <slug>
    Deferred:    <date> (<N> days ago)
    Resume when: <condition or "not specified">
    Context:     <one sentence from "What Is Known So Far", or "none">
    Source:      <"standalone defer" | "tangent during: <parent-slug>">

[2] ...

───────────────────────────────────────────────
For each item:
  e  — evaluate now  (start /architect session)
  c  — close         (move to Closed, remove from Deferred)
  u  — update        (refresh resume condition or add context)
  d  — delete        (remove stub entirely)
  s  — skip

Enter choices: 1=e 2=s 3=c  (or type: skip  to skip all)
───────────────────────────────────────────────
```

### Step 2 — Process choices

**e — Evaluate:** invoke `/architect "<problem>"` as a sub-agent immediately.
Remove the Deferred row after the Architect writes its ADR.

**u — Update:** ask "what's changed?" Update adr.md Resume When and/or
What Is Known So Far. Append `deferred-updated` to log.md. Refresh the
Deferred row Resume When column.

**c — Close:** ask for reason (optional). Update adr.md status to `closed`,
update heading and sections. Append `closed` to log.md. Move row from
Deferred to Closed in `.decisions/CLAUDE.md`.

**d — Delete:** confirm before deleting. Remove `.decisions/<slug>/` entirely.
Remove Deferred row. No Closed row — deletion means it never needed recording.

**s — Skip:** no changes.

### Step 3 — Summary

```
───────────────────────────────────────────────
🏛️  DECISIONS TRIAGE complete
  Evaluated: <n>   Updated: <n>   Closed: <n>   Deleted: <n>   Skipped: <n>
Deferred remaining: <n>
───────────────────────────────────────────────
```

If the Deferred section is now empty, add a `<!-- Last cleared: YYYY-MM-DD -->`
comment to that section in `.decisions/CLAUDE.md`.

Check total line count: if over 80 lines, move oldest Recently Accepted rows
to `history.md` (same rule as `/architect` Step 7).

---

## Token hygiene note

Deferred stubs are small (~300–500 tokens) and never auto-loaded. The main cost
of a crowded Deferred section is the Architect seeing noise in the master index
and potentially re-raising topics already set aside. Keep the Deferred list
short by running `/decisions triage` periodically.
