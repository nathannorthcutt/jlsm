---
group: decisions-backlog
goal: Resolve deferred decision backlog per roadmap
status: active
created: 2026-04-13
---

## Goal

Resolve 65 deferred architecture decisions organized into 9 thematic
clusters per the roadmap at `.decisions/roadmap.md`.

## Scope

### In scope
- Storage & Compression (10 decisions)
- Cache (2 decisions)
- Schema & Field Types (4 decisions)
- Vector (2 decisions)
- Cluster Networking & Discovery (12 decisions)
- Engine API & Catalog (8 decisions)
- Partitioning & Rebalancing (12 decisions)
- Query Execution (3 decisions)
- Encryption & Security (11 decisions)

### Out of scope
- Decisions not in the deferred backlog
- Implementation beyond ADR resolution (implementation follows via /work-start)

## Ordering Constraints

Phase 2 (Schema + remaining Storage minor features) is in progress.
Cluster 5 (Networking) blocks Clusters 6, 7, and 8.
Cluster 7 (Partitioning) blocks Cluster 8 (Query Execution).
Clusters 2, 3, 4, 9 can proceed independently.

## Shared Interfaces
None — decisions are documentation artifacts.

## Success Criteria
- All deferred decisions in the roadmap are either accepted, closed, or
  re-deferred with updated conditions
