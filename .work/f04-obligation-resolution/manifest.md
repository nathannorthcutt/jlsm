# Work Group Manifest: f04-obligation-resolution

**Goal:** Resolve 12 open F04 engine-clustering obligations identified by spec-verify
**Status:** active
**Created:** 2026-04-19
**Work definitions:** 5

## Work Definitions

| WD | Title | Status | Domains | Deps | Produces |
|----|-------|--------|---------|------|----------|
| WD-01 | Protocol Infrastructure | COMPLETE | engine | 0 | — |
| WD-02 | Engine Lifecycle and Local Routing | IMPLEMENTING | engine | 0 | — |
| WD-03 | Remote Dispatch and Parallel Scatter | BLOCKED | engine | 1 | — |
| WD-04 | RAPID Consensus Protocol | SPECIFIED | engine | 1 | — |
| WD-05 | Fault Tolerance and Smart Rebalancing | BLOCKED | engine,  partitioning | 1 | — |

## Dependency Graph

```
WD-01 (Protocol Infrastructure — R39, R53)
  └→ WD-04 (RAPID Consensus — R34-38, R35)
       └→ WD-05 (Fault Tolerance — R41-43, R47-50, R63)

WD-02 (Engine Lifecycle — R56-57-79, R60)
  └→ WD-03 (Remote Dispatch — R68, R77)
```

Two independent chains:
- Protocol chain: WD-01 → WD-04 → WD-05
- Engine chain: WD-02 → WD-03

WD-01 and WD-02 can run in parallel.

## Excluded Obligations (blocked on external dependencies)

| Obligation | Blocked by | Pick up when |
|-----------|-----------|--------------|
| OBL-F04-R10 (dedup) | Replication layer | Replication feature ships |
| OBL-F04-R20 (phi init) | Measurement data | False-positive rate measured in production |
| OBL-F04-R29 (transport timeout) | Network transport (F19-F21) | Network transport feature ships |
