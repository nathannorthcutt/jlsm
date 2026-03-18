---
description: "Analyse the feature brief to identify relevant KB topics and ADRs"
argument-hint: "<feature-slug>"
---

# /feature-domains "<feature-slug>"

Analyses the feature brief to identify relevant KB topics and ADRs.
Idempotent — skips domains already resolved and resumes from the first pending one.

---

## Idempotency pre-flight (ALWAYS FIRST)

Read `.feature/<slug>/status.md`.

**If Domains stage is `complete`:**
```
🗺️  DOMAIN SCOUT · <slug>
───────────────────────────────────────────────
Domain analysis is already complete for '<slug>'.
Domains: .feature/<slug>/domains.md

  Type **yes**  to proceed to work planning  ·  or: stop
```
If "yes": invoke /feature-plan "<slug>" as a sub-agent immediately.
If "stop": display `Next: /feature-plan "<slug>"` and stop.

**If Domains stage is `in-progress`:**
Display opening header, then:
- Load the Domain Resolution Tracker from status.md
- Count resolved vs. pending domains
- Say:
  ```
  Resuming domain analysis for '<slug>'.
  Resolved: <n> domains
  Pending:  <n> domains
  Skipping resolved domains and continuing from first pending.
  ```
- Jump to Step 3 (process only pending domains)

**If Domains stage is `not-started`:**
- Check that `.feature/<slug>/brief.md` exists. If not: "Run /feature first."
- Set status.md: stage Domains → `in-progress`
- Display opening header and proceed to Step 1

Display opening header:
```
───────────────────────────────────────────────
🗺️  DOMAIN SCOUT · <slug>
───────────────────────────────────────────────
```

---

## Step 0a — Progress tracking

Use TodoWrite to show progress in the Claude Code UI (visible via Ctrl+T).
Each TodoWrite call replaces the full list — always include all items.

**Pipeline context:** Include the full feature lifecycle as top-level items.
Mark earlier stages `completed`, current `in_progress`, later `pending`.

**Per-domain granularity:** After extracting domains (Step 1), add an item for
each domain. Update status as each is surveyed and resolved. Use `activeForm`
to show what is happening (e.g., "Checking KB for rate-limiting coverage").

Example checklist during domain resolution:
```json
[
  {"id": "pipeline-scoping", "content": "Scoping", "status": "completed", "priority": "medium"},
  {"id": "pipeline-domains", "content": "Domain analysis", "status": "in_progress", "priority": "high",
   "activeForm": "Resolving domain 2 of 3"},
  {"id": "pipeline-planning", "content": "Work planning", "status": "pending", "priority": "medium"},
  {"id": "pipeline-testing", "content": "Test writing", "status": "pending", "priority": "medium"},
  {"id": "pipeline-implementation", "content": "Implementation", "status": "pending", "priority": "medium"},
  {"id": "pipeline-refactor", "content": "Refactor & review", "status": "pending", "priority": "medium"},
  {"id": "pipeline-pr", "content": "PR draft", "status": "pending", "priority": "medium"},
  {"id": "domain-1", "content": "Rate limiting strategy", "status": "completed", "priority": "high"},
  {"id": "domain-2", "content": "Token storage", "status": "in_progress", "priority": "high",
   "activeForm": "Launching /architect for storage model decision"},
  {"id": "domain-3", "content": "API boundary design", "status": "pending", "priority": "high"},
  {"id": "write-domains", "content": "Write domains.md", "status": "pending", "priority": "medium"},
  {"id": "handoff", "content": "Hand off to work planning", "status": "pending", "priority": "medium"}
]
```

---

---

## Step 0b — KB empty check

Read `.kb/CLAUDE.md`. If the Topic Map table has zero data rows (no topics
registered), the KB is completely empty. Display:

```
── Knowledge base ─────────────────────────────
Your KB is empty — no topics or research entries yet.

Domain analysis will identify areas where research or decisions are needed,
but starting with an empty KB means every domain will likely trigger a
research session.

Options:
  1. research — start a targeted research session first (I'll suggest a topic
     based on the brief)
  2. continue — proceed to domain analysis (research offered per-domain as gaps
     are found)
  3. skip-research — proceed and rely on your domain knowledge (gaps noted but
     no research sessions launched)
```

If **research**: read `brief.md`, identify the single highest-value research
topic for this feature (the domain most likely to affect multiple design
choices), and invoke `/research <topic> <category> "<subject>"` as a sub-agent.
After research completes, continue to Step 1.

If **continue**: proceed to Step 1 normally. Per-domain research offers in
Step 3 will still fire.

If **skip-research**: set a flag `skip_all_research=true` in status.md under
the Domain Resolution Tracker section. Step 3 will classify research gaps as
`gap-noted` instead of offering `/research` per-domain. The user's local
domain knowledge is sufficient — gaps are documented but don't block progress.

If the KB has at least one topic: skip this step silently.

---

## Step 1 — Extract domains from the brief

Read `brief.md` in full. Identify distinct technical domains — areas where an
architectural or research decision is needed. Not every concept, only ones that
require a choice or have research depth worth capturing.

### Research commissions from scoping

Check the `## Research Commissions` section of `brief.md`. If it contains
entries, each one becomes a domain pre-classified as `pending-research`. These
represent uncertainty the user expressed during scoping that was captured as a
research signal rather than deferred.

Add research commission domains to the domain list alongside domains you
identify from the brief's technical content. Do not duplicate — if a research
commission overlaps with a domain you would have extracted anyway, merge them
(the commission's key questions and purpose enrich the domain).

Display:
```
── Identifying domains ─────────────────────────
Domains identified:
  1. <Domain> — <one sentence: what decision or research is needed>
  2. <Domain> — ...
  📋 <n> research commission(s) from scoping included.
Checking KB and decisions store for each.
```

If no research commissions: omit the commission line.

Initialise the Domain Resolution Tracker in status.md with all identified
domains set to `pending`. Research commission domains start as `pending-research`
rather than `pending`. Write this before doing any lookups so a crash here
doesn't lose the domain list.

---

## Step 2 — Survey KB and decisions (only for pending domains)

For each pending domain:
1. Read `.kb/CLAUDE.md` — check for relevant topic/category (top-down navigation)
2. Extract keywords from the domain name and description. Search across all
   category-level `CLAUDE.md` files for entries matching these keywords — this
   catches tangentially related KB entries in other topics/categories. Read only
   the matching category indexes, not subject files.
3. Read `.decisions/CLAUDE.md` — check for relevant ADR
4. Classify using the rules below

### Classification rules

**`resolved`** — an existing ADR explicitly covers this domain's decision.
The ADR must actually exist in `.decisions/` with a confirmed decision (`status:
accepted` or `status: confirmed`). The Domain Scout's own reasoning that
something is "well-understood" or "standard practice" does NOT count as
resolution. If no ADR exists, the domain is not resolved.

**Draft ADRs** (`status: draft`) do NOT count as resolved. Display a warning:
```
  ⚠ DRAFT ADR    <domain> — .decisions/<slug>/adr.md (draft — not yet deliberated)
```
Classify the domain as `pending-decision`. The user can proceed (the draft
provides context) or formalize first via `/decisions review "<slug>"`.

**`pending-decision`** — any domain that involves a design choice, even if the
answer seems obvious. Specifically:
- A choice between approaches (storage model A vs B, API shape X vs Y)
- A data model or schema design (table structure, index strategy, encoding)
- An integration boundary (how modules compose, what depends on what)
- A performance/correctness tradeoff
- Any domain where the guidance section would contain design rationale

The Domain Scout MUST NOT self-resolve these by reasoning about what the "right"
answer is. That is the Architect Agent's job. The scout identifies domains and
checks for existing coverage — it does not make architectural decisions.

**`pending-research`** — KB gap exists and research is needed before a decision
can be made (or the domain is purely informational with no decision component).

**`skipped`** — user explicitly confirmed proceeding without coverage.

### When in doubt: `pending-decision`

If a domain could be `resolved` (because the answer seems obvious) or
`pending-decision` (because no ADR exists), classify it as `pending-decision`.
The Architect Agent's deliberation is cheap; an undocumented design assumption
baked into the implementation is expensive to change later.

Update the Domain Resolution Tracker in status.md immediately after classifying
each domain (don't wait until all are done — crash safety).

**Decision candidates notice:** After classification, check if
`.decisions/.decision-candidates` exists and has `status: new` entries. If so,
append to the domain coverage display:
```
  ℹ <n> undocumented decision candidates from recent sessions.
    Run /decisions candidates to review.
```

Display:
```
── Domain coverage ─────────────────────────────
  ✓ RESOLVED          <domain> — ADR: .decisions/<slug>/adr.md
  ⚠ DRAFT ADR         <domain> — .decisions/<slug>/adr.md (draft — not yet deliberated)
  ⚠ PENDING-RESEARCH  <domain> — no KB entry for <topic/category>
  ⚠ PENDING-DECISION  <domain> — no ADR for this design choice
```

---

## Step 3 — Resolve missing work (pending domains only)

Process each pending domain in order. For each one, resolve it before moving
to the next — don't batch commissions and leave them for the user.

### If research is missing

**If `skip_all_research=true` in status.md:** mark the domain as `gap-noted` in
the Domain Resolution Tracker and domains.md. Do not offer `/research`. Display:
```
  ℹ GAP-NOTED  <domain> — no KB entry (research skipped per user preference)
```
Continue to the next domain.

**Otherwise:**

Write the commission to status.md immediately (before the work is done):
Update Domain Resolution Tracker: status → `pending-research`, commissioned → today's date.

Display:
```
── Research needed ─────────────────────────────
  Domain: <domain>
  Topic: <topic> / <category> / "<subject>"

  Launching research to fill this gap.

  Type **yes** to research now · or: skip (gap will be noted in domains.md)
```
Append `domains-research-commissioned` to cycle-log.md. Wait for user response.

If "yes" (or any response other than "skip"):
- Invoke `/research <topic> <category> "<subject>"` as a sub-agent immediately
- After research completes, verify the KB entry now exists
- If yes → mark domain `resolved` in status.md, display `✓ <domain> — resolved`
- If research failed or was incomplete → mark `gap-noted`, continue

If "skip": mark domain `skipped` in status.md, note gap in domains.md.

### If an architectural decision is missing

Update Domain Resolution Tracker: status → `pending-decision`, commissioned → today's date.

Architectural decisions that affect system structure — data models, indexing
strategies, protocol choices, API boundaries, storage engines — should be
resolved through the Architect Agent rather than decided implicitly during
planning or implementation.

**Detection signals** (any one is sufficient to classify as `pending-decision`):
- The domain involves a choice between competing approaches (e.g., table
  structure A vs B, index type X vs Y)
- The domain has cross-cutting implications (affects multiple constructs or
  future features)
- The domain involves an external system boundary (storage, API, protocol)
- The domain has constraints that need deliberation (performance vs simplicity,
  consistency vs availability)

Display:
```
── Decision needed ─────────────────────────────
  Domain: <domain>
  KB coverage: <path or "none">
  Decision: "<one-sentence framing of the architectural choice>"

  Launching architect to deliberate.

  Type **yes** to decide now · or: skip (proceeds without formal ADR)
```
Append `domains-decision-commissioned` to cycle-log.md. Wait for user response.

If "yes" (or any response other than "skip"):
- Invoke `/architect "<decision problem>"` as a sub-agent immediately
- After architect completes, check if ADR's log.md contains a
  `decision-confirmed` entry
- If yes → mark domain `resolved` in status.md, display `✓ <domain> — resolved`
- If architect session was incomplete → warn and offer to resume:
  ```
  ⚠ <domain> — architect session incomplete
    .decisions/<adr-slug>/log.md has no decision-confirmed entry.

    Type **yes** to resume · or: skip
  ```

If "skip": mark domain `skipped` in status.md, note gap in domains.md.

### Verifying commissioned work on resume

When re-running after commissioning (crash recovery):
- For each domain with status `pending-research`: check if the KB entry now exists.
  If yes → mark `resolved`. If no → re-offer research (same flow as above).
- For each domain with status `pending-decision`: check if the ADR's log.md contains
  a `decision-confirmed` entry. If yes → mark `resolved`. If no → re-offer architect.

---

## Step 4 — Write domains.md

After all domains are resolved (or user confirmed proceeding with gaps noted).

Write `.feature/<slug>/domains.md` (Domains File Template below).

Update status.md: Domains stage → `complete`, last checkpoint → "domains.md written".
Update the Stage Completion table: Domains row → Est. Tokens `~<N>K` (sum of files
loaded: brief ~2K + KB/decisions indexes ~2K + any ADR/KB files read).

Append `domains-resolved` to cycle-log.md:
```markdown
## <YYYY-MM-DD> — domains-resolved
**Agent:** 🗺️ Domain Scout
**Summary:** <n> domains resolved, <n> gaps noted.
**Files read:** brief ~2K, .kb/CLAUDE.md ~1K, .decisions/CLAUDE.md ~1K, <any ADR/KB files>
**Token estimate:** ~<N>K
---
```

Update `.feature/CLAUDE.md` stage column.

---

## Step 5 — Hand off

Display:
```
───────────────────────────────────────────────
🗺️  DOMAIN SCOUT complete · <slug>
  Tokens : <TOKEN_USAGE>
───────────────────────────────────────────────
Domain analysis written to .feature/<slug>/domains.md
Resolved: <n> | Gaps noted: <n>

Review the domain analysis above — the Work Planner will build the implementation
structure from these constraints and ADRs.

───────────────────────────────────────────────
  Type **yes**  ·  or: stop
───────────────────────────────────────────────
```

If "yes": invoke /feature-plan "<slug>" as a sub-agent immediately.
If "stop":
```
When you're ready:
  /feature-plan "<slug>"
```

---

## Domains File Template

```markdown
---
feature: "<slug>"
created: "<YYYY-MM-DD>"
status: "<resolved | has-gaps>"
---

# Domain Analysis — <slug>

## Overview
<2–3 sentences on the technical landscape this feature operates in>

## Domains

### <Domain Name>
**Status:** <resolved | gap-noted>
**Relevance:** <one sentence>

**KB entries:**
- [`.kb/<topic>/<cat>/<subject>.md`](../../.kb/<topic>/<cat>/<subject>.md) — <relevance>

**Governing ADR:**
- [`.decisions/<adr-slug>/adr.md`](../../.decisions/<adr-slug>/adr.md) — <one-line decision summary>

**Guidance for implementation:**
<2–3 sentences the Work Planner must respect>

---

## Unresolved Gaps
<Domains missing coverage with user's confirmation to proceed — or "None.">

## Key Constraints for Implementation
<Bullet list of constraints from ADRs/KB that the Work Planner must bake in>
```
