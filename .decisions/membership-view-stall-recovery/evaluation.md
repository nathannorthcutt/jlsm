---
problem: "membership-view-stall-recovery"
evaluated: "2026-04-13"
candidates:
  - path: ".kb/distributed-systems/cluster-membership/view-stall-recovery.md"
    name: "Tiered Escalation"
    section: "#three-tier-recovery"
  - path: ".kb/distributed-systems/cluster-membership/view-stall-recovery.md"
    name: "Anti-Entropy Only"
    section: "#tier-2-anti-entropy-sync"
  - path: ".kb/distributed-systems/cluster-membership/view-stall-recovery.md"
    name: "Always-Rejoin"
    section: "#tier-3-forced-rejoin"
  - path: ".kb/distributed-systems/cluster-membership/view-stall-recovery.md"
    name: "Operator-Manual Only"
    section: "#quorum-loss-specific-recovery"
constraint_weights:
  scale: 2
  resources: 1
  complexity: 1
  accuracy: 3
  operational: 3
  fit: 2
---

# Evaluation — membership-view-stall-recovery

## References
- Constraints: [constraints.md](constraints.md)
- KB source: [`.kb/distributed-systems/cluster-membership/view-stall-recovery.md`](../../.kb/distributed-systems/cluster-membership/view-stall-recovery.md)

## Constraint Summary
Recovery must restore a consistent membership view without admitting stale state.
Short stalls must recover in seconds; larger divergence in under a minute.
Quorum loss requires operator approval. Accuracy and operational speed are the
binding constraints.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | O(n) sync cost is acceptable; scales linearly |
| Resources | 1 | Minimal resource difference between candidates |
| Complexity | 1 | Not a concern |
| Accuracy | 3 | Correctness — stale state admission is a data integrity risk |
| Operational | 3 | Recovery speed directly affects cluster availability |
| Fit | 2 | All candidates use existing infrastructure |

---

## Candidate: Tiered Escalation (piggyback → anti-entropy → forced rejoin)

**KB source:** [`.kb/distributed-systems/cluster-membership/view-stall-recovery.md`](../../.kb/distributed-systems/cluster-membership/view-stall-recovery.md)
**Relevant sections read:** `#three-tier-recovery`, `#key-parameters`, `#edge-cases-and-gotchas`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 5 | 10 | Tier-1 is O(1); tier-2 is O(n) per sync but infrequent; tier-3 is per-node (#three-tier-recovery) |
|       |   |   |    | **Would be a 2 if:** anti-entropy amplification caused O(n²) burst (mitigated by jittering and concurrent sync limit) |
| Resources | 1 | 5 | 5 | No additional threads; runs on existing protocol thread |
| Complexity | 1 | 3 | 3 | Three tiers with threshold-based escalation — most complex candidate |
| Accuracy | 3 | 5 | 15 | Tier-1: normal protocol guarantees. Tier-2: bidirectional reconciliation ensures both sides converge. Tier-3: fresh view from cluster (#three-tier-recovery) |
|          |   |   |    | **Would be a 2 if:** anti-entropy sync admitted entries from a node that had been intentionally evicted |
| Operational | 3 | 5 | 15 | Tier-1: seconds. Tier-2: <30s. Tier-3: <60s. Optimal recovery time for each divergence level (#key-parameters) |
|             |   |   |    | **Would be a 2 if:** tier escalation was too slow — node sits in tier-1 for minutes before escalating to tier-2 |
| Fit | 2 | 5 | 10 | Uses existing protocol messages (piggyback), transport (anti-entropy digest), and discovery SPI (rejoin) (#implementation-notes) |
|     |   |   |    | **Would be a 2 if:** anti-entropy digest exceeded transport frame size (at 1000 nodes, 24 KB << 64 KiB frame) |
| **Total** | | | **58** | |

**Hard disqualifiers:** None.

**Key strengths:**
- Optimal recovery time — most stalls resolve at tier-1 (zero extra cost)
- Proven at production scale (Consul, Serf, Tarantool)

**Key weaknesses:**
- Three thresholds to configure (piggyback_threshold, divergence_threshold, max_stall_duration)

---

## Candidate: Anti-Entropy Only

**KB source:** [`.kb/distributed-systems/cluster-membership/view-stall-recovery.md`](../../.kb/distributed-systems/cluster-membership/view-stall-recovery.md)
**Relevant sections read:** `#tier-2-anti-entropy-sync`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 4 | 8 | O(n) per sync; always runs even for short stalls where piggyback would suffice |
|       |   |   |   | **Would be a 2 if:** sync triggered on every minor divergence, causing constant O(n) traffic |
| Resources | 1 | 4 | 4 | Slightly more traffic than tiered — anti-entropy for all stalls, not just large ones |
| Complexity | 1 | 4 | 4 | Simpler than tiered — one mechanism, one threshold |
| Accuracy | 3 | 5 | 15 | Bidirectional reconciliation guarantees convergence (#tier-2-anti-entropy-sync) |
|          |   |   |    | **Would be a 2 if:** same evicted-node concern as tiered |
| Operational | 3 | 3 | 9 | Always takes ~30s even for short stalls where piggyback would resolve in seconds |
| Fit | 2 | 4 | 8 | Uses transport for digest exchange; doesn't need discovery SPI (no rejoin tier) |
|     |   |   |   | **Would be a 2 if:** catastrophic divergence (>50% unknown) isn't handled — no rejoin fallback |
| **Total** | | | **48** | |

**Hard disqualifiers:** No rejoin fallback — catastrophic divergence cannot be resolved.

**Key strengths:**
- Simpler than tiered — one mechanism handles all divergence levels

**Key weaknesses:**
- Overkill for short stalls; no rejoin fallback for catastrophic divergence

---

## Candidate: Always-Rejoin

**KB source:** [`.kb/distributed-systems/cluster-membership/view-stall-recovery.md`](../../.kb/distributed-systems/cluster-membership/view-stall-recovery.md)
**Relevant sections read:** `#tier-3-forced-rejoin`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 3 | 6 | Every stall triggers a full rejoin — seed discovery + bootstrap for each node |
| Resources | 1 | 3 | 3 | Full bootstrap for every stall; wasteful for minor divergence |
| Complexity | 1 | 5 | 5 | Simplest — one action for all cases |
| Accuracy | 3 | 4 | 12 | Fresh view from cluster; but brief dual-identity window during rejoin (#tier-3-forced-rejoin) |
|          |   |   |    | **Would be a 2 if:** dual-identity caused ownership conflicts during rebalancing |
| Operational | 3 | 2 | 6 | Rejoin takes ~60s even for short stalls where piggyback would resolve in seconds. Massively disruptive for minor divergence |
| Fit | 2 | 4 | 8 | Uses discovery SPI for rejoin; wasteful use of bootstrap path for minor issues |
|     |   |   |   | **Would be a 2 if:** mass rejoin (many nodes simultaneously) overwhelmed seed providers |
| **Total** | | | **40** | |

**Hard disqualifiers:** Operational score of 2 — 60s recovery for a 1-second stall is unacceptable.

**Key strengths:**
- Guaranteed to produce a fresh, correct view

**Key weaknesses:**
- Massively disproportionate response to minor stalls

---

## Candidate: Operator-Manual Only

**KB source:** [`.kb/distributed-systems/cluster-membership/view-stall-recovery.md`](../../.kb/distributed-systems/cluster-membership/view-stall-recovery.md)
**Relevant sections read:** `#quorum-loss-specific-recovery`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 5 | 10 | No automatic mechanism — zero overhead |
| Resources | 1 | 5 | 5 | Nothing to run |
| Complexity | 1 | 5 | 5 | Nothing to implement |
| Accuracy | 3 | 5 | 15 | Operator verifies before any recovery — maximum safety |
|          |   |   |    | **Would be a 2 if:** operator makes a mistake during manual recovery |
| Operational | 3 | 1 | 3 | **DISQUALIFIER:** recovery takes minutes to hours depending on operator availability. Cluster stays degraded until human intervenes for ALL stalls, not just quorum loss. |
| Fit | 2 | 5 | 10 | No changes needed |
| **Total** | | | **48** | |

**Hard disqualifiers:** Operational score of 1 — requires human intervention for every stall, including short network blips that would self-heal in seconds.

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| Tiered Escalation | 10 | 5 | 3 | 15 | 15 | 10 | **58** |
| Anti-Entropy Only | 8 | 4 | 4 | 15 | 9 | 8 | **48** |
| Operator-Manual Only | 10 | 5 | 5 | 15 | 3 | 10 | **48** |
| Always-Rejoin | 6 | 3 | 5 | 12 | 6 | 8 | **40** |

## Preliminary Recommendation
Tiered Escalation wins (58) by 10 points over both Anti-Entropy Only and Operator-Manual
(48 each). It is the only candidate that scores 5 on both Accuracy and Operational — the
two highest-weighted constraints. The tiered approach minimizes disruption by matching
recovery effort to divergence severity.

## Risks and Open Questions
- Risk: threshold tuning — piggyback_threshold too high delays escalation; too low causes
  unnecessary anti-entropy syncs
- Risk: anti-entropy amplification during mass partition healing
- Open: should tier-2 anti-entropy run proactively on a periodic schedule (like Consul's 30s
  cycle) or only on divergence detection?
