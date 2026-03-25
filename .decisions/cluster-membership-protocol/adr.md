---
problem: "cluster-membership-protocol"
date: "2026-03-20"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/"
---

# ADR — Cluster Membership Protocol

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Cluster Membership Protocols | Chosen approach (Rapid + Phi Accrual composite) | [`.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md) |

---

## Files Constrained by This Decision
<!-- Key source files this decision affects. Used by /curate to detect drift. -->
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/` — membership protocol implementation

## Problem
What protocol should engine nodes use to form a peer-to-peer cluster, track membership with consistent views, and detect split-brain scenarios — without requiring a leader?

## Constraints That Drove This Decision
- **Correctness over availability**: split-brain must be detected reliably; minority partitions must stop serving rather than risk inconsistency
- **Scale to hundreds of nodes**: protocol overhead must not grow super-linearly with cluster size
- **Transport-agnostic**: must work identically over in-JVM direct calls and future network transport

## Decision
**Chosen approach: Rapid + Phi Accrual Composite**

Use a three-layer protocol inspired by the Rapid membership system (USENIX ATC 2018). Rapid provides strongly consistent membership views via multi-process cut detection and leaderless 75% consensus — all nodes agree on the same membership view before acting. Phi accrual failure detection replaces fixed timeouts at the edge level, adapting to network conditions and GC pauses. An optional piggybacked state exchange on the heartbeat channel enables distributing node metadata (capacity, table ownership) without a separate channel.

**Layer responsibilities:**
- **Rapid** — expander-graph monitoring overlay, multi-process cut detection, leaderless view-change consensus (75% quorum)
- **Phi accrual** — adaptive edge failure detection, plugs into Rapid's failure detector SPI
- **Piggybacked state exchange** — node metadata distribution on heartbeat messages (optional extension)

## Rationale

### Why Rapid + Phi Accrual Composite
- **Correctness**: strongly consistent membership views — all nodes agree on the same view before acting. 75% leaderless consensus means minority partitions cannot make progress, providing built-in split-brain protection without a separate quorum layer ([KB: `#rapid-protocol`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md))
- **Scale**: O(n) total messages per period; tested at 2000 nodes with 2-5.8x faster bootstrap than Memberlist/ZooKeeper ([KB: `#rapid-protocol`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md))
- **Correlated failure handling**: multi-process cut detection batches failures into a single view change, preventing cascading rebalances ([KB: `#rapid-protocol`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md))
- **Adaptive detection**: phi accrual self-tunes to GC pauses, network jitter, and load, reducing false positives before they reach cut detection ([KB: `#split-brain-detection`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md))
- **Transport-agnostic**: pluggable `IMessagingClient`/`IMessagingServer` maps directly to the in-JVM/NIO abstraction design ([KB: `#rapid-protocol`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md))

### Why not SWIM + Quorum Split-Brain Protection
- **Weak consistency**: membership views may temporarily diverge across nodes — requires a separate quorum layer bolted on for split-brain protection ([KB: `#tradeoffs > weaknesses`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md))
- **Individual failure detection**: does not batch correlated failures — can trigger cascading rebalances when multiple nodes fail simultaneously
- Close second in scoring (56 vs 57) and more battle-tested, but weaker on the highest-weighted constraint (accuracy/correctness)

### Why not Scuttlebutt + Phi Accrual (Chitchat-style)
- **Weak consistency**: same divergence issue as SWIM; needs separate quorum bolt-on ([KB: `#scuttlebutt-phi-accrual`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md))
- **No batched failure detection**: individual failures can cascade
- **No Java reference implementation** for the full membership protocol (Quickwit's Chitchat is Rust)

### Why not All-to-All Heartbeat
- **O(n^2) messages per period**: disqualifying at hundreds of nodes ([KB: `#complexity-analysis`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md))

### Why not Raft-Based Membership
- **Requires a leader**: directly violates the peer-to-peer no-leader constraint ([KB: `#compared-to-alternatives`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md))

## Implementation Guidance
Key parameters from [`cluster-membership-protocols.md#key-parameters`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md#key-parameters):
- Protocol period (T): 200ms–2s (tune shorter for in-JVM)
- Phi accrual threshold: ~8.0 (adaptive; tune per transport)
- Phi sample window: ~1000 heartbeat samples
- Consensus threshold: 75% of membership must agree on cut detection
- K observers per node: 3–5 in the expander graph overlay

Known edge cases from [`cluster-membership-protocols.md#edge-cases-and-gotchas`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md#edge-cases-and-gotchas):
- GC pauses: phi accrual adapts, but very long pauses may still trigger edge failure reports — Lifeguard-style local health awareness can further mitigate
- Rapid restarts: require re-join with fresh incarnation/generation to avoid stale state
- Cluster bootstrap: first node must handle being alone; subsequent nodes join via discovery SPI
- >25% simultaneous node loss stalls view changes until enough nodes recover

Full protocol detail: [`.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md)
Reference implementation: [Rapid (Java)](https://github.com/lalithsuresh/rapid)

## What This Decision Does NOT Solve
- View changes stall if >25% of nodes are simultaneously unreachable
- Gradual performance degradation detection (slow node vs dead node) — phi accrual helps at the edge level but cut detection is binary
- Dynamic adjustment of the 75% threshold as expected cluster size changes
- The piggybacked state exchange for metadata is an extension beyond the Rapid paper and requires custom design

## Conditions for Revision
This ADR should be re-evaluated if:
- Production network testing reveals that 75% consensus causes unacceptable view-change latency
- The piggybacked state exchange proves insufficient for metadata distribution needs (consider switching to full Scuttlebutt)
- Cluster sizes exceed 500 nodes and the expander graph overlay shows scaling issues
- SWIM + Lifeguard proves sufficient in practice and the consistency guarantee is unnecessary overhead
- A production-hardened Rapid library becomes available in the Java ecosystem

---
*Confirmed by: user deliberation | Date: 2026-03-20*
*Full scoring: [evaluation.md](evaluation.md)*
