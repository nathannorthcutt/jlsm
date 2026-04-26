---
group: implement-membership
goal: Implement the two membership.* DRAFT specs once the transport layer (WG2) is in place. Establishes cluster discovery, health tracking, and recovery.
status: active
created: 2026-04-21
external_deps:
  - { type: group, ref: "implement-transport", required_state: COMPLETE }
---

## Goal

Implement the two membership.* DRAFT specs once the transport layer (WG2) is in place. Establishes cluster discovery, health tracking, and recovery.

## Scope

### In scope
- membership.continuous-rediscovery — peer discovery, membership drift detection
- membership.cluster-health-and-recovery — health signalling, failure detection, recovery orchestration
- Promotion of both specs DRAFT → APPROVED with implementation backing

### Out of scope
- Transport framing (WG2) — assumed present
- Replicated-state primitives (not scoped here; future work if needed)
- Authentication / admission (separate concern)

## Ordering Constraints

WD-01 (continuous-rediscovery) establishes peer enumeration. WD-02 (cluster-health-and-recovery) consumes the membership stream from WD-01 to drive health tracking.

**Cross-group dependency:** All WDs in this group block on WG2 (implement-transport) reaching COMPLETE. That constraint is expressed at the group level here rather than per-WD artifact_deps; work-resolve.sh treats these WDs as BLOCKED until WG2 lands.

## Shared Interfaces

Membership event contract — peer-join / peer-leave / peer-suspected shape — should be published as an interface-contract spec alongside WD-01 so downstream consumers (query routing, replication) can code against it without waiting for this group to finish.
