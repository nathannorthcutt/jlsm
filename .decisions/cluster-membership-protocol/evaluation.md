---
problem: "cluster-membership-protocol"
evaluated: "2026-03-20"
candidates:
  - path: ".kb/distributed-systems/cluster-membership/cluster-membership-protocols.md"
    name: "Rapid + Phi Accrual Composite"
  - path: ".kb/distributed-systems/cluster-membership/cluster-membership-protocols.md"
    name: "SWIM + Quorum Split-Brain Protection"
  - path: ".kb/distributed-systems/cluster-membership/cluster-membership-protocols.md"
    name: "Scuttlebutt + Phi Accrual (Chitchat-style)"
  - path: ".kb/distributed-systems/cluster-membership/cluster-membership-protocols.md"
    name: "All-to-All Heartbeat + Quorum"
  - path: ".kb/distributed-systems/cluster-membership/cluster-membership-protocols.md"
    name: "Raft-Based Membership"
constraint_weights:
  scale: 3
  resources: 1
  complexity: 1
  accuracy: 3
  operational: 2
  fit: 2
---

# Evaluation — cluster-membership-protocol

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used: see candidate sections below

## Constraint Summary
The protocol must scale to hundreds of peer nodes with no leader, detect split-brain
reliably (correctness over availability), and work identically over both in-JVM and
future network transports. The team has high distributed systems expertise, so protocol
complexity is not a concern.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 3 | Hundreds of nodes — protocol overhead must be sub-linear |
| Resources | 1 | Not a constraining factor per user |
| Complexity | 1 | Team is expert-level, high complexity acceptable |
| Accuracy | 3 | Sustained outages from false detection unacceptable |
| Operational | 2 | Fast convergence, non-blocking membership changes required |
| Fit | 2 | Must be transport-agnostic, pure Java, no external deps |

---

## Candidate: Rapid + Phi Accrual Composite

**KB source:** [`.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md)
**Relevant sections read:** `#rapid-protocol`, `#split-brain-detection > phi-accrual`, `#updated-comparison`, `#key-parameters`

This composite layers three concerns:
- **Rapid** — membership views, multi-process cut detection, leaderless 75% consensus
- **Phi accrual** — adaptive edge failure detection, plugs into Rapid's `IEdgeFailureDetectorFactory`
- **Piggybacked state exchange** — optional node metadata on heartbeat channel

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 3 | 5 | 15 | O(n) total messages; tested at 2000 nodes, bootstraps 2-5.8x faster than Memberlist (KB: `#rapid-protocol`) |
| Resources | 1 | 4 | 4 | ~8KB per observer-subject pair for phi sample window + membership state; moderate but bounded |
| Complexity | 1 | 3 | 3 | Three-layer composite; expander graph + cut detection + phi accrual. High complexity — acceptable per constraints |
| Accuracy | 3 | 5 | 15 | Strongly consistent views via 75% consensus; multi-process cut detection prevents rebalancing storms; phi accrual eliminates fixed-timeout false positives (KB: `#rapid-protocol`, `#split-brain-detection`) |
| Operational | 2 | 5 | 10 | Multi-process cut detection batches failures → single view change; phi adapts to GC/network conditions; non-blocking steady state |
| Fit | 2 | 5 | 10 | Leaderless — satisfies no-leader constraint. Java reference impl exists. Pluggable transport (`IMessagingClient`/`IMessagingServer`) maps to in-JVM + future NIO (KB: `#rapid-protocol`) |
| **Total** | | | **57** | |

**Hard disqualifiers:** none

**Key strengths for this problem:**
- Strongly consistent membership without a leader (KB: `#rapid-protocol` — 75% leaderless consensus)
- Multi-process cut detection prevents cascading rebalances from correlated failures (KB: `#rapid-protocol`)
- Split-brain protection is built-in — minority partition cannot reach 75% agreement (KB: `#rapid-protocol`)
- Phi accrual adapts to network/GC conditions, reducing false positives at the edge level (KB: `#split-brain-detection`)
- Pluggable transport maps directly to the in-JVM/NIO abstraction design (KB: `#rapid-protocol`)
- Piggybacked state exchange enables metadata distribution (capacity, ownership) without a separate channel

**Key weaknesses for this problem:**
- Less battle-tested in production than SWIM/Memberlist (academic origin, 2018 paper)
- Higher per-view-change coordination cost than SWIM (75% must agree)
- Reference implementation may need adaptation for jlsm-engine's specific transport abstraction

---

## Candidate: SWIM + Quorum-Based Split-Brain Protection

**KB source:** [`.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md)
**Relevant sections read:** `#swim-protocol`, `#split-brain-detection`, `#complexity-analysis`, `#lifeguard-extensions`, `#key-parameters`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 3 | 5 | 15 | O(1) per-member message load, O(n) total; tested at 2000+ nodes (Consul/Lifeguard) |
| Resources | 1 | 5 | 5 | ~20KB memory per node at 100 nodes; minimal CPU (one probe per period) |
| Complexity | 1 | 4 | 4 | Well-documented protocol with reference implementations; Lifeguard adds complexity |
| Accuracy | 3 | 4 | 12 | Suspicion mechanism + indirect probes reduce false positives; quorum prevents split-brain. Weakly consistent — nodes may briefly disagree, requiring separate quorum bolt-on |
| Operational | 2 | 5 | 10 | Expected detection in one protocol period; O(log n) dissemination; non-blocking |
| Fit | 2 | 5 | 10 | Transport-agnostic by design; Java implementations exist (ScaleCube); no external deps |
| **Total** | | | **56** | |

**Hard disqualifiers:** none

**Key strengths for this problem:**
- Most battle-tested option — Consul, Serf production deployments at 2000+ nodes (KB: `#lifeguard-extensions`)
- Simplest of the viable candidates — well-understood failure detection model (KB: `#swim-protocol`)
- Lightweight per-member overhead (KB: `#complexity-analysis`)

**Key weaknesses for this problem:**
- Weakly consistent — membership views may temporarily diverge (KB: `#tradeoffs > weaknesses`)
- Split-brain protection requires a separate quorum layer — not built into the protocol (KB: `#split-brain-detection`)
- Individual failure detection (not batched) — correlated failures can trigger cascading rebalances

---

## Candidate: Scuttlebutt + Phi Accrual (Chitchat-style)

**KB source:** [`.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md)
**Relevant sections read:** `#scuttlebutt-phi-accrual`, `#updated-comparison`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 3 | 4 | 12 | O(n) total messages; anti-entropy exchange is heavier per-message than SWIM piggybacking |
| Resources | 1 | 4 | 4 | Digest exchange overhead grows with state size; phi sample windows per peer |
| Complexity | 1 | 5 | 5 | Simpler than SWIM+Lifeguard — straightforward three-message exchange (KB: `#scuttlebutt-phi-accrual`) |
| Accuracy | 3 | 4 | 12 | Phi accrual adaptive detection; anti-entropy guarantees all updates propagate. But weakly consistent — needs separate quorum for split-brain |
| Operational | 2 | 4 | 8 | Good steady-state; anti-entropy convergence. No built-in batched failure detection |
| Fit | 2 | 4 | 8 | No Java reference impl for full protocol (Quickwit's Chitchat is Rust). Transport-agnostic in principle |
| **Total** | | | **49** | |

**Hard disqualifiers:** none

**Key strengths for this problem:**
- Built-in metadata distribution via key-value state sharing (KB: `#scuttlebutt-phi-accrual`)
- Simpler to implement than SWIM+Lifeguard
- Phi accrual adapts to network conditions

**Key weaknesses for this problem:**
- Weakly consistent — same as SWIM, needs separate quorum bolt-on
- Heavier per-message overhead (full digest exchange)
- No Java reference implementation for the full membership protocol
- No batched failure detection — individual failures could cascade

---

## Candidate: All-to-All Heartbeat + Quorum

**KB source:** [`.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md)
**Relevant sections read:** `#compared-to-alternatives`, `#complexity-analysis > per-period-overhead`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 3 | 1 | 3 | O(n^2) messages per period — disqualifying at hundreds of nodes |
| Resources | 1 | 2 | 2 | O(n) network bandwidth per node |
| Complexity | 1 | 5 | 5 | Simplest possible approach |
| Accuracy | 3 | 3 | 9 | Direct heartbeat is reliable but timeout-based; no indirect probing |
| Operational | 2 | 3 | 6 | Detection time O(n × T) — slow at scale |
| Fit | 2 | 5 | 10 | Trivial to implement in Java |
| **Total** | | | **35** | |

**Hard disqualifiers:** O(n^2) message complexity at hundreds of nodes

---

## Candidate: Raft-Based Membership

**KB source:** [`.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md)
**Relevant sections read:** `#compared-to-alternatives`, `#practical-usage > when-not-to-use`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 3 | 3 | 9 | Leader bottleneck at hundreds of nodes |
| Resources | 1 | 4 | 4 | Moderate — log replication overhead |
| Complexity | 1 | 3 | 3 | Well-understood but leader election adds complexity |
| Accuracy | 3 | 5 | 15 | Strong consistency — linearizable membership changes |
| Operational | 2 | 3 | 6 | Leader failure causes temporary unavailability |
| Fit | 2 | 1 | 2 | Requires a leader — directly conflicts with no-leaders constraint |
| **Total** | | | **39** | |

**Hard disqualifiers:** Requires a leader — violates no-leader constraint

---

## Comparison Matrix

| Candidate | KB Source | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| [Rapid + Phi Accrual](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md) | cluster-membership-protocols.md | 15 | 4 | 3 | 15 | 10 | 10 | **57** |
| [SWIM + Quorum](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md) | cluster-membership-protocols.md | 15 | 5 | 4 | 12 | 10 | 10 | **56** |
| [Scuttlebutt + Phi](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md) | cluster-membership-protocols.md | 12 | 4 | 5 | 12 | 8 | 8 | **49** |
| [Raft-Based](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md) | cluster-membership-protocols.md | 9 | 4 | 3 | 15 | 6 | 2 | **39** |
| [All-to-All Heartbeat](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md) | cluster-membership-protocols.md | 3 | 2 | 5 | 9 | 6 | 10 | **35** |

## Preliminary Recommendation
Rapid + Phi Accrual Composite wins on weighted total (57) and provides the strongest accuracy/correctness profile. SWIM + Quorum is a close second (56) with more production validation but weaker consistency. The composite approach layers phi accrual into Rapid's pluggable failure detector interface, combining strongly consistent membership with adaptive failure detection.

## Risks and Open Questions
- Rapid has fewer production deployments than SWIM — the Java reference implementation is a design reference, not production-hardened middleware
- The 75% consensus threshold means view changes stall if >25% of nodes are unreachable simultaneously
- Piggybacked state exchange for metadata is an extension beyond the Rapid paper — needs custom design
- Phi accrual sample window sizing needs tuning for in-JVM vs network transport (very different latency profiles)
