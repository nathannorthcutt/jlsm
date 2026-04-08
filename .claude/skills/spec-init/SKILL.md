---
description: "Bootstrap the spec corpus for this project"
argument-hint: ""
---

# /spec-init

Bootstrap the `.spec/` directory structure for operational specifications.
Creates the registry, domain taxonomy, and shard indexes. Run once per project.

---

## Pre-flight guard

1. Check that `jq` is installed:
   ```bash
   command -v jq >/dev/null 2>&1
   ```
   If missing, tell the user to install jq and stop.

2. Check that `.spec/CLAUDE.md` exists. If not:
   ```
   The spec system seed file is missing. Run /setup-vallorcine or
   /upgrade-vallorcine to install the latest vallorcine version first.
   ```
   Stop.

3. Check that `.spec/registry/manifest.json` does NOT exist. If it does:
   ```
   Spec corpus already initialized. Use /spec-write to add specs
   or /spec-resolve to query the corpus.
   ```
   Stop.

---

## Step 1 — Collect domain taxonomy

Ask the user:

```
What are the major technical domains in this project?

Domains group related specifications. Examples:
- storage, networking, encryption, query, compaction
- auth, billing, api, persistence, messaging

List 4-10 domain names (comma-separated), each with a 5-10 word description.
Or say "infer" and I'll suggest domains based on the codebase.
```

If the user says "infer":
- Read the project's top-level directory structure and any existing
  `.kb/CLAUDE.md` topic map and `.decisions/CLAUDE.md` decision index
- Suggest 4-10 domains based on what you find
- Wait for user confirmation or adjustment

Each domain needs: a slug (lowercase, hyphenated) and a description
(keywords that the resolver uses for matching).

---

## Step 2 — Scaffold registry

Create the registry directory and files:

```bash
mkdir -p .spec/registry
```

Write `.spec/registry/manifest.json`:
```json
{
  "version": 1,
  "generated": "<ISO-8601 timestamp>",
  "domains": {
    "<domain-slug>": {
      "shard_path": "domains/<domain-slug>/INDEX.md",
      "description": "<domain description keywords>",
      "feature_count": 0
    }
  },
  "features": {}
}
```

Write `.spec/registry/_obligations.json`:
```json
{
  "version": 1,
  "obligations": []
}
```

---

## Step 3 — Create domain directories and shard indexes

For each domain:

```bash
mkdir -p .spec/domains/<domain-slug>
```

Write `.spec/domains/<domain-slug>/INDEX.md`:
```markdown
# <Domain Name> — Spec Index

> Shard index for the <domain> domain.
> Split this file when it exceeds ~50 entries.

## Feature Registry

| ID | Title | Status | Amends | Decision Refs |
|----|-------|--------|--------|---------------|
```

---

## Step 4 — Update .spec/CLAUDE.md

Add a row to the Domain Taxonomy table in `.spec/CLAUDE.md` for each domain:

```
| <domain-slug> | domains/<domain-slug>/ | <description> | 0 |
```

---

## Step 5 — Ingest existing specs (optional)

Ask the user:

```
Do you have any existing spec files to import? If so, provide the
directory path. Otherwise, say "skip" to start with an empty corpus.
```

If files are provided:
1. For each file, run structural validation:
   ```bash
   bash .claude/scripts/spec-validate.sh "<file>"
   ```
2. If validation passes, copy to the appropriate domain directory
3. Register in manifest via `spec_registry_update()`
4. Update the domain shard INDEX.md
5. Update domain feature_count in manifest

If validation fails for any file, report the errors and skip that file.

---

## Step 6 — Final validation

Run corpus health check:
```bash
bash .claude/scripts/spec-stats.sh
```

Report the results and confirm initialization is complete:

```
Spec corpus initialized.
  Domains: <count>
  Specs: <count> (if any were ingested)

Next steps:
  /spec-write "<id>" "<title>"  — author a new spec
  /spec-resolve "<description>" — resolve context for a feature
```

---

## Hard constraints

- Never create `.spec/decisions/` or `.spec/changelog/` — decisions live in
  `.decisions/`, not duplicated inside `.spec/`
- Never overwrite an existing manifest.json — this skill is for first-time
  bootstrap only
- All file writes use atomic tmp + mv pattern for JSON files
- Domain slugs must be lowercase with hyphens only (no spaces, no underscores)
