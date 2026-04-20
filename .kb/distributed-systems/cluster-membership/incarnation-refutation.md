---
title: "Incarnation-Based Self-Refutation"
aliases: ["self-refutation", "incarnation number", "alive-refute", "SWIM refutation", "Lifeguard buddy system"]
topic: "distributed-systems"
category: "cluster-membership"
tags: ["incarnation", "self-refutation", "swim", "lifeguard", "suspicion", "gossip", "membership"]
complexity:
  time_build: "O(1) per node — just an integer counter"
  time_query: "O(1) comparison per inbound message"
  space: "O(N) — one incarnation per known member"
research_status: "mature"
confidence: "high"
last_researched: "2026-04-19"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/Member.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/internal/RapidMembership.java"
related:
  - ./rapid-consensus.md
  - ./cluster-membership-protocols.md
decision_refs: [".decisions/cluster-membership-protocol/adr.md"]
sources:
  - url: "https://arxiv.org/pdf/1707.00788"
    title: "Lifeguard: Local Health Awareness for More Accurate Failure Detection"
    accessed: "2026-04-19"
    type: "paper"
  - url: "https://ar5iv.labs.arxiv.org/html/1707.00788"
    title: "Lifeguard (ar5iv HTML)"
    accessed: "2026-04-19"
    type: "paper"
  - url: "https://www.cs.cornell.edu/projects/Quicksilver/public_pdfs/SWIM.pdf"
    title: "SWIM: Scalable Weakly-consistent Infection-style Process Group Membership"
    accessed: "2026-04-19"
    type: "paper"
---

# Incarnation-Based Self-Refutation

## summary

Self-refutation lets a live node override a premature suspicion about
itself by broadcasting an `ALIVE(self, incarnation+1)` message. The
incarnation number is a monotonically-increasing per-node counter that
acts as a **vector clock for self-reports** — a later `ALIVE` always
beats an earlier `SUSPECT` about the same node. Without this primitive,
a slow GC pause or transient network blip on a live node can trigger a
permanent DEAD verdict from peers that briefly lost contact. SWIM
introduced the mechanism; Lifeguard refined it with the *buddy system*
and *adaptive suspicion timeout* to make refutation win the race
against timeout in more scenarios.

## how-it-works

### The state machine

Each node maintains an incarnation counter `inc[self]` and a per-peer
map `inc[peer]`. Incoming messages carry `(address, state, incarnation)`.

```
receive msg about Node X with (state_new, inc_new):
    inc_old = inc[X]
    if inc_new > inc_old:
        # Fresher info wins — accept unconditionally.
        inc[X] = inc_new
        state[X] = state_new
    elif inc_new == inc_old:
        # Same incarnation — precedence by state severity (ALIVE < SUSPECT < DEAD).
        if state_new is more-severe-than state[X]:
            state[X] = state_new
    else:
        # inc_new < inc_old: stale gossip; discard.
        pass
```

### Self-refutation

```
on receiving any msg about self with state != ALIVE OR inc_new > inc[self]:
    inc[self] = max(inc[self], inc_new) + 1
    broadcast ALIVE(self, inc[self])
```

The increment **only the suspected node itself** may perform — no
other node can bump another node's incarnation. This is a hard rule:
violating it allows any node to launder a false ALIVE for a failed
peer.

### The race: refutation vs suspicion-timeout

When a peer P suspects node X:

1. P broadcasts `SUSPECT(X, inc_X)`.
2. P starts a **suspicion timeout** `T_s`.
3. If `T_s` fires before P receives `ALIVE(X, inc_X + 1)`, P
   broadcasts `CONFIRM(X, inc_X)` and transitions X to DEAD locally.

For X's refutation to win, the `ALIVE` must reach P before `T_s`
expires. This requires:

- X's message-handling loop is live (not stalled on GC, disk I/O, etc.).
- Gossip fanout gets `ALIVE` to P in < `T_s` − (one-way delay from X
  to P).

### Lifeguard refinements

The basic SWIM timeout is static. Lifeguard (Dadgar et al., 2017)
addresses two pathologies:

**LHA-Suspicion (Local Health Awareness).** A node that has been
ignoring pings recently cannot trust its own suspicions — maybe its
disk is slow, not its peers'. So it raises `T_s` dynamically based on
its own missed-ping count, giving peers more time to refute.

**Buddy System.** When a node P is about to SUSPECT X, P first pings
X directly with a SUSPECT-intent piggyback. This gives X the fastest
possible signal that it needs to refute — X receives the message from
P rather than having to wait for gossip propagation.

**Adaptive `T_s` formula (from Lifeguard paper):**

```
T_s = max(T_min, T_max − (T_max − T_min) · log(C + 1) / log(K + 1))
```

Where `C` = independent suspicions received about the subject so far,
`K` = suspicion count threshold (default 3). More independent suspicions
→ shorter `T_s` → faster convergence once multiple peers agree.

## key-parameters

| Parameter | Description | Typical | Impact |
|-----------|-------------|---------|--------|
| `inc[self]` | Local incarnation counter | starts at 0 | Must be monotonic within a JVM lifetime |
| `T_s` (suspicion timeout) | Time before SUSPECT → DEAD | 5× protocol period | Too short → false positives; too long → slow convergence |
| `T_min` (Lifeguard) | Minimum adaptive timeout | ~500 ms | Floor for fast convergence once K suspicions received |
| `T_max` (Lifeguard) | Maximum adaptive timeout | ~5 s | Ceiling when only 1 suspicion received |
| `K` (Lifeguard) | Suspicion count threshold | 3 | Independent suspicion count at which `T_s` reaches `T_min` |
| ALIVE-refute gossip fanout | Nodes receiving the refute per round | log N | Must reach all suspecting peers before `T_s` |

## algorithm-steps

### Handling an inbound membership message

1. Parse `(address, state, inc_new)` from message payload.
2. If `address == self`:
    a. If `state != ALIVE` OR `inc_new > inc[self]`:
        - `inc[self] = max(inc[self], inc_new) + 1`
        - Broadcast `ALIVE(self, inc[self])`
    b. Return (no further action for self messages).
3. Else:
    a. `inc_old = inc[address]` (or 0 if unknown).
    b. If `inc_new > inc_old`: accept state unconditionally.
    c. Else if `inc_new == inc_old`: apply precedence
       (ALIVE < SUSPECT < DEAD); keep the more-severe state.
    d. Else: drop the message as stale.

### Emitting a SUSPECT

1. Peer P detects X is unreachable (phi or K-timed-out pings).
2. P sets `state[X] = SUSPECT`, `inc[X]` unchanged.
3. P broadcasts `SUSPECT(X, inc[X])`.
4. P schedules `confirmDeadAt(X) = now + T_s`.

### Processing a timeout

1. For each suspected X, if `now >= confirmDeadAt(X)`:
    a. `state[X] = DEAD`.
    b. Broadcast `CONFIRM(X, inc[X])`.
    c. Remove X from the live-monitoring set.

### Processing a refutation

1. On receiving `ALIVE(X, inc_new)` with `inc_new > inc[X]`:
    a. `state[X] = ALIVE`, `inc[X] = inc_new`.
    b. Cancel `confirmDeadAt(X)`.
    c. Abandon any pending consensus round naming X (if integrated
       with RAPID — see rapid-consensus.md).

## implementation-notes

### data-structure-requirements

- Per-peer `state` + `incarnation` in a concurrent map.
- Suspicion timer wheel or `ScheduledExecutorService` keyed by
  address, reset on refutation.
- Self-incarnation protected by a `volatile long` or `AtomicLong` —
  concurrent increment from multiple inbound handlers.

### edge-cases-and-gotchas

- **Incarnation must survive restarts monotonically.** On process
  restart, a fresh node initialises `inc[self] = 0` again; peers that
  remember a higher incarnation will discard the new ALIVE as stale.
  Remedies: persist the last-used incarnation; or treat a DEAD →
  ALIVE transition by a rejoining node as "new member" with `address`
  mutation (append a nonce). jlsm's R13 follows the latter — treat a
  rejoined DEAD node as a new member.
- **Overflow.** `inc` is a 64-bit counter; overflow requires
  2⁶⁴ bumps and is not a practical concern. 32-bit counters are risky
  over long lifetimes.
- **Message reordering.** The `inc_new > inc_old` check naturally
  handles reordering — stale messages are discarded by the comparison.
- **Simultaneous refutations from two observers.** Observer A and B
  both send SUSPECT(X). X sees both, bumps inc twice (once per
  inbound message), and may end up with inc+2 instead of inc+1. This
  is harmless — the refutation still wins if broadcast is timely.
  Optimisation: deduplicate inbound SUSPECT by sender before
  bumping.
- **Refutation storm.** If a node is flapping (genuinely unstable),
  repeated SUSPECT → REFUTE cycles waste bandwidth. Mitigation: cap
  the refutation rate (e.g. no more than one refute per T_s).
- **Clock interactions.** `T_s` is a local duration — use
  `MonotonicClock` (not wall clock) to avoid NTP-backward-step
  extending the timeout. See F04.R53 / `monotonic-clock` in jlsm.

## complexity-analysis

### build-phase

- Initial `inc[self] = 0`; no construction cost.

### query-phase

- O(1) comparison per inbound membership message.
- O(N) broadcast when refuting — every live peer should receive the
  ALIVE, so gossip fanout must cover the cluster within `T_s`.

### memory-footprint

- O(N) incarnation entries — 8 bytes per known member.

## tradeoffs

### strengths

- **Prevents correlated false positives.** A live node with transient
  message-processing delay can still survive by refuting.
- **Minimal overhead.** One counter per node; one extra message per
  refutation. No consensus needed.
- **Composable.** Works as a primitive under SWIM, Rapid, Serf, and
  any gossip-based membership.

### weaknesses

- **Relies on timely message processing.** A truly stalled node (GC
  pause exceeds `T_s`) cannot refute — the protocol correctly marks
  it DEAD. This is a *feature*, not a bug, but it means the
  refutation boundary is latency-bounded.
- **Does not handle Byzantine failures.** A malicious node can send
  arbitrary incarnations; bounds rely on honest behaviour.
- **Restart-sensitive.** Without persistent incarnation, a restarting
  node can be incorrectly marked DEAD for longer than necessary.

### compared-to-alternatives

- **No refutation (hard suspicion → DEAD):** simpler but fragile;
  unsuitable for any cluster >10 nodes.
- **Lease-based liveness:** each node renews a lease; expiry → dead.
  Works but centralises on the lease authority.
- **Quorum-gated suspicion (RAPID):** observers agree before the
  transition happens at all. Refutation is still useful as a
  last-line override — see [rapid-consensus.md](./rapid-consensus.md).

## current-research

### key-papers

Das, A., Gupta, I., & Motivala, A. (2002). *SWIM: Scalable
Weakly-consistent Infection-style Process Group Membership Protocol.*
DSN 2002.
[PDF](https://www.cs.cornell.edu/projects/Quicksilver/public_pdfs/SWIM.pdf)

Dadgar, A., Phillips, J., & Currey, J. (2017). *Lifeguard: Local
Health Awareness for More Accurate Failure Detection.* DSN 2017.
[arXiv:1707.00788](https://arxiv.org/pdf/1707.00788)

### active-research-directions

- Incarnation persistence strategies (WAL vs epoch counter).
- Byzantine-tolerant self-refutation (requires signatures on ALIVE).
- Cross-datacenter refutation latency (gossip vs direct).

## practical-usage

### when-to-use

- Any membership protocol with an intermediate SUSPECT state.
- Systems where transient node delays (GC, disk I/O) are expected
  and should not cause immediate DEAD verdicts.

### when-not-to-use

- Systems without an intermediate SUSPECT state — if you go directly
  from ALIVE to DEAD, refutation requires restructuring the state
  machine first.
- Byzantine environments without message authentication.

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| hashicorp/memberlist | Go | https://github.com/hashicorp/memberlist | Active (Consul, Nomad, Serf) |
| apple/swift-cluster-membership | Swift | https://apple.github.io/swift-cluster-membership/docs/current/SWIM/ | Active |
| lalithsuresh/rapid | Java | https://github.com/lalithsuresh/rapid | Research reference |

## code-skeleton

```java
final class IncarnationTracker {
  private final AtomicLong selfIncarnation = new AtomicLong(0);
  private final Map<NodeAddress, PeerEntry> peers = new ConcurrentHashMap<>();
  private final Broadcaster broadcaster;
  private final NodeAddress self;

  record PeerEntry(MemberState state, long incarnation) {}

  /** Apply an inbound (address, state, inc) tuple; returns true if state changed. */
  boolean onMessage(NodeAddress address, MemberState state, long incarnation) {
    if (address.equals(self)) return handleSelf(state, incarnation);
    return peers.merge(address, new PeerEntry(state, incarnation),
        (oldEntry, newEntry) -> {
          if (newEntry.incarnation > oldEntry.incarnation) return newEntry;
          if (newEntry.incarnation == oldEntry.incarnation
              && severity(newEntry.state) > severity(oldEntry.state)) return newEntry;
          return oldEntry;
        }) != null;
  }

  private boolean handleSelf(MemberState state, long incarnation) {
    if (state == MemberState.ALIVE && incarnation <= selfIncarnation.get()) return false;
    long newInc = selfIncarnation.updateAndGet(cur -> Math.max(cur, incarnation) + 1);
    broadcaster.broadcast(new AliveRefuteMessage(self, newInc));
    return true;
  }

  private static int severity(MemberState s) {
    return switch (s) { case ALIVE -> 0; case SUSPECTED -> 1; case DEAD -> 2; };
  }
}
```

## sources

1. [Lifeguard (arxiv PDF)](https://arxiv.org/pdf/1707.00788) — definitive description of the adaptive suspicion timeout formula and buddy system.
2. [Lifeguard (ar5iv HTML)](https://ar5iv.labs.arxiv.org/html/1707.00788) — same paper, HTML-rendered; easier to grep for specific mechanisms.
3. [SWIM paper](https://www.cs.cornell.edu/projects/Quicksilver/public_pdfs/SWIM.pdf) — original introduction of incarnation numbers + alive-refute mechanism.

---
*Researched: 2026-04-19 | Next review: 2026-10-16*
