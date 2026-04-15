---
problem: "How should the membership protocol recover when view changes stall due to quorum loss or network partitions?"
slug: "membership-view-stall-recovery"
captured: "2026-04-13"
status: "draft"
---

# Constraint Profile — membership-view-stall-recovery

## Problem Statement
Rapid's 75% consensus means view changes stall when >25% of nodes are simultaneously
unreachable. F04 R41 handles detection (transition to degraded mode) but not recovery.
The protocol needs a mechanism to restore a consistent membership view after the stall
resolves — whether by nodes returning, partitions healing, or operator intervention.

## Constraints

### Scale
Up to 1000 nodes. Anti-entropy sync exchanges O(n) membership state — at 1000 nodes,
digest is ~24 KB. Must work within single transport frame.

### Resources
Pure Java, virtual threads. Recovery mechanism runs on the membership protocol's
existing virtual thread. No additional thread pools.

### Complexity Budget
Not a constraint.

### Accuracy / Correctness
Must recover to a consistent membership view — all nodes must agree on the same
view after recovery. Must not admit stale state that contradicts the cluster's
actual membership. Quorum loss recovery (>25% unreachable) must require operator
approval — automatic quorum reduction is dangerous for split-brain safety.

### Operational Requirements
Tier-1 recovery (short stalls) should resolve in seconds. Tier-2 (anti-entropy)
should resolve in <30 seconds. Tier-3 (forced rejoin) should resolve within 1
minute. Quorum loss should enter degraded mode immediately (F04 R41) with
operator API for manual recovery.

### Fit
Builds on Rapid + phi accrual (cluster-membership-protocol ADR). Uses existing
transport (ClusterTransport send/request), discovery SPI (for forced rejoin), and
multiplexed framing (METADATA class for sync messages).

## Key Constraints (most narrowing)
1. **Correctness on quorum loss** — no automatic quorum reduction; operator must approve
2. **Fast recovery for partition healing** — seconds, not minutes
3. **O(n) sync cost acceptable** — 24 KB at 1000 nodes fits in one frame

## Unknown / Not Specified
None — full profile captured.
