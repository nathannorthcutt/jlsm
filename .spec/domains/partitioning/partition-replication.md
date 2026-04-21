---
{
  "id": "partitioning.partition-replication",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "partitioning"
  ],
  "requires": [
    "F11",
    "F27"
  ],
  "invalidates": [
    "F27.R28",
    "F27.R29"
  ],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "table-partitioning"
  ],
  "kb_refs": [
    "distributed-systems/consensus/partition-replication-consensus",
    "distributed-systems/data-partitioning/partition-rebalancing-protocols"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F32"
  ]
}
---
# partitioning.partition-replication — Partition Replication

## Requirements

### Raft group identity

R1. Each partition must have exactly one Raft group responsible for replicating its state. The Raft group must be identified by the partition ID from the partition's `PartitionDescriptor` (F11 R1).

R2. The Raft group must consist of a configurable replication factor N (default: 3). The builder must reject values less than 1 with an `IllegalArgumentException`.

R2a. The builder must reject even replication factor values with an `IllegalArgumentException` (an even group size tolerates the same number of failures as the next smaller odd size, wasting a replica).

R3. Each replica in a Raft group must be identified by a `ReplicaId` record containing the partition ID (long) and the node ID (String). The node ID must correspond to a node in the current SWIM membership view.

### Raft state per replica

R4. Each replica must maintain the following persistent state, durable across restarts: `currentTerm` (long, monotonically increasing), `votedFor` (String node ID or null), and the Raft log (stored in the partition's WAL).

R5. Each replica must maintain the following volatile state: `commitIndex` (long, highest log entry known to be committed), `lastApplied` (long, highest log entry applied to the MemTable), and `role` (one of FOLLOWER, CANDIDATE, LEADER).

R6. The leader must additionally maintain per-follower tracking: `nextIndex` (long, index of the next log entry to send to that follower) and `matchIndex` (long, highest log entry known to be replicated on that follower).

R7. `currentTerm` must be persisted to durable storage before any message referencing that term is sent.

R7a. A node that crashes and restarts must recover its `currentTerm` and `votedFor` from durable storage before participating in any Raft protocol exchange.

### WAL as Raft log

R8. The partition's existing WAL must serve as the Raft log. Each WAL record must carry a `term` field (long) in addition to its existing sequence number, which serves as the Raft log index.

R9. On followers, WAL replay must use the same code path as crash recovery. Committed entries replayed from the WAL must be applied to the MemTable identically to local crash recovery.

R10. When a new leader is elected with a log that diverges from a follower's log, the follower must truncate its WAL from the first divergent entry onward and accept the leader's entries from that point. Truncation must use the existing WAL truncation mechanism.

R11. Uncommitted WAL entries (entries beyond the `commitIndex`) must not be applied to the MemTable. On leader change, uncommitted entries may be overwritten by the new leader's log.

### Raft messages

R12. The system must define the following Raft message types in an exported package of `jlsm-table`: `AppendEntries`, `AppendEntriesResponse`, `RequestVote`, `RequestVoteResponse`, and `InstallSnapshot`. The message type hierarchy must be closed (no user-defined subtypes).

R13. Every Raft message must carry the partition ID (long) and the sender's current term (long). Messages must be routed to the correct Raft group by partition ID using the multiplexed transport (F19).

R14. `AppendEntries` must carry: leader ID (String), `prevLogIndex` (long), `prevLogTerm` (long), a list of log entries (each with term, index, key, value, and operation type), and `leaderCommit` (long).

R15. `AppendEntriesResponse` must carry: success (boolean), the responder's `matchIndex` (long, the index of the last entry accepted), and the responder's term (long).

R16. `RequestVote` must carry: candidate ID (String), `lastLogIndex` (long), and `lastLogTerm` (long).

R17. `RequestVoteResponse` must carry: vote granted (boolean) and the voter's term (long).

### Leader election

R18. A follower that does not receive an `AppendEntries` (including heartbeats) from the leader within the election timeout must transition to CANDIDATE, increment its `currentTerm`, vote for itself, and send `RequestVote` to all other replicas in the group.

R19. The election timeout must be randomized uniformly within a configurable range [minElectionTimeout, maxElectionTimeout] to reduce split-vote probability. The default range must be [150ms, 300ms].

R19a. The builder must reject `minElectionTimeout <= 0` or `minElectionTimeout >= maxElectionTimeout` with an `IllegalArgumentException`.

R20. A voter must grant its vote to a candidate if and only if: (a) the candidate's term is greater than or equal to the voter's `currentTerm`, (b) the voter has not already voted for a different candidate in this term, and (c) the candidate's log is at least as up-to-date as the voter's log. Log comparison: the log with the later last entry term is more up-to-date; if terms are equal, the longer log is more up-to-date.

R21. A candidate that receives votes from a majority of the group (including its own vote) must transition to LEADER and immediately send an empty `AppendEntries` heartbeat to all followers to establish authority.

R22. A candidate that receives an `AppendEntries` from a leader with a term greater than or equal to its own must transition to FOLLOWER and accept the leader's authority.

R23. A candidate whose election timer expires without achieving majority must increment its term and start a new election.

### SWIM-triggered election acceleration

R24. When SWIM marks the current Raft leader of a partition group as SUSPECTED or DEAD, all followers in that group must immediately begin their election timeout countdown (resetting any remaining time to a short randomized value within [0, minElectionTimeout]) rather than waiting for the full election timeout to expire.

R25. SWIM failure detection must not bypass the Raft election protocol. A SWIM notification must only accelerate the election timer; the candidate must still collect majority votes through `RequestVote` to become leader.

R26. When a new leader is elected, the leader must announce the leadership change by piggybacking the partition ID and the new leader's node ID on SWIM protocol messages, enabling fast routing-cache updates across the cluster.

### Log replication (steady state)

R27. The leader must append client writes to its local WAL with the current term and the next sequential log index before sending `AppendEntries` to followers.

R28. The leader must send `AppendEntries` messages to all followers containing new log entries. The `prevLogIndex` and `prevLogTerm` fields must correspond to the entry immediately preceding the new entries in the leader's log.

R29. A follower receiving `AppendEntries` must check that its log contains an entry at `prevLogIndex` with term `prevLogTerm`. If the check passes, the follower must append the entries to its WAL and respond with success. If the check fails, the follower must respond with failure.

R30. When a follower responds with failure, the leader must decrement `nextIndex` for that follower and retry with earlier entries. The leader must not decrement `nextIndex` below 1.

R31. When a follower responds with success, the leader must update `matchIndex` for that follower to the index of the last replicated entry.

R32. The leader must advance `commitIndex` to the highest index N such that a majority of replicas (including the leader) have `matchIndex >= N` and the entry at index N has term equal to the current leader's term.

R33. The leader must include the current `leaderCommit` on every `AppendEntries` message (including heartbeats).

R33a. A follower receiving an `AppendEntries` with new entries must advance its `commitIndex` to `min(leaderCommit, index of last new entry)`. A follower receiving a heartbeat (empty entries list) must advance its `commitIndex` to `min(leaderCommit, index of last existing entry in its log)`.

R33b. A follower must apply all log entries with index in the range (`lastApplied`, `commitIndex`] to the MemTable in index order after advancing `commitIndex`.

### Heartbeats

R34. The leader must send heartbeat `AppendEntries` (with an empty entries list) to all followers at a configurable interval. The default heartbeat interval must be 50ms. The builder must reject values less than 1ms or greater than or equal to `minElectionTimeout` with an `IllegalArgumentException`.

R35. A heartbeat must carry the leader's current `leaderCommit` so that followers can advance their applied state even when no new writes are arriving.

### Write quorum

R36. A client write must not be acknowledged to the caller until the leader has received successful `AppendEntriesResponse` from a majority of the group (including the leader's own local WAL write). The majority quorum for a group of size N is floor(N/2) + 1.

R37. If the leader cannot achieve a write quorum within a configurable write timeout (default: 5 seconds), the write must fail with an `IOException` carrying a message that identifies the partition ID, the number of successful acks received, and the quorum required.

R38. The leader must reject writes when it is not in the LEADER role. Writes submitted to a non-leader must fail with a redirect response containing the current leader's node ID (if known) and the partition ID.

### Read semantics

R39. The system must support two read modes per partition group, configurable at the group level: LEADER_ONLY and LEASE_READ.

R40. In LEADER_ONLY mode, the leader must confirm it still holds leadership by receiving heartbeat acknowledgments from a majority before serving a read. This provides linearizable reads.

R41. In LEASE_READ mode, the leader may serve reads locally without a quorum round-trip while its lease is valid. The lease duration must be less than the minimum election timeout (R19). The leader's lease is refreshed each time it receives heartbeat acknowledgments from a majority.

R42. If the leader's lease has expired (time since last majority heartbeat ack exceeds the lease duration), the leader must fall back to LEADER_ONLY mode for subsequent reads until the lease is renewed.

R43. Follower replicas must reject read requests and redirect the client to the current leader (if known). The redirect must carry the leader's node ID and the partition ID.

### Snapshot transfer

R44. When a follower's log is too far behind the leader (the leader no longer has the entries the follower needs because they have been compacted away), the leader must send an `InstallSnapshot` message containing a point-in-time snapshot of the partition's state.

R45. The snapshot must include: all SSTable files for the partition, a snapshot of the current MemTable flushed to an SSTable, the snapshot's last included log index and term, and the current Raft group configuration.

R46. The snapshot must be transferred in chunks over the multiplexed transport. Each chunk must carry: the partition ID, a chunk sequence number, the total expected chunk count, the snapshot's last included index and term, and the chunk data. The chunk size must be configurable (default: 1 MiB).

R47. A follower receiving a complete snapshot must discard its existing WAL entries up to and including the snapshot's last included index.

R47a. The follower must load the snapshot's SSTables into its read path, replacing any SSTables that covered the same key range prior to the snapshot.

R47b. The follower must set `lastApplied` and `commitIndex` to the snapshot's last included index after installing the snapshot.

R47c. The follower must resume normal log replication from the entry immediately following the snapshot's last included index.

R48. Snapshot transfer must use a learner-replica pattern: while the snapshot is being transferred, the follower must not count toward the write quorum and must not vote in leader elections.

R48a. Once the snapshot is fully installed and the follower has caught up to within a configurable lag threshold (default: 100 entries behind the leader's log), the leader must promote the learner to a voting member via a configuration change (R49).

### Raft group membership changes

R49. The system must support adding and removing replicas from a Raft group through a single-server configuration change protocol. Each configuration change adds or removes exactly one replica at a time.

R50. A configuration change must be proposed as a special log entry. The new configuration takes effect as soon as the entry is appended to the log (not when it is committed). This ensures that at most two configurations overlap at any point, preserving majority overlap between old and new configurations.

R51. The leader must not propose a second configuration change while a previous one is uncommitted. If a configuration change entry is pending, subsequent change proposals must be rejected with an `IllegalStateException`.

R52. When a new replica is added, it must first join as a non-voting learner (R48). The leader must promote the learner to a voting member only after the learner's log is within the lag threshold.

R53. When a replica is removed, the leader must stop sending `AppendEntries` to the removed replica after the configuration change entry is committed. If the removed replica is the leader, it must step down after the removal entry is committed.

### Interaction with ownership epoch (F27)

R54. The ownership epoch (F27 R1) must be incremented when the Raft group's configuration changes (replica added, removed, or leader change).

R54a. All replicas in the group must observe the same ownership epoch for a given configuration because configuration changes are committed through the Raft log.

R55. When a partition transitions to DRAINING (F27 R5), the Raft leader for that partition must stop accepting new client writes (consistent with F27 R9).

R55a. During the drain phase, the Raft leader must continue replicating any in-progress log entries until they are committed or the drain timeout (F27 R8) expires.

R56. When a partition transitions to CATCHING_UP on a new node (F27 R12), the new replica must join the existing Raft group as a learner (R52) and receive state via snapshot transfer (R44-R47c) or log replay, rather than performing standalone WAL replay from object storage.

R57. The CATCHING_UP to SERVING transition (F27 R16) must occur only after the new replica has been promoted from learner to voting member and its log is within the lag threshold of the leader's log.

### Interaction with SWIM membership

R58. When SWIM detects a node failure and the failed node hosted replicas in one or more Raft groups, each affected group must initiate the Raft election protocol (R24-R25) if the failed node was the leader, or update its group membership bookkeeping if the failed node was a follower.

R59. A replica must not be permanently removed from its Raft group solely because SWIM marks it as SUSPECTED.

R59a. Permanent removal of a replica from its Raft group must occur only when all three conditions are met: (a) SWIM marks the node as DEAD, (b) a new membership view incorporating the death is published, and (c) the rebalancing policy (F28) determines a replacement replica should be placed.

R60. When SWIM marks a previously-DEAD node as ALIVE (rejoin), and the rebalancing policy assigns partitions back to that node, the node must re-enter any applicable Raft groups as a learner (R52) and catch up via snapshot or log replication.

### Term and epoch consistency

R61. A replica that receives any Raft message with a term greater than its `currentTerm` must immediately update its `currentTerm` to the message's term and clear its `votedFor`.

R61a. Upon discovering a higher term (R61), the replica must transition to FOLLOWER regardless of its current role (CANDIDATE or LEADER).

R62. A replica must reject any Raft message whose term is less than the replica's `currentTerm`. The rejection response must carry the replica's current term so the sender can update.

R63. The Raft term must be independent of the ownership epoch (F27 R1). The term governs leader election within a Raft group; the epoch governs client routing to the correct replica set. Both must be monotonically increasing within their respective scopes.

### Data loss window (supersedes F27 R28-R29)

R64. With replication enabled, the data loss window defined in F27 R28 is eliminated for committed writes. A write acknowledged to the client has been persisted in the WAL of a majority of replicas. Loss of a minority of replicas does not lose committed writes.

R65. Uncommitted writes (writes received by the leader but not yet acknowledged to the client) may be lost if the leader fails before achieving quorum. This is inherent in any majority-quorum protocol and must be documented in the public API.

R66. The public API documentation must state: "With replication factor N, up to floor(N/2) simultaneous replica failures can be tolerated without data loss for committed (acknowledged) writes. Uncommitted writes in transit may be lost on leader failure."

### Configuration

R67. The replication factor (R2) must be configurable via the partition manager builder. The default must be 3.

R68. The election timeout range (R19), heartbeat interval (R34), write timeout (R37), read mode (R39), lease duration (R41), snapshot chunk size (R46), learner lag threshold (R48a, R52), and log compaction threshold (R85) must all be configurable via the partition manager builder.

R69. Each configuration parameter must have a documented default value and must reject invalid values with an `IllegalArgumentException` whose message identifies the parameter and the constraint violated.

### Thread safety

R70. The Raft group state machine (term, role, votedFor, commitIndex, lastApplied) must be safe for concurrent access from the message-processing thread and client request threads.

R70a. `currentTerm` and `role` must be readable without blocking by all request-processing threads.

R71. Log replication and state machine application must not block the heartbeat timer. Heartbeats must be sent on schedule even when large log entries are being replicated.

R72. Each Raft group on a node must operate independently. A slow or blocked Raft group must not delay message processing for other Raft groups hosted on the same node.

### JPMS module boundaries

R73. Raft message types, the `ReplicaId` record, and the read mode enum must reside in an exported package of the `jlsm-table` module.

R74. Internal Raft state machine implementation, log replication logic, and snapshot transfer mechanics must reside in `jlsm.table.internal` and must not be exported.

### Pre-vote protocol

R75. Before starting an election (R18), a candidate must first conduct a pre-vote round: send a `PreVote` request to all other replicas without incrementing its `currentTerm`. The `PreVote` must carry the candidate's current term plus one and its last log index/term.

R76. A replica must grant a pre-vote if and only if: (a) the pre-vote term is greater than the replica's `currentTerm`, and (b) the candidate's log is at least as up-to-date as the replica's log (same comparison as R20(c)). A pre-vote does not change the replica's `currentTerm` or `votedFor`.

R77. A candidate must proceed to a real election (R18) only after receiving pre-vote grants from a majority of the group. If the pre-vote fails to achieve majority, the candidate must return to FOLLOWER without incrementing its term.

### Leader step-down

R78. A leader that has not received `AppendEntriesResponse` from a majority of the group (including itself) within the election timeout period must step down to FOLLOWER. This prevents a network-partitioned leader from indefinitely believing it holds leadership.

### No-op commit on leader election

R79. A newly elected leader must append a no-op entry (an entry with no client data) to its log at the current term and replicate it to achieve a commit. This is necessary to determine the commit status of entries from previous terms, because Raft does not allow a leader to commit entries from prior terms by counting replicas alone (R32).

### Single-node cluster behavior

R80. When the replication factor is 1, the single replica must immediately transition to LEADER on startup and must not run election timers. All writes are committed after the local WAL append (quorum of 1). Heartbeat and replication logic must be inactive.

### Snapshot failure handling

R81. Each snapshot chunk must carry a CRC-32C checksum. The receiver must verify each chunk's checksum upon receipt. A chunk with a checksum mismatch must be rejected, and the receiver must request retransmission of that chunk from the leader.

R82. If a snapshot transfer is interrupted (leader change, network failure, or timeout), the partially received snapshot must be discarded. The new leader (or the same leader after reconnection) must restart the snapshot transfer from the beginning.

R83. If a second `InstallSnapshot` for a newer snapshot (higher last included index) arrives while a previous snapshot transfer is in progress, the receiver must discard the in-progress snapshot and begin receiving the newer one.

### Leader redirect when leader is unknown

R84. When a non-leader replica rejects a client request (R38, R43) and does not know the current leader's identity (e.g., during an election or after a network partition), the rejection must omit the leader node ID field and include the partition ID and the replica's current term, so the client can retry after a backoff.

### Log compaction trigger

R85. When the Raft log for a partition exceeds a configurable size threshold in bytes or entry count (default: 10,000 entries), the system must create a snapshot of the partition's current state and truncate the log up to the snapshot's last included index. The snapshot must be retained for learner catch-up (R44) until all replicas have advanced past the snapshot point.

### JPMS module boundaries (addendum)

R86. The `PreVote` and `PreVoteResponse` message types must reside in the same exported package as the other Raft message types (R73).

---

## Design Narrative

### Intent

Define the per-partition Raft-based replication protocol that maintains consistent replicas of each partition's data across multiple nodes. This spec resolves the deferred decision "partition-replication-protocol" from the table-partitioning ADR. The approach unifies the WAL with the Raft log, integrates with SWIM for failure-accelerated elections, and layers cleanly atop the ownership epoch and partition state machine from F27.

### Why Raft

**Raft over Multi-Paxos:** Raft and Multi-Paxos have identical steady-state performance (one round-trip for committed writes with a stable leader). Raft is simpler to implement and reason about because it restricts leader election to nodes with up-to-date logs, eliminating the recovery complexity of Paxos where any node can become leader regardless of log state. CockroachDB, TiKV, and YugabyteDB all chose Raft for the same reasons.

**Raft over EPaxos:** EPaxos eliminates the leader bottleneck by allowing any replica to propose, which benefits geo-distributed deployments where clients are near different replicas. However, EPaxos is significantly harder to implement correctly (known bugs were found in the original paper's TLA+ spec), and the conflict-resolution protocol adds latency when concurrent writes to the same partition group conflict. For a single-region or nearby-AZ deployment (the primary target), Raft's leader bottleneck is not a practical concern at the partition-group level since each partition group handles only its own key range.

**Raft over ISR (Kafka-style):** ISR requires fewer replicas for the same fault tolerance (f+1 vs 2f+1), but lacks formal consensus. ISR relies on a correct external controller to manage the ISR set, and "unclean leader election" (promoting a replica not in the ISR) risks data loss. Raft's majority quorum provides stronger guarantees without an external controller dependency.

**Raft over leaderless (Dynamo-style):** Leaderless provides eventual consistency only, with no total ordering of writes. An LSM-tree WAL requires total ordering for correct replay, making leaderless replication structurally incompatible with the existing storage model.

### WAL as Raft log

The existing WAL is a natural fit for the Raft log. Each WAL record already has a sequence number that maps to the Raft log index. Adding a term field to the record header is the only structural change. On followers, WAL replay is identical to crash recovery -- the same code path handles both. This means follower state reconstruction requires no new machinery; the MemTable is rebuilt by replaying committed WAL entries, just as it is after a local crash.

Committed entries flush to the MemTable; uncommitted entries (beyond commitIndex) sit in the WAL but are not applied. On leader change, the new leader may overwrite uncommitted entries via the log consistency check (prevLogIndex/prevLogTerm matching). This is the same truncation mechanism used during crash recovery when incomplete records are detected.

### SWIM integration

SWIM provides distributed failure detection with O(log N) dissemination. The integration is narrow and well-bounded:

1. **Election acceleration (R24-R25):** When SWIM marks a leader as suspect/dead, followers fast-track their election timer. This reduces failover latency from the full election timeout (150-300ms) to near-zero, without bypassing the Raft protocol itself -- the candidate still needs majority votes.

2. **Leadership announcement (R26):** New leaders piggyback their identity on SWIM messages, enabling fast routing-cache updates cluster-wide without a separate announcement protocol.

3. **Membership-driven group changes (R58-R60):** SWIM membership changes trigger Raft group reconfiguration, but only through the standard single-server change protocol (R49-R53). SWIM never directly mutates Raft state.

This layering keeps SWIM and Raft concerns cleanly separated: SWIM handles failure detection and dissemination; Raft handles consensus and replication.

### Snapshot transfer and learner replicas

When a new replica joins a group (node replacement, rebalancing), it starts as a non-voting learner and receives state via snapshot transfer. The snapshot includes all SSTables plus a flushed MemTable snapshot. After installation, the learner replays the Raft log from the snapshot point to catch up. Once within the lag threshold, the learner is promoted to a voting member.

This approach is taken directly from CockroachDB and TiKV. The learner phase ensures the new replica does not participate in quorum decisions while it is far behind, preventing a slow joiner from blocking writes.

### Interaction with F27

F27 defined a single-owner model with drain/catch-up phases and an acknowledged data loss window during unclean ownership transfer. With replication, several F27 semantics evolve:

- **Data loss window (F27 R28-R29) is eliminated for committed writes.** A committed write exists on a majority of replicas. Loss of a minority does not lose data. This spec invalidates F27 R28-R29 and replaces them with R64-R66.

- **Drain phase (F27 R5-R10) applies to the Raft group, not a single node.** When a replica is being removed, the Raft group continues serving reads and writes via the remaining replicas. The departing replica simply stops receiving log entries after the configuration change commits.

- **Catch-up phase (F27 R12-R16) becomes Raft learner catch-up.** Instead of standalone WAL replay from object storage, the new replica joins as a learner and receives state from the leader. This is faster (streaming from a live node vs reading from object storage) and requires no special object-storage coordination.

- **Ownership epoch (F27 R1-R4) remains valid.** The epoch is incremented on Raft group configuration changes, ensuring that clients with stale routing caches are rejected and forced to refresh.

### Pre-vote protocol

The spec includes the pre-vote extension from the Raft dissertation (section 9.6). Without pre-vote, a node that is network-partitioned from the leader will repeatedly time out and increment its term. When it rejoins, its high term forces the entire cluster to adopt the higher term, disrupting the current leader and causing an unnecessary election. Pre-vote prevents this: the partitioned node's pre-vote requests will fail (because it cannot reach a majority), so it never increments its real term. This is implemented in etcd, TiKV, and CockroachDB.

### What was ruled out

- **Multi-Raft optimization (batched heartbeats across groups):** A node participating in many Raft groups could batch heartbeats into a single message per peer. This is a performance optimization that does not change correctness and is deferred.

- **Flexible quorums (FlexiRaft):** Decoupling commit quorum from election quorum enables region-local commits. Deferred until multi-region deployment is a target.

- **Lease reads with TLA+ verified protocol (LeaseGuard):** The spec includes basic lease reads (R41-R42). The formal LeaseGuard protocol ("the log is the lease") is more rigorous but more complex. Deferred as an enhancement.

- **Parallel commit (Fast Raft):** Dual-track commit with a fast path at 3N/4 quorum. Deferred as a throughput optimization.

- **Joint consensus for membership changes:** The spec uses single-server changes (R49) rather than Raft's joint consensus, following the simplified approach from the Raft dissertation. Joint consensus is needed only when changing multiple replicas simultaneously, which can be decomposed into sequential single-server changes.
