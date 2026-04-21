---
group: implement-transport
goal: Implement the three transport.* DRAFT specs as a net-new jlsm-cluster module, establishing the framing and flow-control layer required for membership and remote query work.
status: active
created: 2026-04-21
---

## Goal

Implement the three transport.* DRAFT specs as a net-new jlsm-cluster module, establishing the framing and flow-control layer required for membership and remote query work.

## Scope

### In scope
- transport.multiplexed-framing — frame layout, sequencing, demultiplexing
- transport.traffic-priority — priority lanes, fairness
- transport.scatter-gather-flow-control — scatter/gather request primitive with backpressure
- New jlsm-cluster module skeleton (or similarly named) to host the above
- Promotion of all 3 specs DRAFT → APPROVED with implementation backing

### Out of scope
- Membership protocols (handled by WG3)
- Query-layer integration with the new transport (deferred until membership lands)
- Encryption-on-wire (separate from transport framing; encryption layer is WG5 territory)

## Ordering Constraints

WD-01 (framing) is the foundation. WD-02 (priority) layers on top of framing. WD-03 (scatter-gather) depends on both framing and priority.

## Shared Interfaces

Transport protocol contracts — framing envelope layout, priority-level enum, and the scatter-gather request/response shape — should be published as interface-contract specs once WD-01 lands, so downstream modules can consume them before WG3 starts.
