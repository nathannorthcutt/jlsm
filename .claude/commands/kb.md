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
The knowledge base has not been initialised. Run /setup first.
```
Stop.

---

## kb "<question>" — natural language query

Answers questions like:
  "What do we know about vector indexing?"
  "Which algorithm is best for low-memory environments?"
  "What are the tradeoffs between HNSW and IVF?"

No files are written. Read-only.

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

### Step 1 — Index scan (read indexes only, not subject files)

Read `.kb/CLAUDE.md` in full. From the Topic Map and Recently Added table,
identify candidate topics and categories that relate to the question.

For each candidate topic, read `.kb/<topic>/CLAUDE.md`.
For each candidate category, read `.kb/<topic>/<category>/CLAUDE.md` — the
category index contains a comparison summary and contents table with one-line
descriptions. This is usually enough to answer the question.

If no candidates found:
```
Nothing found in the KB matching "<question>".

KB currently contains:
  <n> topics: <list from CLAUDE.md Topic Map>

If this is a gap: /research <topic> <category> "<subject>"
```
Stop.

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

<If gaps:>
GAPS IN THE KB
  <What would need to be researched to fully answer this>
  To fill this gap: /research <topic> <category> "<subject>"
───────────────────────────────────────────────
<n> subject(s) found.  Want more detail? /kb lookup <topic> <category> <subject>
───────────────────────────────────────────────
```

**Quality rules:**
- Use the category comparison summary if it answers the question — don't load
  full subject files to repeat what the summary already says
- For "which is best" questions: give a direct recommendation, then note the runner-up
- Surface gaps when the question clearly needs something not yet documented
- Never fabricate KB content — if it's not in a file, say it's not documented

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
Use /research <name> <category> "<subject>" to add research.
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

  ↵  create  ·  or type: stop
```

**Step 3 — Create**

Create `.kb/<topic-name>/CLAUDE.md` using the Topic Index Template.
Add a row to the Topic Map in `.kb/CLAUDE.md` (ordered alphabetically).

Display:
```
───────────────────────────────────────────────
📚 KB TOPIC complete · <name>
Created : .kb/<name>/CLAUDE.md
Updated : .kb/CLAUDE.md Topic Map

Next: /research <name> <category> "<subject>"
───────────────────────────────────────────────
```

**Topic Index Template** (`.kb/<topic-name>/CLAUDE.md`):

```markdown
# <Topic Display Name> — Topic Index

> **Managed by vallorcine agents. Use slash commands to modify this file.**
> To add research: `/research <topic-name> <category> "<subject>"`

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
