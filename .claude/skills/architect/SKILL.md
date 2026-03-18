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
  - No flag → full evaluation, continue to Step 1

- Extract the problem statement
- Generate `problem-slug` in kebab-case (e.g. "vector search service" → "vector-search-service")
- Check if `.decisions/<problem-slug>/` exists
  - If yes: read `adr.md` and `log.md`, ask whether this is a revision or a new problem
  - If no: proceed to Step 1

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
5. Proceed to Step 2

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
── Candidates ──────────────────────────────────
Candidates identified:
  ✓ .kb/algorithms/vector-indexing/hnsw.md
  ✓ .kb/algorithms/vector-indexing/ivf-flat.md
  ✓ .kb/infrastructure/databases/connection-pooling.md  (keyword match: "throughput")
  ✗ DiskANN — not in KB (needs research)
```

Keyword-matched candidates are shown with the matching term so the user can
quickly judge relevance. The user can exclude any candidate before subject
files are loaded in Step 4.

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
invoke `/research <topic> <category> "<subject>"` as a sub-agent. After research
completes, re-run the KB survey (Step 2) to pick up the new entry.

If **continue**: proceed to Step 3, noting the gap in the evaluation. The
`evaluation.md` should flag which scores are based on general knowledge rather
than KB-backed evidence.

If **defer**: write a deferred ADR (same as Step 0D) with the coverage gap
noted in "What Is Known So Far." Stop.

**If the KB has direct coverage:** skip this step silently.

---

## Step 3 — Commission missing research (if needed)

For any candidate not in `.kb/`, write `research-brief.md` using the **Research Brief Template**.
Then tell the user:

```
Subjects missing from KB:
  - DiskANN (algorithms/vector-indexing)

Research brief written to: .decisions/<slug>/research-brief.md

Run: /research algorithms vector-indexing "DiskANN"
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

     Load more?  Type **yes**  ·  or: proceed with these
   ```

   If "yes": load the next batch. If proceed: continue with loaded candidates.

**ADR staleness signal:** if any related ADR was accepted before new KB entries
were added to the same category (entries exist that the ADR never evaluated),
flag it:
```
  ℹ ADR <slug> (accepted <date>) has not evaluated <n> newer entries:
      <entry-1>.md (added <date>)
      <entry-2>.md (added <date>)
    Consider: /decisions review "<slug>" after this session.
```
This is informational — it does not block the current decision.

**When category has 8 or fewer candidates:** skip ranking, load all subject
files directly (no ADR lookup needed).

### 4b — Score candidates

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

---

## Step 5 — Write evaluation.md

Write `.decisions/<problem-slug>/evaluation.md` using the **Evaluation File Template**.

Every score row must include:
- The KB file path it was drawn from
- A one-line note explaining the score
- The section anchor most relevant (e.g. `#complexity-analysis`, `#tradeoffs`)

---

## Step 6 — Deliberation loop (REQUIRED before writing adr.md)

**Do not write `adr.md` yet.** Present the recommendation in chat first.
The ADR is only written after the user explicitly confirms.

### 6a — Present the defence summary

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

### 6b — Deliberation chat rules

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

### 6c — Confirmation and write

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

  ↵  Resume scoping  ·  or type: stop
```

If Enter/yes: invoke `/feature "<original description>"` to resume the scoping
interview where it left off (the scoping agent reads status.md and continues
from its checkpoint).

If stop: display the manual command and stop.

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
5. Proceed to Step 7

---

## Step 7 — Update indexes and enforce context budget

1. The log entry was already written at Step 6c — do not write a second entry
2. Create or update `.decisions/<problem-slug>/CLAUDE.md` using the **Problem Index Template**
3. Update `.decisions/CLAUDE.md` master index:
   - Add the problem to the Active Decisions table
   - Check "Recently Accepted": if it exceeds 5 rows, move the oldest row to `history.md`
   - Check total line count: if over 80 lines, continue moving oldest accepted rows to `history.md`
   - If `history.md` does not exist, create it from the **History File Template** first
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
/research <topic> <category> "<subject>"
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

## Comparison Matrix

| Candidate | KB Source | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| [<name>](<relative-kb-path>) | | | | | | | | |
| [<name>](<relative-kb-path>) | | | | | | | | |

## Preliminary Recommendation
<Which candidate wins on weighted total, and a one-sentence plain-language reason>

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

Written to `log.md` at Step 6c immediately after the user confirms.

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
| `review-requested` | /decisions review invoked |
| `review-deliberation-confirmed` | User confirmed review outcome in deliberation |
| `review-completed` | Review concluded with no change after deliberation |
| `revision-confirmed` | New adr-v<N>.md written after deliberation confirmed revision |
| `deferred` | /decisions defer invoked — lightweight adr.md written with status deferred |
| `closed` | /decisions close invoked — lightweight adr.md written with status closed |
| `tangent-captured` | Topic raised and set aside during deliberation on another problem |

---

## Problem Index Template

`.decisions/<problem-slug>/CLAUDE.md` — created or updated at Step 7.

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

**Evaluation**
- [ ] Constraint profile complete — all six dimensions addressed or marked unknown
- [ ] Top 2–3 binding constraints named explicitly before evaluation began
- [ ] Every score in evaluation.md has a note and a KB path
- [ ] Every KB subject read is listed in evaluation.md frontmatter candidates:
- [ ] Hard disqualifiers called out explicitly, not just reflected in low scores

**Deliberation**
- [ ] Defence summary presented in chat before any ADR was written
- [ ] All user questions answered with KB references where applicable
- [ ] Constraint updates from deliberation written to constraints.md
- [ ] Revised summary re-presented with [REVISED] if recommendation changed
- [ ] Override reason recorded before proceeding if override occurred
- [ ] adr.md written only after explicit user confirmation

**ADR**
- [ ] adr.md links to evaluation.md, constraints.md, and log.md in header table
- [ ] adr.md links to every KB source (chosen and rejected) in KB Sources table
- [ ] adr.md implementation guidance links to #key-parameters and #code-skeleton
- [ ] adr.md names what the decision does NOT solve
- [ ] adr.md names conditions for revision
- [ ] Override note block present if override occurred

**Log**
- [ ] log.md has a decision-confirmed entry (Deliberation Log Entry Template)
- [ ] Deliberation log entry records rounds, topics, assumptions confirmed, override status
- [ ] Deliberation log entry lists every KB file read
- [ ] Missing KB subjects have research-brief.md written before being excluded

**Indexes**
- [ ] .decisions/<slug>/CLAUDE.md KB Sources table populated
- [ ] .decisions/<slug>/CLAUDE.md ADR Version History populated
- [ ] .decisions/CLAUDE.md master index updated, line count checked against 80-line cap
- [ ] Oldest accepted rows moved to history.md if cap exceeded

**Deferred / Closed / Tangents**
- [ ] Any deferred problem: adr.md written with status `deferred`, Deferred row in CLAUDE.md
- [ ] Any closed problem: adr.md written with status `closed`, Closed row in CLAUDE.md
- [ ] Any tangent captured during deliberation: `tangent-captured` log entry written,
      stub adr.md written, row added to parent problem's Tangents table in CLAUDE.md
