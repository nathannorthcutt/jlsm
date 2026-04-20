# Work Group Manifest: cross-module-integration

**Goal:** Wire stub index implementations through module boundaries to resolve F10 and F05 obligations
**Status:** active
**Created:** 2026-04-19
**Work definitions:** 3

## Work Definitions

| WD | Title | Status | Domains | Deps | Produces |
|----|-------|--------|---------|------|----------|
| WD-01 | Wire Full-Text Index Integration | SPECIFIED | query,  engine | 0 | — |
| WD-02 | Wire Vector Index Integration | SPECIFIED | query,  engine,  vector-indexing | 0 | — |
| WD-03 | Wire Query Binding Through StringKeyedTable | BLOCKED | engine | 1 | — |

## Dependency Graph

```
WD-01 (Full-Text Index — F10.R79-R84)
  └→ WD-03 (Query Binding — F05.R37)

WD-02 (Vector Index — F10.R85-R90) ─── independent
```

WD-01 and WD-02 can run in parallel.
WD-03 depends on WD-01 (index wiring pattern needed for query binding).
