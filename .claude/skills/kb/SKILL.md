---
description: "Single entry point for all knowledge base operations"
argument-hint: "[subcommand] [arguments]"
---

# /kb [subcommand] [arguments]

Single entry point for all knowledge base operations.

## Subcommands

| Invocation | What it does |
|------------|-------------|
| `/kb "<question>"` | Query the KB in plain language |
| `/kb lookup <topic> <category> <subject>` | Load a specific subject into context |
| `/kb topic "<name>" "<description>"` | Create a new topic |

**Default (no subcommand):** if the first argument looks like a question or
keyword rather than a subcommand name, treat it as `/kb "<question>"`.

---

## Pre-flight guard (all subcommands)

Check that `.kb/CLAUDE.md` exists. If not:
```
The knowledge base has not been initialised. Run /setup-vallorcine first.
```
Stop.

---

## kb "<question>" — natural language query

Answers questions like:
  "What do we know about vector indexing?"
  "Which algorithm is best for low-memory environments?"
  "What are the tradeoffs between HNSW and IVF?"

Read-only unless the user opts into inline research for gaps or stale entries.

Display opening header:
```
───────────────────────────────────────────────
📚 KB QUERY · "<question>"
───────────────────────────────────────────────
```

If no question was provided:
```
What would you like to know? Ask in plain language — e.g.
"what do we know about vector indexing?" or "which caching strategy
is best for low-latency reads?"
```
Wait for input, then proceed.

### Step 0 — Read staleness threshold

Read `.feature/project-config.md` if it exists. Look for:
```
**KB staleness threshold (days):** `<number>`
```
Default to 90 days if not found or no project-config exists.

### Step 1 — Index scan (read indexes only, not subject files)

#### 1a — Top-down navigation (primary path)

Read `.kb/CLAUDE.md` in full. From the Topic Map and Recently Added table,
identify candidate topics and categories that relate to the question.

For each candidate topic, read `.kb/<topic>/CLAUDE.md`.
For each candidate category, read `.kb/<topic>/<category>/CLAUDE.md` — the
category index contains a comparison summary and contents table with one-line
descriptions. This is usually enough to answer the question.

#### 1b — Cross-topic keyword scan (discovery path)

Run: `bash .claude/scripts/kb-search.sh "<question>" --kb-root .kb --top 15`

Parse the scored output. Treat results with score above 0.3 as keyword
candidates. The LLM uses this ranked list to determine which categories to
inspect — it does not need to scan raw index files. Mark each candidate with
its score so the relevance gate (Step 1c) has a signal to work with.

If `kb-search.sh` is not available (output is empty or script missing): fall
back to the previous approach — extract 3–5 keywords from the question, search
across ALL category-level `CLAUDE.md` files for entries whose descriptions
match these keywords. Read only the matching category `CLAUDE.md` files (not
subject files). Merge any new candidates not already found in 1a.

**Cost control:** kb-search.sh reads only category CLAUDE.md files (Phase 1)
plus subject frontmatter for top candidates (Phase 2). The LLM receives a
ranked list — typically 10–20 lines — instead of raw index content.

#### 1c — Relevance gate (prune before deep reads)

After 1a and 1b, you have a set of candidate categories. Before proceeding,
assess whether each candidate is relevant to the *specific question*, not just
the general topic area.

For each candidate category, you have its one-paragraph description and
contents table from the category CLAUDE.md. Ask: "Given the specific
question, would reading entries from this category help answer it?"

- If a candidate category is clearly irrelevant to the specific question,
  drop it. Example: question is "how does encryption at rest work" — drop
  a "key-cache-concurrency" category even if it matched on "encryption."
- If unsure, keep it — false negatives are worse than false positives at
  this stage.

**Result set sanity check:**
- If 0 candidates remain after pruning: broaden — re-run 1b with different
  keywords, or note a KB gap.
- If 10+ candidates remain: the question may be too broad. Note the count
  to the user and ask if they want to narrow the scope, or proceed with
  the top candidates (those from 1a take priority over 1b keyword matches).

This gate is a single judgment step, not a loop. It uses only the index
content already loaded — no new file reads.

#### Staleness check

For each candidate from 1a and 1b, note the `Last Updated` date from
the index tables. If any candidate's last update is older than the staleness
threshold, mark it as stale. Also check `last_researched` frontmatter in
subject files when loaded in Step 2.

If no candidates found, skip to Step 4 (offer research).

### Step 2 — Deep read (only when indexes leave the question unanswered)

Load full subject files only when:
- The question asks for implementation detail (parameters, code skeleton, edge cases)
- The category comparison summary exists but the question requires per-subject depth
- The user asked for a direct comparison between two named subjects

When loading subject files, read only the relevant section:
- "What is X?" → `## summary` only
- "How does X work?" → `## summary` + `## how-it-works`
- "When should I use X?" → `## practical-usage` only
- "What are the tradeoffs?" → `## tradeoffs` only
- "How do I implement X?" → `## algorithm-steps` + `## code-skeleton`
- "What are the parameters?" → `### key-parameters` only
- "How does X compare to Y?" → `## tradeoffs` from both subjects

#### Following `related` links (depth-1 only)

When a loaded subject file has a `related:` frontmatter field with entries,
these are cross-topic links to other KB entries. Follow them at **depth 1
only** — read the linked entry's `## summary` section to assess relevance
to the current question. Do NOT follow the linked entry's own `related`
links (that would be depth 2+).

Only include a related entry in the answer if its summary is directly
relevant to the question being asked. A related link is a hint, not a
mandate to read — most related entries won't be relevant to the specific
question.

### Step 3 — Display the answer

```
<Direct answer in 2–5 sentences. Lead with the finding.
For comparison questions, lead with the recommendation then the reasoning.>

<If a specific subject is the answer:>
BEST MATCH
<topic>/<category>/<subject>
  <2–3 sentence summary>
  <If relevant:> Key parameters: <most important 1–2>
  <If relevant:> Watch out for: <most relevant gotcha>
  Full entry: .kb/<topic>/<category>/<subject>.md

<If multiple subjects:>
RELATED SUBJECTS
<topic>/<category>/<subject-1> — <one-line summary> · Best for: <use case>
<topic>/<category>/<subject-2> — <one-line summary> · Best for: <use case>

<If a comparison:>
COMPARISON SUMMARY
<Which wins for what constraint. One bullet per axis that matters.>

<If relevant ADRs used this KB content:>
DECISIONS USING THIS RESEARCH
  <slug> — <one-line outcome>  (.decisions/<slug>/adr.md)

<If any entries are stale:>
⚠ STALE ENTRIES
  <topic>/<category>/<subject> — last researched <date> (<N> days ago)
  This may not reflect current best practices or recent developments.

<If gaps:>
GAPS IN THE KB
  <What would need to be researched to fully answer this>
───────────────────────────────────────────────
<n> subject(s) found.  Want more detail? /kb lookup <topic> <category> <subject>
───────────────────────────────────────────────
```

If there are stale entries or gaps, proceed to Step 4.
If not, stop here.

**Quality rules:**
- Use the category comparison summary if it answers the question — don't load
  full subject files to repeat what the summary already says
- For "which is best" questions: give a direct recommendation, then note the runner-up
- Surface gaps when the question clearly needs something not yet documented
- Never fabricate KB content — if it's not in a file, say it's not documented

### Step 4 — Offer inline research (only when gaps or stale entries exist)

When no candidates were found:
```
Nothing found in the KB matching "<question>".

KB currently contains:
  <n> topics: <list from CLAUDE.md Topic Map>

I can research this now and add it to the KB.
```
Use AskUserQuestion with options:
  - "Start research"
  - "Stop"

When stale entries were found:
```
Some entries used to answer this question may be outdated.
I can refresh the research and update the KB.
```
Use AskUserQuestion with options:
  - "Refresh stale entries"
  - "Use current data"

When gaps were identified alongside valid results:
```
I answered from what's available, but the KB has gaps that could
improve the answer:
  <gap description>

I can research the gaps now and update the KB.
```
Use AskUserQuestion with options:
  - "Research gaps"
  - "Skip"

**If the user chooses to research:**

For each gap or stale entry, invoke `/research "<subject>" context: "kb query gap: <question>"` as a sub-agent. Wait for it to complete.

After all research completes, re-answer the original question using the
freshly written KB entries. Display:
```
───────────────────────────────────────────────
📚 KB QUERY updated · "<question>"
───────────────────────────────────────────────
<Updated answer incorporating new research>
───────────────────────────────────────────────
```

**If the user skips:** stop. The answer from Step 3 stands.

---

## kb lookup <topic> <category> <subject> — exact retrieval

Loads a specific knowledge base entry into context.

Display opening header:
```
───────────────────────────────────────────────
📚 KB LOOKUP · <topic>/<category>/<subject>
───────────────────────────────────────────────
```

**Argument handling:**
- All three provided → proceed directly
- Missing topic or category → ask: "Please provide topic, category, and subject."
- Missing subject only → read the category CLAUDE.md, list available subjects,
  ask which to load

**Steps:**
1. Read `.kb/<topic>/<category>/CLAUDE.md` — confirm subject exists
2. Read `.kb/<topic>/<category>/<subject>.md` — full subject content
3. Summarise: lead with `## summary` in plain language
4. Surface `### key-parameters` and `## code-skeleton` if task involves implementation
5. Do not load sibling files unless explicitly asked
6. If subject not found: report what exists in that category and suggest alternatives

Subject files may end with `@./<subject>-detail.md` — load the detail file too
if the task requires deep implementation knowledge.

---

## kb topic "<name>" "<description>" — create a new topic

Adds a topic to the knowledge base. Idempotent — reports and stops if it exists.

Display opening header:
```
───────────────────────────────────────────────
📚 KB TOPIC · <topic-name>
───────────────────────────────────────────────
```

**Argument handling:**
- Normalise topic name to lowercase kebab-case: "Machine Learning" → `ml`
- If either argument is missing:
  ```
  Topic name  : <value or "not provided">
  Description : <value or "not provided">
  Topic names should be short, lowercase, kebab-case (e.g. algorithms, ml, systems).
  ```

**Step 1 — Check for existing topic**

Read `.kb/CLAUDE.md`. If the topic already exists:
```
Topic '<name>' already exists.
Path: .kb/<name>/
Use /research "<subject>" to add research.
Use /kb lookup <name> <category> <subject> to retrieve entries.
```
Stop.

**Step 2 — Confirm**

```
── New topic ─────────────────────────────────
Topic       : <name>
Description : <description>
Path        : .kb/<name>/
Existing topics: <list or "none yet">

```
Use AskUserQuestion with options:
  - "Create"
  - "Stop"

**Step 3 — Create**

Create `.kb/<topic-name>/CLAUDE.md` using the Topic Index Template.
Add a row to the Topic Map in `.kb/CLAUDE.md` (ordered alphabetically).

Display:
```
───────────────────────────────────────────────
📚 KB TOPIC complete · <name>
Created : .kb/<name>/CLAUDE.md
Updated : .kb/CLAUDE.md Topic Map

Next: /research "<subject>"
───────────────────────────────────────────────
```

**Topic Index Template** (`.kb/<topic-name>/CLAUDE.md`):

```markdown
# <Topic Display Name> — Topic Index

> **Managed by vallorcine agents. Use slash commands to modify this file.**
> To add research: `/research "<subject>"`

<description>

## Categories

| Category | Path | Files | Last Updated | Description |
|----------|------|-------|--------------|-------------|

## Navigation
Read the category CLAUDE.md to see individual subjects and comparisons.
Use /kb lookup <topic-name> <category> <subject> to load a specific entry.

## Research Gaps
<!-- Added by the Research Agent as categories are populated -->
```
