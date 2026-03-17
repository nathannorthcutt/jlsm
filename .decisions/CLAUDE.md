# Architecture Decisions — Master Index

> **Managed by vallorcine agents. Use slash commands to modify this file.**
> To start a decision: `/architect "<problem>"`
> To review a decision: `/decisions review "<slug>"`

> Pull model. Load on demand only.
> Structure: .decisions/<problem-slug>/adr.md
> Full history: [history.md](history.md)

## Active Decisions
<!-- Proposed or in-progress only. Confirmed/superseded rows move to history.md. -->

| Problem | Slug | Date | Status | Recommendation |
|---------|------|------|--------|----------------|

## Recently Accepted (last 5)
<!-- Once this section exceeds 5 rows, oldest row moves to history.md -->

| Problem | Slug | Accepted | Recommendation |
|---------|------|----------|----------------|
| VectorType serialization encoding | vector-type-serialization-encoding | 2026-03-17 | Flat Vector Encoding — contiguous d×sizeof(T) bytes, no per-vector metadata |
| IndexDefinition API simplification | index-definition-api-simplification | 2026-03-17 | Derive dimensions from schema VectorType — remove vectorDimensions from record |

## Deferred
<!-- Topics recorded but not yet evaluated. Resume with /architect "<problem>" -->

| Problem | Slug | Deferred | Resume When |
|---------|------|----------|-------------|

## Closed
<!-- Topics explicitly ruled out. Won't be raised again unless reopened. -->

| Problem | Slug | Closed | Reason |
|---------|------|--------|--------|

## Archived
Decisions older than the 5 most recent: [history.md](history.md)
