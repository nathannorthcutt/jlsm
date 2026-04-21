---
{
  "id": "membership.cluster-health-and-recovery",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [
    "membership"
  ],
  "requires": [
    "F22"
  ],
  "invalidates": [],
  "decision_refs": [
    "piggybacked-state-exchange",
    "slow-node-detection",
    "membership-view-stall-recovery",
    "cluster-membership-protocol"
  ],
  "kb_refs": [
    "distributed-systems/cluster-membership/cluster-membership-protocols",
    "distributed-systems/cluster-membership/view-stall-recovery",
    "distributed-systems/cluster-membership/fail-slow-detection"
  ],
  "open_obligations": [
    "MembershipView.liveMemberCount must use isRoutable() not == ALIVE",
    "ClusteredTable.collectLiveNodes must use isRoutable()",
    "RendezvousOwnership.computeRankedOwners must use isRoutable()",
    "RapidMembership.protocolTick must ping DEGRADED nodes (isRoutable)",
    "RapidMembership.propagateViewChange must include DEGRADED nodes",
    "RapidMembership.handleViewChangeProposal dropsAlive guard must use isRoutable()",
    "ClusteredEngine.onViewChanged must not treat DEGRADED as departure",
    "Routing weight reduction for DEGRADED nodes requires weighted-node-capacity ADR"
  ],
  "_migrated_from": [
    "F23"
  ]
}
---
# membership.cluster-health-and-recovery — Cluster Health & Recovery

## Requirements

### Piggybacked State Exchange

R1. Each heartbeat message (PING and ACK) must include a fixed-format metadata
section appended after the protocol payload. Format: 1-byte version, 4-byte
IEEE 754 float32 p99_query_ms (big-endian), 4-byte IEEE 754 float32
p99_replication_ms (big-endian), 1-byte unsigned local_health_multiplier
(LHM). Total: 10 bytes per heartbeat.

R1a. Metadata must be piggybacked on both PING and ACK messages. Both sides
of the heartbeat exchange report their own metrics. The sender appends its
current local metrics; the receiver reads them.

R2. Metadata version byte must be 1 for this spec. Receivers that see an
unknown version must read v1 fields at known offsets and ignore additional
bytes. Senders must always write version 1 format.

R3. Metadata parsing must be O(1) fixed-offset access — no iteration, no key
lookup. Use `MemorySegment.get()` with `ValueLayout.JAVA_FLOAT_UNALIGNED` for
p99 values and `ValueLayout.JAVA_BYTE` for version and LHM.

R4. Metadata presence is detected by message payload length. Each message type
that carries metadata defines a `PROTOCOL_PAYLOAD_SIZE` constant (the size of
the base protocol payload without metadata). If `payload.length >
PROTOCOL_PAYLOAD_SIZE` for that message type, the bytes beyond that offset are
the metadata section. Currently: PING protocol payload = 0 bytes, ACK
protocol payload = 0 bytes. If a message type's protocol payload changes in
the future, its `PROTOCOL_PAYLOAD_SIZE` constant must be updated accordingly.

R5. NaN, Infinity, or negative p99 values must be treated as "no data
available." The receiver must substitute the default (0.0f) and exclude the
peer from peer comparison scoring (R17). These values must not be used in
any computation.

R6. The LHM byte is reserved for future Local Health Multiplier. Initially set
to 0 (healthy). Receivers must not interpret non-zero LHM values until a
future spec defines their semantics.

### Slow Node Detection — State Model

R7. The `MemberState` enum must be extended with a `DEGRADED` value.
Transitions: `ALIVE -> DEGRADED` (any detection signal triggers),
`DEGRADED -> ALIVE` (all signals clear for recovery_periods),
`DEGRADED -> SUSPECTED` (phi exceeds failure threshold — same as ALIVE),
`SUSPECTED -> DEAD` (normal failure path).

R8. A predicate `isRoutable()` must return true for `ALIVE` and `DEGRADED`,
false for `SUSPECTED` and `DEAD`. All existing code paths that use binary
`== MemberState.ALIVE` checks for routing, ownership, view change
validation, and heartbeat monitoring must be updated to use `isRoutable()`.

R9. Open obligations for `isRoutable()` adoption at existing call sites:
- `MembershipView.liveMemberCount()` — must count routable members for
  quorum computation
- `ClusteredTable.collectLiveNodes()` — must include DEGRADED nodes in
  scatter-gather target list
- `RendezvousOwnership.computeRankedOwners()` — must include DEGRADED nodes
  in ownership computation
- `RapidMembership.protocolTick()` — MUST ping DEGRADED nodes identically to
  ALIVE. Without this, a DEGRADED node that crashes is never detected via phi
  accrual, creating a permanent undetectable failure.
- `RapidMembership.propagateViewChange()` — must include DEGRADED nodes in
  view change dissemination
- `RapidMembership.handleViewChangeProposal()` — `dropsAlive` guard must
  protect routable members (ALIVE or DEGRADED) from removal without
  SUSPECTED/DEAD transition
- `ClusteredEngine.onViewChanged()` — must not treat ALIVE->DEGRADED as a
  departure; must not trigger `gracePeriodManager.recordDeparture()`

R10. `MembershipListener` must be extended with:
- `onMemberDegraded(Member)` — called when a member transitions to DEGRADED
- `onMemberRecovered(Member)` — called when a DEGRADED member returns to ALIVE

R10a. DEGRADED and RECOVERED transitions are NOT view changes — they do not
bump the epoch. They are out-of-band notifications dispatched by the slow-node
detector on its evaluation thread. Callback implementations must be
thread-safe and must not assume they run on the membership protocol's internal
thread. This avoids epoch inflation from flapping nodes (a node oscillating
between ALIVE and DEGRADED would otherwise bump the epoch on every transition,
causing ownership cache eviction cascades).

### Slow Node Detection — Three Signals

R11. Three detection signals, any of which independently triggers DEGRADED:

**Signal 1 — Phi Threshold Bands (R12-R13):**

R12. The phi accrual failure detector must be extended with an intermediate
threshold band. Phi values in `[phi_warning_threshold, phi_failure_threshold)`
indicate DEGRADED. Default `phi_warning_threshold`: 4.0.

R13. Phi band detection adds threshold comparisons to the existing phi
computation. The per-peer detection state structure (tracking which signals are
active for each peer) is new, but the phi computation itself requires no
additional data structures.

**Signal 2 — Peer Comparison Scoring (R14-R19):**

R14. Each node computes a slowdown ratio for every peer using piggybacked
metadata: `slowdown_ratio = peer_p99 / median_p99` where `median_p99` is the
median p99_query_ms across all known peers with non-zero, finite, positive p99
values (excluding self).

R15. Uses the `p99_query_ms` field from piggybacked metadata (R1).
`p99_replication_ms` is available for future use but not consumed by this spec.

R16. Peers with p99 = 0.0f (no data/default), NaN, Infinity, or negative
values must be excluded from the comparison set before computing the median.

R17. A peer with `slowdown_ratio > slowdown_ratio_threshold` (default 3.0) for
`slowdown_duration` consecutive protocol periods (default 5) is marked
DEGRADED.

R18. If fewer than 2 non-zero peers remain after filtering (R16), peer
comparison is disabled for that evaluation period. No peer can be marked
DEGRADED by this signal.

R19. Small cluster handling: at 2 total nodes, the median is the other node's
value. At 1 node (self only), peer comparison is permanently disabled.

**Signal 3 — Request Latency Tracking (R20-R23):**

R20. The scatter-gather proxy (or any request-response path) must track
per-peer request-response latency using an EWMA with configurable alpha
(default 0.3). The EWMA must be initialized to the latency of the first
request (not zero) to avoid false triggering on the second request.

R21. When `current_latency > ewma x slowdown_factor` (default 3.0) for
`slow_count_threshold` consecutive requests (default 5), the peer is marked
DEGRADED.

R22. Peers with no recent requests from the local observer are not evaluated
by this signal. Peer comparison (signal 2) covers these peers via shared
metadata.

R23. Signal 3 is designed to detect latency spikes (sudden transient
degradation), not sustained elevated latency (where the EWMA adapts and the
ratio normalizes). Sustained degradation relative to peers is detected by
signal 2 (peer comparison). This is an intentional limitation of the fast-
adapting EWMA (alpha=0.3).

### Slow Node Detection — Detection and Recovery Rules

R24. Detection rule: ANY of the three signals triggering is sufficient to
mark a peer DEGRADED. The signals operate independently — no quorum or
consensus.

R25. Recovery rule: ALL three signals must be clear for `recovery_periods`
consecutive protocol periods (default 10) before a DEGRADED peer transitions
back to ALIVE. This hysteresis prevents flapping.

R26. DEGRADED state actions: `[DEFERRED: routing weight reduction requires
weighted-node-capacity ADR]`. Currently: DEGRADED nodes are routable
(`isRoutable() = true`) but receive equal routing weight. Replication continues
normally. The node remains in the membership view. When the weighted-node-
capacity ADR is resolved, DEGRADED nodes should receive reduced routing weight
so queries prefer healthy replicas.

### Membership View Stall Recovery

R27. The membership protocol must implement three escalating recovery tiers:

**Tier 1 — Piggyback Catch-up (R28):**

R28. For stalls lasting fewer than `piggyback_threshold` protocol periods
(default 10): no special action. Normal piggybacked protocol messages
naturally repair small gaps in the membership view via the dissemination
buffer.

**Tier 2 — Anti-Entropy Sync (R29-R33):**

R29. For larger divergence, the protocol must support two-phase anti-entropy
via F19's request-response pattern. The initiator sends a STATE_DIGEST request
containing its view's digest. The responder computes the delta and responds
with STATE_DELTA. The two-phase exchange is one-directional: the initiator
learns the responder's state. For bidirectional reconciliation, both sides
must independently detect divergence and initiate their own sync.

R30. The DIGEST payload contains `{compact_node_hash (16 bytes) + incarnation
(8 bytes)}` for each known member. Hash algorithm: MurmurHash3 x64-128
(available in `jlsm.bloom.hash`). At 1000 nodes: ~24 KiB — fits in one
METADATA-class transport frame.

R31. Anti-entropy sync must be triggered when a node detects incarnation gaps
(received a message referencing an incarnation newer than known) or when
receiving messages from members not in its current view.

R32. Sync must use jitter on the trigger (random delay 0-2s) and limit
concurrent outgoing syncs to 1 per node, to prevent amplification at large
cluster sizes.

R33. STATE_DIGEST is sent as `ClusterTransport.request()`. STATE_DELTA is the
response. The existing `MessageType.STATE_DIGEST` (0x05) and
`MessageType.STATE_DELTA` (0x06) wire tags are used. No new message types
needed.

**Tier 3 — Forced Rejoin (R34-R37):**

R34. For catastrophic divergence (>50% unknown members or partitioned longer
than `max_stall_duration`, default 300s), the node must discard its local view
entirely, call `discoverSeeds()` (via F22 continuous rediscovery), and run the
standard bootstrap join protocol from scratch.

R35. During forced rejoin: (a) fail all in-flight outbound requests with
`IOException`, (b) stop accepting writes immediately, (c) reject all incoming
QUERY_REQUEST and write messages with an error response indicating the node is
rejoining — the rejection must be distinguishable from a transient failure so
callers can re-route to the new owner, (d) continue serving reads for locally-
owned data during the grace period.

R36. The grace period mechanism (existing `GracePeriodManager`) handles
ownership conflicts during the dual-identity window between old incarnation
winding down and new incarnation joining.

R37. Forced rejoin must use the continuous rediscovery mechanism (F22) to find
current cluster seeds.

### Quorum Loss

R38. For quorum loss (>25% simultaneously unreachable, per Rapid's 75%
consensus requirement): the engine must enter degraded mode (existing F04 R41)
and offer an operator API for manual view reset.

R39. No automatic quorum recovery without operator approval. The operator must
explicitly accept the safety tradeoff of forcing a new view with fewer
members.

### Parameters

R40. All parameters must be configurable. Defaults:
- `phi_warning_threshold`: 4.0
- `slowdown_ratio_threshold`: 3.0
- `slowdown_duration`: 5 consecutive protocol periods
- `ewma_alpha`: 0.3
- `slow_count_threshold`: 5 consecutive slow requests
- `recovery_periods`: 10 consecutive protocol periods
- `piggyback_threshold`: 10 protocol periods
- `anti_entropy_interval`: 30s proactive cycle
- `divergence_threshold`: 50% unknown members
- `max_stall_duration`: 300s
- `quorum_loss_timeout`: 300s

All timers must use monotonic clock (`System.nanoTime()`), not wall clock.
"Period" throughout this spec means the protocol period (heartbeat interval,
configurable, default 1s).

### Concurrency

R41. Piggybacked metadata encoding/decoding must be thread-safe and lock-free
(fixed-offset MemorySegment access).

R42. Slow-node detection state (EWMA values, consecutive counters, per-peer
signal flags) must be per-peer and must not require global locks. Per-peer
state can use volatile fields or atomic variables.

R43. Anti-entropy sync must not block the membership protocol's main heartbeat
loop. Sync messages are sent asynchronously via the transport.

### Observability

R44. Queryable metrics:
(a) peers in DEGRADED state (gauge)
(b) DEGRADED transitions per signal type (counter — phi, peer-comparison,
    request-latency)
(c) recovery transitions DEGRADED->ALIVE (counter)
(d) anti-entropy syncs initiated (counter)
(e) anti-entropy syncs completed (counter)
(f) forced rejoins (counter)
(g) peer p99_query_ms (per-peer gauge — from piggybacked metadata)
(h) peer slowdown_ratio (per-peer gauge)
(i) per-peer EWMA request latency (gauge)
(j) stall duration (gauge — time since last successful view change)
(k) current stall tier (gauge — 0=normal, 1=piggyback, 2=anti-entropy,
    3=forced rejoin)

---

## Design Narrative

### Intent

Extend the membership protocol with three capabilities: (1) lightweight
metadata exchange via heartbeats for cluster-wide observability, (2) detection
of slow-but-alive nodes to prevent cascading latency degradation, (3)
automated recovery from membership view stalls of varying severity.

### Why composite slow-node detection

No single signal catches all failure modes. Phi bands detect heartbeat delays
but miss slow-disk nodes with healthy heartbeats. Peer comparison detects
relative degradation via shared metadata but requires piggybacked state.
Request latency measures the actual user-facing symptom (spikes, not sustained
shifts — see R23). ANY trigger + ALL clear provides sensitivity without
flapping.

### Why tiered stall recovery

Most stalls are short (tier 1, zero cost). Only severe divergence triggers
anti-entropy. Forced rejoin is a last resort. Proportionate response avoids
wasting resources on transient network blips.

### Why fixed-field metadata (not KV or CRDT)

O(1) parsing at heartbeat frequency. 10 bytes on ~100-byte heartbeats is 10%
overhead. Performance metrics are last-writer-wins — CRDTs solve a problem
that doesn't exist here. Extensible via version byte for future fields.

### Why DEGRADED transitions are not view changes

Adversarial review (round 2, F2-8) revealed that treating DEGRADED/RECOVERED
as view changes (epoch bumps) causes ownership cache eviction cascades when a
node flaps. Out-of-band notifications avoid epoch inflation while still
informing listeners.

### Why two-phase anti-entropy (not three-phase)

The ADR specifies three-phase (DIGEST → DELTA → DELTA_ACK). The spec uses
two-phase via F19's request-response pattern, which eliminates the DELTA_ACK
message type. The trade-off: two-phase is one-directional (initiator learns
responder's state). For bidirectional reconciliation, both sides must
independently detect divergence and initiate their own sync, doubling
exchanges. This is acceptable because sync is infrequent and the trigger
conditions (R31) ensure both sides detect divergence independently.

### Hardening summary

Two adversarial falsification rounds:
- Round 1: 17 findings — DEGRADED state invisible to existing consumers
  (isRoutable), no DEGRADED→SUSPECTED path, DELTA_ACK elimination, "period"
  undefined, missing MembershipListener callbacks, forced rejoin data handling,
  metadata offset ambiguity, NaN comparison, median edge cases, EWMA cold
  start, anti-entropy concurrency direction
- Round 2: 8 findings — view change proposal guard, DEGRADED crash detection
  black hole, anti-entropy asymmetry, inbound requests during rejoin,
  EWMA signal 3 limitation, callback threading/epoch semantics,
  PROTOCOL_PAYLOAD_SIZE per-type, digest hash algorithm
