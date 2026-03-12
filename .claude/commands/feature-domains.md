# /feature-domains "<feature-slug>"

Analyses the feature brief to identify relevant KB topics and ADRs.
Idempotent — skips domains already resolved and resumes from the first pending one.

---

## Idempotency pre-flight (ALWAYS FIRST)

Read `.feature/<slug>/status.md`.

**If Domains stage is `complete`:**
```
Domain analysis is already complete for '<slug>'.
Domains: .feature/<slug>/domains.md
Next: /feature-plan "<slug>"
Run /feature-resume "<slug>" to see full status.
```
Stop.

**If Domains stage is `in-progress`:**
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
- Proceed to Step 1

---

## Step 1 — Extract domains from the brief

Read `brief.md` in full. Identify distinct technical domains — areas where an
architectural or research decision is needed. Not every concept, only ones that
require a choice or have research depth worth capturing.

Display in chat before any KB/decisions lookups:
```
Domains identified:
  1. <Domain> — <one sentence: what decision or research is needed>
  2. <Domain> — ...
I'll now check the KB and decisions store for each.
```

Initialise the Domain Resolution Tracker in status.md with all identified
domains set to `pending`. Write this before doing any lookups so a crash
here doesn't lose the domain list.

---

## Step 2 — Survey KB and decisions (only for pending domains)

For each pending domain:
1. Read `.kb/CLAUDE.md` — check for relevant topic/category
2. Read `.decisions/CLAUDE.md` — check for relevant ADR
3. Classify:
   - `resolved` — sufficient ADR and/or KB coverage exists
   - `pending-research` — KB gap, research needed
   - `pending-decision` — KB exists but no ADR for this decision
   - `skipped` — user confirmed proceeding without coverage

Update the Domain Resolution Tracker in status.md immediately after classifying
each domain (don't wait until all are done — crash safety).

Display status table:
```
Domain coverage:
  ✓ RESOLVED         <domain> — ADR: .decisions/<slug>/adr.md
  ⚠ PENDING-RESEARCH  <domain> — no KB entry for <topic/category>
  ⚠ PENDING-DECISION  <domain> — KB has <entry>, no ADR yet
```

---

## Step 3 — Commission missing work (pending domains only)

### If research is missing

Write the commission to status.md immediately (before the work is done):
Update Domain Resolution Tracker: status → `pending-research`, commissioned → today's date.

Tell the user:
```
Research needed:
  Domain: <domain>
  Run: /research <topic> <category> "<subject>"
  Then re-run: /feature-domains "<slug>"

Or confirm to proceed without this research (gap will be noted in domains.md).
```
Append `domains-research-commissioned` to cycle-log.md. Wait for user response.

### If an architectural decision is missing

Update Domain Resolution Tracker: status → `pending-decision`, commissioned → today's date.

Tell the user:
```
Architectural decision needed:
  Domain: <domain>
  KB coverage: <path>
  Run: /architect "<decision problem>"
  Then re-run: /feature-domains "<slug>"

Or confirm to proceed without a formal decision.
```
Append `domains-decision-commissioned` to cycle-log.md. Wait for user response.

### Verifying commissioned work on resume

When re-running after commissioning:
- For each domain with status `pending-research`: check if the KB entry now exists.
  If yes → mark `resolved`. If no → repeat commission message.
- For each domain with status `pending-decision`: check if the ADR's log.md contains
  a `decision-confirmed` entry. If yes → mark `resolved`. If no → warn:
  ```
  ⚠ <domain> — architect session may be incomplete
    .decisions/<adr-slug>/log.md has no decision-confirmed entry.
    Run /adr-review "<adr-slug>" or /architect "<problem>" to complete it.
  ```

---

## Step 4 — Write domains.md

After all domains are resolved (or user confirmed proceeding with gaps noted).

Write `.feature/<slug>/domains.md` (Domains File Template below).

Update status.md: Domains stage → `complete`, last checkpoint → "domains.md written".
Append `domains-resolved` to cycle-log.md.
Update `.feature/CLAUDE.md` stage column.

---

## Step 5 — Hand off

```
Domain analysis written to .feature/<slug>/domains.md
Resolved: <n> | Gaps noted: <n>

Next: /feature-plan "<slug>"
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
