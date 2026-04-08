---
description: "Register a spec file in the manifest and validate it"
argument-hint: "<feature-id> <title>"
---

# /spec-write <feature-id> <title>

Register a spec file in the manifest, validate its structure, and update
indexes. This is the mechanical registration step — run it after
`/spec-author` has produced the hardened spec content, or when registering
a prerequisite stub.

For authoring hardened specs with adversarial review, use `/spec-author`
first, then `/spec-write` to register the output.

---

## Inputs expected in context

- The feature ID and title passed as arguments
- A complete spec file (from /spec-author or manual creation)
- Optionally, a resolved context bundle from /spec-resolve

---

## Step 1 — Resolve context and check for conflicts

If no context bundle is already in your context, run the resolver:
```bash
bash .claude/scripts/spec-resolve.sh "<feature description>" 8000
```

Read the resolved bundle. For each existing spec in the bundle:
- Check whether this feature's behavior contradicts any existing requirement
- If a conflict exists, you MUST either:
  (a) Add the conflicting requirement to `invalidates` (explicit override), or
  (b) Adjust this feature's requirements to avoid the conflict
- If you override with `invalidates`, note in the Design Narrative why the
  prior requirement no longer holds

This is not optional. A spec that silently contradicts an existing
requirement is a defect in the spec corpus.

---

## Step 2 — Identify prerequisites

For each assumption this feature makes about existing components (behaviors,
contracts, invariants that must be true for this feature to work), check
whether a spec exists for that component:

```bash
jq -r '.features | keys[]' .spec/registry/manifest.json
```

**If a spec exists:** Add it to `requires`. Verify that the existing spec's
requirements cover what this feature needs. If not, add the missing
requirements to the existing spec (increment its version) or note the gap
in `open_obligations`.

**If no spec exists:** Create a **prerequisite stub spec** for the component.
This is a minimal spec covering only the requirements this feature depends on:

1. Verify each assumed requirement holds in the current code (read the
   relevant implementation)
2. For requirements that hold: write them as numbered requirements
3. For requirements you cannot verify: annotate with
   `[UNVERIFIED: assumes X — source needed before implementation]`
4. Write the stub spec with `state: "APPROVED"` for verified requirements
   (you just verified them), or `state: "DRAFT"` if any requirement is
   UNVERIFIED
5. Add a verification note recording what you checked and when
6. Register the stub in the manifest
7. Add the stub's ID to this feature's `requires` array

Prerequisite stubs are intentionally minimal — they cover only what the
current feature needs, not the full contract of the component. Future
features that depend on the same component will add their own requirements,
growing the spec incrementally.

---

## Step 3 — Determine domain assignment

From the context bundle header `Domains matched:`, take those as the
primary domains. If the implementation touched interfaces from additional
domains not in the bundle, add them.

If no context bundle is available, read `.spec/CLAUDE.md` Domain Taxonomy
and assign domains based on the feature description.

---

## Step 4 — Identify cross-references

**amends:** Feature IDs whose requirements this feature changes or
supersedes (partial overlap allowed).

**requires:** Feature IDs whose interfaces this feature depends on at
runtime (not just at build time). Includes prerequisite stubs created
in Step 2.

**invalidates:** Specific `FXX.RN` requirement IDs this feature makes
obsolete. Use exact format: `F01.R3`. Cross-check against the context
bundle requirements to confirm they exist before writing them.
MUST include any conflicts identified in Step 1.

**decision_refs:** ADR slugs from `.decisions/` that informed this feature.
Check that `.decisions/<slug>/adr.md` exists.

**kb_refs:** KB paths from `.kb/` that informed this feature.
Check that `.kb/<path>.md` exists.

---

## Step 5 — Determine version and file path

Check the registry for existing versions of this feature:
```bash
jq -r --arg id "$FEATURE_ID" '.features[$id].latest_file // ""' \
  .spec/registry/manifest.json
```

If no entry exists: version = 1, filename = `F<nn>-<slug>.md`
If entry exists: increment version, filename = `F<nn>-<slug>.v<N>.md`

The slug is the title lowercased with spaces replaced by hyphens,
truncated to 30 chars. Example: `F07-compaction-engine.md`

---

## Step 6 — Determine target domain shard directory

Primary domain = first entry in domains array.
Directory = `.spec/domains/<primary-domain>/`

---

## Step 7 — Write the spec file

Use this exact structure:

```
---
{
  "id": "<feature-id>",
  "version": <N>,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [<domain list>],
  "amends": [<feature ids or empty>],
  "amended_by": [],
  "requires": [<feature ids or empty>],
  "invalidates": [<FXX.RN refs or empty>],
  "decision_refs": [<ADR slugs or empty>],
  "kb_refs": [<KB paths or empty>],
  "open_obligations": [<obligation strings or empty>]
}
---

# <feature-id> — <Title>

## Requirements
R1. <single falsifiable claim with explicit subject and measurable condition>
R2. ...

---

## Design Narrative

### Intent
<Why this feature exists — not what it does>

### Why this approach
<Rationale for the chosen design>

### What was ruled out and why
<Rejected alternatives with their disqualifying constraints>
<If none known: "Not captured at authoring time.">

### Invalidated requirements
<If any invalidates entries: explain why each prior requirement no longer holds>
<If none: omit this section>

## Verification Notes
```

**Requirement writing rules:**
- One falsifiable claim per requirement
- Explicit subject: "The vector index must..." not "Must..."
- Measurable condition where applicable
- No compound requirements: no "and" joining two obligations
- Present tense, active voice
- Unverified claims annotated: `[UNVERIFIED: assumes X — source needed]`
- **Behavioral, not structural:** requirements must be verifiable by observing
  inputs and outputs, not by reading source code. Never reference specific
  class names, method names, file paths, or call chains.

**State assignment rules:**

The `state` field determines whether a spec enters resolved context bundles
consumed by downstream pipeline stages (feature-plan, feature-test, etc.).

- If all requirements are verified and no conflicts remain from the
  arbitration phase: set `state: "APPROVED"`
- If any conflict was deferred, dropped, or left unresolved during
  arbitration: set `state: "DRAFT"` and add each unresolved conflict to
  `open_obligations` — for example:
  `"open_obligations": ["resolve conflict between R3 and F02.R1"]`
- A DRAFT spec with `open_obligations` entries or `[UNRESOLVED]`/`[CONFLICT]`
  markers in its requirements is **excluded from resolved bundles**. It will
  not be consumed by downstream stages until the conflicts are resolved and
  the state is changed to APPROVED.

---

## Step 8 — Normalize and validate

```bash
sed -i 's/\r//' "<spec-file-path>"
bash .claude/scripts/spec-validate.sh "<spec-file-path>"
```

If validation FAILS, fix all reported errors before proceeding.
Do not proceed past this step with a failing spec.

---

## Step 9 — Update shard INDEX.md

Add a row to the Feature Registry table in the domain shard's INDEX.md:
```
| <ID> | <Title> | ACTIVE | <amends or —> | <decision_refs or —> |
```

---

## Step 10 — Register in manifest

Update the manifest with the new spec:
```bash
tmp=$(mktemp)
jq --arg id "<feature-id>" \
   --arg lf "domains/<primary-domain>/<filename>" \
   --arg st "DRAFT" \
   --argjson doms '["<domain1>","<domain2>"]' \
   '.features[$id] = {"latest_file": $lf, "state": $st, "domains": $doms}' \
   .spec/registry/manifest.json > "$tmp" \
   && mv "$tmp" .spec/registry/manifest.json
```

Increment the domain feature_count:
```bash
tmp=$(mktemp)
jq --arg d "<primary-domain>" '.domains[$d].feature_count += 1' \
  .spec/registry/manifest.json > "$tmp" \
  && mv "$tmp" .spec/registry/manifest.json
```

---

## Step 11 — Update .spec/CLAUDE.md

Add a row to the Recently Added table:
```
| <date> | <ID> | <primary-domain> | <Title> |
```

Keep only the 10 most recent rows. Move older rows off the table.

---

## Step 12 — Confirm

Report:
```
Spec written: .spec/domains/<domain>/<filename>
Validation: PASS
Registry: updated

Prerequisites created: <count or "none">
  <list of stub spec IDs if any>

Conflicts resolved: <count or "none">
  <list of invalidated requirements if any>

Reverse dependencies (specs requiring this feature's prerequisites):
  <query manifest for specs that require the same IDs>

Next: run /spec-verify <feature-id> after implementation review
```

---

## Hard constraints

- Never skip the conflict check (Step 1) — silent contradictions are defects
- Never write a prerequisite stub without verifying requirements against code
- Prerequisite stubs with all requirements verified are APPROVED immediately
- Never skip spec-validate.sh — fix all errors before proceeding
- Never use YAML syntax in front matter — JSON only
- Never omit the human narrative separator (bare --- line)
- Requirements must be numbered R1, R2, R3 — never lettered or nested
- If invalidates references cannot be confirmed against context bundle,
  leave the array empty and note it in open_obligations
- Never set state to APPROVED if unresolved conflicts exist — use DRAFT with
  open_obligations entries so the spec is excluded from resolved bundles
