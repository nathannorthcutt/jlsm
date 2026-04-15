# Work Group: decisions-backlog

**Goal:** Resolve deferred decision backlog per roadmap
**Status:** active
**Created:** 2026-04-13
**Last resolved:** 2026-04-13

## Work Definitions

| WD | Title | Status | Decisions | Effort |
|----|-------|--------|-----------|--------|
| WD-01 | Schema & Field Types | SPECIFIED | 4 | 4 minor |
| WD-02 | Storage & Compression | SPECIFIED | 10 | 2 gap-fill, 6 minor, 2 full |
| WD-03 | Cache | SPECIFIED | 2 | 2 minor |
| WD-04 | Vector | SPECIFIED | 2 | 2 minor |
| WD-05 | Cluster Networking & Discovery | SPECIFIED | 12 | 2 gap-fill, 9 minor, 1 full |
| WD-06 | Engine API & Catalog | READY | 8 | 1 gap-fill, 3 minor, 4 full |
| WD-07 | Partitioning & Rebalancing | READY | 13 | 1 gap-fill, 9 minor, 3 full |
| WD-08 | Query Execution | READY | 3 | 2 minor, 1 full |
| WD-09 | Encryption & Security | READY | 11 | 1 gap-fill, 4 minor, 6 full |

**Total:** 65 decisions (11 gap-fill, 35 minor, 19 full)

## Dependency Graph

```
WD-01 (Schema)         ─── independent
WD-02 (Storage)        ─── independent
WD-03 (Cache)          ─── independent
WD-04 (Vector)         ─── independent
WD-05 (Networking)     ─── blocks WD-06, WD-07
WD-06 (Engine API)     ─── blocked by WD-05 (connection-pooling, message-serialization-format)
WD-07 (Partitioning)   ─── blocked by WD-05 (connection-pooling, message-serialization-format)
WD-08 (Query)          ─── blocked by WD-07 (cross-partition-transactions)
WD-09 (Encryption)     ─── independent
```

## Suggested Execution Order

Phase 2 (current): WD-01 (Schema)
Phase 3: WD-02 gap-fills + WD-03 + WD-04 (parallel)
Phase 4: WD-02 minor/full features
Phase 5: WD-05 (Networking) — unblocks WD-06 and WD-07
Phase 6: WD-06 handle items + WD-09 minor features (parallel)
Phase 7: WD-07 (Partitioning) — safety promotions first
Phase 8: WD-06 distributed items + WD-08 (Query)
Phase 9: WD-09 full features
