# Work Group Manifest: cross-module-integration

**Goal:** Wire stub index implementations through module boundaries to resolve query.index-types and engine.in-process-database-engine obligations
**Status:** active
**Created:** 2026-04-19
**Work definitions:** 3

## Work Definitions

| WD | Title | Status | Domains | Deps | Produces |
|----|-------|--------|---------|------|----------|
| WD-01 | Wire Full-Text Index Integration | COMPLETE | query,  engine | 0 | — |
| WD-02 | Wire Vector Index Integration | COMPLETE | query,  engine,  vector-indexing | 0 | — |
| WD-03 | Wire Query Binding Through StringKeyedTable | COMPLETE | engine | 0 | — |

## Dependency Graph

```
WD-01 (Full-Text Index — query.full-text-index.R1-R84)
  └→ WD-03 (Query Binding — engine.in-process-database-engine.R37)

WD-02 (Vector Index — query.vector-index.R1-R90) ─── independent
```

WD-01 and WD-02 can run in parallel.
WD-03 depends on WD-01 (index wiring pattern needed for query binding).
