# Work Group Manifest: implement-sstable-enhancements

**Goal:** Implement three sstable.* DRAFT specs that layer enhancement capabilities onto the existing sstable writer — byte-budget-driven block caching, pool-aware block sizing, end-to-end integrity validation.
**Status:** active
**Created:** 2026-04-21
**Work definitions:** 3

## Work Definitions

| WD | Title | Status | Domains | Deps | Produces |
|----|-------|--------|---------|------|----------|
| WD-01 | Implement sstable.byte-budget-block-cache | COMPLETE | sstable | 0 | — |
| WD-02 | Implement sstable.pool-aware-block-size | COMPLETE | sstable | 0 | — |
| WD-03 | Implement sstable.end-to-end-integrity | COMPLETE | sstable | 0 | — |

## Dependency Graph

```
WD-01 (byte-budget-block-cache) ─── independent
WD-02 (pool-aware-block-size) ─── independent
WD-03 (end-to-end-integrity)  ─── independent

All three can run in parallel.
```
