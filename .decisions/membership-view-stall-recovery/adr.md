---
problem: "membership-view-stall-recovery"
date: "2026-04-13"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/"
---

# ADR — Membership View Stall Recovery

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Membership View Stall Recovery | Chosen approach — tiered escalation algorithm | [`.kb/distributed-systems/cluster-membership/view-stall-recovery.md`](../../.kb/distributed-systems/cluster-membership/view-stall-recovery.md) |

## Related ADRs
| ADR | Relationship |
|-----|-------------|
| [cluster-membership-protocol](../cluster-membership-protocol/adr.md) | Parent — Rapid's 75% consensus creates the stall condition this recovers from |
| [continuous-rediscovery](../continuous-rediscovery/adr.md) | Used by tier-3 forced rejoin to find current cluster seeds |

---

## Files Constrained by This Decision
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/` — membership protocol recovery path

## Problem
Rapid's 75% consensus means view changes stall when >25% of nodes are simultaneously
unreachable. F04 R41 handles detection (transition to degraded mode) but not recovery.
How should the protocol restore a consistent membership view after the stall resolves?

## Constraints That Drove This Decision
- **Correctness**: must recover to a consistent view without admitting stale state; quorum loss requires operator approval
- **Recovery speed**: short stalls must resolve in seconds, not minutes
- **Proportionate response**: recovery effort must match divergence severity

## Decision
**Chosen approach: [Tiered Escalation](../../.kb/distributed-systems/cluster-membership/view-stall-recovery.md)**

Three escalating recovery tiers, each triggered by increasing divergence severity:

1. **Tier 1 — Piggyback catch-up** (seconds): for stalls < piggyback_threshold protocol
   periods. Normal piggybacked protocol messages naturally repair small gaps in the
   membership view. No special action needed — the dissemination buffer still holds
   missed events.

2. **Tier 2 — Anti-entropy full-state sync** (<30s): for larger divergence where the
   dissemination buffer has been exhausted. Scuttlebutt-style three-message digest
   exchange (DIGEST → DELTA → DELTA-ACK) reconciles arbitrarily diverged views. O(n)
   cost per sync but infrequent. Triggered when a node detects incarnation gaps or
   receives messages from members it believes are DEAD.

3. **Tier 3 — Forced rejoin** (<60s): for catastrophic divergence (>50% unknown members
   or partitioned > max_stall_duration). Node discards its local view entirely, calls
   `discoverSeeds()`, and runs the standard bootstrap join protocol from scratch.

For quorum loss (>25% simultaneously unreachable): the engine enters degraded mode
(F04 R41) and offers an operator API for manual view reset. No automatic quorum
reduction — the operator must explicitly accept the safety tradeoff.

## Rationale

### Why Tiered Escalation
- **Proportionate response**: most stalls are short (tier 1, zero extra cost); only severe divergence triggers expensive recovery ([KB: `#three-tier-recovery`](../../.kb/distributed-systems/cluster-membership/view-stall-recovery.md))
- **Proven at scale**: Consul uses anti-entropy on 30s cycle; Serf triggers anti-entropy on partition healing; Tarantool includes anti-entropy chunks in protocol messages ([KB: `#tier-2-anti-entropy-sync`](../../.kb/distributed-systems/cluster-membership/view-stall-recovery.md))
- **Safety**: forced rejoin is a last resort, not the default. Operator approval required for quorum loss recovery ([KB: `#quorum-loss-specific-recovery`](../../.kb/distributed-systems/cluster-membership/view-stall-recovery.md))

### Why not Anti-Entropy Only
- **Disproportionate for short stalls**: O(n) digest exchange for a 1-second network blip is wasteful when piggyback would suffice
- **No rejoin fallback**: catastrophic divergence (>50% unknown) cannot be resolved by digest exchange alone

### Why not Always-Rejoin
- **60 seconds for a 1-second stall** is massively disproportionate. Triggers unnecessary bootstrap, seed discovery, and brief dual-identity window for every minor divergence.

### Why not Operator-Manual Only
- **Requires human for all stalls** including short network blips that self-heal in seconds. Unacceptable for automated cluster operation.

## Implementation Guidance

Key parameters from [`view-stall-recovery.md#key-parameters`](../../.kb/distributed-systems/cluster-membership/view-stall-recovery.md#key-parameters):
- piggyback_threshold: 10 missed periods (default)
- anti_entropy_interval: 30s proactive cycle (Consul default)
- divergence_threshold: 50% unknown members triggers forced rejoin
- max_stall_duration: 300s before forced rejoin
- quorum_loss_timeout: 300s before offering operator override API

Anti-entropy digest format:
- `{memberId → incarnation}` for all known members
- At 1000 nodes: ~24 KB — fits in one METADATA-class transport frame

Edge cases from [`view-stall-recovery.md#edge-cases-and-gotchas`](../../.kb/distributed-systems/cluster-membership/view-stall-recovery.md#edge-cases-and-gotchas):
- Anti-entropy amplification: jitter sync trigger + limit concurrent syncs to 1 per node
- Rejoin during rebalancing: grace period mechanism handles ownership conflicts
- Clock skew: use monotonic clock (`System.nanoTime()`) for stall duration

## What This Decision Does NOT Solve
- Automatic quorum recovery without operator approval (by design — safety constraint)
- CRDT-based membership as an alternative to Rapid's consensus model (would require revisiting cluster-membership-protocol ADR)

## Conditions for Revision
This ADR should be re-evaluated if:
- Anti-entropy amplification at 1000 nodes proves problematic under real partition-healing scenarios (may need rate-limiting or batched sync)
- Tier-3 dual-identity window causes ownership conflicts that the grace period mechanism cannot absorb
- CRDT-based membership is researched and proves compatible with the strongly-consistent view requirement

---
*Confirmed by: user deliberation | Date: 2026-04-13*
*Full scoring: [evaluation.md](evaluation.md)*
