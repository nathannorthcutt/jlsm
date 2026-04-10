---
description: "Research a technical topic and persist findings to the knowledge base"
argument-hint: "\"<subject>\" [context: \"<hint>\"]"
---

# /research "<subject>"

Researches a subject and persists findings to `.kb/<topic>/<category>/`. The
agent determines placement by scanning existing KB content and reasoning about
domain fit — the caller does not pre-decide topic or category.

For cross-cutting subjects that span multiple domains, the agent identifies
independent **facets** and writes a focused article for each at the appropriate
location. The user confirms the facet plan before anything is written.

---

## Pre-flight guard

Before anything else, check that .kb/CLAUDE.md exists.
If it does not exist, stop and say:
  "The knowledge base has not been initialised. Run /setup-vallorcine first, then retry."

---

## Step 0 — Parse subject and context

Display opening header:
```
───────────────────────────────────────────────
🔬 RESEARCH AGENT
───────────────────────────────────────────────
```

Parse the invocation:

- **Required:** `<subject>` — the research question or topic to investigate.
  This is a free-text description, not a path. Examples:
  - `"HNSW graph construction"`
  - `"join queries across partitioned tables with multiple index types"`
  - `"Panama FFM inline machine code"`

- **Optional:** `context: "<hint>"` — free-text domain context passed by
  callers (features, architects, retros). Used as an input signal during facet
  identification, but never treated as a placement instruction. Examples:
  - `context: "feature-domains for json-serialization, domain: storage"`
  - `context: "architect decision: query-optimizer"`
  - `context: "feature-retro footprint for F08. Suggested: architecture/feature-footprints"`

If subject is missing: ask for it.

Display confirmed subject and any provided context, then proceed.

---

## Step 1 — Preliminary web research

Display: `── Preliminary research ─────────────────────`

**Goal:** Understand what this subject actually is and what domains it touches.
This happens BEFORE any KB scan or placement decisions.

Perform 2–3 targeted searches:
1. `<subject> overview site:arxiv.org OR site:github.com OR site:wikipedia.org`
2. `<subject> use cases tradeoffs implementation`
3. If context hint provided: `<subject> <domain keyword from context>`

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

From the research, extract working notes (not the final article):
- What domains/concerns does this subject touch?
- What distinct audiences would benefit from this research?
- What are the main trade-off dimensions?
- What sources were found, with URLs and access dates?

---

## Step 2 — KB scan

Display: `── Scanning existing KB ─────────────────────`

Run:
```bash
bash .claude/scripts/kb-search.sh "<subject>" --kb-root .kb --top 15
```

Parse the scored output. For each result with a non-trivial score:
- Read the subject file's frontmatter (title, aliases, tags, related)
- Note which categories have existing related content

Display:
```
── KB scan results ───────────────────────────────
  <score>  <topic>/<category>/<subject>  — <title>
  <score>  <topic>/<category>/<subject>  — <title>
  ...
  (<n> results)
```

If `kb-search.sh` is not available (script missing, no runtime): fall back to
reading `.kb/CLAUDE.md` root index and scanning category CLAUDE.md files
manually for keyword overlap with the subject.

---

## Step 3 — Facet identification

Display: `── Identifying facets ──────────────────────`

Combine three inputs:
- What the preliminary web research found (Step 1 working notes)
- What KB scan shows already exists (Step 2 results)
- The caller's context hint, if provided

### Default assumption: 1 facet

Assume the subject is a single facet unless there is a clear reason to split.
A facet is justified ONLY when ALL of these conditions are met:

1. The facet addresses a **distinct concern** (not just a different section of the same article)
2. The facet serves a **different audience** (someone navigating to topic A would not look in topic B)
3. The facet has **unique actionable content** — different algorithm steps, different tradeoffs, different implementation notes. Shared summary is fine; shared everything-else means it's not a real split.
4. The content **cannot live in the same article** without confusing the reader or diluting the focus.

**Invalid splits** (do NOT create separate facets for these):
- "HNSW construction" and "HNSW querying" → same algorithm, same audience, one article with two sections
- The same content reframed for a slightly different angle → one article covers both angles

**Valid splits:**
- "BM25 ranking" → `algorithms/information-retrieval` (the scoring function) AND `systems/search-infrastructure` (operational search service concerns)
- "Lock-free queues" → `algorithms/concurrency` (the algorithm design) AND `systems/messaging` (deployment in message-passing systems)

### Justification required for each additional facet

For facet 2 and beyond, state:
- What concern this facet addresses
- What audience it serves
- Why this content cannot live in the same article as the prior facets
- What unique actionable sections it will contain

### Existing coverage check

If a KB entry already fully covers a proposed facet (discovered in Step 2),
skip that facet. Note it in the display so the user knows it's already covered.

### Placement determination

For each facet, suggest:
- **Topic** — broad domain (e.g. `algorithms`, `systems`, `ml`). Check
  `.kb/CLAUDE.md` Topic Map for existing topics. Prefer existing topics.
- **Category** — focused cluster within the topic (e.g. `vector-indexing`,
  `partitioning`). Check `.kb/<topic>/CLAUDE.md` for existing categories.
  Prefer existing categories when the fit is good.
- **Filename** — kebab-case of the facet's core concept

If a topic or category doesn't exist yet, note that it will be created. New
topics need a one-line description.

### Cap

Maximum 10 facets per research session. If analysis suggests more than 10,
consolidate related concerns or flag that the subject is too broad and ask
the user to narrow it.

---

## Step 4 — User confirmation of facet plan

Display the facet plan:
```
── Facet plan ──────────────────────────────────
Subject: "<subject>"

Proposed facets (<N>):

  [1] <Facet title>
      Path: .kb/<topic>/<category>/<filename>.md
      Audience: <who benefits from this article>
      Distinct because: <one-line justification>

  [2] <Facet title>
      Path: .kb/<topic>/<category>/<filename>.md
      Audience: <who benefits>
      Distinct because: <justification>

  [Already covered — no new article]
      <topic>/<category>/<subject> already covers <concern>.

Related KB entries that will be cross-linked:
  <topic>/<category>/<subject> — <title>
  ...
───────────────────────────────────────────────
```

Use AskUserQuestion with these options:
- `Confirm` (description: "Proceed with this facet plan")
- `Modify` (description: "I want to change placements, add, or remove facets")
- `Stop` (description: "Cancel research session")

If "Modify": ask the user what to change, update the plan, and re-display.
Continue until the user confirms or stops.

If "Stop": end the session.

---

## Step 5 — Targeted research pass (per facet, if needed)

For each confirmed facet, assess whether the preliminary research from Step 1
provides enough depth to write a full, useful article.

If a facet needs more depth:
- Perform one additional targeted web search: `<subject> <facet-specific angle>`
- Fetch 1–2 additional authoritative sources specific to this facet
- Same fetch discipline as Step 1

**This is the second and final research pass.** No further web research loops.
Write the article with what you have — note gaps in the Research Gaps section
of the category index for future research sessions.

---

## Step 6 — Write subject files

Display: `── Writing KB entries ───────────────────────`

Write one full article per confirmed facet.

- Path: `.kb/<topic>/<category>/<filename>.md`
- Use the Subject File Template below
- Keep under 200 lines; extract overflow to `<subject>-detail.md` with
  `@./<subject>-detail.md` at the bottom
- If file already exists: append `## Updates YYYY-MM-DD` section — NEVER overwrite
- Populate `applies_to:` from the context hint if it implies specific files
- Populate `decision_refs:` from the context hint if it references an ADR
- Populate `related:` — see cross-linking rules below

### Cross-linking rules

1. **All new articles link to each other.** Every article written in this
   session must include the other new articles in its `related:` list.

2. **Update existing entries.** For each existing KB entry identified as
   related in Step 2 (KB scan results that the user confirmed in the facet
   plan), read that entry and append the new article's path to its `related:`
   list. Use the update rule — never overwrite, only append.

3. **Only add links you can verify exist.** Check that the target file is
   present before adding a related link.

### Topic and category creation

If a facet requires a new topic or category:
- **New topic:** Create `.kb/<topic>/CLAUDE.md` using the Topic Index Template
  (defined below). Add a row to `.kb/CLAUDE.md` Topic Map.
- **New category:** Create `.kb/<topic>/<category>/CLAUDE.md` using the
  Category CLAUDE.md Template (defined below). Add a row to the topic's
  CLAUDE.md.

---

## Step 7 — Update CLAUDE.md indexes (bottom-up, always in this order)

**1. Category CLAUDE.md** — `.kb/<topic>/<category>/CLAUDE.md`
- Add new subjects to the Contents table
- Update comparison summary if 2+ subjects now exist in this category
- Update research gaps list
- Update last_updated and file count
- Update `Tags:` line if new keywords are relevant

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

## Step 8 — Closing report

Display after all indexes are updated:
```
───────────────────────────────────────────────
🔬 RESEARCH AGENT complete
───────────────────────────────────────────────
Facets written: <n>
  .kb/<topic1>/<cat1>/<subject1>.md
  .kb/<topic2>/<cat2>/<subject2>.md
  ...
Cross-links: <n> existing entries updated
Updated: <n> CLAUDE.md indexes
───────────────────────────────────────────────
```

To query what's in the KB later: `/kb "<question>"`

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
confidence: "<high | medium | low>"
last_researched: "<YYYY-MM-DD>"
applies_to: []
related: []
decision_refs: []
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

### Confidence field guidance

Set `confidence` based on the strength of the sources backing the entry's claims:

- **high** — claims backed by peer-reviewed papers, official documentation, or verified benchmarks
- **medium** — claims from reputable blog posts, conference talks, or the model's training knowledge that aligns with multiple sources
- **low** — claims from a single unverified source, the model's general knowledge without corroboration, or extrapolations

When updating an existing entry, reassess confidence if new sources materially
change the evidence base. Confidence can go up (new paper corroborates a blog
claim) or down (a cited benchmark turns out to be synthetic).

---

## Category CLAUDE.md Template

Created when the first subject in a category is written. The `Tags:` line
lists keywords that kb-search.sh uses for discovery. Include synonyms,
abbreviations, and domain-specific terms that someone searching for this
category's content might use. Example: a compression category might have
`Tags: lz4, zstd, snappy, entropy, codec, deflate, block-compression`.

```markdown
# <Category> — Category Index
*Topic: <topic>*
*Tags: <keyword1>, <keyword2>, <keyword3>, ...*

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

## Topic Index Template

Created when a new topic is needed for a facet. Used by Step 6 when creating
topics that don't exist yet.

```markdown
# <Topic Display Name> — Topic Index

> **Managed by vallorcine agents. Use slash commands to modify this file.**
> To add research: `/research "<subject>"`

<one-line description of what this topic covers>

## Categories

| Category | Path | Files | Last Updated | Description |
|----------|------|-------|--------------|-------------|

## Navigation
Read the category CLAUDE.md to see individual subjects and comparisons.
Use /kb lookup <topic-name> <category> <subject> to load a specific entry.

## Research Gaps
<!-- Added by the Research Agent as categories are populated -->
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

## Quality checklist (self-verify before ending session)

- [ ] Preliminary web research completed before KB scan
- [ ] KB scan ran (or fallback used) to find existing related content
- [ ] Facet plan confirmed by user before any files were written
- [ ] Each additional facet beyond the first has explicit justification
- [ ] All subject files are at .kb/<topic>/<category>/<subject>.md
- [ ] Every subject file has topic and category in frontmatter
- [ ] Every subject file has sources frontmatter with URLs and accessed dates
- [ ] Every ## section heading is lowercase and hyphenated
- [ ] code-skeleton section contains runnable pseudocode
- [ ] No subject file exceeds 200 lines
- [ ] All new articles cross-linked to each other via related:
- [ ] Existing related entries updated with new article paths
- [ ] context: hint reflected in applies_to: and decision_refs: where applicable
- [ ] Category CLAUDE.md contents table updated (Tags line reviewed)
- [ ] Topic CLAUDE.md category row updated
- [ ] .kb/CLAUDE.md Recently Added table updated, line count under 80
- [ ] No existing file was overwritten
