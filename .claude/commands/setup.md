# /setup

Verifies the knowledge base and decisions directory structures are in place.
Run once when first adding these agents to a project, or any time you want
to confirm the structure is intact. Safe to re-run — skips files that exist.

## Step 1 — Check what exists

Inspect the project for each of these files and report FOUND or MISSING:
- .kb/CLAUDE.md
- .kb/_refs/complexity-notation.md
- .kb/_refs/benchmarking-methodology.md
- .decisions/CLAUDE.md
- Root CLAUDE.md (check for .kb/ and .decisions/ pointer blocks)

## Step 2 — Create .kb/ structure (missing files only)

**.kb/CLAUDE.md**

```markdown
# Knowledge Base — Root Index

> Pull model. Navigate: topic → category → subject file.
> Do not scan this directory recursively.
> Structure: .kb/<topic>/<category>/<subject>.md

## Topic Map

| Topic | Path | Categories | Files | Last Updated |
|-------|------|------------|-------|--------------|

## Recently Added (last 10)
| Date | Topic | Category | Subject |
|------|-------|----------|---------|

## Shared References
`_refs/complexity-notation.md` — notation key used in algorithm files
`_refs/benchmarking-methodology.md` — how benchmark figures are cited

Older entries: [_archive.md](_archive.md)
```

**.kb/_refs/complexity-notation.md**

```markdown
---
type: reference-fragment
title: Complexity Notation Guide
---
# Complexity Notation Used In This Knowledge Base

- **n** — number of items in the index / dataset size
- **d** — dimensionality of vectors
- **k** — number of nearest neighbors requested
- **M** — graph connectivity parameter (HNSW-specific)
- **nprobe** — number of cluster probes at query time (IVF-specific)
- All complexities are average-case unless marked ⚠️ worst-case
- Space complexity counts index storage only, not raw data storage
```

**.kb/_refs/benchmarking-methodology.md**

```markdown
---
type: reference-fragment
title: Benchmarking Methodology
---
# How Benchmarks Are Cited

Unless otherwise noted, performance figures reference the ANN-Benchmarks
suite (https://ann-benchmarks.com) under these conditions:
- Datasets: GloVe-100 (1.2M 100-dim vectors) or SIFT-1M (1M 128-dim vectors)
- Hardware: single CPU core unless GPU explicitly noted
- Metric: queries-per-second (QPS) at recall@10 = 0.90
- All figures are approximate — hardware variance is ±15%

When citing non-ANN-Benchmarks figures, state dataset, hardware, and metric explicitly.
```

## Step 3 — Create .decisions/ structure (missing files only)

**.decisions/CLAUDE.md**

```markdown
# Architecture Decisions — Master Index

> Pull model. Load on demand only.
> Structure: .decisions/<problem-slug>/adr.md
> Full history: [history.md](history.md)

## Active Decisions
<!-- Proposed or in-progress only. Accepted/superseded rows move to history.md. -->

| Problem | Slug | Date | Status | Recommendation |
|---------|------|------|--------|----------------|

## Recently Accepted (last 5)
<!-- Once this section exceeds 5 rows, oldest row moves to history.md -->

| Problem | Slug | Accepted | Recommendation |
|---------|------|----------|----------------|

## Archived
Decisions older than the 5 most recent accepted: [history.md](history.md)
```

## Step 4 — Check root CLAUDE.md

Read the project root CLAUDE.md. If it does not reference .kb/ and .decisions/,
display this block and tell the user to add it manually (do not edit automatically):

```
## Agent Roles
- **Research Agent** — researches topics, writes to .kb/. Use /research.
- **Architect Agent** — evaluates options against constraints, writes to .decisions/. Use /architect.
- **Coding Agent** — implements. Reads .kb/ and .decisions/ on demand via links in ADRs.

## Knowledge Base
Research at .kb/<topic>/<category>/<subject>.md. On-demand only — do not scan proactively.
Use /research <topic> <category> "<subject>" to add research.
Use /kb-lookup <topic> <category> <subject> to retrieve an entry.

## Architecture Decisions
Decisions at .decisions/<problem-slug>/adr.md. On-demand only.
Full deliberation history at .decisions/<problem-slug>/log.md.
Use /architect "<problem>" to start a decision session.
Use /adr-review <slug> to review or update an existing decision.
```

## Step 5 — Report

Print a summary of what was found, created, and skipped. Then show:

```
Setup complete. Available commands:
  /research <topic> <category> "<subject>"   add new research to the KB
  /kb-lookup <topic> <category> <subject>    retrieve a KB entry into context
  /architect "<problem statement>"            start an architecture decision session
  /adr-review <problem-slug>                 review or update an existing decision
```
