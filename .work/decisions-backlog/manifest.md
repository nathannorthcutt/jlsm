# Work Group: decisions-backlog

**Goal:** Resolve deferred decision backlog per roadmap
**Status:** active
**Created:** 2026-04-13
**Last resolved:** 2026-04-13

## Work Definitions

| WD | Title | Status | Domains | Deps | Produces |
|----|-------|--------|---------|------|----------|
| WD-01 | Schema & Field Types | COMPLETE | decisions | 0 | adr:, adr:, adr:, adr: |
| WD-02 | Storage & Compression | COMPLETE | decisions | 0 | adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr: |
| WD-03 | Cache | COMPLETE | decisions | 0 | adr:, adr: |
| WD-04 | Vector | COMPLETE | decisions | 0 | adr:, adr: |
| WD-05 | Cluster Networking & Discovery | COMPLETE | decisions | 0 | adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr: |
| WD-06 | Engine API & Catalog | COMPLETE | decisions | 0 | adr:, adr:, adr:, adr:, adr:, adr:, adr: |
| WD-07 | Partitioning & Rebalancing | COMPLETE | decisions | 0 | adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr: |
| WD-08 | Query Execution | COMPLETE | decisions | 0 | adr:, adr:, adr: |
| WD-09 | Encryption & Security | COMPLETE | decisions | 0 | adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr:, adr: |
| WD-10 | Rebalancing Safety & Recovery | COMPLETE | decisions | 0 | adr:, adr:, adr:, adr:, adr: |
| WD-11 | Partition Optimization | COMPLETE | decisions | 0 | adr:, adr:, adr: |
| WD-12 | Encrypted Query Capabilities | COMPLETE | decisions | 0 | adr:, adr:, adr:, adr: |
| WD-13 | Catalog & Scan Lifecycle | COMPLETE | decisions | 0 | adr:, adr:, adr:, adr: |

**Total:** 81 decisions (11 gap-fill, 35 minor, 19 full + 16 newly unblocked)

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
WD-10 (Rebalancing Safety) ─── independent (unblocked by WD-07)
WD-11 (Partition Opt)  ─── independent (unblocked by WD-07)
WD-12 (Encrypted Query)─── independent (unblocked by WD-09)
  └─ internal: fuzzy-matching after prefix-queries
WD-13 (Catalog & Scan) ─── independent (unblocked by WD-06 + WD-08)
  └─ internal: scan-lease-gc-watermark after scan-snapshot-binding
  └─ internal: table-migration-protocol after catalog-replication
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
