---
title: "isMember ignores member state"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/**"
research_status: active
last_researched: "2026-03-26"
---

# isMember ignores member state

## What happens
`MembershipView.isMember(NodeAddress)` returns true if the address is present
in any state (ALIVE, SUSPECTED, DEAD). Callers that use `isMember()` as a proxy
for "is this node alive?" get incorrect results. In particular:
- Departure detection logic checking `!newView.isMember(addr)` misses members
  that transitioned from ALIVE to DEAD/SUSPECTED — the member is still "in" the
  view, just with a different state.
- View change notification logic skips `onMemberLeft()` for DEAD members because
  `isMember()` returns true.

## Why implementations default to this
`isMember()` is intuitively read as "is this address known to the cluster?" which
is correct for its definition. But callers often mean "is this node operational?"
without realizing the semantic gap. The three-state lifecycle (ALIVE/SUSPECTED/DEAD)
makes the distinction critical.

## Test guidance
- When testing membership change handlers, always test ALIVE→DEAD and ALIVE→SUSPECTED
  transitions, not just member removal from the view
- Verify that departure-tracking logic fires on state transitions, not just absence
- Check that listener callbacks (onMemberLeft, onMemberSuspected) are invoked for
  state transitions within a view, not only for members dropped from the view

## Found in
- engine-clustering (round 1, 2026-03-26): ClusteredEngine.onViewChanged() and RapidMembership.handleViewChangeProposal()
