---
type: reference-fragment
title: Feature Footprint Entry Template
---
# Feature Footprint Entry Template

KB entries with `type: feature-footprint` are condensed records of what a
feature built, what domains it touched, and what was learned. Generated during
`/feature-retro`, they provide cross-reference context for future features
working in the same areas.

## Frontmatter

```yaml
---
title: "<feature-slug>"
type: feature-footprint
domains: ["<domain1>", "<domain2>"]
constructs: ["<ClassName>", "<InterfaceName>"]
applies_to:
  - "<file path pattern>"
related: []
decision_refs: []
spec_refs: []
research_status: stable
last_researched: "<YYYY-MM-DD>"
---
```

### Field definitions

- `type: feature-footprint` — distinguishes from research and adversarial entries
- `domains` — the domains this feature touched (from domain analysis)
- `constructs` — key types added or modified (public API surface)
- `applies_to` — file patterns this feature owns
- `related` — paths to other KB entries covering related concepts
- `decision_refs` — ADR slugs from `.decisions/` that governed this feature
- `spec_refs` — spec IDs from `.spec/` that this feature implements
- `research_status: stable` — footprints don't go stale in the same way;
  they're historical records. Use `stable` (12-month staleness review)

## Required sections

```markdown
# <feature-slug>

## What it built
<!-- 2-3 sentences: what capability was added -->

## Key constructs
<!-- List of new/modified types with one-line descriptions -->
- `ConstructName` — <what it does>

## Adversarial findings
<!-- Patterns discovered during aTDD or audit; cross-ref to adversarial-finding KB entries -->
- <finding-name>: <one-line summary> → [KB entry](<path>)

## Cross-references
<!-- Links to ADRs, other KB entries, related features -->
- ADR: .decisions/<slug>/adr.md
- Related features: <feature-slugs that depend on or extend this>
```

## How it's used

- **Domain scout** reads footprints during `/feature-domains` to understand
  what prior work exists in a domain
- **Curation** (`/curate`) cross-references footprints with git history to
  detect drift, stale dependencies, and orphaned code
- **Test writer** uses the adversarial findings section to find relevant
  patterns when writing tests for constructs in the same domain
