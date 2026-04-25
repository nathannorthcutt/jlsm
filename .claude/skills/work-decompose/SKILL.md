---
description: "Decompose a work group into work definitions with dependency graph"
argument-hint: "<group-slug> [--from-obligations [--spec <spec-id>] [--domain <domain>]]"
---

# /work-decompose "<group-slug>" [--from-obligations [--spec <spec-id>] [--domain <domain>]]

Decomposes a work group into individual work definitions. Identifies natural
boundaries, artifact dependencies, shared interface contracts, and the
ordering graph.

Run after `/work "<goal>"` has created the work group.

**Modes:**
- **Default:** Analyze the work group scope and decompose by identified
  boundaries.
- **`--from-obligations`:** Ingest open obligations from
  `.spec/registry/_obligations.json` and synthesize WDs from them. Filters
  by `--spec` (spec ID) or `--domain` (domain name) if provided. This mode
  creates SPECIFIED WDs (specs already exist) that go directly to
  `/work-start`.

---

## Pre-flight

1. Check `.work/<group-slug>/work.md` exists. If not:
   ```
   Work group '<group-slug>' not found. Create it first:
     /work "<goal>"
   ```
   Stop.

2. Read `.work/<group-slug>/work.md` — scope, ordering constraints, shared
   interfaces.

3. Read `.work/<group-slug>/manifest.md` — check if WDs already exist.
   If WDs exist:
   ```
   ───────────────────────────────────────────────
   🔧 DECOMPOSE · <group-slug>
   ───────────────────────────────────────────────
   This work group already has <N> work definitions.
   ```
   Use AskUserQuestion with options:
     - "Add more work definitions"
     - "Replace all and re-decompose"
     - "Stop"
   If "Replace all": delete existing WD-*.md files and clear the manifest table.
   If "Add more": proceed to Step 2 with existing WDs as context.

Display opening header:
```
───────────────────────────────────────────────
🔧 DECOMPOSE · <group-slug>
───────────────────────────────────────────────
```

---

## Obligation intake mode (--from-obligations)

If `--from-obligations` is present, skip Steps 1-4 and use this flow instead.

### OB-1 — Read and filter obligations

Read `.spec/registry/_obligations.json`. Filter to open obligations only
(`status == "open"`).

Apply filters if provided:
- `--spec <spec-id>` → only obligations where `spec` matches. Spec ID can be
  legacy `FXX` (e.g., `F12`) or domain.slug (e.g., `query.full-text-index`).
- `--domain <domain>` → only obligations where `domains` array contains the value

If no open obligations match the filters:
```
No open obligations found matching the filters.
```
Stop.

Display:
```
Found <N> open obligations:

| # | ID | Spec | Affected Reqs | Blocked By |
|---|-----|------|---------------|------------|
| 1 | <id> | <spec> | <count> | <blocked_by> |
...
```

### OB-2 — Group obligations into WDs

Analyze the obligations and group them by natural boundaries:

1. **Same blocked_by** → obligations sharing a blocker should be in the same
   WD (they need the same prerequisite work)
2. **Code locality** — obligations affecting the same class/module should be
   together
3. **Dependency ordering** — if obligation A's fix is a prerequisite for
   obligation B's fix, A's WD must come first (use `type: wd` deps)
4. **Size** — keep each WD at a manageable scope. Split obligations that
   represent multi-week efforts into separate WDs.

For each proposed WD, determine:
- **status: SPECIFIED** — specs already exist, these are implementation-only
- **artifact_deps** — add `type: wd` deps for ordering constraints between WDs
- **Acceptance criteria** — map from the obligation's `affects` list:
  each affected requirement becomes a testable criterion
- **Summary** — synthesize from the obligation `description` field
- **Implementation notes** — include `blocked_by` context and any
  implementation hints from the description

### OB-3 — Present decomposition

Present the proposed WDs using the same table format as Step 3 (below).
Include the dependency graph and note which obligations map to which WD.

Proceed to Step 4 (confirm with user), then Step 5 (write WDs), Step 6
(update manifest), and Step 7 (summary).

**Key difference from default mode:** all WDs are written with
`status: SPECIFIED` (not DRAFT) because the specification work is already
done — the specs exist and the obligations describe the gap.

---

## Step 1 — Read project context

Read silently:
1. `.kb/CLAUDE.md` — topic map for domain identification
2. `.decisions/CLAUDE.md` — existing ADRs that may constrain decomposition
3. `.spec/CLAUDE.md` — existing specs that WDs may depend on or produce
4. `.spec/registry/manifest.json` — existing spec IDs and domains

This context informs which artifacts already exist (and can be referenced
as dependencies) vs. which need to be produced by new work definitions.

---

## Decomposition scope (what this skill does and doesn't do)

`/work-decompose` **shapes the relationships between chunks of work** —
it does NOT fully scope each chunk. WD-internal specs, architectural
decisions that only affect one WD, and implementation details are
deferred to `/work-plan`.

The output of `/work-decompose` is:
1. WD files (`.work/<group>/WD-NN.md`) defining the chunks and their deps
2. **Only the shared artifacts needed to prevent WD-level divergence:**
   - Group-level ADRs for decisions that affect multiple WDs
   - Specs for requirements that apply across WDs (the enforcement layer)
   - Interface-contract specs at cross-WD seams

The rule for what belongs in decompose vs work-plan: **does this
decision or artifact cross a WD boundary?** If yes, it's decompose's
job. If no, it's a WD-local concern and `/work-plan` handles it.

**Zero new artifacts is a valid decompose outcome.** When natural seams
are obvious and no shared-data decisions need making, the skill writes
WD files and exits. No research, no architect, no specs.

---

## Step 2 — Phase A: Seam-finding

Analyze the work group scope from work.md. The goal is to identify
**natural seams in the problem** — boundaries that emerge from the
work's structure, not arbitrary chunks.

### 2a — Look for seams, not chunks

Natural seams:
- **Produce/consume boundaries** — one body of work produces artifacts
  that another consumes.
- **Domain edges** — different domains with independent concerns.
- **Ordering constraints** — where A must complete before B.
- **Shared surfaces** — where multiple bodies of work must agree on an
  interface, protocol, or data shape.

Do NOT target a WD count. The number falls out of the composition:
an atomic problem is one WD, a problem with 50 natural seams is 50 WDs.
Splitting a WD to "feel smaller" or merging two to "feel larger" is
arbitrary and wrong.

### 2b — Dispatch /research for unknowns that affect seam identification

**Criterion:** dispatch `/research` when you cannot choose between two
materially different decompositions without external information. If
your uncertainty would only change WD-internal details, defer to
`/work-plan`. If it would change the *shape* of the WD chunks or their
boundaries, research now.

As you analyze the scope, if you hit such an unknown, dispatch
`/research` as a subagent:

```
Invoke `/research "<subject>" context: "work-decompose for <group-slug>,
seam-finding: <what you're trying to resolve>"` as a sub-agent.
```

After each research subagent completes, verify the KB entry exists and
continue seam analysis with the new findings. Multiple research
dispatches are allowed — seam-finding is exploratory.

Examples that meet the criterion (different decompositions hinge on
the answer):
- "We're touching an unfamiliar protocol — what are the conventional
  layering boundaries?" (boundaries decide WD count and shape)
- "Is there a canonical way to decompose this class of problem that we
  should follow?" (canonical pattern dictates the carve)
- "What existing patterns does the project already use here?"
  (consistency with prior carves)

Examples that do NOT meet the criterion (decomposition unaffected):
- WD-internal technology choices (those surface in `/work-plan` later)
- Implementation details (deferred to the feature pipeline)
- Performance tuning decisions (don't change WD shape)
- Algorithmic alternatives within a single WD (WD-local concern)

### 2c — Produce the seam analysis (internal — do not display yet)

Draft:
- **Tentative WD chunks** — based on the seams identified. These may
  move after Phase B.
- **Coordination surfaces** — cross-WD seams that need settled
  artifacts to prevent WD-level divergence. For each surface, note:
  - Which WDs share it
  - What kind of artifact settles it (ADR, spec, interface contract,
    or breakdown ADR if the shape itself is unclear)
- **Existing artifacts that apply** — group-level ADRs/specs already in
  `.decisions/` or `.spec/` that constrain this work (don't re-author
  them).
- **Cross-group blockers** — if this group can't start until another
  work group finishes, that's an `external_deps:` on `work.md`, not a
  coordination surface to settle here. Record it; Phase C will add the
  frontmatter. See the work.md template in `/work` for shape.

---

## Step 3 — Present Phase A output

Show the user what Phase A found and what Phase B needs to settle
before the decomposition is final.

```
## Tentative decomposition — Phase A

Natural seams identified: <N>
  <short description of each seam>

Tentative work definitions (may shift after Phase B):
  WD-01 — <title> — <short description>
  WD-02 — <title> — <short description>
  ...

## Coordination surfaces needing settlement

Each of these crosses a WD boundary and must be settled before the
decomposition finalizes. Settling them may also move the seams.

| # | Kind            | Subject                           | Why it's cross-WD       |
|---|-----------------|-----------------------------------|-------------------------|
| 1 | breakdown ADR   | How to carve the X subsystem      | Seam shape unclear      |
| 2 | shared-data ADR | Canonical encoding for IDs        | WD-02, WD-03 both use   |
| 3 | interface spec  | Event contract for peer lifecycle | WD-01 produces, WD-02/3 consume |
| 4 | shared spec     | Key rotation cadence requirements | Enforcement across all WDs |

(or "None — seams are clear, no cross-WD settlement needed.")

## Existing artifacts that apply
  <list ADRs/specs already in the repo that constrain this decomposition>

## Research dispatched
  <list /research subagent invocations + resulting KB entries>
```

Use AskUserQuestion with options:
  - "Proceed to Phase B" (run the /architect and /spec-author passes)
  - "Pre-commit some decisions" (Other — specify what you already know)
  - "Defer all to /work-plan" (skip Phase B — fast, but expect WD-level divergence)
  - "Adjust the seams first" (Other — specify changes to tentative WDs)

If "Pre-commit some decisions": record the pre-committed choices, then
remove matching items from the surfaces list before proceeding.

If "Defer all": skip to Step 5 (Phase C) with the tentative decomposition.
Log a warning in manifest.md that Phase B was deferred.

If "Adjust the seams": apply changes and re-present.

If "Proceed to Phase B": continue to Step 4.

---

## Step 4 — Phase B: Architect and shared-spec authoring (user-serial)

Work through the coordination surfaces from Step 3 **one at a time**.
Architect passes require user deliberation and cannot be parallelized.

### When does an artifact belong in Phase B?

The test is **decidability**: an artifact belongs in Phase B if its
shape cannot be decided correctly from any single WD's perspective
alone. Phase B authoring brings input from all future producers and
consumers of the artifact at once, settling the shape before any of
them plan.

Concrete signals an artifact belongs in Phase B:
- **Multi-producer.** Two or more WDs will emit data matching the
  artifact's contract (the columnar telemetry signal schema is the
  canonical example — both memtable and reader emit signals; neither
  alone has the full picture).
- **Multi-consumer with shape ambiguity.** Multiple WDs will consume
  the artifact AND there's no canonical author whose perspective is
  authoritative. (Bilateral producer→consumer relationships generally
  do not need Phase B — the producer authors during `/work-plan` and
  consumers reference via `artifact_deps`.)
- **Cross-WD invariant.** The artifact enforces a property that must
  hold across multiple WDs (e.g., "all WDs must use the same encoding
  for IDs"). Local authoring would bake in one WD's needs and miss the
  others'.

When the test fails — the shape *is* decidable from one WD's
perspective — let it sequence. The producing WD authors during
`/work-plan`; downstream WDs reference via `artifact_deps` with
`required_state: APPROVED`. This is the Group Envelope (Gap 3) flow
and it's preferable when applicable: less coordination, less
authoring-while-decomposing, simpler sequencing.

For each surface that passes the decidability test, in order:

### Breakdown ADR (if seam shape was unclear)

Run this FIRST when Phase A couldn't cleanly identify seams:

```
Invoke `/architect "<decomposition problem>" context: "work-decompose
for <group-slug>, breakdown: how to carve <subsystem>"` as a sub-agent.
```

The breakdown ADR's output is the architectural model for the group —
it decides the shape of the problem space, which typically reveals the
natural seams. After it completes, **go back to Step 2c and re-draft
tentative WDs** using the breakdown ADR as input. Then continue with
the remaining surfaces.

### Shared-data ADR (for cross-WD decisions)

For each shared-data decision:

```
Invoke `/architect "<decision problem>" context: "work-decompose for
<group-slug>, shared-data across WDs <list>"` as a sub-agent.
```

The user deliberates interactively within the architect sub-agent. When
it returns, the ADR exists in `.decisions/<slug>/`. Record the ADR slug.

### Companion spec for each ADR

ADRs describe *why* a decision was made. Specs define *what the system
must do* as a result — they are the enforceable layer consumed by
`/work-plan`, `/feature-test`, `/audit`, and `/spec-verify`. For every
shared-data ADR that has behavioral implications across WDs, author a
companion spec:

```
Invoke `/spec-author "<domain>.<slug>" "<title>" context: "companion
spec for ADR <adr-slug>, shared across WDs <list>"` as a sub-agent.
```

After authoring, verify the spec is APPROVED in
`.spec/registry/manifest.json`. If DRAFT, falsification was incomplete
— stop and surface the error.

### Interface-contract specs (for cross-WD seams)

For each interface-contract seam identified in Step 3:

```
Invoke `/spec-author "<domain>.<interface-name>" "<title>" context:
"interface contract authored during work-decompose, consumed by WDs
<list>" --kind interface-contract` as a sub-agent.
```

Verify APPROVED state before continuing.

### Record Phase B outputs

As each surface settles, record the artifacts produced:
- ADR slugs
- Spec paths (with domain/slug)
- Interface-contract paths

These become the `artifact_deps:` WDs will reference in Step 6.

---

## Step 5 — Phase C: Finalize decomposition

With Phase B artifacts in hand, re-evaluate the tentative WDs from
Step 2c. Seams may have moved — a breakdown ADR or shared-data decision
often reveals a cleaner carve than the Phase A tentative. Re-chunk if
needed.

For each final WD, determine:
- Which Phase B artifacts it consumes → `artifact_deps` entries
- Which existing artifacts it consumes → `artifact_deps` entries
- Whether there's a WD-level ordering constraint → `wd:` dep entries
- What this WD will produce — **optional, leave empty if unclear**. WDs
  often produce WD-local specs during `/work-plan` that aren't worth
  predicting at decompose time. Only list `produces:` entries for
  artifacts whose shape is already settled (typically Phase B outputs
  that this WD is the author-of-record for).

### Choosing `required_state` for `wd:` deps

`wd:` deps take a `required_state`. Pick deliberately — the default
choice has real planning consequences:

- **`required_state: SPECIFIED`** — downstream WD can be PLANNED as
  soon as the upstream's spec is APPROVED (i.e., upstream `/work-plan`
  finished, even if implementation hasn't). Allows planning to overlap
  implementation: WD-03 can have its spec authored against WD-01's
  approved spec while WD-01 is still being implemented. Use this when
  the downstream consumes the upstream's *spec contract*, not its
  implemented behavior.

- **`required_state: COMPLETE`** — downstream WD cannot start planning
  until upstream is fully implemented. Strict sequential. Use this when
  the downstream needs the upstream's runtime behavior (e.g.,
  integration tests against the real implementation, not the spec).

Default: prefer `SPECIFIED` for spec-consumer relationships; reach for
`COMPLETE` only when there's a runtime-coupling reason. The Group
Envelope (Gap 3) reads `artifact_deps` to feed `/work-plan` — using
`SPECIFIED` extends the parallelism the envelope enables.

Present the final decomposition:

```
## Final decomposition — Phase C

Work definitions: <N>  (natural composition from the problem's seams)

| WD | Title | Domains | Consumes (deps) | Produces |
|----|-------|---------|-----------------|----------|
| WD-01 | <title> | <domains> | <artifact deps> | <produces, or "—"> |
| WD-02 | <title> | <domains> | <artifact deps> | — |
...

## Dependency Graph

WD-01 (no deps)
  └→ WD-02 (needs: <domain>/<spec> APPROVED from Phase B)
       └→ WD-03 (needs: WD-02 COMPLETE)

## Phase B artifacts produced
  ADRs: <list>
  Specs: <list>
  Interface contracts: <list>

## Invariant check
  Every cross-WD reference has a group-level artifact: ✓ | ✗
```

Use AskUserQuestion with options:
  - "Looks good — write these"
  - "Merge some WDs" (Other)
  - "Split a WD" (Other)
  - "Adjust dependencies" (Other)

If the invariant check fails, list the missing artifacts and do NOT
offer "Looks good" as an option until they're resolved — either author
them (loop back to Step 4) or declare them explicitly out of scope.

If any adjustment: apply and re-present from Step 5.

---

## Step 6 — Write work definitions

For each confirmed WD, write `.work/<group-slug>/WD-<NN>.md`:

```yaml
---
id: WD-<NN>
title: <title>
group: <group-slug>
status: DRAFT
domains: [<domain1>, <domain2>]
artifact_deps:
  # spec refs use `path:`. Either form is accepted:
  #   - slash form  : "<domain>/<spec-name>"  (matches .spec/domains/<...>)
  #   - ID form     : "<domain>.<spec-name>"  (matches the spec's id field)
  - { type: spec, path: "<domain>/<spec-name>", required_state: APPROVED }
  # adr refs use `slug:` (matches .decisions/<slug>/adr.md)
  - { type: adr, slug: "<decision-slug>", required_status: accepted }
  # wd refs use `ref:` (must point to a WD in the same group; cross-group
  # coordination uses external_deps: on work.md instead)
  - { type: wd, ref: "WD-<NN>", required_state: SPECIFIED }
produces:
  - { type: spec, path: "<domain>/<spec-name>" }
  - { type: spec, path: "<domain>/<interface-name>", kind: interface-contract }
  - { type: adr, slug: "<decision-slug>" }
---

## Summary
<2-3 sentence description of what this work definition accomplishes>

## Acceptance Criteria
<observable outcomes that confirm the work is complete>

## Implementation Notes
<constraints, dependency ordering notes, or considerations>
```

**Numbering:** WD-01, WD-02, etc. — sequential, zero-padded to 2 digits.

**artifact_deps rules:**
- Only list artifacts this WD needs whose state is part of the contract.
  Always-APPROVED foundational specs that every WD reads are listed by
  Phase A as "existing artifacts that apply" — they don't need per-WD
  declaration unless the WD specifically gates on them.
- `type: spec` uses `path:`. Both forms accepted:
  - **slash form** — `"<domain>/<spec-name>"`, matches `.spec/domains/<...>.md`
  - **ID form** — `"<domain>.<spec-name>"`, matches the spec's `id` field in
    `.spec/registry/manifest.json`
  Resolver and validator both accept either via `work_check_spec_dep`.
- `type: adr` uses `slug:` — matches `.decisions/<slug>/adr.md` (single token,
  no domain prefix).
- `type: kb` uses `path:` — matches `.kb/<path>.md`.
- `type: wd` uses `ref:` — a WD ID in the **same group** (e.g., `"WD-01"`).
  Cross-group coordination uses `external_deps:` on `work.md` instead; the
  validator rejects cross-group `wd:` refs with a pointer at `external_deps`.
- `spec`, `adr`, `wd` deps must include `required_state` or `required_status`.
  See "Choosing required_state for wd: deps" in Step 5 — `SPECIFIED` allows
  parallel planning; `COMPLETE` forces sequential. Don't reach for `COMPLETE`
  by reflex.
- `kb` deps are existence-only (no state check).

**produces rules:**
- **Optional.** Leave empty (`produces: []`) for any WD whose outputs aren't
  decided at decompose time — that's the honest case for most WDs, since
  their WD-local specs emerge during `/work-plan`.
- List `produces:` entries only when this WD is the author-of-record for a
  specific, already-scoped artifact — typically a Phase B interface contract
  or shared spec that another WD explicitly consumes via `artifact_deps`.
- Interface contracts use `kind: interface-contract`.
- Do NOT predict WD-local specs here — `/work-plan` authors them.

**Scope discipline — what belongs in the WD file:**
- Title, domains, summary, acceptance criteria — yes
- Pre-existing artifact_deps (Phase B outputs or earlier) — yes
- wd: ordering constraints — yes
- WD-local implementation plans — NO (that's `/work-plan`'s output)
- WD-internal architectural choices — NO (deferred to `/work-plan`)

If you find yourself wanting to write a lot of WD-local detail at this
stage, that's a signal the wrong stage is trying to do the work. Stop
and defer.

---

## Step 7 — Update manifest

Update `.work/<group-slug>/manifest.md`:

### Work Definitions table

Populate from the WD files:
```
| WD | Title | Status | Domains | Deps | Produces |
|----|-------|--------|---------|------|----------|
| WD-01 | <title> | DRAFT | <domains> | <dep count> | <produces summary> |
...
```

### Dependency Graph

Write the text dependency graph from Step 3.

### Update .work/CLAUDE.md

Update the Active Work Groups row for this group: set WDs count to the total.

### Run invariant check

```bash
bash .claude/scripts/work-validate.sh --group "<group-slug>" --decompose
```

This verifies that every cross-WD reference has a settled group-level
artifact (from Phase B or pre-existing). If the check fails, display the
unsettled references and offer options:
- "Re-open Phase B to settle them"
- "Mark them out of scope in work.md and proceed"
- "Stop"

Decomposition is not complete until the invariant passes or is
explicitly waived.

---

## Step 8 — Summary and next steps

```
Decomposition complete: <N> work definitions in '<group-slug>'.

  <N> with no dependencies (READY — ready for /work-plan)
  <N> blocked on artifact dependencies
  <N> Phase B artifacts produced this session

Next steps:

  1. Check readiness:
       /work-status "<group-slug>"

  2. Plan the highest-unblocking work definition:
       /work-plan "<group-slug>" next

     /work-plan MUST run on every WD — even when Phase B settled
     everything and planning has nothing to add, /work-plan transitions
     DRAFT → SPECIFIED so `/work-start` will accept the WD. Skipping
     /work-plan is not supported and would bypass WD-local scope
     discipline.
```

Stop.
