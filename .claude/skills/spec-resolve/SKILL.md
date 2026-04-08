---
description: "Resolve a context bundle of relevant specs for a feature"
argument-hint: "<feature-description>"
---

# /spec-resolve <feature-description>

Build a resolved context bundle of relevant specifications for a feature.
The bundle is a pre-filtered, token-budgeted input artifact for the
implementation agent. You never navigate the `.spec/` corpus manually —
the bash resolver does all file discovery and filtering.

---

## Pre-flight guard

Run corpus health check:
```bash
bash .claude/scripts/spec-stats.sh
```
If output says "Not initialized", tell the user to run `/spec-init` first.
Stop.

---

## Step 1 — Run the resolver script

```bash
bash .claude/scripts/spec-resolve.sh "$ARGUMENTS" 8000
```

Capture the full output as BUNDLE.

---

## Step 2 — Domain completeness check (single inference step)

Read the `Domains matched:` line from the bundle header.

Then read ONLY `.spec/CLAUDE.md` — specifically the Domain Taxonomy table.
No other files.

Ask yourself: given the feature description, could there be relevant
domains not captured by keyword matching? Consider:
- Does the feature touch interfaces owned by an unmatched domain?
- Does the feature description use synonyms or abstractions that don't
  appear literally as domain names?

If you identify missing domains, run once more with override:
```bash
OVERRIDE_DOMAINS="storage,query,compaction" \
  bash .claude/scripts/spec-resolve.sh "$ARGUMENTS" 8000
```
Replace BUNDLE with this output.

**Hard limit: maximum two script invocations. Do not loop.**

If after two runs the domain set still seems incomplete, append a note
to the bundle header:
```
Possible missing domains: <your assessment> — load manually if needed
```

---

## Step 3 — Handle budget omissions

If `Omitted (budget):` is non-empty, append to the bundle:
```
## Budget Omissions
These specs were excluded due to token budget. If your feature directly
modifies their interfaces, load them individually:
<list omitted IDs>
```

---

## Step 4 — Output the bundle

Emit the complete bundle. Do not summarize, rewrite, or interpret it.
The bundle is an input artifact for the implementation agent.

---

## Hard constraints

- Maximum two script invocations per resolve call
- Never read spec files outside what the script returns
- Never invoke /spec-resolve recursively
- If NEEDS_DOMAIN_INFERENCE=true appears in script stderr, that is
  handled by step 2 — it is not an error
