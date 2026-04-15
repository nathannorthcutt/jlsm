# Work Group: decisions-backlog

**Goal:** Resolve deferred decision backlog per roadmap
**Status:** active
**Created:** 2026-04-13
**Last resolved:** 2026-04-13

## Work Definitions

| WD | Title | Status | Domains | Deps | Produces |
|----|-------|--------|---------|------|----------|
| WD-01 | Schema & Field Types | SPECIFIED | decisions | 0 | adr:, adr:, adr:, adr: |
| WD-02 | Storage & Compression | SPECIFIED | decisions | 0 | adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr: |
| WD-03 | Cache | SPECIFIED | decisions | 0 | adr:, adr: |
| WD-04 | Vector | SPECIFIED | decisions | 0 | adr:, adr: |
| WD-05 | Cluster Networking & Discovery | SPECIFIED | decisions | 0 | adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr: |
| WD-06 | Engine API & Catalog | SPECIFIED | decisions | 0 | adr:, adr:, adr:, adr:, adr:, adr:, adr: |
| WD-07 | Partitioning & Rebalancing | SPECIFIED | decisions | 0 | adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr: |
| WD-08 | Query Execution | SPECIFIED | decisions | 0 | adr:, adr:, adr: |
| WD-09 | Encryption & Security | SPECIFIED | decisions | 0 | adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr: |

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
