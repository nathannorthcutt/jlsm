---
title: "RAPID Consensus Protocol"
aliases: ["Rapid", "Suresh Rapid", "multi-process cut detection"]
topic: "distributed-systems"
category: "cluster-membership"
tags: ["rapid", "consensus", "multi-process-cut", "expander-graph", "membership", "fast-paxos", "atc18"]
complexity:
  time_build: "O(N·log N) for overlay construction"
  time_query: "O(log N) monitoring fanout per tick"
  space: "O(K·N) overlay + O(K·M) pending alerts for M pending subjects"
research_status: "mature"
confidence: "high"
last_researched: "2026-04-19"
applies_to: ["modules/jlsm-engine/src/main/java/jlsm/engine/cluster/internal/RapidMembership.java"]
related:
  - ./cluster-membership-protocols.md
  - ./incarnation-refutation.md
  - ../consensus/partition-replication-consensus.md
decision_refs: [".decisions/cluster-membership-protocol/adr.md"]
sources:
  - url: "https://www.usenix.org/conference/atc18/presentation/suresh"
    title: "Stable and Consistent Membership at Scale with Rapid"
    accessed: "2026-04-19"
    type: "paper"
  - url: "https://manuel.bernhardt.io/2020/04/30/10000-node-cluster-with-akka-and-rapid"
    title: "10000 nodes and beyond with Akka Cluster and Rapid"
    accessed: "2026-04-19"
    type: "blog"
  - url: "https://github.com/lalithsuresh/rapid"
    title: "lalithsuresh/rapid — reference Java implementation"
    accessed: "2026-04-19"
    type: "repo"
---

# RAPID Consensus Protocol

## summary

RAPID (Suresh et al., USENIX ATC 2018) is a membership protocol that
gives strongly-consistent view changes at cluster scale. It combines
three mechanisms: a bounded **expander-graph monitoring overlay** so
each node watches only O(log N) peers, **multi-process cut detection**
that aggregates K independent observers before any view change is
proposed, and a **leaderless Fast Paxos round** that commits the view
change in one round-trip whenever the observers agree on the set of
affected members. When observers disagree, RAPID falls back to classical
Paxos. The protocol defeats the "noisy neighbour" problem that SWIM
suffers from — transient network blips no longer mutate the view —
while scaling to 10k+ nodes.

## how-it-works

### The three layers

```
┌──────────────────────────────────────────────┐
│  View Change Consensus (Fast Paxos → Paxos)  │  R34, R36, R37
├──────────────────────────────────────────────┤
│  Multi-Process Cut Detector (H/L + K votes)  │  R34
├──────────────────────────────────────────────┤
│  Expander-Graph Monitoring Overlay           │  R35
└──────────────────────────────────────────────┘
         ↑ edge alerts (suspect / alive)
```

Each layer produces a sharper signal for the next. The monitoring
overlay emits per-edge alerts. The cut detector aggregates those alerts
across K observers into per-subject verdicts. Consensus commits a view
change when one or more subjects cross the high watermark.

### Expander-graph overlay

RAPID builds a **K-regular random graph** over ALIVE members. Each
member becomes a vertex; each vertex has exactly K outgoing edges
(monitors) and K incoming edges (observers). The graph is an *expander*
(random k-regular graphs are good expanders with high probability),
which gives two useful guarantees:

- **Strong connectivity under random failures** — the graph remains
  connected even when a constant fraction of vertices fail.
- **Fast information propagation** — alerts reach all live vertices in
  O(log N) gossip rounds.

**Degree K.** The reference implementation uses K=10 as a default. A
common alternative is K = ⌈log₂(N)⌉. Smaller K reduces monitoring load
but weakens the cut detector (fewer observers → more false
positives/negatives). Larger K increases ping traffic per period.

**Construction.** Given a seed and a membership set, each node can
deterministically compute its K monitors and K observers via a shared
pseudorandom ring permutation. No coordination is required to build the
overlay — it is a pure function of (current view, seed).

**Maintenance under churn.** On view change, the overlay is
*recomputed* (re-derived from the new view). Because construction is
deterministic, every node arrives at the same overlay. Incremental
rewiring (adding/removing a vertex without full recomputation) is
possible but optional — full recomputation is cheap at cluster scale.

### Multi-process cut detection

An **edge alert** is a message from an observer about a subject:
"I suspect X went DOWN" or "X just came UP." Alerts are broadcast to
all nodes; every node independently runs its own cut detector.

For each subject S, each node counts the number of distinct observers
that have reported an alert about S of the same type (all DOWN, or all
UP). The cut detector uses two watermarks:

- **L (low)** — below L alerts, the node considers the report "noise"
  and ignores it. (Typical: 2.)
- **H (high)** — at or above H alerts, the subject is "stable" —
  enough observers agree that the event is real. (Typical: K − 2, so
  H = 8 when K = 10.)

A proposal is **ready** when:

1. At least one subject has alert count ≥ H, AND
2. No subject has alert count in the exclusive range (L, H) — i.e. no
   subject is "in flight" with ambiguous signal.

Condition 2 is the key insight. It prevents partial proposals: if some
subject has 4/10 alerts, the cut detector waits for it to either settle
(≥ H) or fade (≤ L) before any view change is proposed. This bundles
correlated failures into a single proposal.

The proposal contains the set of subjects with alert count ≥ H. Every
node independently derives the same proposal because they all see the
same alert stream (alerts are broadcast reliably).

### Fast Paxos consensus

When a proposal is ready, each node broadcasts a **view-change vote**
containing the proposed subjects. Vote-counting is simple: every node
tallies the votes it sees.

- **Fast path (Fast Paxos):** if ≥ 3/4·N identical votes arrive before
  the round timeout, the view change is committed. The 3/4 quorum is
  the Fast Paxos majority needed for one-round decisions.
- **Slow path (classical Paxos):** if no cut receives 3/4·N
  agreement, RAPID falls back to a leader-based classical Paxos round.
  A coordinator proposes one of the submitted cuts (commonly the one
  with the most votes) and collects a simple majority (>1/2·N) to
  commit.

Because the cut detector is deterministic given the same alert stream,
the fast path succeeds in the common case — every correct node
produces the same proposal, and a single round commits it.

### Self-refutation

If a live node learns via an alert that it's being suspected (or, more
commonly, receives a SUSPICION_PROPOSAL naming itself), it **bumps its
incarnation** and broadcasts a higher-incarnation ALIVE message. The
ALIVE message cancels any pending proposal that named the node. See
`incarnation-refutation.md` for the mechanics of refutation.

### key-parameters

| Parameter | Description | Typical | Impact |
|-----------|-------------|---------|--------|
| K | Monitors/observers per vertex | 10 (paper) or ⌈log₂ N⌉ | Higher K → stronger cut signal, more ping traffic |
| L | Low watermark | 2 | Lower L → more false positives; higher L → slower reaction |
| H | High watermark | K − 2 | Lower H → faster proposal; higher H → stronger agreement before commit |
| Consensus round timeout | Fast-Paxos deadline | ~1–2 × protocolPeriod | Too short → unnecessary Paxos fallback; too long → slow reaction |
| Batching window | Edge alert aggregation | 100 ms – 500 ms | Larger window → fewer rounds, more batched view changes |
| Alert broadcast fanout | O(log N) or K | — | Must reach all nodes before round timeout |

## algorithm-steps

### Per-tick monitoring (each node)

1. Query the overlay: `monitors = overlay.monitorsOf(self)` — the K
   subjects this node is responsible for.
2. For each monitor, probe liveness (ping / phi-accrual check).
3. If phi exceeds threshold for a subject, emit a DOWN alert about
   that subject to the cluster (broadcast, best-effort gossip).

### Per-alert handling (each node)

1. Record `(subject, observer)` in the alert table for the alert type.
2. Compute, for each subject, the number of distinct observers in its
   alert table.
3. Let `proposal = { S : alertCount(S) ≥ H }` and
   `inFlight = { S : L < alertCount(S) < H }`.
4. If `inFlight == ∅` AND `proposal ≠ ∅`, the cut is stable — emit a
   consensus vote with `proposal`.

### Per-vote handling (each node)

1. Tally votes for each distinct proposal value.
2. If some proposal has ≥ ⌈3/4·N⌉ votes, commit the view change
   (advance epoch, apply to local view, notify listeners).
3. Else if the round timeout fires and no fast path, fall back to
   classical Paxos led by a coordinator (any node with a deterministic
   tiebreaker such as lowest nodeId can coordinate).

### Self-refutation

1. On receiving a SUSPICION or proposal naming self:
   a. Bump local incarnation: `inc ← inc + 1`.
   b. Broadcast `ALIVE(self, inc)`.
2. On receiving `ALIVE(subject, inc_new)`: if `inc_new > localIncOf(subject)`,
   discard all pending DOWN alerts about subject and update the local
   view's incarnation for subject.

## implementation-notes

### data-structure-requirements

- **Alert table:** `Map<Subject, Map<AlertType, Set<Observer>>>` with
  TTL on stale alerts (older than one consensus round).
- **Active proposal:** a single pending vote snapshot. Abandon on
  timeout; replace on fresh stable proposal.
- **Consensus round:** `Map<ProposalValue, Set<Voter>>` keyed by the
  sorted subject set so identical proposals compare equal.
- **Overlay:** a `Map<NodeAddress, Set<NodeAddress>>` for `monitorsOf`
  and a reverse index for `observersOf`. Recomputed on view change.

### edge-cases-and-gotchas

- **Alert stream fragmentation.** If the cluster is partitioned into
  groups smaller than the ALIVE set, each partition's cut detector
  sees only its own observers' alerts. H must be reachable within the
  partition, otherwise no side makes progress.
- **Overlay drift during view change.** The overlay must not change
  mid-round. Snapshot the view at round start; compute monitors from
  the snapshot for the round's duration.
- **Proposal idempotence.** A node may receive the same vote twice
  (duplicate broadcast). Tallying must be set-based, not count-based,
  to avoid double-counting.
- **Vote retransmission.** On fast-path failure and Paxos fallback, do
  not reuse fast-path votes as classical Paxos votes — the two rounds
  have different quorum semantics.
- **Self-refutation race.** A node may refute after the proposal
  crosses H but before consensus commits. Refutation must cancel the
  pending round for that subject, not the entire proposal (other
  subjects in the same proposal may still be valid).

## complexity-analysis

### build-phase

- Overlay construction: O(N) per node using a shared permutation; no
  communication.
- Alert broadcast: O(log N) rounds per alert via gossip, or O(N)
  messages if broadcast is direct.

### query-phase

- Monitoring load per tick: O(K) pings per node → O(K·N) total
  across the cluster, O(log N) factor over SWIM's O(N²) worst case.

### memory-footprint

- Alert table: O(|subjects-in-flight| · K) entries per node.
- Consensus round: O(|proposals| · N) votes per node. In the common
  case, one proposal wins and this is O(N).

## tradeoffs

### strengths

- **Stable under correlated failures.** Many SWIM protocols oscillate
  when a rack or AZ flaps; RAPID's H/L watermarks and bundled
  proposals bring a whole failure group in one view change.
- **Scales to 10k+ nodes.** Paper reports 2000-node bootstrap 2–5.8×
  faster than Memberlist or ZooKeeper.
- **Strongly consistent views.** Every correct node sees the same
  sequence of view changes (unlike SWIM's eventually-consistent gossip).

### weaknesses

- **Higher per-change cost.** A single-node churn still triggers a
  consensus round — more expensive than SWIM's unilateral transition.
- **Round timeout tuning.** Too short → Paxos fallback overhead; too
  long → slow reaction. Paper recommends tuning against observed
  gossip latency.
- **Less battle-tested.** SWIM has been in production for a decade
  longer.

### compared-to-alternatives

- [cluster-membership-protocols.md](./cluster-membership-protocols.md) —
  SWIM + phi accrual, the incumbent. RAPID replaces its unilateral
  suspicion transition with consensus.
- [partition-replication-consensus.md](../consensus/partition-replication-consensus.md) —
  Raft/Paxos for data replication. RAPID's consensus is membership-only,
  leaderless, and co-located with the monitoring overlay.

## current-research

### key-papers

Suresh, L., Malkhi, D., Gopalan, P., Carreiro, I. P., & Lokhandwala, Z.
(2018). *Stable and Consistent Membership at Scale with Rapid.*
USENIX ATC 2018.
[PDF](https://www.usenix.org/system/files/conference/atc18/atc18-suresh.pdf)

### active-research-directions

- Integration with hierarchical cluster topologies (rack-/zone-aware K).
- Adaptive H/L based on observed alert noise.
- Byzantine-tolerant variants (the baseline assumes crash failures).

## practical-usage

### when-to-use

- Clusters >100 nodes where SWIM's O(N) monitoring load becomes
  painful.
- Workloads that cannot tolerate split-brain or inconsistent views.
- Environments with correlated failure modes (rack-level, AZ-level
  outages).

### when-not-to-use

- Small clusters (<20 nodes) where SWIM is simpler and adequate.
- Extremely dynamic membership where per-change consensus cost
  dominates (ephemeral workloads, Lambda-style scaling).

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| lalithsuresh/rapid | Java (reference) | https://github.com/lalithsuresh/rapid | Maintained by paper author |
| Akka Cluster (Rapid addon) | Scala | see Bernhardt article | Experimental, covered in blog source |

## code-skeleton

```java
final class RapidConsensus {
  private final int K, H, L;
  private final Map<Subject, Map<AlertType, Set<Observer>>> alerts = new ConcurrentHashMap<>();
  private final Map<SortedSet<Subject>, Set<Voter>> votes = new ConcurrentHashMap<>();
  private final ExpanderOverlay overlay;
  private final ClusterTransport transport;
  private volatile ProposalRound pending;

  void onEdgeAlert(Subject s, AlertType t, Observer o) {
    alerts.computeIfAbsent(s, _ -> new ConcurrentHashMap<>())
          .computeIfAbsent(t, _ -> ConcurrentHashMap.newKeySet())
          .add(o);
    maybeStartRound();
  }

  private void maybeStartRound() {
    var proposal = new TreeSet<Subject>();
    for (var e : alerts.entrySet()) {
      int count = aggregateCount(e.getValue());
      if (count >= H) proposal.add(e.getKey());
      else if (count > L) return; // in-flight — wait
    }
    if (!proposal.isEmpty()) broadcastVote(proposal);
  }

  void onVote(SortedSet<Subject> proposal, Voter voter) {
    var voters = votes.computeIfAbsent(proposal, _ -> ConcurrentHashMap.newKeySet());
    voters.add(voter);
    int quorum = (3 * currentViewSize() + 3) / 4;  // ceil(3/4 · N)
    if (voters.size() >= quorum) commitViewChange(proposal);
  }
}
```

## sources

1. [Stable and Consistent Membership at Scale with Rapid](https://www.usenix.org/conference/atc18/presentation/suresh) — USENIX ATC 2018, primary paper. Authoritative source for the cut-detector H/L semantics, Fast Paxos fast path, and expander-graph construction.
2. [10000 nodes and beyond with Akka Cluster and Rapid](https://manuel.bernhardt.io/2020/04/30/10000-node-cluster-with-akka-and-rapid) — Practitioner overview of RAPID integrated into Akka Cluster; useful for tuning parameters and confirming the three-layer architecture. Notes the 100ms batching window is too small at 10k+ nodes.
3. [lalithsuresh/rapid](https://github.com/lalithsuresh/rapid) — Reference Java implementation by the paper's first author; source for K=10 default and concrete message schema.

---
*Researched: 2026-04-19 | Next review: 2026-10-16*
