# Batch Spec Author

Generate hardened specifications for all features that don't have one yet.
Process each feature serially. Auto-confirm all prompts — do not wait for
user input at any checkpoint.

---

## Process

### 1. Build the work list

Read `.spec/domains/` to find existing specs (any file matching `F*-*.md`).
Read `.feature/` to find all feature slugs (exclude `_archive/`).

A feature needs a spec if no file in `.spec/domains/*/` contains the
feature slug in its filename. Build the list of features without specs.

### 2. For each feature without a spec

Process one at a time. For each:

#### 2a. Read feature context

Read these files for the feature:
- `.feature/<slug>/brief.md` — the feature description
- `.feature/<slug>/domains.md` — domain analysis (if exists)
- `.feature/<slug>/work-plan.md` — work plan (if exists)

If `brief.md` does not exist, skip this feature with a note:
`[SKIP] <slug> — no brief.md`

#### 2b. Run spec authoring (Pass 1 — Structured Draft)

Follow the spec-author process from `.claude/skills/spec-author/SKILL.md`,
Pass 1 only:
- Extract surface requirements from the brief/domains/work-plan
- Identify prerequisites against existing specs
- Expand by operational sequence
- Expand by failure mode
- Collapse user decisions, expand requirements

Write the draft spec. **Do not stop for user confirmation.** Proceed
directly to Pass 2.

#### 2c. Run spec authoring (Pass 2 — Adversarial Falsification)

Launch a subagent for adversarial falsification. The subagent receives
the complete draft spec and feature context. It must:
- Use prove/disprove framing: "This requirement is complete and
  unfalsifiable. Disprove it by constructing a concrete attack."
- Trace enforcement paths for new requirements
- Check cross-requirement interactions
- Surface uncertain findings

**Do not stop for user arbitration.** Auto-accept all confirmed gaps
and implementation constraints. Drop uncertain findings — they can be
reviewed later.

#### 2d. Write the spec

Assemble the final spec (Pass 1 + confirmed Pass 2 findings).

Determine the spec ID: use the next available F-number by checking
`.spec/registry` for the highest existing ID.

Determine the domain: use the feature's domain analysis if available,
otherwise infer from the brief.

Write the spec file to `.spec/domains/<domain>/F<NN>-<slug>.md`.

Run the spec registration:
```bash
bash .claude/scripts/spec-validate.sh
```

#### 2e. Report progress

After each feature, print:
```
[N/total] <slug> — <requirement count> requirements, <domain>
```

### 3. Summary

After all features are processed, print:

```
## Batch Spec Author Complete

| Feature | Requirements | Domain | Spec ID |
|---------|-------------|--------|---------|
| ... | | | |

Total: <n> features processed, <skipped> skipped
```

## Rules

- Do NOT wait for user input at any point
- Do NOT run audits, tests, or implementation work
- Do NOT modify existing specs — only create new ones
- Process features in alphabetical order by slug
- Each spec authoring (Pass 1 + Pass 2) runs in a subagent to keep
  orchestrator context clean
- If a subagent fails or times out, log the failure and move to the
  next feature
