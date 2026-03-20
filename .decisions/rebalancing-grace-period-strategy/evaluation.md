---
problem: "rebalancing-grace-period-strategy"
evaluated: "2026-03-20"
candidates:
  - path: "general-knowledge"
    name: "Eager Reassignment with Deferred Cleanup"
  - path: "general-knowledge"
    name: "View-Epoch Grace Period (original)"
  - path: "general-knowledge"
    name: "Delayed View Application"
  - path: "general-knowledge"
    name: "Immediate Rebalance (No Grace)"
constraint_weights:
  scale: 1
  resources: 1
  complexity: 1
  accuracy: 3
  operational: 3
  fit: 3
---

# Evaluation — rebalancing-grace-period-strategy

## References
- Constraints: [constraints.md](constraints.md)
- Related ADRs: cluster-membership-protocol, partition-to-node-ownership

## Constraint Summary
The rebalancing strategy must compose cleanly with Rapid's consistent membership views and
HRW's deterministic assignment. All nodes must agree on grace period state. Rolling restarts
must not trigger unnecessary data movement. Unaffected tables continue serving normally.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 1 | Rebalancing frequency is low (membership changes are infrequent) |
| Resources | 1 | Data movement is bounded by O(K/N) |
| Complexity | 1 | High complexity budget |
| Accuracy | 3 | No split-brain ownership; deterministic grace state |
| Operational | 3 | Rolling restart tolerance; non-disruptive rebalancing |
| Fit | 3 | Must compose with Rapid views + HRW; deterministic from shared state |

---

## Candidate: View-Epoch Grace Period

**KB source:** general knowledge (no direct KB entry)

Rapid produces a new membership view on each change. The ownership layer maintains a
"grace list" of recently departed nodes, keyed by (node_id, departure_view_epoch,
departure_timestamp). HRW computes assignment using `current_members ∪ grace_list_members`,
but partitions assigned to grace-list nodes are marked UNAVAILABLE (not served). When a
grace-list entry expires (wall-clock time > departure_timestamp + grace_duration), it is
removed and HRW recomputes on `current_members` only. The grace list is deterministic
because all nodes see the same view epochs and timestamps from Rapid.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | Grace list is tiny (only recently departed nodes). No scaling concern |
| Resources | 1 | 5 | 5 | Negligible — a few extra entries in the grace list |
| Complexity | 1 | 3 | 3 | Grace list management, expiration timers, two-mode HRW (with/without grace). Moderate |
| Accuracy | 3 | 5 | 15 | Deterministic: all nodes compute same grace list from same Rapid views. No split ownership — grace partitions are UNAVAILABLE, not dual-assigned |
| Operational | 3 | 5 | 15 | Rolling restarts: node departs, enters grace list, returns before expiry → grace entry removed, no rebalancing. Unaffected tables serve normally. Configurable grace duration |
| Fit | 3 | 5 | 15 | Composes perfectly: Rapid view changes feed both HRW and grace list. Pure function of (views, timestamps, grace_duration). Node returning after grace is new (different node_id or new view epoch) |
| **Total** | | | **58** | |

**Hard disqualifiers:** none

**Key strengths:**
- Separates failure detection (Rapid) from rebalancing tolerance (grace period) — different concerns, different tuning knobs
- Node joins take effect immediately — new capacity is used right away
- Grace partitions are explicitly UNAVAILABLE — no ambiguity about ownership

**Key weaknesses:**
- Requires wall-clock time agreement (or logical time) for grace expiration — clock skew could cause disagreement. Mitigated by Rapid's view epoch as a coordination point.
- Two-mode HRW adds complexity to the ownership computation

---

## Candidate: Delayed View Application

**KB source:** general knowledge

Hold back the Rapid view change from HRW for the grace duration. The ownership
layer keeps using the old view for HRW computation. After grace expires, switch
to the new view and recompute.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | No scaling concern |
| Resources | 1 | 5 | 5 | Negligible |
| Complexity | 1 | 4 | 4 | Simpler than grace list — just a timer and a view buffer |
| Accuracy | 3 | 3 | 9 | Routes queries to a departed node during grace — queries fail rather than being marked unavailable. Multiple view changes during grace period create ambiguity about which view to use |
| Operational | 3 | 3 | 9 | Delays benefit of new nodes joining — they sit idle during grace period. Cascading view changes are problematic |
| Fit | 3 | 3 | 9 | Fights Rapid's design: Rapid provides a new consistent view, and this approach ignores it. Introduces inconsistency between what Rapid reports and what HRW uses |
| **Total** | | | **41** | |

**Hard disqualifiers:** none, but significant design tension

**Key strengths:**
- Simplest implementation — just delay the view switch

**Key weaknesses:**
- Delays new node capacity during grace period
- Routes to departed nodes during grace (queries fail silently vs explicit UNAVAILABLE)
- Fights Rapid's consistent view design

---

## Candidate: Immediate Rebalance (No Grace)

**KB source:** general knowledge

Apply every Rapid view change immediately to HRW. No grace period at the
ownership layer. Rely on Rapid's suspicion timeout (3T–10T protocol periods)
as the de facto grace period before a node is declared dead.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | No scaling concern |
| Resources | 1 | 5 | 5 | Negligible |
| Complexity | 1 | 5 | 5 | Simplest possible — no grace logic at all |
| Accuracy | 3 | 4 | 12 | Deterministic — HRW on current view. But conflates failure detection with rebalancing tolerance |
| Operational | 3 | 2 | 6 | Rolling restarts trigger rebalancing if suspicion timeout < restart duration. Rapid's timeout is tuned for failure detection, not operational tolerance — wrong knob |
| Fit | 3 | 4 | 12 | Composes cleanly with Rapid + HRW but lacks independent rebalancing control |
| **Total** | | | **45** | |

**Hard disqualifiers:** none, but operational weakness is significant

**Key strengths:**
- Zero additional complexity — just HRW on current Rapid view
- No clock/timer coordination needed

**Key weaknesses:**
- Cannot independently tune failure detection vs rebalancing tolerance
- Rolling restarts in k8s trigger unnecessary data movement

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| View-Epoch Grace Period | 5 | 5 | 3 | 15 | 15 | 15 | **58** |
| Immediate Rebalance | 5 | 5 | 5 | 12 | 6 | 12 | **45** |
| Delayed View Application | 5 | 5 | 4 | 9 | 9 | 9 | **41** |

## Preliminary Recommendation
View-Epoch Grace Period wins on weighted total (58) by a wide margin. It cleanly separates failure detection (Rapid's concern) from rebalancing tolerance (operational concern), composes naturally with Rapid views and HRW, and handles rolling restarts without unnecessary data movement.

## Risks and Open Questions
- Clock skew could cause nodes to disagree on grace expiration — mitigate by using Rapid view epoch as coordination point and allowing a tolerance window
- Grace period evaluation based on general knowledge, not KB-backed research (gap noted)
