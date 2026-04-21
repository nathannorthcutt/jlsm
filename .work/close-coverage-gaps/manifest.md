# Work Group Manifest: close-coverage-gaps

**Goal:** Bring four partial-coverage DRAFT specs to APPROVED by closing implementation gaps and filling test-annotation coverage.
**Status:** active
**Created:** 2026-04-21
**Work definitions:** 2

## Work Definitions

| WD | Title | Status | Domains | Deps | Produces |
|----|-------|--------|---------|------|----------|
| WD-01 | Close engine.clustering + engine.in-process-database-engine gaps | READY | engine | 0 | — |
| WD-02 | Close query.index-types + query.query-executor gaps | READY | query | 0 | — |

## Dependency Graph

```
WD-01 (engine gaps) ─── independent
WD-02 (query gaps) ─── independent
```
