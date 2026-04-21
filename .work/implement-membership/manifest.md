# Work Group Manifest: implement-membership

**Goal:** Implement the two membership.* DRAFT specs once the transport layer (WG2) is in place. Establishes cluster discovery, health tracking, and recovery.
**Status:** active
**Created:** 2026-04-21
**Work definitions:** 2

## Work Definitions

| WD | Title | Status | Domains | Deps | Produces |
|----|-------|--------|---------|------|----------|
| WD-01 | Implement membership.continuous-rediscovery | READY | membership | 0 | — |
| WD-02 | Implement membership.cluster-health-and-recovery | BLOCKED | membership | 1 | — |

## Dependency Graph

```
WD-01 (continuous-rediscovery)
  └→ WD-02 (cluster-health-and-recovery)

Both WDs block on WG2 (implement-transport) per work.md Ordering Constraints.
```
