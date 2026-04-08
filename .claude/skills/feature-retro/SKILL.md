---
description: "Post-feature retrospective reviewing scope, assumptions, and lessons learned"
argument-hint: "<feature-slug>"
---

# /feature-retro "<feature-slug>"

Post-feature retrospective. Reviews what actually happened against what was
planned: scope divergence, invalidated assumptions, missed domain gaps, and
lessons learned. Writes findings back to `.decisions/` and `.kb/` so future
features benefit.

Run after `/feature-pr` or after the PR merges — whenever the feature is fresh
in memory.

---

## Pre-flight guard

Check that `.feature/<slug>/` exists (or `.feature/_archive/<slug>/`).
If neither exists: "No feature found for '<slug>'. Check the slug or run
/feature-resume --list to see active features."

If found in `_archive/`: read from there. The feature is complete but the
working files are still available locally.

Check that cycle-log.md has at least one `implemented` or `refactor-complete`
entry. If not: "Feature '<slug>' has not completed implementation. Run the
retrospective after the TDD cycle finishes."

Display opening header:
```
───────────────────────────────────────────────
🔍 FEATURE RETRO · <slug>
───────────────────────────────────────────────
```

---

## Step 1 — Load context

Read in order:
1. `status.md` — stage completion, cycle count, token usage
2. `brief.md` — original scope, acceptance criteria, assumptions
3. `work-plan.md` — planned constructs, contracts, work units
4. `domains.md` — domain analysis, ADR references, noted gaps
5. `cycle-log.md` — full history (what actually happened)
6. `test-plan.md` — what was tested

Do NOT read implementation or test source files.

---

## Step 2 — Analyse divergence

Compare what was planned against what happened. Check each dimension:

### 2a — Scope divergence

Compare brief.md acceptance criteria against cycle-log.md outcomes:
- Were all acceptance criteria met?
- Were any criteria added mid-implementation (not in the original brief)?
- Were any criteria dropped or descoped?
- Did any escalations (code-escalation, test-to-planner-escalation) indicate
  scope was wrong?

### 2b — Assumption validation

Read the `## Open Assumptions` section from brief.md. For each assumption:
- Was it validated during implementation?
- Was it invalidated? (the implementation had to work around it)
- Is it still untested?

### 2c — Domain gap review

Read domains.md `## Unresolved Gaps` section and any `gap-noted` domains:
- Did any noted gaps cause problems during implementation?
- Were any domains missed entirely that should have been identified?
- Did any ADRs referenced in domains.md turn out to be wrong or insufficient?

### 2d — Estimation accuracy

Read the Stage Completion table from status.md:
- Compare Est. Tokens vs Actual Tokens for each stage
- Identify stages that were significantly over or under (>30% delta)
- Note the total estimate vs actual

### 2e — TDD cycle efficiency

From cycle-log.md:
- How many cycles did it take?
- How many escalations occurred?
- How many missing tests were found during refactor?
- Were any contracts revised?

---

## Step 3 — Display the retrospective

```
── Feature Retrospective · <slug> ─────────────

SCOPE
  Acceptance criteria: <n met> / <n total>
  <If any dropped:>  Dropped: <list>
  <If any added:>    Added mid-flight: <list>
  Verdict: <on-track | minor drift | significant drift>

ASSUMPTIONS
  <For each assumption from brief.md:>
  ✓ <assumption> — validated
  ✗ <assumption> — invalidated: <what happened>
  ? <assumption> — untested

DOMAINS
  <If gaps caused issues:>
  ⚠ <domain> — gap caused: <issue description>
  <If domains were missed:>
  ⚠ Missing domain: <domain that should have been identified>
  <If ADRs were insufficient:>
  ⚠ <adr-slug> — insufficient: <what was missing>
  <If all clean:>
  ✓ All domains adequately covered

TOKEN ACCURACY
  | Stage          | Est.   | Actual  | Δ      |
  |----------------|--------|---------|--------|
  <from status.md Stage Completion table>
  | Total          | ~<N>K  | <N>K in | <+/-%> |
  <If any stage >30% off:>
  Note: <stage> was <N>% <over|under> estimate.

TDD EFFICIENCY
  Cycles: <n>
  Escalations: <n> (<types>)
  Missing tests found: <n>
  Contract revisions: <n>
  Verdict: <clean | minor friction | significant rework>

───────────────────────────────────────────────
```

---

## Step 4 — Extract actionable findings

For each finding, classify and offer an action:

### Findings that should update `.decisions/`

If an ADR was invalidated or insufficient:
```
── ADR Update ─────────────────────────────────
  <adr-slug> — <what was wrong or missing>

  Type **yes** to open a review · or: skip
```
If "yes": invoke `/decisions revisit "<adr-slug>"` as a sub-agent.

If a design decision was made during implementation without an ADR (detected
from contract revisions or escalations that changed the approach):
```
── Undocumented Decision ──────────────────────
  <description of what was decided>
  Source: <escalation / contract revision / scope change>

  Type **yes** to create an ADR · or: skip
```
If "yes": invoke `/architect "<decision problem>"` as a sub-agent.

### Findings that should update `.kb/`

If implementation revealed information that would be useful for future features
(e.g., a library behaved unexpectedly, a pattern worked well, a constraint
was discovered):
```
── KB Finding ─────────────────────────────────
  <what was learned>
  Relevant topic: <topic> / <category>

  Type **yes** to research and document · or: skip
```
If "yes": invoke `/research <topic> <category> "<subject>"` as a sub-agent.

### Feature footprint

Always generate a feature footprint KB entry. This is not optional — every
completed feature should leave a trace in the knowledge base.

```
── Feature Footprint ─────────────────────────
  Generating KB entry for <slug>...
  Domains: <domains from domains.md>
  Key constructs: <new/modified types>
```

Invoke `/research architecture feature-footprints "<slug>"` as a sub-agent,
providing it with:
- The domains from domains.md
- The key constructs from work-plan.md (new + modified)
- Any adversarial findings (from known_issues.md if it exists)
- Cross-references to ADRs created during this feature

The research agent writes the entry following the template at
`.kb/_refs/feature-footprint-template.md`.

### Adversarial finding graduation

If `.feature/<slug>/known_issues.md` exists and contains RESOLVED or TENDENCY
entries (from aTDD rounds or audit passes):

```
── Adversarial Findings ──────────────────────
  <n> RESOLVED patterns, <n> TENDENCY patterns found
  These should be documented in .kb/ for future features.

  Type **yes** to graduate findings to KB · or: skip
```

If "yes": for each significant pattern (not one-off fixes), invoke
`/research <domain> adversarial-findings "<pattern-name>"` as a sub-agent.
The research agent writes entries following the template at
`.kb/_refs/adversarial-finding-template.md`.

Group related findings — don't create a separate KB entry for every individual
bug. A single entry per bug *class* (e.g., "assertion-based safety checks",
"silent null returns on error paths") is the right granularity.

### Estimation calibration

If token estimates were consistently off in one direction:
```
── Estimation Note ────────────────────────────
  <stage> estimates were consistently <high|low> by ~<N>%.
  Consider adjusting the per-construct estimate in /feature-plan
  Step 2b (currently 3.5K per construct).
```
This is informational only — no file is written. The user decides whether
to adjust.

---

## Step 4b — Capability index update

**Guard:** Only run this step if `.capabilities/CLAUDE.md` exists. If no
capability index, skip silently.

Check whether this feature contributes to an existing capability or
introduces a new one.

Read `.capabilities/CLAUDE.md` to get the current capability map.
Read the feature brief to understand what was built.

Use AskUserQuestion:
- "Update existing capability" — this feature adds to or improves an
  existing capability (select which one)
- "Create new capability" — this feature introduces a genuinely new
  project capability
- "Quality improvement" — this feature is a performance fix, bug fix,
  or internal improvement that doesn't change what the project can do
  (add as `type: quality` feature entry on the parent capability)
- "Skip" — don't update the capability index

If "Update existing" or "Quality improvement": read the selected capability
entry. Add this feature to the `features:` array with a one-line
description. Update the Recently Updated table in CLAUDE.md.

If "Create new": run `/capabilities add "<name>"` with the feature brief
as context.

---

## Step 5 — Write retro summary

Append `retro-complete` to cycle-log.md:
```markdown
## <YYYY-MM-DD> — retro-complete
**Agent:** 🔍 Retro
**Scope:** <on-track | minor drift | significant drift>
**Assumptions:** <n validated> / <n total> (<n invalidated>)
**Domain gaps:** <n issues found>
**Token accuracy:** <total est> vs <total actual> (<delta%>)
**TDD efficiency:** <n cycles>, <n escalations>, <n missing tests>
**Actions taken:**
- <ADR reviewed: <slug>> | <ADR created: <slug>> | <KB updated: <topic>>
- Feature footprint: .kb/architecture/feature-footprints/<slug>.md
- <If adversarial findings graduated:> Adversarial findings: <n> patterns → .kb/
- ...
---
```

Display:
```
───────────────────────────────────────────────
🔍 FEATURE RETRO complete · <slug>
───────────────────────────────────────────────
  Scope: <verdict>
  Actions: <n ADR reviews> · <n new ADRs> · <n KB updates>

  Retrospective logged in cycle-log.md.
  <If feature not yet archived:>
  When the PR merges: /feature-complete "<slug>"
───────────────────────────────────────────────
```

---

## Step 6 — Generate narrative article (enhanced, optional)

After the retro summary is written, attempt to generate a rich narrative
markdown article showing the full feature story — pipeline phases, token
usage per stage, conversations, escalations, TDD cycles, and crash recovery.

Run:
```bash
bash .claude/scripts/narrative-wrapper.sh "<slug>" ".feature/<slug>"
```

The wrapper tries Python, then Node.js, then exits silently if neither is
available. The narrative is an enhancement — retro is complete without it.

If the script writes `.feature/<slug>/narrative.md`:
```
  📖 Narrative article generated: .feature/<slug>/narrative.md
```

If the script exits without producing a file, say nothing — the retro
is complete regardless.

The narrative article includes:
- shields.io badges (duration, tokens, model, vallorcine version)
- Mermaid gantt chart of pipeline phases
- Phase-by-phase breakdown with conversations, escalations, TDD cycles
- Progressive disclosure for background narration

---

## Write authority

The retro command writes to:
- `.feature/<slug>/cycle-log.md` (retro-complete entry only)
- `.feature/<slug>/narrative.md` (via narrative-wrapper.sh, optional)

It does NOT directly write to `.decisions/` or `.kb/` — it invokes
`/architect`, `/decisions revisit`, and `/research` as sub-agents, and those
commands handle their own writes. The feature footprint and adversarial finding
graduation steps also use `/research` sub-agents.
