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

## Step 2 — Identify work boundaries

Analyze the work group scope from work.md. Identify natural boundaries where:

- **Artifact dependencies create seams** — one body of work produces artifacts
  (specs, ADRs, interface contracts) that another body consumes.
- **Domain boundaries separate concerns** — work in different domains can
  proceed independently.
- **Ordering constraints force sequencing** — some work must complete before
  other work can start.
- **Interface contracts mediate interaction** — when two bodies of work need
  to agree on a shared surface, the interface definition is its own work unit
  (or part of the first unit that produces the contract).

### Internal analysis (do not display yet)

For each proposed work definition, draft:
- A title (imperative verb phrase)
- Which domains it touches
- What artifacts it depends on (existing specs, ADRs, KB entries)
- What artifacts it produces (new specs, interface contracts, ADRs)
- Rough ordering: what must come before/after

---

## Step 3 — Present decomposition

Present the proposed work definitions as a table:

```
## Proposed Work Definitions

| WD | Title | Domains | Depends on | Produces |
|----|-------|---------|------------|----------|
| WD-01 | <title> | <domains> | <artifact deps or "none"> | <produced artifacts> |
| WD-02 | <title> | <domains> | <artifact deps> | <produced artifacts> |
...

## Dependency Graph

WD-01 (no deps)
  └→ WD-02 (needs: auth/jwt-token-contract from WD-01)
       └→ WD-03 (needs: auth/middleware-interface from WD-02)
WD-04 (no deps, parallel with WD-01)

## Shared Interface Contracts

| Interface | Produced by | Consumed by | Domain |
|-----------|------------|-------------|--------|
| <name> | WD-01 | WD-02, WD-03 | <domain> |
...
(or "None needed — work definitions are independent.")
```

### Scope signal

If any WD has >5 artifact dependencies, flag it:
```
⚠ WD-03 has 7 artifact dependencies — consider splitting into smaller units.
```

---

## Step 4 — Confirm with user

Use AskUserQuestion with options:
  - "Looks good — write these"
  - "Merge some WDs" (with Other for specifics)
  - "Split a WD" (with Other for specifics)
  - "Adjust dependencies" (with Other for specifics)

If any adjustment: apply changes and re-present from Step 3.

---

## Step 5 — Write work definitions

For each confirmed WD, write `.work/<group-slug>/WD-<NN>.md`:

```yaml
---
id: WD-<NN>
title: <title>
group: <group-slug>
status: DRAFT
domains: [<domain1>, <domain2>]
artifact_deps:
  - { type: spec, path: "<domain>/<spec-name>", required_state: APPROVED }
  - { type: adr, slug: "<decision-slug>", required_status: accepted }
produces:
  - { type: spec, path: "<domain>/<spec-name>" }
  - { type: spec, path: "<domain>/<interface-name>", kind: interface-contract }
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
- Only list artifacts this WD needs that don't yet exist or aren't in the
  required state. Don't list artifacts that already exist and are APPROVED.
- `type: spec` — path is relative to `.spec/domains/`, must include domain prefix
- `type: adr` — slug matches `.decisions/<slug>/adr.md`
- `type: kb` — path matches `.kb/<path>.md`
- `type: wd` — ref is a WD ID in the same group (e.g., "WD-01"). Use for
  code-level dependencies where one WD's implementation must complete before
  another can start. Unlike artifact deps, wd deps express ordering constraints
  that aren't mediated by a spec or ADR artifact.
- spec, adr, and wd deps must include `required_state` or `required_status`
- kb deps are existence-only (no state check)

**produces rules:**
- List artifacts this WD will create as part of its specification phase
- Interface contracts use `kind: interface-contract`

---

## Step 6 — Update manifest

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

---

## Step 7 — Summary and next steps

```
Decomposition complete: <N> work definitions in '<group-slug>'.

  <N> with no dependencies (ready for specification)
  <N> with dependencies (blocked until deps are met)
  <N> shared interface contracts identified

Next steps:

  1. Check readiness:
       /work-status "<group-slug>"

  2. Specify the first ready work definition:
       /work-plan "<group-slug>" next

  Or implement directly (if specs already exist):
       /work-start "<group-slug>" next

  3. Or specify interface contracts first (recommended when
     multiple WDs share surfaces):
       /spec-author "<interface-name>" for each shared interface
```

Stop.
