---
description: "Research a technical topic and persist findings to the knowledge base"
argument-hint: "<topic> <category> \"<subject>\""
---

# /research <topic> <category> "<subject>"

Researches a technical topic and persists findings to .kb/<topic>/<category>/.

---

## Pre-flight guard

Before anything else, check that .kb/CLAUDE.md exists.
If it does not exist, stop and say:
  "The knowledge base has not been initialised. Run /setup-vallorcine first, then retry."

---

## Step 0 — Clarification gate (ALWAYS FIRST)

Display opening header first:
```
───────────────────────────────────────────────
🔬 RESEARCH AGENT
───────────────────────────────────────────────
```

Parse the invocation for topic and category.

- If BOTH are provided (e.g. `/research algorithms vector-indexing "HNSW"`): proceed to Step 1
- If EITHER is missing: display the clarification template below and wait for the user

Clarification template:
```
── Clarification needed ────────────────────────
Before I start researching, I need to confirm where to place the findings.

Topic    : <value if provided, or "not specified">
Category : <value if provided, or "not specified">

Topics are broad domains (e.g. algorithms, ml, systems, research, hardware).
Categories are focused clusters within a topic (e.g. vector-indexing, transformers, caching).

Path will be: .kb/<topic>/<category>/<subject>.md

Please confirm or provide the missing values.
If unsure, suggest a topic and category and I will validate against existing entries.
```

After the user responds: echo confirmed values, then proceed.

### Topic resolution (ALWAYS read .kb/CLAUDE.md first)

1. Read `.kb/CLAUDE.md` — the Topic Map table is the authoritative list of
   approved topics. Any topic that already has a row there is valid.

2. If the user's topic matches an existing row: proceed.

3. If the topic does not exist in .kb/CLAUDE.md:
   - If `.kb/CLAUDE.md` Topic Map is empty (new project): suggest from the
     default list below and offer to create it
   - If the Topic Map has entries but this topic isn't there: tell the user
     the topic doesn't exist yet and offer to create it:

```
── New topic needed ─────────────────────────────
"<topic>" is not in the knowledge base yet.

Existing topics: <list from .kb/CLAUDE.md Topic Map>

To add it: /kb topic "<topic>" "<one-line description>"
  Type **yes**  to create the topic now  ·  or: manual
```

   If "yes": invoke `/kb topic "<topic>" "<description>"` as a sub-agent,
   wait for it to complete, then proceed with the research session.
   If "manual": stop and let the user run it manually.

4. Never add a topic row to .kb/CLAUDE.md directly from /research —
   that is /kb topic's job.

### Default topic suggestions (for empty knowledge bases only)

| Topic | Covers |
|-------|--------|
| `algorithms` | Algorithm designs, complexity analysis, pseudocode |
| `ml` | Machine learning methods, architectures, training techniques |
| `systems` | Infrastructure, databases, distributed systems, caching |
| `research` | Academic papers, survey summaries, open problems |
| `hardware` | GPU/CPU architecture, memory systems, accelerators |

Users may define any topic name — these are starting suggestions, not an
exhaustive list.

---

## Step 1 — Pre-flight index check

1. Read `.kb/CLAUDE.md` — check if topic exists in the domain map
2. Read `.kb/<topic>/CLAUDE.md` — check if category exists (if topic file exists)
3. Read `.kb/<topic>/<category>/CLAUDE.md` — check what subjects already exist (if category exists)

Display:
```
── Pre-flight check ─────────────────────────────
```
Report to user:
- What already exists in this topic/category
- What research gaps are noted in the category index
- What you plan to research

If category already has content, display:
```
  Type **yes**  to proceed anyway  ·  or: stop
```

---

## Step 2 — Web research

Display: `── Researching ──────────────────────────────`

For each subject:
1. Search: `<subject> algorithm site:arxiv.org OR site:github.com OR site:wikipedia.org`
2. Search: `<subject> implementation pseudocode complexity analysis`
3. Search: `<subject> benchmark <current_year - 1> OR <current_year>`
4. Fetch full content from top 3–5 authoritative sources
5. Record every URL with title and access date — these go in the sources frontmatter

### Fetch discipline

Fetches can hang indefinitely on slow or unresponsive sources. Rules:
- **Never block on a single fetch.** If a fetch hasn't returned within ~30
  seconds, move on. The research can proceed with the sources that did respond.
- **Prefer smaller pages.** Arxiv HTML versions, GitHub wiki pages, and
  documentation sites are usually fast. Avoid fetching large PDFs, full
  repository archives, or pages that require JavaScript rendering.
- **3 sources is enough.** Don't fetch 5 sources if 3 gave you what you need.
  Each additional fetch is a timeout risk for diminishing return.
- **If a fetch fails or times out:** note the URL in the subject file's sources
  as `(not fetched — timeout/error)` so future research knows to try again or
  use a different source. Do not retry in the same session.

---

## Step 3 — Write subject files

Display: `── Writing KB entries ───────────────────────`

- Path: `.kb/<topic>/<category>/<subject>.md`
- Filename: kebab-case of the subject name (e.g. `hnsw.md`, `ivf-flat.md`)
- Keep under 200 lines; if longer, extract to `<subject>-detail.md` and add `@./<subject>-detail.md` at the bottom
- If file already exists: append `## Updates YYYY-MM-DD` section — NEVER overwrite
- Populate `applies_to:` frontmatter with source file paths this research is
  relevant to (if known from the research context, commissioning ADR, or feature
  work). Leave empty if no specific files are known — `/curate` will help
  populate this over time as correlations are discovered.

Use the Subject File Template below.

---

## Step 4 — Update CLAUDE.md indexes (bottom-up, always in this order)

**1. Category CLAUDE.md** — `.kb/<topic>/<category>/CLAUDE.md`
- Add new subjects to the Contents table
- Update comparison summary if 2+ subjects now exist in this category
- Update research gaps list
- Update last_updated and file count

**2. Topic CLAUDE.md** — `.kb/<topic>/CLAUDE.md`
- Add category row if new, or update file count and last_updated for existing
- Update topic-level last_updated

**3. KB Root CLAUDE.md** — `.kb/CLAUDE.md`
- Add new topic row if this is a new topic
- Update file count and date for existing topic row
- Add entries to Recently Added table (most recent first)
- Cap enforcement: if Recently Added exceeds 10 rows, move oldest rows to
  `.kb/_archive.md` (create if needed) with pointer: `Older entries: [_archive.md](_archive.md)`
- Hard cap: `.kb/CLAUDE.md` must stay under 80 lines at all times

---

## Subject File Template

```markdown
---
title: "<Full Name of Algorithm/Concept>"
aliases: ["<shorthand>", "<alternate name>"]
topic: "<topic>"
category: "<category>"
tags: ["<tag1>", "<tag2>"]
complexity:
  time_build: "<e.g. O(n log n)>"
  time_query: "<e.g. O(log n)>"
  space: "<e.g. O(n * M)>"
research_status: "<active | mature | stable | deprecated>"
last_researched: "<YYYY-MM-DD>"
applies_to: []
sources:
  - url: "<URL>"
    title: "<title>"
    accessed: "<YYYY-MM-DD>"
    type: "<paper | docs | blog | repo | benchmark>"
---

# <Full Name>

## summary
<!-- 2–4 sentences: what it is, what problem it solves, when to use it -->
<!-- Make this self-contained — agents doing a quick lookup read this section first -->

## how-it-works
<!-- Plain language first, precise detail second -->
<!-- ASCII or Mermaid diagrams encouraged for structure-heavy concepts -->

### key-parameters
| Parameter | Description | Typical Range | Impact on Accuracy/Speed |
|-----------|-------------|---------------|--------------------------|

## algorithm-steps
<!-- Numbered pseudocode-level steps. Sufficient for a coding agent to implement. -->
1. **Step**: Description
2. ...

## implementation-notes

### data-structure-requirements

### edge-cases-and-gotchas

## complexity-analysis

### build-phase

### query-phase

### memory-footprint

## tradeoffs

### strengths

### weaknesses

### compared-to-alternatives
<!-- Bullets with relative links to sibling subject files -->

## current-research

### key-papers
<!-- APA citations with DOI/URL -->

### active-research-directions

## practical-usage

### when-to-use

### when-not-to-use

## reference-implementations
| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|

## code-skeleton
```python
class SubjectName:
    def __init__(self, params): ...
    def build(self, data: list[list[float]]) -> None: ...
    def query(self, vector: list[float], k: int) -> list[int]: ...
```

## sources
1. [Title](URL) — annotation: what it covers and why it is authoritative

---
*Researched: <YYYY-MM-DD> | Next review: <YYYY-MM-DD + 180 days>*
```

---

## Category CLAUDE.md Template

Created when the first subject in a category is written.

```markdown
# <Category> — Category Index
*Topic: <topic>*

<1 paragraph describing this category and why it matters>

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [subject.md](subject.md) | Subject Name | mature | O(log n) query | Use case |

## Comparison Summary
<!-- Narrative comparison — write once 2+ subjects exist -->

## Recommended Reading Order
1. Start: [subject.md](subject.md) — foundational concept
2. Then: ...

## Research Gaps
- <subject not yet documented>

## Shared References Used
@../../_refs/complexity-notation.md
```

---

## Staleness policy

| research_status | Review cadence |
|-----------------|----------------|
| active | 3 months |
| mature | 6 months |
| stable | 12 months |
| deprecated | No review — append final note naming superseding subject |

---

## Update rule (existing files)

Never overwrite. Append this block:

```markdown
## Updates YYYY-MM-DD

### What changed
Brief description of what new information was added.

### New sources
1. [Title](URL) — annotation

### Corrections
Any prior errors found and corrected, with explanation.
```

---

## Closing report

Display after all indexes are updated:
```
───────────────────────────────────────────────
🔬 RESEARCH AGENT complete
⏱  Token estimate: ~<N>K
   Loaded: KB indexes ~3K, <n> source pages fetched
   Wrote:  <n> subject files, <n> CLAUDE.md indexes updated
───────────────────────────────────────────────
Wrote: .kb/<topic>/<category>/<subject>.md
Updated: category index, topic index, KB root index
```

To query what's in the KB later: `/kb "<question>"`

---

## Quality checklist (self-verify before ending session)

- [ ] Clarification gate fired and resolved — both topic and category confirmed
- [ ] All subject files are at .kb/<topic>/<category>/<subject>.md
- [ ] Every subject file has topic and category in frontmatter
- [ ] Every subject file has sources frontmatter with URLs and accessed dates
- [ ] Every ## section heading is lowercase and hyphenated
- [ ] code-skeleton section contains runnable pseudocode
- [ ] No subject file exceeds 200 lines
- [ ] Category CLAUDE.md contents table updated
- [ ] Topic CLAUDE.md category row updated
- [ ] .kb/CLAUDE.md Recently Added table updated, line count under 80
- [ ] No existing file was overwritten
