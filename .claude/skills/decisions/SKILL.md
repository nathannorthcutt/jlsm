---
description: "Single entry point for all architecture decision operations"
argument-hint: "[subcommand] [arguments]"
---

# /decisions [subcommand] [arguments]

Single entry point for all architecture decision operations.

## Subcommands

| Invocation | What it does |
|------------|-------------|
| `/decisions "<question>"` | Query decisions in plain language |
| `/decisions revisit "<slug or topic>"` | Revisit decisions — find by topic or slug, understand why, check conditions, deliberate, optionally kick off a feature |
| `/decisions defer "<problem>" [--until <condition>]` | Park a topic for later |
| `/decisions close "<problem>" [--reason <text>]` | Rule a topic out permanently |
| `/decisions triage` | Review all deferred items and act on them |
| `/decisions roadmap` | Cluster, classify, and prioritize the deferred backlog |
| `/decisions list [--status <filter>] [--search <term>]` | Browse and filter all decisions |
| `/decisions explain "<slug>"` | Plain-language summary of a decision with KB context |
| `/decisions candidates` | Review undocumented decision candidates from recent sessions |
| `/decisions backfill [<path>] [--limit N]` | Surface implicit decisions from source code. Path required for large projects (over backfill_file_threshold) |

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

Try a broader term, or run /decisions revisit "<slug>" for a specific one.
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
Want more detail? /decisions revisit "<most-relevant-slug>"
───────────────────────────────────────────────
```

**Quality rules:**
- Lead with the conclusion — not background or preamble
- If decisions conflict or constrain each other, note it
- If something was explicitly closed, say so: "ruled out on <date> because <reason>"
- If genuinely uncertain (deferred with no context), say so

---

## decisions revisit "<slug or topic>" — revisit and re-evaluate decisions

Revisits existing decisions. Accepts a slug for a specific decision, a topic
keyword, or a free text description to search across all accepted ADRs.
Understands why the user wants to revisit before checking conditions or
starting deliberation. May commission follow-up research if the user's
concern points to a gap in the KB.

Display opening header:
```
───────────────────────────────────────────────
🏛️  DECISIONS REVISIT
───────────────────────────────────────────────
```

### Step 1 — Find matching decisions

The argument can be:
- A **slug** — exact match against `.decisions/<slug>/`
- A **topic keyword** — matched against problem slugs, recommendation text,
  and constraint descriptions in the index
- A **free text description** — matched against ADR content (problem statement,
  decision text, constraints, conditions for revision)

Read `.decisions/CLAUDE.md` (and `history.md` if it exists). Find all accepted
decisions that match the argument. For each match, read the `adr.md` to get
the full decision and "Conditions for Revision" section.

If no matches: "No accepted decisions match '<argument>'. Try /decisions list
to browse all decisions."

If one match: proceed directly to Step 2 with that decision.

If multiple matches, present them using AskUserQuestion. Build the options
dynamically — one option per match, plus an "All" option:

```
Found <N> decisions matching "<argument>":
```

Use AskUserQuestion with:
- One option per match, labeled `<slug> — <recommendation summary> (accepted <date>)`
- A final option: `All` (description: "Revisit all matching decisions in order")

### Step 2 — Understand the motivation

Before checking conditions or loading evaluation data, ask the user why they
want to revisit. This conversation shapes the entire re-evaluation — a concern
about performance at scale leads to different analysis than discovering a new
algorithm.

```
── <slug> ─────────────────────────────────────
Decision: <recommendation> (accepted <date>)

What's prompting you to revisit this?

For example:
  • Constraints have changed (scale, resources, team, timeline)
  • You've seen or learned about an alternative approach
  • Implementation revealed unexpected problems
  • It's been a while and you want to sanity-check
  • You want to change direction regardless

Or just describe what's on your mind.
```

Wait for the user's response. Use their answer to:

1. **Identify which constraint dimensions are affected** — if they mention
   performance, that maps to Scale or Accuracy. If they mention a new library,
   that's new research. If they say "it feels wrong," probe once for specifics
   before proceeding.

2. **Determine if follow-up research is needed** — if the user mentions a
   technology, approach, or paper that isn't in the KB, offer to commission
   research before re-evaluating:
   ```
   I don't see <topic> in the KB.
   ```
   Use AskUserQuestion:
     - "Research first" (description: "Run /research, then re-evaluate with new data")
     - "Proceed without" (description: "Continue with what we have")

   If "Research first": invoke `/research "<subject>" context: "decisions revisit: <slug>"` as a sub-agent, then continue to Step 3 with
   the new KB entry available.

3. **Determine if the user already knows the answer** — if they say "I want
   to change to X regardless," skip condition checking and go straight to
   Step 4 as an override.

Append a `revisit-requested` log entry with the user's stated motivation.

### Step 3 — Check revision conditions

Read:
1. `.decisions/<slug>/adr.md` — "Conditions for Revision" section
2. `.decisions/<slug>/constraints.md` — original constraints
3. `.decisions/<slug>/evaluation.md` — candidate scoring

Check each revision condition against the current state of the codebase and KB,
**informed by the user's motivation from Step 2**:
- **Scale thresholds** — read relevant source files or configs to check if
  thresholds have been crossed
- **New research available** — check `.kb/` for entries added after the ADR's
  accepted date that are in the same topic/category as the ADR's candidates
- **Time-based review** — check if the ADR's age exceeds any stated review
  interval
- **Technology changes** — check if the ADR references technologies or
  constraints that may have evolved
- **User's concern** — map their motivation to the relevant conditions and
  highlight which ones are affected

Present the assessment:
```
Revision conditions for <slug>:
  ✓ <condition 1> — triggered: <evidence>
  ✗ <condition 2> — not triggered: <current state>
  ? <condition 3> — unknown: <what would need checking>

Based on your concern about <user's motivation>:
  <Which conditions are relevant and what the evidence suggests>
```

If no conditions are triggered AND the user's concern doesn't point to a
gap: present this and offer to proceed anyway or confirm the decision still
holds.

### Step 4 — Deliberate

Load the full decision context:
1. `.decisions/<slug>/adr.md`
2. `.decisions/<slug>/constraints.md`
3. `.decisions/<slug>/evaluation.md`
4. `.decisions/<slug>/log.md`

Branch based on the user's motivation and condition assessment:

**Constraints changed:** ask for updated values, re-score changed dimensions,
determine if recommendation changes.

**New KB research:** read new subject file(s), score against current constraints,
add to candidate pool, re-run comparison. Apply composite candidate detection
(Step 4b2 from `/architect`) if the new research opens combination possibilities.

**Implementation problems:** ask user to describe specifically, map to a
constraint dimension, determine if ADR remains valid.

**Override:** ask one question only — "Can you tell me why? I'll record it."
Accept any reason or none. Proceed with override noted.

**Sanity check (no specific concern):** present a brief summary of the decision,
its constraints, and its conditions. Ask: "Does this still match your
understanding of the problem? Anything feel off?" If the user confirms it's
fine, mark as reviewed and move on.

### Step 5 — Present and confirm

Present the outcome as a defence summary in chat (same format as
`/architect` Step 7a) with one of these headers:
- `[STILL VALID]` — recommendation holds; explain with KB evidence
- `[REVISED]` — recommendation changes; explain what changed
- `[OVERRIDE]` — user-directed change; state what changed and the reason

Follow all deliberation chat rules from `/architect` Step 7b.

### Step 6 — Write confirmed outcome

**Still valid (no change):**
- Update `adr.md` frontmatter: `last_reviewed: YYYY-MM-DD`
- Append `revisit-confirmed` log entry (includes user's motivation and
  "decision reaffirmed" outcome)

**Revision:**
1. Mark current `adr.md`: `status: superseded`, `superseded_by: adr-v<N>.md`
2. Write `adr-v<N>.md` (from `/architect` ADR Template)
3. Append to `constraints.md` or `evaluation.md` as needed (`## Updates YYYY-MM-DD`)
4. Append `revision-confirmed` log entry
5. Update `.decisions/<slug>/CLAUDE.md` ADR Version History
6. Update `.decisions/CLAUDE.md` master index

After writing the outcome, check whether implementation work is needed:

**If the decision was revised:**
```
── Decision revised: <slug> ────────────────────
Previous: <old recommendation>
Revised:  <new recommendation>
Reason:   <user's motivation from Step 2>

This revision may require implementation changes.

  feature  — start /feature to implement the change
             (enters pipeline at planning — architecture context already loaded)
  later    — note it and move on
```

**feature** — Generate a feature from the revision:
- Slug: `revise-<adr-slug>` (e.g., `revise-session-storage`)
- Description: "Implement revised architecture decision: <new recommendation>.
  Previous approach was <old recommendation>. Changed because: <revision reason>."
- Create `.feature/<slug>/` directory and write `brief.md` with:
  - The revision context (what changed, why)
  - The new ADR as the governing decision
  - Acceptance criteria derived from the ADR's "Implementation Guidance" section
- Write `status.md` with stage set to `domains` (skip scoping — the brief is
  the ADR revision itself)
- Write `domains.md` with the ADR already marked as `resolved` (it was just
  confirmed via deliberation)
- Invoke `/feature-plan` as the next step — the feature enters the pipeline
  at planning, skipping scoping and domains since the architectural context
  is already established

**later** — note in the review log and continue to next matched decision.

**If the decision is still valid:**
```
  ✓ Decision holds — reaffirmed <today's date>.
```
Continue to next matched decision or finish.

Every invocation produces at minimum a `revisit-requested` and a
`revisit-confirmed` or `revision-confirmed` log entry.

### Step 7 — Summary

```
───────────────────────────────────────────────
🏛️  DECISIONS REVISIT complete
  Revisited: <n>   Still valid: <n>   Revised: <n>   Features started: <n>
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
depends_on: []
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
re-presenting the full summary. See `/architect` Step 7b for full rules.

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

## decisions roadmap — cluster, classify, and prioritize the backlog

Strategic planning pass across all deferred decisions. Clusters by theme,
classifies each by effort, identifies dependencies, and writes a
prioritized sequence. **Planning only — never executes work.**

### Step 1 — Run the scan

```bash
bash .claude/scripts/decisions-scan.sh
```

Read the output at `.decisions/.roadmap-scan.md`.

If zero deferred decisions: display "No deferred decisions to plan." Stop.

### Step 2 — Read problem statements

For each deferred decision in the scan output, read the first ~30 lines of
`.decisions/<slug>/adr.md` to get the Problem and Resume When sections.
**Do not read full ADRs** — you only need the problem scope, not the
analysis context.

### Step 3 — Cluster by theme

Group decisions into thematic clusters using:
1. **Parent ADR grouping** (from scan output — decisions sharing a parent
   are already related)
2. **Problem statement similarity** (merge parent groups that address the
   same domain — e.g., two parent groups both about encryption)

Name each cluster descriptively (e.g., "Storage & Compression",
"Encryption & Security", "Partitioning & Rebalancing").

### Step 4 — Classify by effort

For each decision, classify as:

- **Gap-fill** — config option, single-method addition, validation check,
  thread-safety contract. 1-2 sessions. No architecture pass needed.
  Signals: problem mentions "config", "validation", "flag", "parameter",
  "contract"; scope is a single file or interface.

- **Minor feature** — bounded new capability extending existing architecture.
  Brief `/architect` eval needed. 2-4 sessions.
  Signals: new type, new protocol extension, new API surface; scope is
  2-3 files within one module.

- **Full feature** — significant new architecture, multiple modules, new
  abstractions. Full `/architect` + `/feature` pipeline. 5+ sessions.
  Signals: problem mentions "protocol", "distributed", "consensus",
  "SDK", "new module"; has multiple viable approaches requiring evaluation.

### Step 5 — Identify dependencies

Check for:
1. Decisions whose problem statement references another deferred decision
2. Decisions in the same cluster where one is clearly foundational
   (e.g., "connection-pooling" before "transport-traffic-priority")
3. Cross-cluster dependencies (e.g., networking must stabilize before
   distributed query features)

### Step 6 — Suggest ordering

Order clusters by:
1. **Foundation first** — clusters that other clusters depend on
2. **Quick wins early** — clusters dominated by gap-fills
3. **Independence** — clusters with no external dependencies can be
   parallelized

Within each cluster, order by:
1. Gap-fills before minor features before full features
2. Correctness risks and safety concerns first
3. Dependencies respected (A before B if B depends on A)

Also identify:
- **Immediate promotions** — decisions that are correctness/safety risks
  and should be addressed regardless of cluster ordering
- **Duplicate merges** — decisions from different parents that describe
  the same problem (suggest merging into one ADR)
- **Research suggestions** — full features where a `/research` pass would
  help before committing to `/architect`

### Step 7 — Write outputs

**a. Roadmap document** — write `.decisions/roadmap.md`:

```markdown
# Decisions Roadmap

**Generated:** <date>
**Deferred:** <n> decisions in <n> clusters

## Summary

<n> gap-fill | <n> minor feature | <n> full feature

## Clusters (priority order)

### 1. <Cluster Name> (<n> decisions)

<one-sentence description of why this cluster matters>

**Gap-fills:** <slug>, <slug>, ...
**Minor features:** <slug>, <slug>, ...
**Full features:** <slug>, ...

**Dependencies:** <cluster> must precede <cluster> because ...

### 2. ...

## Immediate Actions

- **Promote:** <slugs> — <reason>
- **Merge:** <slug A> + <slug B> — same problem
- **Research first:** <slugs> — <what to research>

## Suggested Sequence

Phase 1: <cluster> gap-fills (batch TDD pass)
Phase 2: <cluster> gap-fills + minor features
...
```

**b. Per-ADR metadata** — for each deferred decision where a dependency
was identified, update the ADR's frontmatter to add:

```yaml
depends_on: ["<other-slug>"]
```

This allows `/architect` and `/feature` to surface dependencies when the
user goes to work on a specific decision. Only add `depends_on` for
concrete dependencies, not cluster-level ordering preferences.

### Step 8 — Present to user

Display the roadmap summary and use AskUserQuestion:
- "Start with Phase 1" — begin working through the first cluster
- "Pick a cluster" — choose a specific cluster to focus on
- "Done" — roadmap is written, user will return later

If "Start with Phase 1" or "Pick a cluster": suggest the appropriate
next command for each item (e.g., `/architect "<slug>"` for minor/full
features, or a direct implementation reference for gap-fills).

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

**Roadmap suggestion:** If 10+ deferred items exist, check whether
`.decisions/roadmap.md` exists. If no roadmap (or roadmap is stale),
use AskUserQuestion before proceeding:
- "Run roadmap first" — cluster and prioritize before per-item triage
- "Continue with triage" — process items one at a time as usual

If the user picks "Run roadmap first": execute the roadmap subcommand
(Step 1-8 above), then return here for triage.

### Step 1 — Build the triage list

For each deferred item, read `.decisions/<slug>/adr.md` to get full context.

**Roadmap context:** If `.decisions/roadmap.md` exists, note each item's
cluster and classification (gap-fill / minor / full) from the roadmap.
Display this alongside the triage entry so the user sees the strategic
context while triaging.

Display:
```
Deferred topics (<n> total)
───────────────────────────────────────────────

[1] <slug>
    Deferred:    <date> (<N> days ago)
    Resume when: <condition or "not specified">
    Depends on:  <slugs from depends_on field, or "none">
    Context:     <one sentence from "What Is Known So Far", or "none">
    Source:      <"standalone defer" | "tangent during: <parent-slug>">
    Roadmap:     <cluster name — gap-fill/minor/full, if roadmap.md exists>

[2] ...

───────────────────────────────────────────────
```

If a decision has `depends_on` entries that are still deferred (not yet
confirmed), note this in the display: "blocked by: <slug> (still deferred)".
This helps the user avoid evaluating decisions whose prerequisites aren't
resolved yet.

Process items one at a time. For each item, use AskUserQuestion with these
options:
- `Evaluate` (description: "Start /architect session for this topic")
- `Close` (description: "Move to Closed, remove from Deferred")
- `Update` (description: "Refresh resume condition or add context")
- `Delete` (description: "Remove stub entirely")
- `Skip` (description: "Leave as-is for now")

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

Check total line count: if over 80 lines, archive oldest Recently Accepted rows
to `history.md` (same crash-safe order as `/architect` Step 8: create history.md
if needed → append row to history.md → remove row from CLAUDE.md).

---

## decisions list [--status <filter>] [--search <term>] — browse all decisions

Lists all decisions with filtering. Read-only — no files are written.

Display opening header:
```
───────────────────────────────────────────────
🏛️  DECISIONS LIST
───────────────────────────────────────────────
```

### Step 1 — Load index

Read `.decisions/CLAUDE.md` in full. Parse all sections: Active, Recently
Accepted, Deferred, Closed.

If `.decisions/history.md` exists, also read it (contains archived rows moved
from the main index when it exceeded 80 lines).

### Step 2 — Apply filters

**`--status <filter>`** — show only decisions matching this status. Values:
- `accepted` or `confirmed` — confirmed ADRs
- `draft` — draft ADRs (from backfill)
- `deferred` — parked topics
- `closed` — ruled out permanently
- `all` — everything (default if no filter)

**`--search <term>`** — case-insensitive substring match against problem slug,
problem description, and recommendation text. Multiple terms are AND-matched.

If both flags are provided, apply both (intersection).

### Step 3 — Display

```
  <n> decisions found<  (filtered: status=<filter>, search="<term>")>

  STATUS     SLUG                         DATE        SUMMARY
  ────────   ──────────────────────────   ─────────   ───────────────────────
  accepted   rate-limiting-strategy       2026-03-10  Token bucket with Redis
  accepted   storage-engine-choice        2026-03-08  LSM tree (jlsm-core)
  draft      secondary-index-model        2026-03-16  Separate LSM per index
  deferred   cache-invalidation           2026-03-12  Resume: after v2 launch
  closed     graphql-api                  2026-03-05  Ruled out: REST sufficient

  ────────────────────────────────────────────────
  Total: <n accepted> accepted · <n draft> draft · <n deferred> deferred · <n closed> closed

  Details: /decisions revisit "<slug>"
  Query:   /decisions "<question>"
```

If no decisions match the filter:
```
  No decisions found matching status=<filter><, search="<term>">.
  Total in store: <n>
```

Sort order: accepted first (newest first), then draft, deferred, closed.

---

## decisions explain "<slug>" — plain-language summary

Generates a readable summary of a decision with its supporting KB evidence.
Useful for PR descriptions, onboarding, and team communication. Read-only.

Display opening header:
```
───────────────────────────────────────────────
🏛️  DECISIONS EXPLAIN · <slug>
───────────────────────────────────────────────
```

If slug not found: "No decision found for '<slug>'. Run /decisions list to see all decisions."

### Step 1 — Load

Read in order:
1. `.decisions/<slug>/adr.md` — the decision itself
2. `.decisions/<slug>/constraints.md` — if it exists (what drove the decision)
3. `.decisions/<slug>/evaluation.md` — if it exists (what was compared)

For each KB link found in adr.md or evaluation.md:
- Read only the `## Summary` or `## Key Parameters` section of the linked
  `.kb/` file — not the full entry.

### Step 2 — Generate summary

Write a plain-language explanation structured as:

```
── <Problem Slug> ─────────────────────────────

STATUS: <accepted | draft | deferred | closed>
DATE:   <decision date>

WHAT WE DECIDED
<2-3 sentences in plain language. No jargon. Written for someone who has
never seen the ADR. Lead with the conclusion.>

WHY
<2-3 sentences explaining the key constraints and tradeoffs that led here.
Reference specific KB findings if they were influential.>

<If alternatives were evaluated:>
WHAT WE CONSIDERED
  ✓ <chosen option> — <one-line reason it won>
  ✗ <rejected option> — <one-line reason it lost>
  ✗ <rejected option> — <one-line reason>

<If assumptions or revision conditions exist:>
ASSUMPTIONS
  - <assumption that could invalidate this>
  - <condition that should trigger a review>

<If KB entries were referenced:>
SUPPORTING RESEARCH
  - .kb/<path> — <one-line relevance>
  - .kb/<path> — <one-line relevance>

<If status is draft:>
NOTE: This is a draft — the rationale above has not been through formal
deliberation. Run /decisions revisit "<slug>" to formalize.

<If status is deferred:>
NOTE: This topic is deferred. Resume condition: <condition or "not specified">.

───────────────────────────────────────────────
```

### Step 3 — Offer next actions

```
  Copy this summary into a PR description or share with your team.

  To revisit: /decisions revisit "<slug>"
  To see all:  /decisions list
```

Stop. No files are written.

---

## decisions candidates — review discovered decision candidates

Reviews undocumented decision candidates accumulated from recent sessions.
Candidates are written to `.decisions/.decision-candidates` by a PostSessionEnd
hook that scans conversation transcripts for decision-shaped language.

Display opening header:
```
───────────────────────────────────────────────
🏛️  DECISIONS CANDIDATES
───────────────────────────────────────────────
```

### Candidate file format

`.decisions/.decision-candidates` is an append-only file. Each candidate is a
YAML-like block separated by `---`:

```yaml
---
date: "2026-03-16"
session: "<session-id>"
signal: "<quote from transcript>"
context: "<surrounding context>"
suggested_problem: "<one-line problem statement>"
status: "new"
---
```

Status values: `new` (unreviewed), `processed` (acted on), `dismissed`.

### Step 1 — Load candidates

Read `.decisions/.decision-candidates`. If it doesn't exist or has no `new`
entries:
```
No undocumented decision candidates to review.
Candidates are discovered automatically at the end of each session.
```
Stop.

Filter to `status: new` entries only.

### Step 2 — Present candidates

```
── <n> candidates from recent sessions ────────
```

Present each candidate one at a time:

```
── <i> of <n> ─────────────────────────────────
  "<signal>"
  Session: <date>
  Suggested: <suggested_problem>

Use AskUserQuestion with these options:
- `Decide` (description: "Start full /architect deliberation for this candidate")
- `Draft` (description: "Write a partial ADR with rationale, to formalize later")
- `Defer` (description: "Park for later with context")
- `Dismiss` (description: "Not a real decision — mark as dismissed")

Wait for user response. Process identically to `/decisions backfill` Step 4:
- **decide** → invoke `/architect "<suggested_problem>"` as a sub-agent
- **draft** → prompt for rationale, write draft ADR
- **defer** → prompt for who/context, write deferred stub
- **dismiss** → mark as dismissed in the candidates file

After processing, update the candidate's status in `.decision-candidates`
to `processed` or `dismissed`.

### Step 3 — Summary

```
───────────────────────────────────────────────
🏛️  DECISIONS CANDIDATES complete
  Decided:   <n>
  Drafted:   <n>
  Deferred:  <n>
  Dismissed: <n>
  Remaining: <n>
───────────────────────────────────────────────
```

### Discovery — how candidates are surfaced to users

Candidates accumulate silently. The following pipeline commands check for
`new` candidates in `.decisions/.decision-candidates` and display a notice
if any exist:

**`/feature-domains`** — after Step 2 (domain coverage display):
```
  ℹ <n> undocumented decision candidates from recent sessions.
    Run /decisions candidates to review.
```

**`/feature-resume`** — in the Step 2 status display:
```
  ℹ <n> decision candidates pending review.
```

**`/feature-resume --status`** — in the Current Blocker section (as informational,
not a blocker):
```
  Decision candidates: <n> pending (/decisions candidates)
```

These notices are informational only — they never block the pipeline.

### PostSessionEnd hook — transcript scanning

The hook script scans the current session transcript for decision-shaped
language patterns:

**Signal patterns** (regex-like, case-insensitive):
- "let's go with ...", "let's use ..."
- "I decided to ...", "we decided to ..."
- "chose X over Y", "picked X instead of Y"
- "the reason we're doing X is ..."
- "going with X because ..."
- "ruled out X", "not going to use X"

**Filtering:**
- Skip if the signal is inside a code block (implementation, not a decision)
- Skip if it references an existing ADR slug from `.decisions/CLAUDE.md`
- Skip if an identical signal already exists in `.decision-candidates`
- Skip signals that are clearly about implementation details, not architecture
  ("let's use a for loop", "going with the simpler if/else")

**What gets captured:**
- The signal text (the decision-shaped quote)
- Surrounding context (2-3 sentences before/after)
- A suggested problem statement (inferred from the context)
- The session date

The hook script is installed as `.claude/hooks/post-session-decisions.sh`.
It runs silently — no output to the user. It only appends to the candidates
file.

---

## decisions backfill [<path>] [--limit N] — retroactive decision extraction

Scans archived features and source structure to surface implicit architectural
decisions that were never documented as ADRs. Presents candidates one at a time
for the user to decide, draft, defer, or dismiss. On projects with more than
`backfill_file_threshold` source files (default 50, set in project-config.md),
a `<path>` argument is required to scope the scan.

Display opening header:
```
───────────────────────────────────────────────
🏛️  DECISIONS BACKFILL
───────────────────────────────────────────────
```

### Step 0 — Parse arguments and size check

- `<path>` (optional): scope the source scan to this module/package path.
  Archived feature scan still runs (results are filtered to domains that
  relate to constructs in the scoped path).
- `--limit N` (optional, default 5): max candidates to present this session.

**Size check (before any scanning):**

Read `.feature/project-config.md` for the source directory, language, and
`Backfill file threshold` (default 50). Count source files using bash:

```bash
find <source-dir> -type f \( -name "*.<ext>" \) | wc -l
```

Use language from project-config to determine extensions:
- Java: `*.java`
- TypeScript/JavaScript: `*.ts *.tsx *.js *.jsx`
- Python: `*.py`
- Go: `*.go`
- Rust: `*.rs`
- Multiple languages: union of applicable extensions

**If `<path>` was provided:** skip the size check, proceed to Step 1.

**If no `<path>` and file count is under threshold:** proceed to Step 1.

**If no `<path>` and file count is at or over threshold:** require a path.
List available top-level directories under the source root with file counts:

```
This project has ~<n> source files. To keep scan costs predictable,
specify a module or package path:

  /decisions backfill <source-dir>/<module-1>
  /decisions backfill <source-dir>/<module-2>

Available:
  <source-dir>/<module-1>/    (<n> files)
  <source-dir>/<module-2>/    (<n> files)
  <source-dir>/<module-3>/    (<n> files)
  ...
```
Stop. Do not proceed without a path.

### Step 1 — Load dismissed list

Read `.decisions/.backfill-dismissed` if it exists. This file contains one
candidate key per line (format: `<source>:<identifier>`) that the user has
previously dismissed. These are filtered out of all results.

Format:
```
# Dismissed backfill candidates — do not resurface
archive:table-indices-and-queries:secondary-index-storage-model
source:modules/jlsm-table:sealed-interface-predicate
```

### Step 2 — Scan for candidates

Build a candidate list from two sources. Score and rank by signal strength.

#### Source A — Archived feature domains (highest signal)

Scan `.feature/_archive/*/domains.md` for each archived feature.

For each domain entry in the file:
- If `Governing ADR:` says "None required", "None needed", "None", or is absent
  AND the domain's guidance section contains design rationale (not just "standard
  pattern" or "well-understood")
  → candidate.
- If an ADR already exists in `.decisions/` that covers this domain → skip.

Candidate key: `archive:<feature-slug>:<domain-name-slugified>`
Signal: **high** — someone identified this as a domain worth analyzing and the
decision was made implicitly.

Extract from the archived domains.md:
- Domain name
- Guidance text (the rationale that was baked in without deliberation)
- Feature it came from

#### Source B — Source code structure (moderate signal)

Scan the source tree (or scoped `<path>`) for structural patterns that imply
architectural decisions. Read file names and structure, NOT full file contents.

**What to scan for:**

| Pattern | Signal | What to extract |
|---------|--------|----------------|
| Module boundaries (module-info.java, go.mod, package.json in subdirs) | high | Why is this a separate module? What does it own? |
| Sealed interface/abstract class hierarchies with 3+ implementations | high | Why this extension model? What are the variants? |
| Custom encoding/serialization (binary formats, custom codecs) | high | Why not a standard format? What tradeoffs? |
| Dependency edges between internal modules | moderate | Why does A depend on B? |

**What to NOT scan for:**
- Framework/library choices (tooling, not architecture)
- Naming conventions, test structure, formatting (linter territory)
- Standard language patterns (builder pattern, factory, etc. unless project-specific)
- Anything with an existing ADR in `.decisions/`

Candidate key: `source:<path>:<pattern-description-slugified>`
Signal: **moderate** — structural implication, may or may not reflect a deliberate choice.

#### Filtering and ranking

1. Remove candidates whose key appears in `.backfill-dismissed`
2. Remove candidates that match an existing ADR in `.decisions/CLAUDE.md`
3. Partition into: **new** candidates and **deferred** candidates (existing
   stub ADRs with `status: deferred` that match a scanned pattern)
4. Sort new candidates by signal strength (high before moderate)
5. Append deferred candidates after all new candidates
6. Take the first `--limit` items

### Step 3 — Present candidates

Display summary:
```
── Scan results ────────────────────────────────
  Candidates found: <n total> (<n new>, <n deferred>)
  Previously dismissed: <n filtered>
  Showing: <limit> of <n>
```

Present each candidate one at a time:

```
── <i> of <limit> ─────────────────────────────
  <Domain or pattern name>
  Source: <"archived feature '<slug>'" | "source structure at <path>">
  Signal: <high | moderate>

  <2-3 sentences describing the implicit decision. For archived features,
  quote the guidance text from domains.md. For source patterns, describe
  what the structure implies.>
```

Use AskUserQuestion with these options:
- `Decide` (description: "Start full /architect deliberation for this candidate")
- `Draft` (description: "Write a partial ADR with rationale, to formalize later")
- `Defer` (description: "Park for later with context")
- `Dismiss` (description: "Not a real decision — won't resurface")

Wait for user response.

### Step 4 — Process each candidate

**decide** → invoke `/architect "<decision problem>"` as a sub-agent immediately.
The problem statement is derived from the candidate description. After architect
completes, display result and continue to next candidate.

**draft** → prompt:
```
Describe the rationale in a few sentences — why was this decision made?
(Or type: skip  to leave the draft empty for someone else to fill in.)
```

Write a draft ADR to `.decisions/<slug>/adr.md`:
```markdown
---
problem: "<slug>"
date: "<YYYY-MM-DD>"
version: 1
status: "draft"
source: "backfill"
---

# <Problem Slug> — Draft

## Problem
<derived from candidate description>

## Decision (draft — not yet deliberated)
<user's rationale, or "Not yet documented. Needs deliberation.">

## Context
<quoted guidance from archived feature, or source structure description>

## Source
<"Extracted from archived feature '<feature-slug>' domain analysis" |
 "Identified from source structure at <path>">

## Next Step
Run `/decisions revisit "<slug>"` to formalize through deliberation.
```

Create `log.md` with a `backfill-draft` entry. Add a row to `.decisions/CLAUDE.md`
in the Active section with status `draft`.

Display:
```
  ✓ Draft written: .decisions/<slug>/adr.md
    To formalize: /decisions revisit "<slug>"
```

**defer** → prompt:
```
Who should answer this? (name, role, or "unknown")
Any additional context?  (or type: skip)
```

Write a deferred stub (same as `/decisions defer` format) with additional
`source: "backfill"` frontmatter and `## Assigned To` section.

Display:
```
  ✓ Deferred: .decisions/<slug>/adr.md
    Assigned to: <who or "unassigned">
```

**dismiss** → append the candidate key to `.decisions/.backfill-dismissed`.
Display:
```
  ✗ Dismissed — won't resurface.
```

### Step 5 — Summary

```
───────────────────────────────────────────────
🏛️  DECISIONS BACKFILL complete
  Decided:    <n>  (full architect deliberation)
  Drafted:    <n>  (partial ADR, needs review)
  Deferred:   <n>  (assigned for later)
  Dismissed:  <n>  (won't resurface)
  Remaining:  <n>  (run again to see more)
───────────────────────────────────────────────
```

If remaining > 0:
```
  Run /decisions backfill again to see the next batch.
```

---

## Draft ADR visibility rules

Draft ADRs (created by backfill) have `status: draft` in their frontmatter.

**Domain Scout behaviour:** when the Domain Scout finds a draft ADR that covers
a domain, it displays a warning but does NOT block:
```
  ⚠ DRAFT ADR    <domain> — .decisions/<slug>/adr.md (draft — not yet deliberated)
```
The domain is classified as `pending-decision` (not `resolved`). The user can
choose to proceed or formalize the draft first via `/decisions revisit`.

**`/decisions triage` behaviour:** draft ADRs appear alongside deferred items.
Same action options: evaluate, update, close, delete, skip.

---

## Token hygiene note

Deferred and draft stubs are small (~300–500 tokens) and never auto-loaded.
The main cost of a crowded Deferred section is the Architect seeing noise in
the master index and potentially re-raising topics already set aside. Keep the
Deferred list short by running `/decisions triage` periodically.
