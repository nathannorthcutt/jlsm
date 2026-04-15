---
description: "Run a full architecture decision session for a given problem"
argument-hint: "<problem statement>"
---

# /architect "<problem statement>"

Runs a full architecture decision session for the given problem.
No file is written to .decisions/ until the user confirms in deliberation chat.

---

## Pre-flight guard

Before anything else, check that .decisions/CLAUDE.md exists.
If it does not exist, stop and say:
  "The decisions directory has not been initialised. Run /setup-vallorcine first, then retry."

---

Display opening header:
```
───────────────────────────────────────────────
🏛️  ARCHITECT AGENT
───────────────────────────────────────────────
```

## Step 0 — Parse intent and slug the problem

- Check whether the invocation includes a disposition flag:
  - `/decisions defer "<problem>" [--until <condition>]` → lightweight deferred write, skip to **Step 0D**
  - `/decisions close "<problem>" [--reason <text>]` → lightweight closed write, skip to **Step 0C**
  - No flag → full evaluation, continue to Step 0.5

- Extract the problem statement
- Generate `problem-slug` in kebab-case (e.g. "vector search service" → "vector-search-service")
- Check if `.decisions/<problem-slug>/` exists
  - If yes: read `adr.md` and `log.md`, ask whether this is a revision or a new problem
  - If no: proceed to Step 0.5

---

### Step 0D — Deferred write (lightweight)

Write `.decisions/<problem-slug>/adr.md` with status `deferred`:

```markdown
---
problem: "<problem-slug>"
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
<--until condition, or "not specified — check back manually">

## What Is Known So Far
<any context or constraints already stated, or "none captured">

## Next Step
Run `/architect "<problem>"` when ready to evaluate.
```

Append to `log.md`:
```markdown
## <YYYY-MM-DD> — deferred

**Agent:** Architect Agent
**Event:** deferred
**Summary:** <problem> marked as deferred. Resume condition: <condition or "unspecified">.

---
```

Update `.decisions/CLAUDE.md` — add a row to the **Deferred** section.
Update `.decisions/<problem-slug>/CLAUDE.md` (Problem Index) with status `deferred`.

Display:
```
───────────────────────────────────────────────
🏛️  ARCHITECT AGENT — deferred
───────────────────────────────────────────────
Deferred: <problem-slug>
Reason:   <reason or "not specified">
Resume when: <condition or "not specified">

Recorded in .decisions/<problem-slug>/adr.md
Listed in .decisions/CLAUDE.md under Deferred.

To revisit: /architect "<problem>"
───────────────────────────────────────────────
```
Stop.

---

### Step 0C — Closed write (lightweight)

Write `.decisions/<problem-slug>/adr.md` with status `closed`:

```markdown
---
problem: "<problem-slug>"
date: "<YYYY-MM-DD>"
version: 1
status: "closed"
---

# <Problem Slug> — Closed (Won't Pursue)

## Problem
<problem statement>

## Decision
**Will not pursue.** This topic is explicitly ruled out and should not be raised again.

## Reason
<reason given, or "not specified">

## Context
<any relevant constraints or context that informed this closure, or "none captured">

## Conditions for Reopening
<if the user specified any, or "none — treat as permanently closed">
```

Append to `log.md`:
```markdown
## <YYYY-MM-DD> — closed

**Agent:** Architect Agent
**Event:** closed
**Summary:** <problem> closed — will not pursue. Reason: <reason or "not specified">.

---
```

Update `.decisions/CLAUDE.md` — add a row to the **Closed** section.
Update `.decisions/<problem-slug>/CLAUDE.md` with status `closed`.

Display:
```
───────────────────────────────────────────────
🏛️  ARCHITECT AGENT — closed
───────────────────────────────────────────────
Closed: <problem-slug>
Reason: <reason or "not specified">
This topic will not be raised again.

Recorded in .decisions/<problem-slug>/adr.md
Listed in .decisions/CLAUDE.md under Closed.

To reopen: /architect "<problem>" (will note prior closure)
───────────────────────────────────────────────
```
Stop.

---

## Step 0.5 — Scope verification

Before collecting constraints, verify the problem scope is complete by
cross-referencing the problem statement against existing specs and decisions.

**Dependency check:** If `.decisions/<slug>/adr.md` exists and has a
`depends_on` field with entries, check whether those dependencies are
resolved (status: confirmed). If any dependency is still deferred, warn:

```
This decision depends on <slug> which is still deferred.
Evaluating out of order may produce a design that conflicts
with the dependency's eventual resolution.
```

Use AskUserQuestion:
- "Proceed anyway" — evaluate despite unresolved dependency
- "Evaluate dependency first" — switch to evaluating the dependency
- "Cancel" — stop

### 0.5a — Extract keywords

Extract 3-5 keywords from the problem statement. These should be core technical
concepts, not generic words (e.g. for "Float16 serialization format" → `float16`,
`serialization`, `encoding`, `numeric`, `format`).

### 0.5b — Cross-reference search

Search for entries that reference these keywords:

1. **`.spec/domains/`** — scan requirement lines in all domain specs for keyword
   matches. Use the same keyword extraction approach as `/spec` discovery mode.
2. **`.decisions/CLAUDE.md`** — scan the master index for accepted/deferred ADRs
   whose problem statements or recommendation summaries match keywords.

If `.spec/` does not exist, skip the spec search. If `.decisions/` does not exist,
skip the decisions search. If neither exists, skip this step entirely.

### 0.5c — Surface broader scope

If cross-references reveal that the problem's key concepts appear in specs or
decisions the user did not mention, present the broader scope:

```
── Scope check ─────────────────────────────────
The stated problem is: <problem statement>

Cross-references found:
  · .spec/domains/<domain>.md: "<matching requirement text>"
    → This suggests the problem may also affect <feature/component>
  · .decisions/<slug>/adr.md: "<related decision summary>"
    → This prior decision constrains the same <concept>

Should the scope include these?
```

Use AskUserQuestion with these options:
- `Widen scope` (description: "Include the cross-referenced specs and decisions in the evaluation")
- `Proceed with original scope` (description: "Keep the narrower scope — this is a deliberate choice")

If **Widen scope**: append the additional scope to the problem statement. The widened scope
carries through to constraint collection and KB survey.

If **proceed**: note the narrower scope was a deliberate choice. Record a
`scope-narrowed` entry in `log.md` so downstream agents know the boundary was
intentional, not accidental.

**If no cross-references found:** skip silently and proceed to Step 1.

---

## Step 1 — Constraint collection gate

Check whether the invocation includes a full constraint profile across all six dimensions.
If any dimension is missing or unclear, display the collection dialogue and wait.

### Six constraint dimensions

| Dimension | What to capture | Example |
|-----------|----------------|---------|
| Scale | Data volume, request rate, growth | "10M vectors, 500 QPS, 2× growth/year" |
| Resources | Hardware, infrastructure limits | "CPU only, 16GB RAM, no GPU" |
| Complexity Budget | Team skill, operational capacity | "Small team, no ML expertise" |
| Accuracy / Correctness | Tolerance for error or approximation | "0.95 recall@10 acceptable" |
| Operational | Latency, uptime, rebuild cadence | "<50ms p99, index rebuilt nightly" |
| Fit | Language, stack, existing tooling | "Python stack, team knows NumPy" |

### Constraint collection dialogue

```
── Constraint collection ───────────────────────
To evaluate options I need your constraint profile. Please provide:

Scale              : <volume, throughput, growth — or "not specified">
Resources          : <hardware, infrastructure limits>
Complexity Budget  : <team skill level, ops capacity>
Accuracy           : <tolerance for approximation or error>
Operational        : <latency targets, uptime, rebuild frequency>
Fit                : <language, stack, existing tooling>

You can answer all at once or I will ask follow-ups.
Missing dimensions will be noted as unknowns and will reduce decision confidence.
```

### After collecting constraints

1. Create `.decisions/<problem-slug>/`
2. Write `constraints.md` using the **Constraints File Template**
3. Append the initial entry to `log.md` using the **Log Entry Template** with event `created`
4. State the top 2–3 constraints that most narrow the solution space
5. Proceed to Step 1b

---

### Step 1b — Constraint falsification

Before proceeding to KB survey, prove that the constraint profile is actually
complete. The default assumption is **constraints are missing** — the agent must
do work to demonstrate completeness, not work to find gaps.

Display: `── Constraint falsification ────────────────`

**For each constraint dimension — including those with values:**

1. Read `.spec/domains/` files that touch the problem domain's constructs
   (found at Step 0.5, or by keyword match against the problem statement)
2. Read `.decisions/` ADRs for related problems (if any were surfaced at Step 0.5)
3. Read `.kb/` category indexes for the problem domain

**For each dimension, answer one of:**

- **Found implied constraint:** The source implies a constraint the user did not
  state. Surface it: "F02's spec requires long arithmetic for offsets — this implies
  a correctness constraint on integer width. Should I add this?"
- **Confirmed complete:** "Checked <N> sources (<list>). No additional implied
  constraints found for <dimension>."

**Present findings before proceeding:**

```
── Constraint falsification ────────────────────
Checked: .spec/domains/<relevant>, .decisions/<related-slugs>, .kb/<categories>

Implied constraints found:
  · <dimension>: <source> implies <constraint description>
  · <dimension>: <source> implies <constraint description>

Confirmed complete:
  · <dimension> — checked <N> sources, no implied constraints
  · <dimension> — checked <N> sources, no implied constraints

Add the implied constraints?
```

Use AskUserQuestion with these options:
- `Add all` (description: "Add all implied constraints to the constraint profile")
- `Select individual` (description: "Choose which implied constraints to add")

If **Add all**: update `constraints.md` with the newly surfaced constraints. Append a
`## Constraint Falsification — <YYYY-MM-DD>` section to constraints.md noting what
was checked and what was added.

If **Select individual**: add only the selected constraints.

If user declines all: record that the agent checked and the user declined. The
constraint file should note: "Falsification checked <N> sources. User declined
implied constraints: <list>."

**If no implied constraints found in any source:** record that falsification was
performed and proceed:

```
── Constraint falsification ────────────────────
Checked: <N> specs, <N> ADRs, <N> KB categories
No implied constraints found beyond the user's stated profile.
Proceeding to KB survey.
```

Proceed to Step 2.

---

## Step 2 — KB survey

Display: `── Surveying KB ─────────────────────────────`

### 2a — Top-down navigation (primary path)

Read `.kb/CLAUDE.md` → relevant topic/category `CLAUDE.md` files.
Do NOT read individual subject files yet — use category indexes to identify candidates.

### 2b — Cross-topic keyword scan (discovery path)

Extract 3–5 keywords from the problem statement and constraint dimensions.
Search across ALL category-level `CLAUDE.md` files for entries whose descriptions
match these keywords. This catches tangentially related research that lives in
a different topic/category than the obvious one.

```bash
# Example: problem is "rate limiting strategy"
# Keywords: rate, limiting, throughput, capacity, request
# Scan all category indexes for matches
grep -ril "rate\|throughput\|capacity" .kb/*/CLAUDE.md .kb/*/*/CLAUDE.md
```

Read only the matching category `CLAUDE.md` files (not subject files). Extract
any entries not already found in 2a.

**Cost control:** this reads category indexes only (~10-30 lines each). Even on
a large KB (50 categories), the scan costs ~1-2K tokens — less than a single
subject file. Subject files are NOT loaded until Step 4 after the user reviews
the candidate list.

### 2c — Merge and present candidates

Combine results from 2a (direct navigation) and 2b (keyword scan). Mark the
source of each candidate:

```
── Candidates (preliminary — evaluation and falsification follow) ──
Candidates identified:
  ✓ .kb/algorithms/vector-indexing/hnsw.md
  ✓ .kb/algorithms/vector-indexing/ivf-flat.md
  ✓ .kb/infrastructure/databases/connection-pooling.md  (keyword match: "throughput")
  ✗ DiskANN — not in KB (needs research)

This list may change — falsification (Step 6) can discover missing
candidates or invalidate existing ones. Decision is at Step 7.
```

Keyword-matched candidates are shown with the matching term so the user can
quickly judge relevance.

**Neutral presentation:** list candidates without editorial commentary.
Do not indicate which candidate you expect to win, which seems "natural,"
or which is "obviously" best. Do NOT use AskUserQuestion here — this is
informational display, not a decision point. Proceed directly to Step 2d.

---

## Step 2d — Coverage gap check

After presenting candidates, assess whether the KB has **direct** coverage for
the decision at hand. "Direct" means a KB subject whose primary topic is the
problem domain — not a tangential match from keyword scanning.

**If no KB subject directly covers the decision topic** (only tangential or no
matches), display:

```
── Coverage gap ────────────────────────────────
No KB entry directly covers <decision topic>.
Available coverage is tangential:
  · <entry> — covers <what it covers>, not <what's needed>

This is a significant architectural decision. Options:
  1. research — run a targeted research session before evaluating
     (I'll suggest a topic based on the constraints)
  2. continue — evaluate with available knowledge
     (tangential KB + general industry practice)
  3. defer — park this decision until research is done separately

For decisions with long-term consequences (data formats, storage layouts,
encoding strategies), research is strongly recommended.
```

If **research**: identify the highest-value research subject for this decision,
invoke `/research "<subject>" context: "architect decision: <problem slug>"` as a sub-agent. After research
completes, re-run the KB survey (Step 2) to pick up the new entry.

If **continue**: proceed to Step 3, noting the gap in the evaluation. The
`evaluation.md` should flag which scores are based on general knowledge rather
than KB-backed evidence.

If **defer**: write a deferred ADR (same as Step 0D) with the coverage gap
noted in "What Is Known So Far." Stop.

**If the KB has direct coverage:** skip this step silently.

---

## Step 2e — Work group context (if applicable)

Check whether `.work/` exists. If it does, run:
```bash
bash .claude/scripts/work-context.sh --domains "<comma-separated domains from this decision>"
```

If the output is non-empty, read it silently and use it to inform Steps 3-5:

- **Forward compatibility:** If other work definitions in the same domains are
  planned, note which ones. The decision being made here may affect their
  specifications. Flag any potential conflicts: "This ADR constrains
  WD-03's planned interface — verify compatibility before accepting."

- **Ordering gates:** If this decision depends on an artifact that another WD
  is supposed to produce but hasn't yet, surface it: "This decision assumes
  <artifact> exists, but WD-01 has not yet produced it. Consider gating this
  decision or making the assumption explicit."

- **Multi-consumer ADRs:** If the work context shows multiple WDs depend on
  the same ADR slug, note the breadth of impact: "This decision affects N
  work definitions — changes after acceptance will require coordinated updates."

This step is silent when no work groups exist or when the decision's domains
don't overlap with any planned work.

---

## Step 3 — Commission missing research (if needed)

For any candidate not in `.kb/`, write `research-brief.md` using the **Research Brief Template**.
Then tell the user:

```
Subjects missing from KB:
  - DiskANN (algorithms/vector-indexing)

Research brief written to: .decisions/<slug>/research-brief.md

Run: /research "DiskANN" context: "architect decision: <problem slug>"
Then re-run: /architect "<problem>"

To proceed without DiskANN, confirm and I will evaluate available candidates only.
```

Append a log entry: event `research-commissioned`, listing subjects requested.

---

## Step 4 — Deep evaluation

### 4a — ADR-informed candidate ranking (when category has >8 candidates)

If any category in the candidate list has more than 8 entries, rank candidates
before loading subject files to control token cost:

1. Check `.decisions/CLAUDE.md` for accepted ADRs. For each, read the
   `candidates:` frontmatter in `evaluation.md` (not the full file — just the
   candidate list with paths and scores, typically ~10-20 lines per ADR).
2. Identify ADRs that reference entries in the same category as the current
   candidates. These are "related ADRs."
3. Build a priority ranking using the current decision's constraint profile:

   - **High priority:** entries that scored well (4-5) on constraint dimensions
     that overlap with the current decision's top constraints
   - **Normal priority:** entries with no ADR history (new/unranked — must be
     evaluated since they've never been scored)
   - **Low priority:** entries that scored poorly (1-2) across multiple related
     ADRs on dimensions that still matter for the current decision

4. Load subject files for the top 8 candidates by this ranking. Display:
   ```
   ── Candidate ranking (15 in category, loading top 8) ──
     ✓ hnsw.md           — scored 5/5 performance in <related-adr-slug>
     ✓ diskann.md         — new, unranked
     ✓ ivf-pq.md          — scored 4/5 memory in <related-adr-slug>
     ✓ ...
     · vamana.md          — scored 2/5 complexity in <related-adr-slug>
     · brute-force.md     — scored 1/5 scalability in <related-adr-slug>

   ```

   Use AskUserQuestion with these options:
   - `Load more` (description: "Load the next batch of candidates for evaluation")
   - `Proceed` (description: "Continue with the candidates already loaded")

   If **Load more**: load the next batch. If **Proceed**: continue with loaded candidates.

> **IMPORTANT: Prior scores are not evidence for current scoring.**
>
> Prior ADR scores come from different constraint profiles. They determine
> **loading order only** — which candidates to examine first. Every loaded
> candidate must be scored fresh from its KB entry against the **current**
> constraints.
>
> Do NOT cite "scored 5 in previous ADR" as evidence for any score in this
> evaluation. Do NOT carry forward prior scores as starting points. Do NOT
> let a candidate's history in other decisions bias its evaluation here.
> The KB entry is the evidence. The current constraints are the lens.

**ADR staleness signal:** if any related ADR was accepted before new KB entries
were added to the same category (entries exist that the ADR never evaluated),
flag it:
```
  ℹ ADR <slug> (accepted <date>) has not evaluated <n> newer entries:
      <entry-1>.md (added <date>)
      <entry-2>.md (added <date>)
    Consider: /decisions revisit "<slug>" after this session.
```
This is informational — it does not block the current decision.

**When category has 8 or fewer candidates:** skip ranking, load all subject
files directly (no ADR lookup needed).

### 4b — Score candidates

**IMPORTANT: This is intermediate analysis, not a decision point.** Display
a clear header before scoring begins:

```
── Evaluating candidates (analysis in progress — decision is at Step 7) ──
```

Do NOT use AskUserQuestion or pause for input during scoring. The user should
not be choosing between candidates at this stage — falsification (Step 6) may
discover missing candidates, challenge scores, or revise the recommendation.
The deliberation at Step 7 is the only point where the user selects a candidate.

For each loaded candidate:
1. Read the full subject file at `.kb/<topic>/<category>/<subject>.md`
2. Record the exact path — every score must reference it
3. Score against each constraint dimension (1–5, see scale below)
4. Identify any hard disqualifiers

### Scoring scale

| Score | Meaning |
|-------|---------|
| 5 | Excellent fit — purpose-built for this constraint |
| 4 | Good fit — meets constraint with minor caveats |
| 3 | Acceptable — meets constraint with notable tradeoffs |
| 2 | Poor fit — significant friction against this constraint |
| 1 | Disqualifying — cannot satisfy this constraint |

Weight scores by the user's stated priorities. Never override user priorities with generic defaults.

**Inline score falsification:** For every score >= 4, immediately write a
"Would be a 2 if:" line stating the specific scenario that would downgrade this
score. This goes directly into evaluation.md as a sub-row beneath the score:

```
| Resources | 3 | 5 | 15 | Runs in <2GB RAM for 1M vectors (#memory-profile) |
|           |   |   |    | **Would be a 2 if:** dataset exceeds 50M vectors and RAM stays at 16GB |
```

This makes thoroughness the path of least resistance: a score of 4 or 5 requires
both a justification AND a downgrade scenario. A score of 3 or below requires only
the justification. The agent must do more work to give a high score than a moderate
one.

Do NOT defer this to the falsification subagent at Step 6 — it happens inline
during scoring so evaluation.md captures it as primary evidence.

**Neutral scoring:** record scores factually. Do not declare a winner or
express a preference during scoring. The scores speak for themselves — the
recommendation is presented at Step 7a after all candidates (including
composites) have been scored and the user can see the full comparison matrix.
Even if one candidate dominates every dimension, the user confirms at Step 7.

### 4b2 — Identify composite candidates

After individual scoring, check whether **combinations** of candidates would
satisfy constraints better than any single candidate. This is common when:

- Two candidates have complementary strengths (one scores 5 on performance,
  another scores 5 on memory — together they cover both)
- The problem has distinct sub-problems that map to different approaches
  (e.g., hot vs cold data paths, read-heavy vs write-heavy workloads,
  different data types or access patterns)
- No single candidate scores 4+ across all high-priority constraints, but
  a pair does when each handles the sub-problem it's best suited for

**How to identify composites:**

1. For each pair of candidates where both score 3+ on at least one dimension:
   check whether their strengths are complementary (high scores on different
   constraint dimensions, or different sub-problems within the design space)
2. If a complementary pair exists, define the **composition boundary** — the
   rule that routes work to each approach (e.g., "vectors < 10K dimensions use
   approach A, vectors ≥ 10K use approach B")
3. Score the composite as a single candidate: for each constraint dimension,
   take the score from whichever component handles that sub-problem

**Present composites alongside individual candidates:**

```
── Composite candidate identified ─────────────
  <Candidate A> + <Candidate B>
  Boundary: <routing rule — e.g. "A for hot path, B for cold storage">
  Why: <A scores 5 on X but 2 on Y; B scores 2 on X but 5 on Y; together they cover both>

```

Use AskUserQuestion with these options:
- `Include composite` (description: "Add this composite to the evaluation alongside individual candidates")
- `Skip` (description: "Don't evaluate this composite — proceed with individual candidates only")

If **Include composite**: add the composite to evaluation.md as a candidate row with a
`(composite)` marker. Score each dimension using the component that handles
that sub-problem. The ADR's Decision section should describe both components
and the boundary rule.

Do NOT force composites — if a single candidate scores 4+ across all
high-priority constraints, a composite adds complexity without benefit.
Only propose composites when no individual candidate adequately covers
the full problem space.

### 4c — Assess coverage adequacy (iterate if thin)

After scoring all loaded candidates, assess whether the evaluation has enough
coverage to make a confident recommendation. Check for these signals:

1. **No candidate scores 4+ on all high-priority constraints** — the best
   option still has significant gaps
2. **Fewer than 3 viable candidates** (score 3+ across the board) — the
   evaluation lacks meaningful alternatives to compare against
3. **A constraint dimension has no strong signal in any candidate** — the KB
   may be missing an entire class of approaches
4. **The top candidate is only marginally better than rejected ones** — a
   better alternative may exist outside the current KB coverage

If any of these signals fire, **commission a targeted follow-up research pass**
before writing evaluation.md:

```
── Coverage gap detected ────────────────────────────────
The current candidates don't fully cover this problem:
  <signal — e.g. "No candidate scores above 3 on the performance constraint">

I'd like to research additional approaches before making a recommendation:
  - <subject 1> (<topic>/<category>) — <why this might help>
  - <subject 2> (<topic>/<category>) — <why this might help>

```

Use AskUserQuestion with these options:
- `Commission research` (description: "Research additional approaches before making a recommendation")
- `Proceed` (description: "Evaluate with current candidates despite the coverage gap")

If **Commission research**:
- Write `research-brief.md` (append to existing if one was written at Step 3)
- Invoke `/research "<subject>" context: "architect decision: <problem slug>"` as a sub-agent for each subject
- After research completes, re-run Step 4b (score the new candidates)
- Re-run Step 4c (check coverage again — allows multiple iterations)
- Cap at 3 research iterations total (Steps 3 + 4c combined) to prevent
  unbounded loops. After 3 iterations, proceed with what's available and
  note the gap in the CONFIDENCE section.

If "proceed": continue to Step 5 with current candidates. Note the coverage
gap in the evaluation's confidence assessment.

Append a log entry for each follow-up: event `research-commissioned`,
noting this is an iterative pass (e.g. "follow-up research after thin
initial coverage").

---

## Step 5 — Write evaluation.md

Write `.decisions/<problem-slug>/evaluation.md` using the **Evaluation File Template**.

Every score row must include:
- The KB file path it was drawn from
- A one-line note explaining the score
- The section anchor most relevant (e.g. `#complexity-analysis`, `#tradeoffs`)

---

## Step 6 — Falsification (mandatory)

After writing evaluation.md, launch a subagent to challenge the recommendation
before presenting it to the user. This step is not skippable.

**Do NOT pause for user input before falsification.** The candidate list and
scores shown during Step 4 are preliminary — falsification may discover missing
candidates, weaken scores, or change the recommendation entirely. Proceed
directly from Step 5 to Step 6 without asking the user to choose or confirm.

Display: `── Falsification (may revise candidates) ──`

### Subagent dispatch

Launch a subagent with these inputs:
- The comparison matrix from evaluation.md (all candidates, all scores)
- The constraint profile from constraints.md
- The recommended candidate (highest weighted total)
- All rejected candidates

### Subagent prompt

> You are a falsification agent. Your job is to find reasons the recommendation
> is wrong. You are not trying to be balanced — you are trying to break the
> recommendation. If it survives, the decision is stronger.
>
> **Inputs provided:**
> - Scoring matrix with all candidates and per-constraint scores
> - Constraint profile with weights and priorities
> - Recommended candidate and rejected candidates
>
> Perform these four challenges:
>
> **1. Score justification**
> For each score >= 4 on the recommended candidate:
> - Cite specific evidence from the KB entry that justifies this score.
> - What would have to be true for this score to be a 2 instead?
>
> **2. Rejection challenge**
> For the top rejected candidate (highest weighted total among rejected):
> - What scenario or constraint reweight would make this the right choice?
> - What is the strongest argument for this candidate over the recommendation?
>
> **3. Assumption exposure**
> - What assumption, if wrong, would make the recommendation the worst choice?
> - Name the single most dangerous assumption.
>
> **4. Missing candidate check**
> - Is there an approach not represented in the KB that could score better?
> - What search terms would find it?
>
> **Return format:**
> ```
> ## Challenged Scores
> <For each score >= 4: the score, evidence for, evidence against, verdict (holds/weakened)>
>
> ## Strongest Counter-Argument
> <Top rejected candidate name>
> <The scenario where it wins>
> <Why this scenario is or isn't likely given the constraints>
>
> ## Most Dangerous Assumption
> <The assumption>
> <What happens if it's wrong>
>
> ## Missing Candidates
> <Any suggestions, or "None identified">
> <Search terms if applicable>
> ```
>
> **These sections are REQUIRED. Do not rename, merge, or omit sections.**
> Every section must appear in the output even if the finding is "None identified"
> or "No weakening found." An empty section header with a placeholder is acceptable;
> a missing section is not.

### After subagent returns

1. Read the falsification results.
2. If any challenged score's verdict is "weakened" AND the weakening would
   change the weighted total ranking: re-score the affected candidates and
   re-run the comparison matrix. If the recommendation changes, mark it
   `[REVISED]` and update evaluation.md before proceeding.
3. If missing candidates are identified: offer the user the choice to research
   them (same flow as Step 4c) or proceed. Cap at the same 3-iteration
   research limit.
4. Incorporate the falsification results into the deliberation display at
   Step 7a (see Falsification Results section below).
5. Proceed to Step 7.

---

## Step 7 — Deliberation loop (REQUIRED before writing adr.md)

**Do not write `adr.md` yet.** Present the recommendation in chat first.
The ADR is only written after the user explicitly confirms.

### 7a — Present the defence summary

Display this in chat (do NOT write it to a file):

```
───────────────────────────────────────────────
🏛️  RECOMMENDATION — <problem-slug>
─────────────────────────────────────────────────────────────

Recommended approach: <Subject Name>
Rejected: <Candidate 2> (<one-word reason>), <Candidate 3> (<one-word reason>)

WHY THIS APPROACH

<3–5 plain-language sentences connecting the recommendation to the binding
constraints. No jargon. State what tradeoffs are accepted and why they are
acceptable given the constraints.>

WHY NOT THE ALTERNATIVES

<Candidate 2>: <One sentence — which constraint it fails and how.>
<Candidate 3>: <One sentence — which constraint it fails and how.>

KEY ASSUMPTIONS
These are baked in. If any are wrong, the decision may need to change:
  1. <e.g. "Team will not gain GPU access within 12 months">
  2. <e.g. "Dataset will not exceed 20M vectors in year one">
  3. <e.g. "Python remains the primary implementation language">

WHAT THIS DOES NOT SOLVE
  - <Limitation 1>
  - <Limitation 2>

FALSIFICATION RESULTS
  Challenged scores:
    <Constraint> on <Candidate>: scored <N> — <holds | weakened>
      Evidence for: <summary>
      Evidence against: <summary>
  Strongest counter-argument:
    <Top rejected candidate> would win if <scenario>.
    <Why recommendation still holds, or [REVISED] if it doesn't.>
  Most dangerous assumption:
    <The assumption and what breaks if it's wrong.>

CONFIDENCE: <High | Medium | Low>
<One sentence reason — e.g. "All six constraints specified; all candidates in KB.">
───────────────────────────────────────────────
Do you agree with this recommendation?

  • Confirm — say "yes", "confirmed", "looks good", etc.
  • Challenge — raise any concern or disagreement
  • Provide new information — share a constraint or fact I didn't have
  • Request a reweight — ask me to prioritise constraints differently
  • Override — choose a different approach; I will document the reason

I will answer questions and iterate until we reach an agreed position.
─────────────────────────────────────────────────────────────
```

### 7b — Deliberation chat rules

**If the user asks a clarifying question:**
- Answer directly with a KB source reference if applicable
- Do not re-present the full summary — answer then return to waiting
- If the question reveals a constraint gap, note it and ask for the value

**If the user flags a topic as out-of-scope, deferred, or not worth pursuing now:**

Do not let it disappear. Immediately capture it:

1. Acknowledge briefly: "Got it — I'll log that and set it aside."
2. Append a `tangent-captured` entry to `log.md`:
```markdown
## <YYYY-MM-DD> — tangent-captured

**Agent:** Architect Agent
**Event:** tangent-captured
**During:** deliberation on <current problem-slug>
**Topic:** <the tangent topic, one line>
**Disposition:** <deferred | closed>
**User's words:** "<exact phrase the user used>"
**Resume condition:** <condition stated, or "not specified">

---
```
3. If disposition is **deferred**: also create a stub `.decisions/<tangent-slug>/adr.md`
   with status `deferred` (Step 0D template) and add it to the Deferred section of
   `.decisions/CLAUDE.md`. Tell the user: "Logged as deferred — it'll appear in
   /decisions when you're ready to pick it up."
4. If disposition is **closed**: create a stub `.decisions/<tangent-slug>/adr.md`
   with status `closed` (Step 0C template) and add it to the Closed section of
   `.decisions/CLAUDE.md`. Tell the user: "Logged as closed — won't surface it again."
5. Return to deliberation without re-presenting the full summary.

**If the user challenges the recommendation:**
- Acknowledge the challenge specifically — do not defend generically
- Re-evaluate the challenged constraint against the KB evidence
- If the challenge is valid: say "You're right — this changes the scoring"
  then re-present the defence summary with `[REVISED]` in the header
- If not supported by KB evidence: explain why, cite the KB section,
  then ask if the user wants to proceed with an override

**If the user provides new information:**
- Incorporate it, restate the affected constraint, update weight if needed
- Re-evaluate candidates against the updated profile
- If recommendation changes: re-present with `[REVISED]`
- If recommendation holds: explain why it still holds

**If the user requests a different constraint weight:**
- Accept without argument — weights are the user's prerogative
- Recalculate and re-present with `[REWEIGHTED]` in the header

**If the user wants to override:**
- Do not resist — acknowledge the override
- Ask one question only: "Can you tell me the reason? I'll record it in the log."
- Accept any reason given (or none)
- Proceed to confirmation with the override noted

**Clarifying questions the Architect may ask:**
One per turn maximum. Only ask when genuinely ambiguous:
- A missing constraint materially affects scoring between top candidates
- A user challenge implies a constraint not in the profile
- An assumption in the summary is directly contested

Never re-ask questions already answered in the constraint profile.
Never ask hypothetical or open-ended future questions.

**Action proposals during deliberation:**
When the deliberation surfaces follow-up work (spec amendments, research
commissions, deferred decisions), NEVER present the choice as a prose
question ("Want me to X now, or defer to Y?"). Use AskUserQuestion with
labeled options. Prose questions do not force a pause — Claude may answer
its own question and execute without user consent.

### 7c — Confirmation and write

When the user confirms (any affirmative):

1. Say: "Decision confirmed. Writing the ADR now."

Display after writing:
```
───────────────────────────────────────────────
🏛️  ARCHITECT AGENT complete
⏱  Token estimate: ~<N>K
   Loaded: KB indexes ~3K, <n> subject files ~<N>K
   Wrote:  constraints ~1K, evaluation ~2K, adr ~3K, log entry ~1K
───────────────────────────────────────────────
Decision written: .decisions/<slug>/adr.md
```

**Check for paused feature:** scan `.feature/*/status.md` for any feature with
`substage: paused-for-research` or `substage: paused-for-architect`. If found:

```
Feature "<slug>" is paused waiting for this decision.

```

Use AskUserQuestion with these options:
- `Resume scoping` (description: "Continue the paused feature's scoping interview")
- `Stop` (description: "Leave the feature paused — resume manually later")

If **Resume scoping**: invoke `/feature "<original description>"` to resume the scoping
interview where it left off (the scoping agent reads status.md and continues
from its checkpoint).

If **Stop**: display the manual command and stop.

**If no paused feature found:** display the decision path and stop:
```
  To use this decision in a feature: /feature "<description>"
```

2. If constraints were updated during deliberation:
   - Append `## Updates YYYY-MM-DD` to `constraints.md`
3. Populate the `files:` frontmatter field in the ADR with key source files this
   decision constrains. Derive from: constraint discussion (files mentioned),
   implementation guidance (files that will implement the decision), and KB
   sources (files referenced in related research). These enable `/curate` to
   detect when code drifts from the decision.
4. Write `.decisions/<problem-slug>/adr.md` using the **ADR Template**
   - If an override occurred, add this block after the Decision section:
     ```
     > **Override note:** The scored evaluation favoured <original candidate>.
     > This ADR reflects the user's choice of <overridden candidate>.
     > Override reason: <reason provided, or "not stated">
     > See [evaluation.md](evaluation.md) for the original scoring.
     ```
4. Write the deliberation log entry to `log.md` using the **Deliberation Log Entry Template**
5. For each item listed in the "What This Decision Does NOT Solve" section
   of the ADR just written, create a deferred decision stub:
   - Slugify the concern (first ~5 words, kebab-case)
   - Check if `.decisions/<slug>/` already exists — skip if so
   - Write `.decisions/<slug>/adr.md` using the Step 0D deferred template:
     - Problem: the concern text
     - Why Deferred: "Scoped out during `<current-problem-slug>` decision.
       `<reason from the NOT Solve item>`."
     - Resume When: "When `<current-problem-slug>` implementation is stable
       and this concern becomes blocking."
     - What Is Known So Far: "Identified during architecture evaluation of
       `<current-problem-slug>`. See `.decisions/<current-slug>/adr.md` for
       the architectural context."
     - Next Step: "Run `/architect "<concern>"` when ready to evaluate."
   - Add a row to the Deferred section of `.decisions/CLAUDE.md`
   - Create a minimal `log.md` with a `deferred` event entry

   Display what was created:
   ```
   Out-of-scope items tracked as deferred decisions:
     ✓ <slug-1> — "<concern-1>"
     ✓ <slug-2> — "<concern-2>"
     ✗ <slug-3> — already exists (skipped)

   These will appear in /decisions triage.
   ```
   If zero items are in the NOT Solve section: skip this step silently.
6. Proceed to Step 8

---

## Step 8 — Update indexes and enforce context budget

1. The log entry was already written at Step 7c — do not write a second entry
2. Create or update `.decisions/<problem-slug>/CLAUDE.md` using the **Problem Index Template**
3. Update `.decisions/CLAUDE.md` master index:
   - Add the problem to the Active Decisions table
   - Check "Recently Accepted": if it exceeds 5 rows, archive the oldest row.
     Check total line count: if over 80 lines, continue archiving oldest accepted rows.
     **Archive order (crash-safe):**
     1. If `history.md` does not exist, create it from the **History File Template** first
     2. Append the row to `history.md` (write-first — survives crash)
     3. Remove the row from `CLAUDE.md`
   - Ensure `Archived: [history.md](history.md)` pointer line is present

---

## Constraints File Template

```markdown
---
problem: "<problem statement>"
slug: "<problem-slug>"
captured: "<YYYY-MM-DD>"
status: "draft"
---

# Constraint Profile — <problem-slug>

## Problem Statement
<Full restatement of what needs to be designed>

## Constraints

### Scale
<What was provided, or "not specified">

### Resources
<What was provided, or "not specified">

### Complexity Budget
<What was provided, or "not specified">

### Accuracy / Correctness
<What was provided, or "not specified">

### Operational Requirements
<What was provided, or "not specified">

### Fit
<What was provided, or "not specified">

## Key Constraints (most narrowing)
1. **<constraint>** — <why this narrows the space most>
2. **<constraint>** — <why>
3. **<constraint>** — <why>

## Unknown / Not Specified
<Dimensions not answered — note impact on decision confidence>
<If all dimensions answered: "None — full profile captured.">
```

---

## Research Brief Template

```markdown
---
problem: "<problem-slug>"
requested: "<YYYY-MM-DD>"
status: "pending"
---

# Research Brief — <problem-slug>

## Context
The Architect is evaluating options for: <problem statement>

Binding constraints for this evaluation:
- <constraint 1>
- <constraint 2>

## Subjects Needed

### <Subject Name>
- Requested path: `.kb/<topic>/<category>/<subject>.md`
- Why needed: <what gap this fills>
- Sections most important for this decision:
  - `## complexity-analysis` — needed to score Resources and Operational constraints
  - `## tradeoffs` — needed to compare against <other candidate>
  - `## practical-usage` — needed to score Complexity and Fit

## Commands to run
/research "<subject>" context: "architect decision: <problem slug>"
```

---

## Evaluation File Template

```markdown
---
problem: "<problem-slug>"
evaluated: "<YYYY-MM-DD>"
candidates:
  - path: ".kb/<topic>/<category>/<subject1>.md"
    name: "<Subject 1>"
  - path: ".kb/<topic>/<category>/<subject2>.md"
    name: "<Subject 2>"
  # Composite candidates (if identified at Step 4b2):
  # - paths: [".kb/<topic>/<category>/<subjectA>.md", ".kb/<topic>/<category>/<subjectB>.md"]
  #   name: "<Subject A> + <Subject B> (composite)"
  #   boundary: "<routing rule>"
constraint_weights:
  scale: <1-3>
  resources: <1-3>
  complexity: <1-3>
  accuracy: <1-3>
  operational: <1-3>
  fit: <1-3>
---

# Evaluation — <problem-slug>

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used: see candidate sections below

## Constraint Summary
<2–3 sentences summarising what the binding constraints demand from a solution>

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | | |
| Resources | | |
| Complexity | | |
| Accuracy | | |
| Operational | | |
| Fit | | |

---

## Candidate: <Subject 1 Name>

**KB source:** [`.kb/<topic>/<category>/<subject1>.md`](../../.kb/<topic>/<category>/<subject1>.md)
**Relevant sections read:** `#complexity-analysis`, `#tradeoffs`, `#practical-usage`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | | | | <one-line note linking to KB finding> |
| Resources | | | | |
| Complexity | | | | |
| Accuracy | | | | |
| Operational | | | | |
| Fit | | | | |
| **Total** | | | **<sum>** | |

**Hard disqualifiers:** <none, or list with KB source>

**Key strengths for this problem:**
- <strength tied to a constraint, with KB anchor>

**Key weaknesses for this problem:**
- <weakness tied to a constraint, with KB anchor>

---

## Candidate: <Subject 2 Name>

**KB source:** [`.kb/<topic>/<category>/<subject2>.md`](../../.kb/<topic>/<category>/<subject2>.md)
**Relevant sections read:** `#complexity-analysis`, `#tradeoffs`, `#practical-usage`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | | | | |
| Resources | | | | |
| Complexity | | | | |
| Accuracy | | | | |
| Operational | | | | |
| Fit | | | | |
| **Total** | | | **<sum>** | |

**Hard disqualifiers:** <none, or list>

**Key strengths for this problem:**
**Key weaknesses for this problem:**

---

## Composite Candidate (if identified at Step 4b2)

*Omit this section entirely if no composite was proposed or accepted.*

**Components:** [<Subject A>](<kb-path-A>) + [<Subject B>](<kb-path-B>)
**Boundary rule:** <what routes work to each component — e.g. "A handles hot path, B handles cold storage">

| Constraint | Weight | Component | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-----------|-------------|----------|-----------------|
| Scale | | <A or B> | | | <which component handles this, and why> |
| Resources | | <A or B> | | | |
| Complexity | | <A+B combined> | | | <note integration overhead> |
| Accuracy | | <A or B> | | | |
| Operational | | <A+B combined> | | | <note operational complexity of running both> |
| Fit | | <A+B combined> | | | |
| **Total** | | | | **<sum>** | |

**Integration cost:** <what's needed to combine them — routing logic, abstraction layer, etc.>
**When this composite is better than either alone:** <which constraint dimensions it unlocks>

---

## Comparison Matrix

| Candidate | KB Source | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| [<name>](<relative-kb-path>) | | | | | | | | |
| [<name>](<relative-kb-path>) | | | | | | | | |
| [<A + B> (composite)]() | | | | | | | | |

## Preliminary Recommendation
<Which candidate wins on weighted total, and a one-sentence plain-language reason.
If the composite wins, state both components and the boundary rule.>

## Risks and Open Questions
- <Risk 1: what assumption could be wrong>
- <Risk 2: what constraint might change>
- <Open: what KB research would change this evaluation if it existed>
```

---

## ADR Template

```markdown
---
problem: "<problem-slug>"
date: "<YYYY-MM-DD>"
version: 1
status: "confirmed"
supersedes: null
files: []
---

# ADR-<NNN> — <Problem Slug>

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| <Subject 1> | Chosen approach | [`.kb/<topic>/<category>/<subject1>.md`](../../.kb/<topic>/<category>/<subject1>.md) |
| <Subject 2> | Rejected candidate | [`.kb/<topic>/<category>/<subject2>.md`](../../.kb/<topic>/<category>/<subject2>.md) |

---

## Files Constrained by This Decision
<!-- Key source files this decision affects. Used by /curate to detect drift. -->
<!-- Populated from constraint discussion, implementation guidance, and KB sources. -->
<!-- Listed in frontmatter files: field for grep-based scanning. -->

## Problem
<1–2 sentence restatement of what needs to be designed>

## Constraints That Drove This Decision
- **<constraint 1>**: <why it was the most narrowing factor>
- **<constraint 2>**: <why>
- **<constraint 3>**: <why>

## Decision
**Chosen approach: [<Subject Name>](../../.kb/<topic>/<category>/<subject>.md)**

<2–3 sentence plain-language statement of what to build and why.
No jargon. A developer unfamiliar with the research should understand this.>

## Rationale

### Why [<chosen approach>](../../.kb/<topic>/<category>/<subject>.md)
- **<constraint>**: <how this approach satisfies it, with link to KB section if relevant>
- **<constraint>**: <same>

### Why not [<rejected candidate 1>](../../.kb/<topic>/<category>/<rejected1>.md)
- **<constraint it fails>**: <one sentence explanation>

### Why not [<rejected candidate 2>](../../.kb/<topic>/<category>/<rejected2>.md)
- **<constraint it fails>**: <one sentence explanation>

## Implementation Guidance
Key parameters from [`<subject>.md#key-parameters`](../../.kb/<topic>/<category>/<subject>.md#key-parameters):
- <parameter>: <value/range for this use case>

Known edge cases from [`<subject>.md#edge-cases-and-gotchas`](../../.kb/<topic>/<category>/<subject>.md#edge-cases-and-gotchas):
- <gotcha>

Full implementation detail: [`.kb/<topic>/<category>/<subject>.md`](../../.kb/<topic>/<category>/<subject>.md)
Code scaffold: [`<subject>.md#code-skeleton`](../../.kb/<topic>/<category>/<subject>.md#code-skeleton)

## What This Decision Does NOT Solve
- <Limitation 1 — be explicit about scope>
- <Limitation 2>

## Conditions for Revision
This ADR should be re-evaluated if:
- <Scale threshold, e.g. "data exceeds 50M vectors">
- <Team change, e.g. "GPU becomes available">
- <Research, e.g. "DiskANN is added to KB and benchmarked">
- <Time, e.g. "Review at 6-month mark regardless">

---
*Confirmed by: user deliberation | Date: <YYYY-MM-DD>*
*Full scoring: [evaluation.md](evaluation.md)*
```

---

## Deliberation Log Entry Template

Written to `log.md` at Step 7c immediately after the user confirms.

```markdown
## <YYYY-MM-DD> — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** <one sentence, e.g. "HNSW confirmed after deliberation; CPU-only constraint confirmed by user.">

### Deliberation Summary

**Rounds of deliberation:** <n>
**Recommendation presented:** <Subject Name>
**Final decision:** <Subject Name> *(same as presented | override — see below)*

**Topics raised during deliberation:**
- <Topic, e.g. "User asked about memory usage at 10M vectors">
  Response: <one sentence summary>
- <Topic, e.g. "User challenged complexity score for IVF-Flat">
  Response: <did it change the recommendation? why/why not?>

**Constraints updated during deliberation:**
- <Dimension>: <what changed — or "None">

**Assumptions explicitly confirmed by user:**
- <Assumption 1>
- <Assumption 2>

**Override:** <None | Yes — user chose <candidate> over scored winner <candidate>>
**Override reason:** <reason given, or "not stated">

**Confirmation:** User confirmed with: "<exact words used>"

**Files written after confirmation:**
- `adr.md` — decision record v<N>
- `constraints.md` — <"no changes" | "updated: <what changed>">

**KB files read during evaluation:**
- [`.kb/<topic>/<category>/<subject1>.md`](../../.kb/<topic>/<category>/<subject1>.md)
- [`.kb/<topic>/<category>/<subject2>.md`](../../.kb/<topic>/<category>/<subject2>.md)

---
```

---

## Log Entry Template (for non-deliberation events)

```markdown
## <YYYY-MM-DD> — <event-type>

**Agent:** Architect Agent
**Event:** <event-type>
**Summary:** <one sentence describing what happened>

**Files written/updated:**
- `<path>` — <what changed>

**KB files read:**
- [`.kb/<topic>/<category>/<subject>.md`](../../.kb/<topic>/<category>/<subject>.md)

---
```

### Event types

| Event type | When written |
|------------|-------------|
| `created` | Problem directory and constraints.md first written |
| `research-commissioned` | research-brief.md written, Research Agent requested |
| `research-received` | Architect re-run after Research Agent completed |
| `decision-confirmed` | User confirmed in deliberation — adr.md written immediately after |
| `revisit-requested` | /decisions revisit invoked — includes user's stated motivation |
| `revisit-confirmed` | Decision reaffirmed after revisit deliberation |
| `revision-confirmed` | New adr-v<N>.md written after deliberation confirmed revision |
| `deferred` | /decisions defer invoked — lightweight adr.md written with status deferred |
| `closed` | /decisions close invoked — lightweight adr.md written with status closed |
| `tangent-captured` | Topic raised and set aside during deliberation on another problem |
| `out-of-scope-promoted` | Out-of-scope item from accepted ADR promoted to deferred stub via /curate or auto-created at Step 7c |

---

## Problem Index Template

`.decisions/<problem-slug>/CLAUDE.md` — created or updated at Step 8.

```markdown
---
problem: "<problem-slug>"
status: "<confirmed | superseded>"
active_adr: "adr.md"
last_updated: "<YYYY-MM-DD>"
---

# <Problem Slug> — Decision Index

**Problem:** <statement>
**Status:** <confirmed | superseded>
**Current recommendation:** <one line>
**Last activity:** <YYYY-MM-DD> — <event type from log>

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | <date> |
| [evaluation.md](evaluation.md) | Candidate scoring matrix | <date> |
| [constraints.md](constraints.md) | Constraint profile | <date> |
| [research-brief.md](research-brief.md) | Research Agent commission | <date> |
| [log.md](log.md) | Full decision history + deliberation summaries | <date> |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| <Subject 1> | Chosen | [`.kb/<topic>/<category>/<subject1>.md`](../../.kb/<topic>/<category>/<subject1>.md) |
| <Subject 2> | Rejected — <reason> | [`.kb/<topic>/<category>/<subject2>.md`](../../.kb/<topic>/<category>/<subject2>.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | <date> | active | Initial recommendation |

## Tangents Captured During Deliberation
<!-- Topics that came up and were set aside. Each has its own .decisions/<slug>/ entry. -->

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
```

---

## History File Template

`.decisions/history.md` — created only when CLAUDE.md first needs to archive a row.

```markdown
# Architecture Decisions — Full History

> Append-only. Rows move here from CLAUDE.md when they age out of "Recently Accepted (last 5)".
> Load this file only when auditing historical decisions.

## Accepted Decisions

| Problem | Slug | Accepted | Recommendation | Superseded? |
|---------|------|----------|----------------|-------------|

## Superseded Decisions

| Problem | Slug | Date | Superseded By | Reason |
|---------|------|------|---------------|--------|
```

---

## Quality checklist (self-verify before ending session)

For each item below, write the brief justification indicated. Do not check boxes —
write the answer. If you cannot write a substantive answer, the item has not been met.

**Evaluation**

1. **Constraint completeness:** Which dimension has the weakest specification, and
   why is it sufficient to proceed? (If all six are strong, state which is most
   likely to change and why it won't affect the recommendation.)

2. **Binding constraints identified:** Name the top 2-3 binding constraints and
   state when in the session they were locked (before or after evaluation began).

3. **Weakest evidence link:** Which score in evaluation.md has the thinnest KB
   backing, and why is it still sufficient for the recommendation to hold?

4. **Candidate coverage:** Which KB subject read during evaluation contributed
   least to the decision, and why was it still worth loading?

5. **Hard disqualifier audit:** For each rejected candidate, state whether it was
   rejected by hard disqualifier or by weighted total. If by total only, state the
   margin.

**Falsification**

6. **Subagent completion:** State whether the falsification subagent returned all
   four required sections (Challenged Scores, Strongest Counter-Argument, Most
   Dangerous Assumption, Missing Candidates). If any section was thin, state what
   it said.

7. **Score challenge outcome:** For the highest-scored dimension on the recommended
   candidate, restate the "Would be a 2 if" scenario from evaluation.md and whether
   the falsification subagent found additional evidence for or against.

8. **Counter-argument disposition:** State the strongest counter-argument from
   falsification and why the recommendation survives it (or why it was revised).

9. **Assumption risk:** Name the most dangerous assumption and what the user
   confirmed about it during deliberation (or note it was not discussed).

**Deliberation**

10. **Sequence verified:** State that the defence summary was presented before any
    ADR was written, and whether the user's first response was a confirmation,
    challenge, or question.

11. **Constraint drift:** State whether any constraints changed during deliberation,
    and if so, whether constraints.md was updated.

12. **Override record:** State whether an override occurred. If yes, state the
    reason recorded. If no, write "No override."

**ADR**

13. **Link integrity:** State the number of KB source links in adr.md and confirm
    each points to an existing file.

14. **Scope boundary:** Quote the first item from "What This Decision Does NOT
    Solve" and confirm a deferred stub exists for it (or state why not).

15. **Revision triggers:** State the most likely revision condition and estimate
    when it might fire.

**Indexes**

16. **Master index budget:** State the current line count of `.decisions/CLAUDE.md`
    after updates, and whether any rows were archived to `history.md`.
