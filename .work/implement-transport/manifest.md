# Work Group Manifest: implement-transport

**Goal:** Implement the three transport.* DRAFT specs as a net-new jlsm-cluster module, establishing the framing and flow-control layer required for membership and remote query work.
**Status:** active
**Created:** 2026-04-21
**Work definitions:** 3

## Work Definitions

| WD | Title | Status | Domains | Deps | Produces |
|----|-------|--------|---------|------|----------|
| WD-01 | Implement transport.multiplexed-framing | READY | transport | 0 | — |
| WD-02 | Implement transport.traffic-priority | BLOCKED | transport | 1 | — |
| WD-03 | Implement transport.scatter-gather-flow-control | BLOCKED | transport | 2 | — |

## Dependency Graph

```
WD-01 (framing)
  ├→ WD-02 (priority)
  │    └→ WD-03 (scatter-gather)
  └→ WD-03 (scatter-gather)

Linear critical path: WD-01 → WD-02 → WD-03.
```
