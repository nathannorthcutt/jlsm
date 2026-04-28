---
description: "Subdivide a mature spec into a parent + child sub-domain specs"
argument-hint: "<spec-id>"
---

# /spec-split <spec-id>

Subdivide a mature spec into a parent + concern-specific children. The
parent stays a full spec — it retains R-numbered cross-cutting
requirements that span all children — while each child owns the
requirements for its concern. Subdivision is a **natural progression**
for a domain that has accumulated multiple distinct concerns. It is
not a remediation step.

Typical entry points:
- `/curate` flagged this spec as a subdivision candidate.
- `/spec-author` Pass 2 surfaced a just-in-time signal during an
  amendment.
- You ran `/spec-split` directly because you know the spec has grown
  past coherence into multiple concerns.

For the conceptual model and file layout, see `.spec/CLAUDE.md`
"Layered specs" section.

---

## Step 1 — Resolve the parent spec

Resolve `<spec-id>` to its file via the registry:

```bash
SPEC_PATH="$(jq -r --arg id "$1" '.specs[] | select(.id == $id) | .path' .spec/registry/manifest.json)"
```

If empty: report "spec not found in registry" and stop.

Read the spec file. Display the spec's current state:

```
Spec: <spec-id>
  Version: <version>
  State: <state>
  Total requirements: <N>
  File size: <K> tokens (~<KB> KB)
  Children today: <list of any existing children, or "none">
```

If the spec already has children listed in the manifest with
`parent_spec == <spec-id>`, this is a re-split — proceed but tell the
user the existing children will not be moved.

---

## Step 2 — Identify natural concern boundaries

Read the spec's machine section. Group requirements by:

1. **Section headers** — many specs already have `## <Concern>`
   subsections under the machine section (Pass 1 of `/spec-author`
   produces these by behavioral category). Each such header is a
   candidate concern boundary.
2. **Behavioral category** — even without explicit headers, requirements
   often cluster by what aspect of behavior they describe: lifecycle,
   validation, error handling, persistence, etc.
3. **Subject-token clusters** — requirements that share dominant
   subject tokens (e.g. "rotation", "DEK", "revocation") tend to
   cluster.
4. **Cross-references** — requirements that frequently cite each other
   are coupled and should usually live together. Requirements that
   sit alone (no internal cross-refs) are candidates for cross-cutting
   placement at parent.

Identify cross-cutting requirements as those that:
- Are referenced by requirements in multiple proposed concern groups,
  OR
- State umbrella invariants that any concern's behavior must respect
  (e.g. "all DEKs must be wrappable under their tenant root key"),
  OR
- Define glossary-like contracts the children build on top of.

**Output:** an internal proposal (don't surface yet) with:

- A list of concern groups, each with: a slug, a 2-5 word title, a
  one-sentence summary, and the list of R-numbers it claims.
- A list of cross-cutting R-numbers that stay at the parent.

If your analysis cannot find at least 2 distinct concerns, **stop and
tell the user**: "This spec doesn't subdivide cleanly — its
requirements form a single coupled concern. Subdivision would fragment
the coherent contract. Recommend leaving it as-is or amending the
content rather than splitting." Do NOT propose a split that doesn't
hold together.

---

## Step 3 — Present the proposal and confirm

Display the proposed split:

```
── Proposed subdivision ─────────────────────────
Parent: <spec-id>
  Cross-cutting (stays at parent): R<n>, R<n>, R<n>  (<count> reqs)

Children:
  <slug-1> — <title>             (<count> reqs)
    Requirements: R<n>, R<n>, R<n>, ...
    Summary: <one-line description>

  <slug-2> — <title>             (<count> reqs)
    Requirements: R<n>, R<n>, R<n>, ...
    Summary: <one-line description>

Each child becomes:
  <spec-id>.<slug>
  .spec/domains/<top>/<rest>/<slug>.md

@spec annotation rewrites:
  <count> moved requirements → annotations rewritten in source dirs
  Cross-cutting annotations stay as-is.
```

**Use AskUserQuestion** with options:
- "Proceed with this split"
- "Edit the proposal" — re-prompt the user to adjust concern
  boundaries or move requirements between groups
- "Cancel"

If "Edit": ask the user what to change. After their response, regenerate
the proposal and re-confirm via AskUserQuestion. Loop until they
approve or cancel.

If "Cancel": stop. No changes made.

---

## Step 4 — Build the split plan and dry-run

Write the confirmed proposal to a JSON plan file:

```bash
PLAN_FILE=".spec/.split-plan.json"
cat > "$PLAN_FILE" <<EOF
{
  "parent_id": "<spec-id>",
  "children": [
    {
      "id": "<spec-id>.<slug-1>",
      "title": "<title-1>",
      "domains": ["<domain>"],
      "requirements": ["R<n>", "R<n>", ...]
    },
    ...
  ]
}
EOF
```

Run a dry-run first to validate the plan structure:

```bash
bash .claude/scripts/spec-split.sh --plan "$PLAN_FILE" --dry-run
```

If the dry-run fails (it shouldn't, since you built the plan from a
known-valid proposal — but the script enforces stricter invariants),
surface the error to the user and stop. Common dry-run failures:

- A child id doesn't extend parent by exactly one segment (you put
  more than one dot in the child's slug). Children at this layer must
  be immediate children only; deeper nesting requires a separate
  later split.
- A requirement was claimed by two children (proposal had a typo).
- Every requirement was claimed by some child, leaving no
  cross-cutting set at parent. The script requires at least one
  cross-cutting requirement.

---

## Step 5 — Execute the split

Run the script for real:

```bash
bash .claude/scripts/spec-split.sh --plan "$PLAN_FILE"
```

The script:
1. Snapshots the parent file + manifest into a rollback log under
   `.spec/.split-log/<id>-<timestamp>.json`.
2. Carves child files from the parent's body, preserving R-number
   identity (R45 stays R45 in its new home).
3. Rewrites the parent to retain only cross-cutting requirements and
   bumps its version.
4. Updates the manifest with child entries, each carrying
   `parent_spec: <spec-id>`.
5. Sweeps `@spec parent.Rxx` annotations in source dirs (auto-detected:
   `src/`, `lib/`, `app/`, `main/`, `modules/`, `examples/`,
   `benchmarks/` — never `test/`, `tests/`, `__tests__`, `spec/`,
   `node_modules/`, `vendor/`, `target/`, `build/`, `dist/`). Use
   `--scan-dirs <csv>` if your project's source layout differs.
6. Runs `spec-validate.sh` on parent + every child.
7. **On any post-write validation failure, automatically replays the
   rollback log** — parent file restored, children deleted, manifest
   reverted, annotation rewrites reversed. Rollback log is preserved
   for one release in case manual inspection is needed.

If the script exits non-zero, surface its stderr to the user. Do not
attempt to "fix" the situation manually — the rollback has already
restored the prior state. The most common cause is a frontmatter
field mismatch in the parent or a child; investigate and re-run.

---

## Step 6 — Post-split verification

After a successful split, verify the family by running spec-trace on
each child:

```bash
for child_id in <list-of-child-ids>; do
  bash .claude/scripts/spec-trace.sh "$child_id" 2>/dev/null \
    | head -20
done
```

Confirm each rewritten annotation resolves cleanly. If any child shows
an annotation that doesn't resolve (typically "no annotations found"
when there should be some), that's a sign the rewrite missed the file
because of a non-standard scan dir; re-run with `--scan-dirs` or
manually annotate.

Display the success summary:

```
───────────────────────────────────────────────
✂️  SPEC SPLIT complete · <spec-id>
───────────────────────────────────────────────
Parent: <spec-id>  (<count> cross-cutting reqs)
Children:
  <slug-1>  →  <count> reqs
  <slug-2>  →  <count> reqs
@spec rewrites: <N> annotations across <M> files
Rollback log: <path>
───────────────────────────────────────────────
```

If the split was triggered by `/curate` or `/spec-author` Pass 2,
remind the user that any open work definitions or follow-up audits
referencing the parent will now resolve to the parent's cross-cutting
contract; concern-specific work now references the new child IDs.

---

## When NOT to subdivide

Subdivision fragments a coherent contract. Don't do it when:

- The spec is large but the concern is **indivisible** — every
  requirement reinforces the same behavior, just with different
  inputs.
- The category clusters are **not load-bearing yet** — fewer than ~10
  requirements per candidate child means subdivision is premature.
- The parent's requirements are **already structured well** as a
  single coherent contract, and the size is justified by depth in
  one concern (e.g. a single complex algorithm).

In any of these cases, decline the split. Tell the user honestly:
"This spec is mature but its concerns are interlocked — subdividing
would make adversarial review weaker, not stronger. Keeping it whole
is correct."

---

## Recursion

A child can itself subdivide later via `/spec-split <child-id>`. The
mechanics are identical; the file system grows another nesting level.
There is no hard cap on depth; in practice three levels is unusual
and four would suggest the domain model itself needs rethinking.

## Errors and recovery

- **Plan validation failed (no cross-cutting reqs)**: a successful
  split must leave at least one R-number at parent. Move at least
  one requirement out of the children's lists.
- **Child file already exists**: a previous split was attempted and
  the child files weren't cleaned up. Inspect; either remove the
  stale child files manually or pick different child slugs.
- **spec-validate failed post-split**: the script auto-rolled back.
  Read the validate output, fix the issue (typically a frontmatter
  field), re-run.
- **Manual rollback after the fact**: every split writes a JSON
  rollback log at `.spec/.split-log/<id>-<timestamp>.json`. Replay
  with `bash .claude/scripts/spec-split.sh --rollback <log>`.
