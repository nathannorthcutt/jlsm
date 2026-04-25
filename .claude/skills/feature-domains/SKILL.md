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

Check whether the project has spec infrastructure (`test -f .spec/CLAUDE.md ||
test -d .spec/registry`). Display the appropriate next step:

If spec infrastructure exists:
```
🗺️  DOMAIN SCOUT · <slug>
───────────────────────────────────────────────
Domain analysis is already complete for '<slug>'.
Domains: .feature/<slug>/domains.md

  Use AskUserQuestion with options:
    - "Proceed to spec authoring"
    - "Stop"
```
If "Proceed to spec authoring": invoke `/spec-author "<feature-id>" "<slug>"` as a sub-agent immediately.
If "Stop": display `Next: /spec-author "<feature-id>" "<slug>"` and stop.

If no spec infrastructure:
```
🗺️  DOMAIN SCOUT · <slug>
───────────────────────────────────────────────
Domain analysis is already complete for '<slug>'.
Domains: .feature/<slug>/domains.md

  Use AskUserQuestion with options:
    - "Proceed to work planning"
    - "Stop"
```
If "Proceed to work planning": invoke /feature-plan "<slug>" as a sub-agent immediately.
If "Stop": display `Next: /feature-plan "<slug>"` and stop.

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

Use AskUserQuestion with options:
  - "Research first (I'll suggest a topic based on the brief)"
  - "Continue (research offered per-domain as gaps are found)"
  - "Skip research (rely on domain knowledge, gaps noted)"
```

If **"Research first"**: read `brief.md`, identify the single highest-value research
subject for this feature (the domain most likely to affect multiple design
choices), and invoke `/research "<subject>" context: "feature-domains for <feature>, domain: <domain>"` as a sub-agent.
After research completes, continue to Step 1.

If **"Continue"**: proceed to Step 1 normally. Per-domain research offers in
Step 3 will still fire.

If **"Skip research"**: set a flag `skip_all_research=true` in status.md under
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
3. Check for **feature footprint** entries (`type: feature-footprint`) in relevant
   categories. Footprints are condensed records from prior features that document
   what was built, key decisions, and cross-references to ADRs and KB research.
   If a footprint overlaps with this feature's domain, note it — the Work Planner
   should reference it for context on prior art and known constraints.
4. Check for **adversarial finding** entries (`type: adversarial-finding`) in
   relevant categories. These are bug patterns discovered during prior feature
   audits. Do NOT surface these to the user or include them in domains.md — they
   pass through silently to the test phase where the spec analyst pre-pass
   (Step 1c of /feature-test) will read and apply them.
5. Read `.decisions/CLAUDE.md` — check for relevant ADR
6. **Group envelope (AUTHORITATIVE).** If `brief.md` has a `## Group Envelope`
   section, it lists group-level specs/ADRs/KB entries that the enclosing work
   group settled during `/work-decompose` Phase B. Read each one's content:
   - For a `spec <domain>/<name>` entry: open `.spec/domains/<domain>/<name>.md`
     (or resolve via `.spec/registry/manifest.json`) and read the requirement
     statements.
   - For an `adr <slug>` entry: open `.decisions/<slug>/adr.md` and read the
     decision.

   Then check if the envelope item covers the current domain's decision. If yes,
   classify the domain as `resolved` with source `group envelope: <ref>` — no
   `/architect` dispatch. Envelope hits take precedence over Step 5's
   `.decisions/CLAUDE.md` scan (same classification, explicit source).

   **Contradiction escalation.** If WD-local analysis would reach a decision that
   contradicts an envelope spec or ADR (different data shape, incompatible
   assumption, overlapping scope with divergent intent), do NOT override locally.
   Classify the domain as `escalate-decompose` with a note describing the
   contradiction. The user must revisit `/work-decompose` to surface the
   cross-WD decision that was missed in Phase B. See "Classification rules"
   below.
7. **Work group context (redundancy hint).** If `.work/` exists, run:
   ```bash
   bash .claude/scripts/work-context.sh --domains "<current domain>"
   ```
   If output is non-empty: check if other work definitions have already
   explored this domain. If so, note it: "WD-01 in <group> already explored
   this domain — its domain results may be reusable." This avoids commissioning
   redundant research across related work definitions. Do not display the full
   work context — just note the overlap for the Domain Scout's classification.
   This is distinct from Step 6's group envelope check — envelope items are
   AUTHORITATIVE (skip decision entirely); redundancy hints are ADVISORY (reuse
   prior analysis if relevant).
8. Classify using the rules below

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
provides context) or formalize first via `/decisions revisit "<slug>"`.

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

**`escalate-decompose`** — WD-local analysis would contradict a group-level
spec or ADR listed in the brief's `## Group Envelope` section. The
decomposition assumed a shared shape or decision that doesn't actually fit
this WD, so the contradiction is a cross-WD concern that Phase B missed.
Stop domain analysis for this feature and tell the user:
```
  ⚠ ESCALATE  <domain> — contradicts group envelope item <ref>
              <one-line description of the contradiction>

  This surfaces a cross-WD decision Phase B missed. Revisit with:
    /work-decompose "<group-slug>"
```
Do NOT self-resolve. Do NOT override the envelope. Decomposition must settle
this before planning can continue.

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

### Escalation check (always first)

If any domain was classified `escalate-decompose` during Step 2, do NOT
proceed with resolution. Phase B of `/work-decompose` missed a cross-WD
coordination surface that WD-local analysis has now surfaced. Display:

```
───────────────────────────────────────────────
🚨 ESCALATE to /work-decompose · <slug>
───────────────────────────────────────────────
Domain(s) contradict the group envelope:
  - <domain A> — contradicts <envelope ref>: <short reason>
  - <domain B> — contradicts <envelope ref>: <short reason>

Group-level decomposition missed these cross-WD decisions. Fix at the
group level, not locally — overriding the envelope here would leave
sibling WDs planning against stale assumptions.

Next:
  /work-decompose "<group-slug>"

Domain analysis will resume after the new envelope is settled.
───────────────────────────────────────────────
```

Set status.md: stage Domains → `escalated`, substage → `envelope-contradiction`.
Append `domains-escalated-decompose` to cycle-log.md. Stop.

### Resolve per-domain

Process each remaining pending domain in order. For each one, resolve it
before moving to the next — don't batch commissions and leave them for the
user.

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

  Use AskUserQuestion with options:
    - "Research now"
    - "Skip (note gap)"
```
Append `domains-research-commissioned` to cycle-log.md. Wait for user response.

If "Research now":
- Invoke `/research "<subject>" context: "feature-domains for <feature>, domain: <domain>"` as a sub-agent immediately
- After research completes, verify the KB entry now exists
- If yes → mark domain `resolved` in status.md, display `✓ <domain> — resolved`
- If research failed or was incomplete → mark `gap-noted`, continue

If "Skip (note gap)": mark domain `skipped` in status.md, note gap in domains.md.

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

  Use AskUserQuestion with options:
    - "Decide now"
    - "Skip (no ADR)"
```
Append `domains-decision-commissioned` to cycle-log.md. Wait for user response.

If "Decide now":
- Invoke `/architect "<decision problem>"` as a sub-agent immediately
- After architect completes, check if ADR's log.md contains a
  `decision-confirmed` entry
- If yes → mark domain `resolved` in status.md, display `✓ <domain> — resolved`
- If architect session was incomplete → warn and offer to resume:
  ```
  ⚠ <domain> — architect session incomplete
    .decisions/<adr-slug>/log.md has no decision-confirmed entry.

    Use AskUserQuestion with options:
      - "Resume"
      - "Skip"
  ```

If "Skip (no ADR)": mark domain `skipped` in status.md, note gap in domains.md.

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
  Use AskUserQuestion with options:
    - "Proceed"
    - "Stop"
───────────────────────────────────────────────
```

### Step 5a — Determine next stage (spec authoring or planning)

Check whether the project has spec infrastructure:
```bash
test -f .spec/CLAUDE.md || test -d .spec/registry
```

**If `.spec/CLAUDE.md` or `.spec/registry` exists:** the project uses the spec
system. The next stage is spec authoring — specs must be written (or confirmed
current) before work planning, because the planner consumes spec requirements
as its primary input.

If "Proceed":
- Update status.md: Spec Authoring stage → `in-progress`

**If `pipeline_mode: specification` (from `/work-plan`):** stop here.
Domain analysis is complete. The calling command (`/work-plan`) will
iterate over the identified specs and invoke `/spec-author` for each
one sequentially. Display:
```
Domain analysis complete. Specs to produce:
  <list of spec titles and domains from domains.md>

Returning to /work-plan for sequential spec authoring.
```
Return control to the caller.

**Otherwise (full or implementation mode):** invoke spec authoring as
a single pass:
- Display:
  ```
  Spec infrastructure detected — routing through spec authoring.
  Specs will define behavioral requirements before work planning begins.
  ```
- Invoke `/spec-author "<feature-id>" "<slug>"` as a sub-agent immediately.
  The spec-author handles the full lifecycle: draft → falsify → arbitrate →
  register (via `/spec-write` internally). Do NOT call `/spec-write`
  separately — `/spec-author` owns registration.
  After spec-author completes, invoke `/feature-plan "<slug>"` as a sub-agent.

If "Stop":
```
When you're ready:
  /spec-author "<feature-id>" "<slug>"

After spec authoring, continue with:
  /feature-plan "<slug>"
```

**If neither `.spec/CLAUDE.md` nor `.spec/registry` exists:** the project does
not use the spec system. Hand off directly to work planning for backwards
compatibility.

If "Proceed": invoke /feature-plan "<slug>" as a sub-agent immediately.
If "Stop":
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

**Prior feature footprints:** <if any footprints reference this domain>
- [`.kb/<topic>/<cat>/<footprint>.md`](../../.kb/<topic>/<cat>/<footprint>.md) — <what was built, key constraint>

**Guidance for implementation:**
<2–3 sentences the Work Planner must respect>

---

## Unresolved Gaps
<Domains missing coverage with user's confirmation to proceed — or "None.">

## Key Constraints for Implementation
<Bullet list of constraints from ADRs/KB that the Work Planner must bake in>
```
