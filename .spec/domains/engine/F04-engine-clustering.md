---
{
  "id": "F04",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": ["engine"],
  "requires": [],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "cluster-membership-protocol",
    "partition-to-node-ownership",
    "rebalancing-grace-period-strategy",
    "scatter-gather-query-execution",
    "discovery-spi-design",
    "transport-abstraction-design"
  ],
  "kb_refs": [],
  "open_obligations": []
}
---

# F04 — Engine Clustering

## Requirements

### Node identity and configuration

R1. Each cluster node must be identified by a node address comprising a unique node identifier, a host, and a port. The node identifier must be non-null and non-empty. The host must be non-null and non-empty. The port must be in the range [1, 65535].

R2. The cluster configuration must support a builder pattern with the following independently configurable parameters: grace period, protocol period, ping timeout, indirect probe count, phi threshold, and consensus quorum percent. Each parameter must have a documented default value.

R3. The cluster configuration must reject a grace period of zero or negative duration at construction time with an illegal argument exception.

R4. The cluster configuration must reject a consensus quorum percent outside the range (0, 100] at construction time with an illegal argument exception. A quorum of zero is invalid because it would allow empty-cluster decisions.

R5. The cluster configuration must reject a phi threshold that is not a finite positive number at construction time with an illegal argument exception. NaN and infinity are invalid.

R6. The cluster configuration must reject a ping timeout that is zero or negative at construction time with an illegal argument exception.

R7. The cluster configuration must reject an indirect probe count that is negative at construction time with an illegal argument exception. Zero is valid and means no indirect probing.

### Message protocol

R8. Each message must contain a message type, a sender node address, a monotonically increasing sequence number, and an opaque payload. The payload must be defensively copied on construction to prevent external mutation after creation.

R9. The message type must distinguish at minimum the following categories: ping, acknowledgment, view change, query request, query response, state digest, and state delta.

R10. Message sequence numbers must be monotonically increasing per sender. A receiver must be able to detect duplicate messages by comparing the sequence number against the highest previously observed sequence number from the same sender.

R11. The message payload must accept a zero-length byte array (empty payload). Null payloads must be rejected with a null pointer exception at construction time.

### Member state tracking

R12. Each cluster member must track a state that is one of: alive, suspected, or dead. A member must also track an incarnation number that increases monotonically for a given node identity.

R13. A member in the alive state may transition to suspected. A member in the suspected state may transition to alive (if the suspicion is refuted) or to dead. A member in the dead state must not transition to alive or suspected. A dead node that reconnects must be treated as a new member with a higher incarnation number.

R14. A membership view must track the current set of members and an epoch number. The epoch must increase monotonically on every membership change (join, leave, suspect, or death).

R15. The membership view must report the count of live members (those in the alive state). Suspected and dead members must not be counted as live.

R16. The membership view must determine quorum by comparing the live member count against the configured consensus quorum percent of the total known members (alive plus suspected). Dead members must not factor into quorum calculation.

R17. The membership view must support membership testing: given a node identifier, it must report whether that node is a current member (alive or suspected) of the cluster.

### Failure detection

R18. The failure detector must use a phi accrual model: it must maintain a sliding window of inter-heartbeat arrival times per monitored member and compute phi as negative log base 10 of (1 minus the CDF of the current heartbeat delay under a normal distribution fitted to the window).

R19. When the computed phi for a member exceeds the configured phi threshold, the failure detector must mark that member as suspected. The failure detector must not directly mark a member as dead; the membership protocol is responsible for the alive-to-dead lifecycle.

R20. The failure detector must initialize its sliding window for a newly joined member with the configured protocol period as the initial expected heartbeat interval. The detector must not declare a new member suspected before receiving at least one heartbeat.

R21. The failure detector's sliding window must have a bounded maximum size. When the window is full, the oldest sample must be evicted before adding a new one. The maximum window size must be configurable.

### Discovery SPI

R22. The discovery provider must be a pluggable SPI with three operations: discover seed addresses, register self, and deregister self. All three operations must be defined by an interface contract with no concrete implementation required by the library.

R23. The discover operation must return a collection of seed node addresses. An empty collection must be valid and must mean no seeds are currently known (the node starts as a solo cluster).

R24. The register operation must be idempotent: calling register multiple times with the same node address must produce the same observable state as calling it once.

R25. The deregister operation must be idempotent: calling deregister for a node that is not registered must succeed silently, not throw an exception.

R26. The library must provide an in-JVM discovery provider implementation for testing. This implementation must store registrations in a thread-safe in-memory data structure shared across instances within the same JVM process.

### Transport SPI

R27. The cluster transport must be a pluggable SPI with operations for: sending a one-way message, sending a request and receiving a future response, and registering a handler for incoming messages by message type.

R28. The send operation must be fire-and-forget: it must not block waiting for delivery confirmation. Delivery failures must be silently absorbed by the transport, not propagated as exceptions to the sender. The failure detector is the mechanism for detecting unreachable nodes.

R29. The request operation must return a future that completes with the response message or completes exceptionally after a configurable timeout. The timeout must be enforced by the transport, not by the caller.

R30. Handler registration must be keyed by message type. Registering a second handler for the same message type must replace the previous handler, not add a second one. The replacement must be atomic from the perspective of concurrent message delivery.

R31. The library must provide an in-JVM transport implementation for testing. This implementation must deliver messages between transport instances within the same JVM process without network I/O, using thread-safe in-memory queues or direct dispatch.

R32. The in-JVM transport must simulate configurable message delivery delay and message loss rate for fault injection testing. Default values must be zero delay and zero loss.

### Membership protocol

R33. The membership protocol must be a pluggable SPI with operations for: starting the protocol, querying the current membership view, adding a membership listener, and initiating a graceful leave.

R34. The membership protocol implementation must use a RAPID-style protocol: an expander graph overlay for monitoring, multi-process cut detection for membership changes, and a consensus round requiring a configurable percentage (default 75%) of observers to agree before applying a view change.

R35. The membership protocol must distribute monitoring responsibility across an expander graph where each member monitors a bounded number of peers (the graph degree). The graph degree must be configurable with a default value sufficient for reliable failure detection in clusters up to 1000 nodes.

R36. When a member suspects a peer via the failure detector, it must not unilaterally remove the peer. Instead, it must broadcast a suspicion to the peer's other monitors. A membership change must only be applied when the configured consensus percentage of the peer's monitors agree on the suspicion.

R37. The consensus round for a single membership change must complete within a bounded time. If consensus is not reached within the bound, the suspicion must be dropped and the member must remain in its current state. The consensus timeout must be configurable.

R38. A member receiving a suspicion about itself must refute it by incrementing its incarnation number and broadcasting an alive announcement with the new incarnation. The protocol must accept a refutation with a higher incarnation number as overriding any pending suspicion for that member.

R39. The membership protocol must notify registered listeners of view changes, member joins, member departures, and member suspicions. Listener notification must be asynchronous and must not block protocol message processing. A slow or failing listener must not delay or prevent protocol progress.

R40. The graceful leave operation must notify the cluster of the departing member before ceasing protocol participation. After initiating leave, the member must not send further protocol messages. Other members must process the leave notification as a confirmed departure, not a suspicion.

### Split-brain detection and handling

R41. The membership protocol must detect a potential split-brain condition when the live member count drops below the configured quorum threshold. Upon detecting loss of quorum, the node must transition to a read-only or degraded mode and must refuse write operations that would change partition ownership.

R42. A node that has lost quorum must continue attempting to contact seed nodes and other last-known members. If quorum is re-established through reconnection, the node must merge views by adopting the view with the higher epoch. If epochs are equal, the view with more live members must be preferred.

R43. View merges after a partition heal must reconcile conflicting member states. For any member present in both views, the state with the higher incarnation number must take precedence. If incarnation numbers are equal, the more severe state must take precedence (dead > suspected > alive).

### Partition ownership

R44. The ownership model must assign partitions to nodes using rendezvous (highest random weight) hashing. For each partition, the node with the highest hash score for the (partition, node) pair must be the owner. The hash function must be deterministic and produce consistent results across all nodes given the same inputs.

R45. Ownership assignments must be computed from the current membership view and cached by epoch. When the membership view epoch changes, the ownership cache must be invalidated and recomputed lazily or eagerly before serving the next query. Stale ownership data from a previous epoch must never be used to route queries.

R46. Ownership must be deterministic: given the same membership view (same set of live members and same epoch), all nodes must independently compute identical ownership assignments for every partition. No coordination messages are required for ownership agreement.

R47. When a member departs the cluster, ownership of its partitions must not be immediately reassigned. The grace period manager must hold departing members' partitions in an unowned state for the configured grace period duration before triggering rebalance.

R48. After the grace period expires for a departed member, the ownership model must recompute assignments excluding that member. The recomputation must redistribute only the departed member's partitions; partitions owned by still-live members must not move.

R49. A node that rejoins after its grace period has expired must be treated as a new member. Its previous partition assignments must not be restored; it must receive new assignments through normal ownership computation based on the current membership view.

R50. A node that rejoins within its grace period must reclaim its previous partition assignments without triggering a rebalance. The grace period manager must cancel the pending rebalance timer for that node upon its return.

### Grace period management

R51. The grace period manager must track each departed member with a monotonic departure timestamp. The grace period duration must come from the cluster configuration and must apply uniformly to all departures.

R52. The grace period manager must support cancellation: when a departed member returns, its pending grace period timer must be cancelled and the member must be restored to active ownership immediately.

R53. The grace period manager must not use wall-clock time for duration comparisons. It must use a monotonic time source to avoid incorrect expiration due to clock adjustments.

R54. The grace period manager must handle concurrent departures independently. The grace period for one departed member must not affect or delay the grace period for another.

### Clustered engine

R55. The clustered engine must wrap a local engine instance and augment it with cluster-aware behavior. The local engine must remain fully functional for locally-owned partitions regardless of cluster state.

R56. The clustered engine must be constructable via a builder pattern that accepts the local engine, cluster configuration, transport, discovery provider, and membership protocol as mandatory parameters.

R57. The clustered engine must join the cluster during startup by registering with the discovery provider, starting the transport, and initiating the membership protocol. If any of these steps fail, the engine must roll back completed steps (deregister, stop transport) and throw an exception. Partial initialization must not leave the engine in an inconsistent state.

R58. The clustered engine must leave the cluster during shutdown by initiating a graceful protocol leave, deregistering from the discovery provider, and stopping the transport. Shutdown must be idempotent: calling shutdown on an already-shut-down engine must succeed silently.

### Clustered table and scatter-gather queries

R59. The clustered table must act as a partition-aware proxy over the local engine's tables. For each CRUD operation, the clustered table must determine the target partition and route the operation to the owning node.

R60. For operations targeting locally-owned partitions, the clustered table must execute them directly on the local engine without any network round-trip.

R61. For operations targeting remotely-owned partitions, the clustered table must serialize the operation as a message, send it via the transport to the owning node, and deserialize the response. The remote partition client must handle serialization and deserialization.

R62. Queries that span multiple partitions must use scatter-gather execution: the clustered table must send the query to all relevant partition owners in parallel, collect responses, and merge results into a single unified result set.

R63. The scatter-gather implementation must support partition pruning: if the query contains a predicate on the partition key, the clustered table must send the query only to the nodes owning partitions that match the predicate, not to all nodes.

R64. Scatter-gather query results must include metadata indicating whether the result is complete or partial. The metadata must list which partitions were unavailable if the result is partial.

R65. If a partition owner is unreachable during scatter-gather, the clustered table must return a partial result for the reachable partitions rather than failing the entire query. The partial result metadata must indicate which partitions were unavailable.

R66. The scatter-gather timeout for waiting on partition responses must be configurable. If a partition owner does not respond within the timeout, that partition must be treated as unavailable and excluded from the result with appropriate partial result metadata.

R67. The merge phase of scatter-gather must preserve the ordering guarantees of the underlying query. If the query specifies a sort order, the merge must perform an ordered merge across partition results, not a concatenation.

### Remote partition client

R68. The remote partition client must serialize CRUD operations into the message format defined by the transport. Each serialized operation must include sufficient information for the remote node to identify the target table and partition.

R69. The remote partition client must deserialize responses and propagate remote exceptions as local exceptions. A remote node returning an error must result in an exception at the calling node, not a silent failure or corrupted result.

R70. The remote partition client must set a per-request timeout on the transport's request future. If the future does not complete within the timeout, the client must cancel the future and report the partition as unavailable.

### Observability and diagnostics

R71. The membership protocol must expose the current view epoch, the live member count, and the quorum status through a queryable interface. These values must be consistent with the most recently applied view.

R72. Membership listener notifications must include the old view epoch and the new view epoch so that listeners can detect whether they missed intermediate view changes.

R73. Partial result metadata must be inspectable by the caller: it must expose whether the result is complete, the total number of partitions queried, the number of partitions that responded, and the identifiers of unavailable partitions.

### Concurrency and thread safety

R74. The membership view must be safe to read concurrently from multiple threads. Writes to the view (view changes) must be serialized: concurrent view updates must not produce a view with an inconsistent combination of member states and epoch.

R75. The ownership cache must be safe to read concurrently from multiple query threads. Cache invalidation and recomputation on epoch change must not produce races where some queries see stale ownership and others see current ownership during the same epoch.

R76. The grace period manager must be safe to use concurrently from the membership protocol thread (departures) and the ownership computation thread (expiration checks). Concurrent departure registration and expiration must not result in lost departures or double rebalancing.

R77. Scatter-gather query execution must issue partition requests in parallel using the transport's asynchronous request mechanism. The implementation must not issue requests sequentially and wait for each response before sending the next.

### Input validation

R78. All public API methods on the clustered engine and clustered table must validate arguments eagerly. Null arguments must be rejected with a null pointer exception. Invalid partition identifiers must be rejected with an illegal argument exception.

R79. The clustered engine builder must reject null mandatory parameters (local engine, configuration, transport, discovery provider, membership protocol) at build time, not at first use. The exception message must identify which parameter is null.

### Lifecycle and resource management

R80. The clustered engine must implement a closeable or auto-closeable lifecycle. Closing the engine must shut down the cluster (graceful leave, deregister, stop transport) and then close the underlying local engine. Errors during shutdown must be accumulated and thrown after all resources have been released.

R81. The in-JVM transport must be closeable. Closing the transport must reject subsequent send and request calls with an illegal state exception. Messages in flight at close time must have their response futures completed exceptionally.

## Cross-References

- ADR: .decisions/cluster-membership-protocol/adr.md
- ADR: .decisions/partition-to-node-ownership/adr.md
- ADR: .decisions/rebalancing-grace-period-strategy/adr.md
- ADR: .decisions/scatter-gather-query-execution/adr.md
- ADR: .decisions/discovery-spi-design/adr.md
- ADR: .decisions/transport-abstraction-design/adr.md

---

## Design Narrative

### Intent

Add cluster membership and partition-aware query routing to jlsm-engine so that multiple engine instances can cooperate as a peer-to-peer cluster. Tables are partitioned across members, queries scatter-gather across partition owners, and membership changes trigger graceful rebalancing after a configurable grace period. The design is fully leaderless: no single node holds special authority, and ownership is computed deterministically from the membership view.

### Why this approach

**RAPID-style membership over gossip:** Classic gossip protocols (SWIM, Serf) propagate membership changes probabilistically, leading to temporary inconsistency windows where different nodes disagree on membership. RAPID's multi-process cut detection ensures that membership changes are applied atomically once consensus is reached, which is essential for deterministic ownership: all nodes must agree on the membership view to compute identical partition assignments.

**Rendezvous hashing over consistent hashing:** Consistent hashing requires virtual nodes and ring rebalancing to achieve uniform distribution. Rendezvous hashing produces uniform distribution naturally, requires no ring structure, and minimally disrupts assignments when members join or leave (only the affected partitions move). It is also simpler to implement correctly.

**Grace period over immediate rebalance:** Immediate rebalancing on departure causes unnecessary data movement for transient failures (restart, brief network blip). The grace period gives departing nodes time to return and reclaim ownership, reducing churn. After expiration, rebalance affects only the departed node's partitions.

**Phi accrual over fixed-timeout failure detection:** Fixed timeouts require tuning per environment and fail in heterogeneous networks. The phi accrual detector adapts to observed heartbeat patterns per member, producing fewer false positives in networks with variable latency.

**SPI-based transport and discovery:** The library is a composable toolkit, not a monolithic database. Embedding a specific network stack (Netty, gRPC) would impose dependencies consumers may not want. SPI contracts for transport and discovery let consumers plug in their preferred stack. In-JVM implementations are provided for testing.

### What was ruled out

- **Leader election:** Adds complexity and a single point of failure. Deterministic ownership from shared membership view achieves coordination without a leader.
- **Data replication:** Replication is a separate concern (consistency model, replica placement, conflict resolution) that should be layered on top of cluster membership, not bundled with it.
- **Built-in network transport:** Imposes heavyweight dependencies (Netty, gRPC) on all consumers. The SPI approach lets consumers choose.
- **Gossip-based membership (SWIM):** Probabilistic convergence creates windows where nodes disagree on membership, breaking deterministic ownership computation.
- **Consistent hashing ring:** More complex than rendezvous hashing for the same result; requires virtual nodes for uniformity; more disruptive on membership changes.
- **Immediate rebalance on departure:** Causes unnecessary partition movement for transient failures. The grace period is a simple and effective mitigation.

### Out of scope

- Data replication across nodes
- Persistence of cluster state to durable storage
- Built-in network transport implementation
- Partition splitting or merging
- Cross-cluster federation
- Authentication or encryption of cluster messages
- Read replicas or follower nodes
