---
{
  "id": "engine.clustering",
  "version": 7,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "engine"
  ],
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
  "open_obligations": [],
  "_migrated_from": [
    "F04"
  ]
}
---
# engine.clustering — Engine Clustering

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

R10. Message sequence numbers must be monotonically increasing per sender. The `Message.sequenceNumber` field exposes this value so that receivers that care about exactly-once semantics (e.g., future replication protocols) can deduplicate by comparing against the highest previously observed sequence number for the sender. The base membership protocol does not itself implement duplicate-message suppression because view-change application is already idempotent under R91 and R92.

R11. The message payload must accept a zero-length byte array (empty payload). Null payloads must be rejected with a null pointer exception at construction time.

### Member state tracking

R12. Each cluster member must track a state that is one of: alive, suspected, or dead. A member must also track an incarnation number that increases monotonically for a given node identity.

R13. A member in the alive state may transition to suspected. A member in the suspected state may transition to alive (if the suspicion is refuted) or to dead. A member in the dead state must not transition to alive or suspected. A dead node that reconnects must be treated as a new member with a higher incarnation number.

R14. A membership view must track the current set of members and an epoch number. The epoch must increase monotonically on every membership change (join, leave, suspect, or death).

R15. The membership view must report the count of live members (those in the alive state). Suspected and dead members must not be counted as live.

R16. The membership view must determine quorum by comparing the live member count against the configured consensus quorum percent of the total known members (alive plus suspected). Dead members must not factor into quorum calculation.

R17. The membership view must support membership testing: given a node identifier, it must report whether that node is a current member (alive or suspected) of the cluster.

R82. The membership view must not report dead members as current members. Membership testing must distinguish between alive/suspected members (current) and dead members (departed). A dead node must be treated as a non-member for the purposes of join blocking, leave processing, and view change proposals. <!-- covers: F-R1.dispatch_routing.1.4, F-R1.dispatch_routing.1.5, F-R1.dispatch_routing.1.6, F-R1.ss.1.4, F-R1.ss.1.5 -->

### Failure detection

R18. The failure detector must use a phi accrual model: it must maintain a sliding window of inter-heartbeat arrival times per monitored member and compute phi as negative log base 10 of (1 minus the CDF of the current heartbeat delay under a normal distribution fitted to the window).

R19. When the computed phi for a member exceeds the configured phi threshold, the failure detector must mark that member as suspected. The failure detector must not directly mark a member as dead; the membership protocol is responsible for the alive-to-dead lifecycle.

R20. The failure detector must not declare a new member suspected before it has accumulated at least two heartbeats from that member (one initial timestamp plus one inter-arrival interval). Until that threshold is met, `phi(node)` returns 0.0 so the node cannot exceed any positive phi threshold. The protocol period is used by `RapidMembership.protocolTick` to seed an initial heartbeat for a member with no history (so the phi clock begins ticking), rather than being embedded in the detector's internal window as a synthetic first interval.

R21. The failure detector's sliding window must have a bounded maximum size. When the window is full, the oldest sample must be evicted before adding a new one. The maximum window size must be configurable.

R83. The failure detector must evict heartbeat history for members that have been removed from the membership view. Heartbeat records for departed members must not accumulate indefinitely. <!-- covers: F-R1.resource_lifecycle.1.5 -->

R84. The membership protocol must record a heartbeat for newly joined members upon first contact. A member with no heartbeat history must not be immune to failure detection indefinitely. <!-- covers: F-R1.cb.2.2 -->

R85. The failure detector's protocol tick must verify bidirectional reachability by recording heartbeats from probe acknowledgments, not just from incoming pings. Unidirectional probing (send only, no ACK-based heartbeat) can miss asymmetric network failures where the monitored node can receive but not send. <!-- covers: F-R1.cb.2.3 -->

### Discovery SPI

R22. The discovery provider must be a pluggable SPI with three operations: discover seed addresses, register self, and deregister self. All three operations must be defined by an interface contract with no concrete implementation required by the library.

R23. The discover operation must return a collection of seed node addresses. An empty collection must be valid and must mean no seeds are currently known (the node starts as a solo cluster).

R24. The register operation must be idempotent: calling register multiple times with the same node address must produce the same observable state as calling it once.

R25. The deregister operation must be idempotent: calling deregister for a node that is not registered must succeed silently, not throw an exception.

R26. The library must provide an in-JVM discovery provider implementation for testing. This implementation must store registrations in a thread-safe in-memory data structure shared across instances within the same JVM process.

### Transport SPI

R27. The cluster transport must be a pluggable SPI with operations for: sending a one-way message, sending a request and receiving a future response, and registering a handler for incoming messages by message type.

R28. The send operation must be fire-and-forget: it must not block waiting for delivery confirmation. Delivery failures must be silently absorbed by the transport, not propagated as exceptions to the sender. The failure detector is the mechanism for detecting unreachable nodes.

R29. The request operation must return a future that completes with the response message, or completes exceptionally on delivery failure. A per-request timeout must be enforced at the client layer (`RemotePartitionClient.timeoutMs`): if the response does not arrive within the timeout, the client must cancel the future and surface the failure as a partition-unavailable condition. The transport itself does not impose a global request timeout — its only blocking operation is direct-dispatch handler invocation, and request futures otherwise propagate whatever the handler returns.

R30. Handler registration must be keyed by message type. Registering a second handler for the same message type must replace the previous handler, not add a second one. The replacement must be atomic from the perspective of concurrent message delivery.

R31. The library must provide an in-JVM transport implementation for testing. This implementation must deliver messages between transport instances within the same JVM process without network I/O, using thread-safe in-memory queues or direct dispatch.

R32. The in-JVM transport must simulate configurable message delivery delay and message loss rate for fault injection testing. Default values must be zero delay and zero loss.

### Membership protocol

R33. The membership protocol must be a pluggable SPI with operations for: starting the protocol, querying the current membership view, adding a membership listener, and initiating a graceful leave.

R34. The membership protocol must use multi-process cut detection with quorum-based consensus before applying a SUSPECT view change. When a node's local failure detector raises phi above the configured threshold for a peer, the node must start a consensus round by broadcasting a SUSPICION_PROPOSAL (VIEW_CHANGE sub-type 0x05) to the peer's observer set. The view change must be committed only when a quorum of observers (configurable via `consensusQuorumPercent`, default 75%) return agreeing SUSPICION_VOTE (sub-type 0x06) responses within the consensus round timeout. On quorum, the view advances to the next epoch with the peer marked SUSPECTED. Without quorum, the round is abandoned and the peer remains in its current state.

R35. The membership protocol must distribute monitoring responsibility across an expander-graph overlay so per-tick monitoring work is sub-linear in cluster size. Each node must monitor only the peers assigned as its outgoing edges in the overlay (default degree: `ceil(log2(ALIVE cluster size))`, clamped to at most `size - 1`; configurable via `expanderGraphDegree`). The overlay must be deterministic given `(alive members, degree, epoch)` so every node derives the same structure independently. The overlay must be rebuilt on every view change.

R36. When a consensus round is initiated, the proposing node must broadcast SUSPICION_PROPOSAL messages to every observer in the suspected peer's observer set. Observers receiving the proposal must independently evaluate their own phi reading for the suspected peer and respond with a SUSPICION_VOTE — agree if the local phi also exceeds threshold, disagree otherwise. Observers must never vote on their own behalf; a proposal targeting the receiver must trigger self-refutation (R38) rather than a vote.

R37. A consensus round must enforce a bounded timeout, configurable via `consensusRoundTimeout` (default 2 seconds). If quorum is not reached before the timeout fires, the round must be abandoned and the peer must remain in its current state. An abandoned round must not retransmit; the next protocol tick re-evaluates phi and may start a fresh round.

R38. A live member receiving a SUSPICION_PROPOSAL targeting itself must refute by incrementing its incarnation counter and broadcasting an ALIVE_REFUTATION (VIEW_CHANGE sub-type 0x07) to the current view. Observers receiving a refutation must cancel any pending consensus round targeting the refuter. Higher-incarnation ALIVE announcements must supersede lower-incarnation SUSPECT/DEAD records about the same subject.

R39. The membership protocol must notify registered listeners of view changes, member joins, member departures, and member suspicions. Listener notification must be asynchronous and must not block protocol message processing. A slow or failing listener must not delay or prevent protocol progress.

R40. The graceful leave operation must notify the cluster of the departing member before ceasing protocol participation. After initiating leave, the member must not send further protocol messages. Other members must process the leave notification as a confirmed departure, not a suspicion.

R86. The membership protocol's start operation must roll back all completed initialization steps (handler registration, discovery registration, scheduler creation) if any subsequent step throws an exception. After rollback, the protocol must be in the same state as before start was called. <!-- covers: F-R1.resource_lifecycle.1.3 -->

R87. The membership protocol's lifecycle state transitions (not-started to started, started to closed) must be atomic. Concurrent calls to start must not result in duplicate resource allocation, and concurrent calls to close must not result in duplicate cleanup execution. <!-- covers: F-R1.conc.1.1, F-R1.conc.1.3, F-R1.concurrency.3.1, F-R1.concurrency.3.4 -->

R88. The membership protocol must validate the sender of view change proposals against the current membership. Messages from non-members must be rejected. Empty message payloads and unknown sub-type bytes must be rejected with an error response, not silently acknowledged. <!-- covers: F-R1.dispatch_routing.1.7, F-R1.dispatch_routing.1.1, F-R1.dispatch_routing.1.3 -->

R89. The membership protocol must not hold protocol-level locks during network I/O or listener notification callbacks. Lock scopes must be limited to in-memory state mutations. <!-- covers: F-R1.conc.1.4, F-R1.conc.1.5 -->

R90. The membership protocol must reject view change proposals that drop members currently in the alive state from the membership set. Only members in the suspected or dead state may be removed by a view change. <!-- covers: F-R1.ss.1.10 -->

R91. The membership protocol must not increment the epoch when processing a leave notification for a member that is already in the dead state. Duplicate leave notifications must be idempotent with respect to the epoch counter. <!-- covers: F-R1.dispatch_routing.1.5 -->

R92. The membership protocol must reject received views with an epoch less than or equal to the current epoch. Epoch regression must be treated as an error, not silently applied. <!-- covers: F-R1.ss.1.6 -->

### Split-brain detection and handling

R41. The clustered engine must transition to a read-only operational mode when the current membership view does not satisfy the configured quorum threshold. In read-only mode, mutating table operations (`create`, `update`, `delete`, `insert`) must reject writes with `QuorumLostException` (a checked `IOException` subtype), while read operations (`get`, `scan`) remain available. The engine must return to the normal operational mode on the first view change that restores quorum. The current mode must be observable via `ClusteredEngine.operationalMode()`.

R42. While the engine is in read-only mode, it must proactively retry contact with the seeds captured at join time. A `SeedRetryTask` schedules the retry at a configurable interval; each retry re-invokes `membership.start(seeds)` and is a no-op once quorum is restored. The retry task must be idempotent with respect to `start()`/`stop()`, must cancel cleanly on engine close, and must not propagate retry failures that the caller cannot act on (logged and swallowed).

R43. When a view-change proposal with a strictly greater epoch is accepted (subject to R90's no-drop-alive check), the membership protocol must reconcile the new view against the current view on a per-member basis before installation. The reconciliation rules are: higher `incarnation` wins; on equal incarnations, severity `DEAD` > `SUSPECTED` > `ALIVE`; the merged view takes the maximum epoch. Reconciliation is performed by `ViewReconciler.reconcile(localView, proposedView)` as a pure function so the rules are independently testable.

### Partition ownership

R44. The ownership model must assign partitions to nodes using rendezvous (highest random weight) hashing. For each partition, the node with the highest hash score for the (partition, node) pair must be the owner. The hash function must be deterministic and produce consistent results across all nodes given the same inputs.

R45. Ownership assignments must be computed from the current membership view and cached by epoch. When the membership view epoch changes, the ownership cache must be invalidated and recomputed lazily or eagerly before serving the next query. Stale ownership data from a previous epoch must never be used to route queries.

R93. The ownership cache must be bounded in the number of entries per epoch. When the bound is reached, the cache must evict the oldest entries rather than growing without limit. The bound must be configurable. <!-- covers: F-R1.concurrency.4.2 -->

R46. Ownership must be deterministic: given the same membership view (same set of live members and same epoch), all nodes must independently compute identical ownership assignments for every partition. No coordination messages are required for ownership agreement.

R47. When a member departs a view, the clustered engine must hold that member's partitions unowned for the configured `gracePeriod` before redistributing them. `ClusteredEngine.onViewChanged` records each departure via `GracePeriodManager` and schedules deferred work through `GraceGatedRebalancer`; no partition reassignment happens at the moment the view changes. Only on grace expiry does the engine invoke `RendezvousOwnership.differentialAssign(...)` to compute replacement owners.

R48. When grace expires for a departed member, only that member's partitions must be recomputed. `RendezvousOwnership.differentialAssign(oldView, newView, affectedPartitionIds)` mutates cache entries solely for the supplied partition IDs; ownership assignments for partitions owned by still-live members must not move. The affected partition set is supplied by the engine's pre-departure view snapshot.

R49. A node that rejoins after its grace period has expired is treated as a new member: the membership protocol admits it via the normal join path, and HRW assigns partitions across the current live-member set. No special re-admission path is required because the prior ownership state was already collapsed at grace expiry (R47, R48).

R50. When a departed node returns while still in grace, `GraceGatedRebalancer.cancelPending(returning)` must cancel any scheduled differential rebalance for that node and invoke `GracePeriodManager.recordReturn(returning)`. The returning node reclaims its previous assignments (which were never reassigned) without any partition movement. Cancellation must be idempotent and safe against races with a concurrently-firing grace-expiry check.

### Grace period management

R51. The grace period manager must track each departed member with a monotonic departure timestamp. The grace period duration must come from the cluster configuration and must apply uniformly to all departures.

R52. The grace period manager must support cancellation: when a departed member returns, its pending grace period timer must be cancelled and the member must be restored to active ownership immediately.

R53. The grace period manager must not use wall-clock time for duration comparisons. It must use a monotonic time source to avoid incorrect expiration due to clock adjustments.

R54. The grace period manager must handle concurrent departures independently. The grace period for one departed member must not affect or delay the grace period for another.

R94. The grace period manager must remove expired departure records during expiration checks. Departure records that have been expired and processed must not remain in the manager's internal state indefinitely. <!-- covers: F-R1.cb.1.9, F-R1.resource_lifecycle.2.4 -->

R95. The grace period manager must accept an injectable time source and must not call wall-clock methods directly. All duration comparisons within a single operation must use a single timestamp capture to prevent boundary inconsistencies between related checks. <!-- covers: F-R1.shared_state.4.2, F-R1.shared_state.4.3, F-R1.concurrency.4.3 -->

R96. When a member that has already departed departs again (e.g., due to membership flapping), the grace period manager must retain the original departure timestamp. The grace period must be anchored to the first departure, not the most recent one. <!-- covers: F-R1.shared_state.4.5 -->

### Clustered engine

R55. The clustered engine must wrap a local engine instance and augment it with cluster-aware behavior. The local engine must remain fully functional for locally-owned partitions regardless of cluster state.

R56. The clustered engine must be constructable via a builder pattern that accepts the local engine, cluster configuration, transport, discovery provider, and membership protocol as mandatory parameters.

R57. The clustered engine must join the cluster during startup by registering with the discovery provider, starting the transport, and initiating the membership protocol. If any of these steps fail, the engine must roll back completed steps (deregister, stop transport) and throw an exception. Partial initialization must not leave the engine in an inconsistent state.

R58. The clustered engine must leave the cluster during shutdown by initiating a graceful protocol leave, deregistering from the discovery provider, and stopping the transport. Shutdown must be idempotent: calling shutdown on an already-shut-down engine must succeed silently.

R97. The membership protocol's close operation must deregister the node from the discovery provider and deregister all transport message handlers before releasing other resources. Failure to deregister must not prevent the remaining shutdown sequence from executing. <!-- covers: F-R1.resource_lifecycle.1.1, F-R1.resource_lifecycle.1.2 -->

R98. The clustered engine's table creation must detect and close a previously existing table proxy with the same name before registering the new proxy. If table creation fails after the local table is created, the local table must be rolled back (dropped). <!-- covers: F-R1.cb.1.6, F-R1.resource_lifecycle.2.6 -->

R102. The clustered engine constructor must assign every final field before registering any membership listener or transport message handler. Listener and handler registration must be the last observable actions of construction so that any callback triggered during or after registration observes all engine fields as fully initialized. <!-- covers: F-R1.concurrency.1.2, F-R1.concurrency.1.3 -->

R103. The clustered engine constructor must unwind every successfully completed registration step if any subsequent construction step throws. In particular, a failure to register the transport message handler must cause the previously registered membership listener to be removed before the original exception propagates to the caller. <!-- covers: F-R1.resource_lifecycle.1.1 -->

R104. When a clustered engine join operation rolls back after a failure of a prior step, exceptions thrown by rollback actions (including checked exceptions from discovery deregistration) must be attached to the original failure via suppressed exceptions rather than replacing it. The caller must always observe the original cause as the primary exception. <!-- covers: F-R1.resource_lifecycle.1.2 -->

R105. Membership listener callbacks delivered to the clustered engine after close has begun must be observable as no-ops. A callback arriving concurrently with or after close must not mutate ownership state, grace-period state, or any other engine-owned shared state. <!-- covers: F-R1.shared_state.1.1 -->

### Clustered table and scatter-gather queries

R59. The clustered table must act as a partition-aware proxy over the local engine's tables. For each CRUD operation, the clustered table must determine the target partition and route the operation to the owning node.

R99. The clustered table must share the same ownership instance as the clustered engine rather than creating an independent instance. Ownership cache state must be consistent across all tables and the engine within a single cluster node. <!-- covers: F-R1.cb.1.1, F-R1.shared_state.3.2, F-R1.resource_lifecycle.2.7 -->

R60. For operations targeting locally-owned partitions, the clustered table must execute them directly on the local engine without any network round-trip.

R61. For operations targeting remotely-owned partitions, the clustered table must serialize the operation as a message, send it via the transport to the owning node, and deserialize the response. The remote partition client must handle serialization and deserialization.

R62. Queries that span multiple partitions must use scatter-gather execution: the clustered table must send the query to all relevant partition owners in parallel, collect responses, and merge results into a single unified result set.

R100. The scan operation on a clustered table must close remote partition client instances after each partition's results have been collected, even on the normal (non-exception) path. <!-- covers: F-R1.resource_lifecycle.2.2 -->

R63. `ClusteredTable.scan(fromKey, toKey)` must fan out only to owners of partitions whose key range intersects `[fromKey, toKey)`. Partition-to-key mapping is supplied by a configured `PartitionKeySpace` (public SPI); the table delegates owner resolution to `RendezvousOwnership.ownersForKeyRange(tableName, fromKey, toKey, view, keyspace)`. The default `SinglePartitionKeySpace` yields no pruning (backward-compat — all live members are still contacted); `LexicographicPartitionKeySpace(splitKeys, partitionIds)` narrows fanout to the partitions whose lexicographic range overlaps the query range. When the intersecting owner set is empty, the scan returns an empty iterator with complete `PartialResultMetadata(0, 0, ∅, true)`.

R64. Scatter-gather query results must include metadata indicating whether the result is complete or partial. The metadata must list which partitions were unavailable if the result is partial.

R65. If a partition owner is unreachable during scatter-gather, the clustered table must return a partial result for the reachable partitions rather than failing the entire query. The partial result metadata must indicate which partitions were unavailable.

R66. The scatter-gather timeout for waiting on partition responses must be configurable. If a partition owner does not respond within the timeout, that partition must be treated as unavailable and excluded from the result with appropriate partial result metadata.

R67. The merge phase of scatter-gather must preserve the ordering guarantees of the underlying query. If the query specifies a sort order, the merge must perform an ordered merge across partition results, not a concatenation.

R106. The clustered table's scan operation must track in-flight scatter fanout tasks and cancel each tracked task on close. A close that fires while scatter requests are outstanding must not leak scheduling work on the shared scatter executor. <!-- covers: F-R1.resource_lifecycle.2.2 -->

R107. When a scatter fanout request is cancelled (for example, because the clustered table is closing), the scatter executor thread servicing that request must be released from any synchronous transport call it is blocked on. The cancellation mechanism must not allow a single blocked request to pin the servicing thread past the fanout's abandonment. <!-- covers: F-R1.shared_state.2.3 -->

R108. The clustered table's scatter scan must report every failure that occurs while releasing a per-partition remote client, including failures observed only after the partition's results have been collected. Close-path failures must be surfaced through a diagnostic channel and must never be silently discarded by assertion checks that are disabled in production. <!-- covers: F-R1.concurrency.1.4 -->

R113. The ordered merge phase of scatter-gather must fail with an explicit runtime error if any per-partition iterator yields a malformed element (for example, an entry whose key is null). The merge must not propagate NullPointerException or AssertionError through the priority-queue comparator. <!-- covers: F-R1.data_transformation.1.5 -->

### Remote partition client

R68. The remote partition client must serialize CRUD operations into the message format defined by the transport. Each serialized operation must include sufficient information for the remote node to identify the target table and partition.

R69. The remote partition client must deserialize responses and propagate remote exceptions as local exceptions. A remote node returning an error must result in an exception at the calling node, not a silent failure or corrupted result.

R70. The remote partition client must set a per-request timeout on the transport's request future. If the future does not complete within the timeout, the client must cancel the future and report the partition as unavailable.

R101. The remote partition client must serialize the full document content and operation mode for create, update, and delete operations. The client must deserialize non-empty response payloads for get and range scan operations. A stub implementation that discards payloads or returns empty results violates the partition-aware proxy contract. <!-- covers: F-R1.cb.1.2, F-R1.cb.1.3, F-R1.cb.1.4, F-R1.cb.1.5 -->

R109. The remote partition client must validate, with a runtime check, that the transport's request future is non-null before awaiting it. A null future must be reported to the caller as an I/O failure; it must not rely on assertions that are disabled in production to surface. <!-- covers: F-R1.concurrency.1.9 -->

R110. When a remote partition client's per-request timeout fires, the client must cancel the originating transport future so that the transport can release any per-request server-side state. Simply completing a downstream future is insufficient — the cancellation signal must reach the source future that the transport is observing. <!-- covers: F-R1.concurrency.1.10 -->

R111. The remote partition client must distinguish local-origin failures (for example, a failure to encode a request payload) from remote-node failures when completing per-partition futures. A locally thrown encoding exception must not be reported to the caller as a node-unavailability condition. <!-- covers: F-R1.data_transformation.1.7 -->

R112. The query request handler's response encoder must fail with a semantic I/O error when the cumulative size of the response payload would overflow the wire-format size field. Silent integer wrap that yields a negatively-sized or truncated payload is not acceptable. <!-- covers: F-R1.data_transformation.1.2 -->

R114. Decoding of a range-scan response must distinguish a legitimately empty range from a malformed response that carries a populated payload but no schema. A malformed response must fail explicitly; it must not silently degrade to an empty result iterator. <!-- covers: F-R1.data_transformation.1.6 -->

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

---

## Verification Notes

### Verified: v3 — 2026-04-18

Full verification pass covering 101 requirements (R1–R101). Overall: PASS_WITH_NOTES. Detailed per-requirement verdict table is captured in `F04-verification-diff.md` alongside the list of files touched and the deferred-obligation roadmap.

**Overall: PASS_WITH_NOTES**

- Amendments applied: R10, R20, R29, R34, R35, R36, R37, R38, R41, R42, R43, R47, R48, R50, R63 (14 requirements rewritten to reflect shipped behavior).
- Code fixes applied: R16, R17, R28, R32, R64, R70, R73, R78, R81, R82, R83, R93 (+ flow-on call-site updates in `RapidMembership.handleViewChangeProposal`).
- Regression / R73 / R32 / R82 / R93 tests added across `MembershipViewTest`, `PartialResultMetadataTest`, `ClusteredEngineTest`, `InJvmTransportTest`, `ConcurrencyAdversarialTest`, `ResourceLifecycleAdversarialTest`, `SharedStateAdversarialTest`.
- Obligations deferred: 14 (see `OBL-F04-R34-38-consensus`, `OBL-F04-R35-expander-graph`, `OBL-F04-R41-43-split-brain`, `OBL-F04-R47-50-grace-gated-rebalance`, `OBL-F04-R63-partition-pruning`, `OBL-F04-R56-57-79-engine-join`, `OBL-F04-R60-local-short-circuit`, `OBL-F04-R68-payload-table-id`, `OBL-F04-R77-parallel-scatter`, `OBL-F04-R53-monotonic-clock`, `OBL-F04-R39-async-listeners`, `OBL-F04-R10-duplicate-dedup`, `OBL-F04-R20-phi-init`, `OBL-F04-R29-transport-timeout` in `_obligations.json`).
- `./gradlew spotlessApply check` passes cleanly at 475 cluster-subsystem tests.

State remains **DRAFT** (not APPROVED) because 6 open obligations represent genuine code bugs that the user chose to document rather than repair in-session (R39, R53, R56, R57, R60, R68, R77, R79). Promoting to APPROVED must wait until those obligations are resolved or explicitly accepted as permanent deviations.

#### Amendments (v2 → v3)

See `F04-verification-diff.md` §2 for the full list with obligation mapping. Summary of substantive rewrites:

- **R10:** "receiver must be able to detect duplicates" → capability (via `Message.sequenceNumber`), not a protocol-level enforcement.
- **R20:** "initialize with protocolPeriod" → "phi floor of >=2 heartbeats; membership protocol seeds first heartbeat at phi==0".
- **R29:** "timeout enforced by transport" → "timeout enforced at client layer (`RemotePartitionClient.timeoutMs`)".
- **R34–R38:** full RAPID consensus / expander-graph monitoring / observer-broadcast suspicion / consensus-round timeout / self-refutation → Rapid-inspired unilateral protocol with epoch-ordered view acceptance.
- **R41–R43:** quorum-loss read-only mode / reconnect-and-merge / view-state reconciliation → no-op on quorum loss; epoch-ordered replacement; deferred.
- **R47–R50:** grace-period-gated rebalance → immediate rebalance on view change; `GracePeriodManager` tracks but does not gate.
- **R63:** partition pruning on predicate → full fanout to every live member.

#### Amendments (v3 → v4)

Phase 1 of the RAPID consensus work (feature `f04-obligation-resolution--wd-04`) delivered full multi-process cut detection, expander-graph monitoring, observer-quorum rounds, and self-refutation. Requirements R34–R38 were rewritten forward to describe the shipped behaviour; the previous AMENDED text has been replaced and the two obligations that tracked the deferral (`OBL-F04-R34-38-consensus`, `OBL-F04-R35-expander-graph`) were flipped to `resolved` in `.spec/registry/_obligations.json`.

- **R34:** unilateral suspicion → multi-process cut detection with observer-quorum consensus (SUSPICION_PROPOSAL → SUSPICION_VOTE → view change on quorum; default 75%).
- **R35:** ping every ALIVE member → per-node monitoring restricted to expander-graph outgoing neighbours (default degree `ceil(log2(N))`, clamped to `size - 1`, deterministic per (members, degree, epoch)).
- **R36:** immediate unilateral SUSPECT → proposer broadcasts SUSPICION_PROPOSAL to the observer set; observers independently vote; proposals targeting self trigger refutation, not a vote.
- **R37:** no consensus-round timeout → bounded timeout via `consensusRoundTimeout` (default 2 seconds); expired rounds are abandoned silently.
- **R38:** self-refutation deferred → self-refutation implemented as incarnation bump + ALIVE_REFUTATION broadcast; observers cancel matching rounds; higher-incarnation ALIVE supersedes lower-incarnation SUSPECT/DEAD.
- **ClusterConfig extensions:** four new parameters (`consensusRoundTimeout`, `expanderGraphDegree`, `cutDetectorLowWatermark`, `cutDetectorHighWatermark`) exposed via the builder with defaults and full constructor validation.

#### Amendments (v4 → v5)

Adversarial audit against WD-03 (feature `f04-obligation-resolution--wd-03`) surfaced 13 regression-prevention contracts that the v4 spec did not explicitly require. These were added as R102–R114, each tagged to the audit finding that motivated it. No previous requirement was rewritten; the additions strengthen existing requirements (R68, R70, R77, R86, R97, R100, R101) by codifying previously implicit invariants.

- **R102–R105:** clustered engine construction and close-path hygiene — safe publication of final fields before listener/handler registration; rollback on partial construction failure; suppressed-exception accumulation in join rollback; listener callbacks after close must be no-ops.
- **R106–R108, R113:** scatter-gather fanout robustness — track and cancel in-flight scatter tasks on close; unblock cancelled scatter threads from synchronous transport calls; surface per-partition client-close failures through a diagnostic channel (not assertions); fail explicitly on malformed per-partition iterator elements in the merge comparator.
- **R109–R112, R114:** remote partition client robustness — runtime-enforce non-null transport futures; cancel the source transport future on timeout (not a wrapper); attribute local-origin encoding failures distinctly from remote-node failures; use checked arithmetic in the response encoder's size accumulation; reject malformed range-scan responses instead of silently degrading to an empty iterator.

#### Amendments (v5 → v6)

Phase 2 obligation resolution (feature `f04-obligation-resolution--wd-05`) closed the final three deferred obligations: `OBL-F04-R41-43-split-brain`, `OBL-F04-R47-50-grace-gated-rebalance`, and `OBL-F04-R63-partition-pruning`. Requirements R41–R43, R47–R50, and R63 were rewritten forward to describe the shipped behaviour; previous AMENDED text has been replaced and the obligations were flipped to `resolved` in `.spec/registry/_obligations.json`. `open_obligations` in the F04 front matter is now empty.

- **R41:** no quorum-gated mode → `ClusterOperationalMode` (NORMAL / READ_ONLY); reads continue, mutations throw `QuorumLostException` while quorum is lost; transitions are observable via `ClusteredEngine.operationalMode()`.
- **R42:** no recovery action → `SeedRetryTask` reinvokes `membership.start(seeds)` on a configurable interval while quorum is lost; idempotent start/stop; retained seed list captured at join.
- **R43:** straight-replacement view installation → `ViewReconciler.reconcile(local, proposed)` applies higher-incarnation-wins with severity `DEAD > SUSPECTED > ALIVE` on ties; called from `RapidMembership.handleViewChangeProposal` before the new view is installed.
- **R47–R50:** immediate rebalance on view change → `GraceGatedRebalancer` schedules deferred differential rebalance at grace expiry; `RendezvousOwnership.differentialAssign` touches only the departed member's partitions; `cancelPending(NodeAddress)` aborts a pending rebalance when the node returns within grace.
- **R63:** full fanout to every live member → `PartitionKeySpace` SPI (`SinglePartitionKeySpace` fallback + `LexicographicPartitionKeySpace` range-based) backing `RendezvousOwnership.ownersForKeyRange`; `ClusteredTable.scan(fromKey, toKey)` contacts only owners of range-overlapping partitions, preserving R60/R67/R77/R100/R64 semantics.

Module surface: `RendezvousOwnership` is now non-`final` to permit in-tree test spying (`GraceGatedRebalancerTest`); behaviour is unchanged. `ClusteredTable` gained an 8-arg canonical constructor accepting `(TableMetadata, ClusterTransport, MembershipProtocol, NodeAddress, RendezvousOwnership, Engine, PartitionKeySpace, Supplier<ClusterOperationalMode>)`; legacy constructors delegate to it with `SinglePartitionKeySpace("default")` and a `() -> NORMAL` mode supplier.

### Verified: v7 — 2026-04-21 (state: APPROVED)

Coverage promotion work (WD `close-coverage-gaps / WD-01`). All 114 requirements (R1–R114) now
have direct `@spec` annotations at both impl and test sites; `spec-trace.sh engine.clustering`
reports `Annotations: 343 | Requirements traced: 114` and no "No test annotations" list. No
requirement was rewritten; all deltas are annotation additions only.

**Overall: PASS** — all requirements traced with implementation + test evidence.

Coverage strategy:

- **R1** — `NodeAddress` record + `NodeAddressTest` (validation of nodeId/host/port)
- **R2–R7** — `ClusterConfig` builder + `ClusterConfigTest` (defaults and parameter validation)
- **R8–R11** — `Message` record + `MessageTest`; `MessageType` enum + `MessageTypeTest` (R9)
- **R12–R17, R82** — `Member` / `MemberState` / `MembershipView` + corresponding tests
- **R18–R21, R83** — `PhiAccrualFailureDetector` + `PhiAccrualFailureDetectorTest`
- **R22–R26** — `DiscoveryProvider` SPI + `InJvmDiscoveryProvider` + `InJvmDiscoveryProviderTest`
- **R27–R32, R81** — `ClusterTransport` SPI + `InJvmTransport` + `InJvmTransportTest`
- **R33–R40, R71–R72, R84–R92, R97** — `MembershipProtocol` / `RapidMembership` + `RapidMembershipTest`, `RapidMembershipAsyncListenerTest`, `RapidMembershipReconciliationTest`, `ConsensusCoordinatorTest`, `ExpanderGraphOverlayTest`, `DispatchRoutingAdversarialTest`, `ContractBoundariesAdversarialTest`
- **R41–R43** — `ClusterOperationalMode`, `QuorumLostException`, `SeedRetryTask`, `ViewReconciler` + tests
- **R44–R50, R93–R96** — `RendezvousOwnership`, `GracePeriodManager`, `GraceGatedRebalancer` + their tests
- **R51–R54, R76, R94–R96** — `GracePeriodManagerTest` (covers all grace-lifecycle requirements)
- **R55–R58, R78–R80, R97–R98, R102–R105** — `ClusteredEngine` + `ClusteredEngineJoinTest`, `ClusteredEngineTest`, `EngineClusteringAdversarialTest`, `ResourceLifecycleAdversarialTest`
- **R59–R67, R73, R77, R99–R100, R106–R108, R113** — `ClusteredTable` + `ClusteredTableTest`, `ClusteredTableLocalShortCircuitTest`, `ClusteredTableScanParallelTest`, `ClusteredTableScanPruningTest`, `ClusteredTableReadOnlyTest`, `DataTransformationAdversarialTest`, `ResourceLifecycleAdversarialTest`
- **R64, R73** — `PartialResultMetadata` + `PartialResultMetadataTest`
- **R68–R70, R101, R109–R112, R114** — `RemotePartitionClient` + `RemotePartitionClientTest`, `RemotePartitionClientAsyncTest`, `DataTransformationAdversarialTest`
- **R74–R76** — `ConcurrencyAdversarialTest`, `SharedStateAdversarialTest` (concurrency / shared state)
- **R105** — `SharedStateAdversarialTest` (listener callbacks after close are no-ops)

No obligations opened. No amendments to requirement text. Promotion is coverage-only: adding
direct annotation chain at the enforcement sites for requirements whose behaviour was already
enforced by shipped code and validated by existing tests. `./gradlew :modules:jlsm-engine:test`
passes green at WD end.
