---
{
  "id": "F37",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": ["engine"],
  "requires": ["F05", "F19", "F32", "F36"],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": ["table-catalog-persistence"],
  "kb_refs": [
    "systems/database-engines/catalog-persistence-patterns",
    "distributed-systems/replication/catalog-replication-strategies"
  ],
  "open_obligations": []
}
---

# F37 — Catalog Operations

## Requirements

### Atomic multi-table DDL

R1. The engine must expose an `atomicDdl()` method that returns a `DdlBatch` builder.

R1a. The `DdlBatch` must accept an ordered sequence of DDL operations (create table, alter table, drop table).

R2. The `DdlBatch` must support `createTable(String name, Schema schema)`, `dropTable(String name)`, and `alterTable(String name, SchemaUpdate update)` as chained builder methods. Each method must return the `DdlBatch` for fluent composition.

R3. The `DdlBatch` must expose an `execute()` method that applies all queued operations atomically. Either all operations succeed and the catalog reflects every change, or none succeed and the catalog remains unchanged.

R4. If any individual operation within the batch fails validation (null name, duplicate create, drop of nonexistent table), the entire batch must be rejected before any mutation occurs. The exception must identify the failing operation's index (zero-based) and the reason.

R5. If a system failure (I/O error) occurs during batch execution after partial application, the engine must roll back all applied operations. The rollback must restore the catalog to its pre-batch state.

R5a. If rollback itself fails (I/O error during undo), the engine must mark the catalog as requiring recovery and throw an `IOException` whose message includes the batch ID and the number of operations successfully rolled back. On next startup, the recovery protocol (R9) must resolve the inconsistency.

R6. The engine must persist a batch intent record before applying any mutation. On recovery, an incomplete batch intent must trigger automatic rollback of any partially applied operations.

R6a. If the batch intent record is corrupt on recovery (CRC mismatch, truncated record), the engine must log a warning identifying the batch ID (if recoverable) and skip the record. The catalog state must be validated against the most recent known-good state.

R7. The batch intent record must include a monotonically increasing batch ID (long).

R7a. The batch intent record must include the number of operations and each operation's type and parameters.

R7b. The batch intent record must include a CRC32C checksum covering the entire record.

R8. After successful batch execution, the engine must persist a batch completion record to the catalog WAL. On recovery, a batch with both intent and completion records must be treated as fully applied.

R9. On recovery, a batch with an intent record but no completion record must be rolled back. The rollback must use the intent record's operation list to undo any partially applied changes (drop any tables that were created, recreate any tables that were dropped, revert any schema alterations).

R10. The `DdlBatch` must reject an empty batch (no operations queued) with an `IllegalStateException` when `execute()` is called.

R11. The `DdlBatch` must reject a batch where the same table name appears as the target of two operations of the same type (e.g., two creates of "A" or two drops of "A") with an `IllegalArgumentException` identifying the conflicting name. A batch that drops table "A" and then creates table "A" (in that order) is permitted and must execute the drop before the create.

R12. Concurrent `execute()` calls on different `DdlBatch` instances must be serialized. The engine must hold an exclusive DDL lock during batch execution to prevent interleaving of atomic batches.

R13. The DDL lock must have a configurable acquisition timeout (default: 10 seconds). If the lock cannot be acquired within the timeout, `execute()` must throw an `IOException` with a message indicating DDL contention.

R14. The `atomicDdl()` method must reject calls when the engine is closed (F05 R8) with an `IllegalStateException`.

### Catalog Raft group

R15. In clustered mode, the catalog must be replicated through a dedicated Raft group (the "catalog group") separate from data partition Raft groups. The catalog group must use the same Raft implementation as partition replication (F32).

R16. The catalog group must consist of a configurable number of replicas (default: 3, minimum: 1, must be odd). The builder must reject even values or values less than 1 with an `IllegalArgumentException`.

R17. The catalog Raft group must replicate all catalog mutations (table create, drop, alter, batch DDL, partition map updates) through the Raft consensus log.

R17a. A catalog mutation must not be considered committed until a majority of catalog replicas have acknowledged it.

R18. The catalog group leader must be the sole node authorized to accept DDL operations. DDL requests received by a catalog follower must be rejected with a redirect response containing the current leader's node ID.

R19. The catalog group must maintain a monotonically increasing catalog epoch (long). The epoch must be incremented on every committed catalog mutation (table create, drop, alter, partition map change).

R20. The catalog epoch must be persisted as part of the Raft log entry for each catalog mutation.

R20a. A node recovering from a crash must be able to reconstruct the current catalog epoch by replaying the catalog Raft log.

R20b. The catalog epoch must not wrap. If the epoch reaches `Long.MAX_VALUE`, the catalog leader must reject further mutations with an `IOException` indicating epoch exhaustion. This condition requires manual intervention (cluster reinitialization).

### Catalog Raft group bootstrap

R70. On initial cluster formation, the engine builder must accept a list of initial catalog group member node IDs. The first node in the list must bootstrap the catalog Raft group as a single-member group and then add the remaining members one at a time via the Raft configuration change protocol (F32 R49-R51).

R71. When the replication factor is 1 (single-node mode), the single catalog replica must immediately transition to LEADER on startup without running election timers (consistent with F32 R80). All catalog mutations are committed after the local WAL append (quorum of 1). Epoch dissemination via SWIM (R31a) is inactive.

R72. If the catalog Raft group has no leader (e.g., during an election or when a majority of catalog replicas are unreachable), DDL operations must fail with an `IOException` indicating that the catalog is unavailable. The exception must include a retry-after hint based on the election timeout range (F32 R19).

### Catalog Raft group SWIM interaction

R73. When SWIM marks a catalog group member as DEAD and the rebalancing policy determines a replacement, the catalog leader must remove the dead member and add the replacement via sequential Raft configuration changes (F32 R49-R53).

R74. When SWIM marks a catalog group member as SUSPECTED, the catalog group must not remove the member. The Raft protocol's internal leader election handles temporary unavailability.

### Catalog leader operations

R21. The catalog leader must serialize all DDL operations through the Raft log, including atomic multi-table DDL batches (R1-R14). A batch must be committed as a single Raft log entry containing the complete batch intent.

R22. The catalog leader must validate all DDL preconditions (table existence, name uniqueness, schema validity) before proposing the operation to the Raft log. A validation failure must be reported to the caller without proposing.

R23. The catalog leader must assign the new catalog epoch before proposing the mutation. The epoch must be included in the Raft log entry so that all replicas apply the same epoch for the same mutation.

R24. After a DDL Raft entry is committed (majority acknowledged), the catalog leader must apply the mutation to its local catalog state and return success to the caller.

R25. If the catalog leader loses leadership during a pending DDL operation (term change detected), the operation must fail with an `IOException` indicating leadership loss. The caller must retry against the new leader.

R25a. A DDL operation submitted to the catalog leader must have a configurable commit timeout (default: 30 seconds). If the Raft commit (majority acknowledgment) does not complete within this timeout, the operation must fail with an `IOException` identifying the timeout. The caller must not assume the operation failed -- it may have been committed but the response was lost.

### Follower catalog application

R26. Catalog followers must apply committed DDL Raft entries to their local catalog state in log order. The local catalog must reflect the same table set and schemas as the leader after applying the same log prefix.

R27. A follower that falls behind the leader's log must catch up via Raft log replay or snapshot transfer (F32 R44-R47c).

R27a. The catalog snapshot must include the complete catalog state: all table metadata, the partition map, the current catalog epoch, and any in-progress phased DDL state (R40-R48).

R28. After applying a catalog mutation, each follower must update its local catalog epoch to match the epoch in the Raft entry.

### Epoch-based catalog cache

R29. Every node in the cluster must maintain a local catalog cache containing the full catalog state (table metadata, partition map). The cache must store the catalog epoch corresponding to its current state.

R30. On each operation that requires catalog metadata (query routing, DDL, partition lookup), the node must compare its local cache epoch against the highest catalog epoch it has observed (via Raft heartbeats per R31 or SWIM messages per R31a). If the local epoch is lower than the observed epoch, the node must refresh its cache before proceeding.

R31. The catalog leader must piggyback the current catalog epoch on Raft `AppendEntries` heartbeats sent to catalog followers.

R31a. Catalog group members must piggyback the current catalog epoch on SWIM protocol messages sent to all cluster nodes.

R32. A node that discovers its catalog epoch is stale must pull the updated catalog state from any catalog group member. The pull request must include the node's current epoch so the responder can send only the delta if possible.

R33. The delta response must contain all catalog mutations between the requester's epoch and the current epoch, encoded as a sequence of DDL operations. If the delta is too large (more than a configurable threshold, default: 1000 mutations), the responder must send a full catalog snapshot instead.

R34. A catalog cache refresh must not block in-flight read operations that were initiated at the previous epoch. Only new operations initiated after the refresh completes must observe the updated catalog.

R35. A node whose catalog cache epoch is more than a configurable maximum staleness (default: 2 epochs behind the highest observed cluster epoch) must reject new client requests for tables whose metadata may have changed. The rejection must return a STALE_CATALOG error to the client with the current leader's node ID (if known) for redirect. In-flight operations initiated before the staleness was detected may complete at the previous epoch.

### Schema change propagation

R36. When the catalog leader commits a DDL operation, the new catalog epoch must be disseminated to all cluster nodes within a bounded time. The dissemination must use the SWIM protocol's epidemic broadcast (piggybacking on membership messages) to reach nodes that are not in the catalog Raft group.

R37. The catalog leader must send a catalog epoch notification to all catalog followers via the next Raft heartbeat after the DDL commit. Followers must propagate the epoch to non-catalog nodes via SWIM piggybacking within the next SWIM protocol round.

R38. A node that receives a catalog epoch notification with an epoch higher than its local cache must initiate an asynchronous cache refresh (R32). The refresh must not block the SWIM message processing path.

R39. DDL operations that change table schemas (create, alter, drop) must include the affected table names in the catalog Raft log entry.

R39a. Followers and cache-refreshing nodes must be able to determine which tables were affected by a catalog mutation without downloading the full catalog.

### F1-style phased DDL for schema alterations

R40. Schema alterations (add field, drop field, change field type) must follow a phased protocol ensuring at most two schema versions coexist in the cluster at any time. The phases are: DELETE_ONLY, WRITE_ONLY, and PUBLIC.

R41. When a schema alteration is submitted, the catalog leader must commit an initial Raft entry transitioning the affected element to DELETE_ONLY state. In DELETE_ONLY state, the new element accepts only delete operations (preventing orphaned data from nodes still on the old schema).

R42. The catalog leader must not advance from DELETE_ONLY to WRITE_ONLY until all catalog group followers have applied the DELETE_ONLY entry.

R42a. The catalog leader must not advance from DELETE_ONLY to WRITE_ONLY until the DELETE_ONLY epoch has been propagated to all cluster nodes that are ALIVE in the current SWIM membership view. Nodes marked SUSPECTED or DEAD are excluded from the propagation requirement. Propagation confirmation must use the SWIM protocol's dissemination guarantee (O(log N) rounds).

R43. In WRITE_ONLY state, the new schema element accepts writes but is not visible to reads. Background backfill of existing data (if required by the alteration) must occur during this phase.

R44. The catalog leader must not advance from WRITE_ONLY to PUBLIC until all cluster nodes that are ALIVE in the current SWIM membership view have confirmed receipt of the WRITE_ONLY epoch. Nodes marked SUSPECTED or DEAD are excluded. Confirmation must be via explicit acknowledgment piggybacked on the next SWIM protocol round from each node.

R45. In PUBLIC state, the schema element is fully visible and operational. The alteration is complete.

R46. Each phase transition must be committed as a separate Raft log entry with a new catalog epoch. The three-phase alteration requires three successive epoch increments.

R47. If the catalog leader loses leadership during a multi-phase alteration, the new leader must resume the alteration from the last committed phase. The Raft log contains the complete alteration history; no out-of-band coordination is required.

R48. A node that falls more than one schema version behind (misses an entire phase) must stop serving traffic for the affected table until it catches up. This enforces the two-version invariant from the F1 protocol.

R48a. Phased DDL alterations on different tables may proceed concurrently. The DDL lock (R12) serializes individual phase transitions but does not block a phase transition on table "B" while waiting for epoch propagation for table "A".

R48b. If a `dropTable` operation is submitted while a phased DDL alteration is in progress on that table (any phase), the drop must abort the in-progress alteration. The drop takes precedence: the table transitions to dropped state, and any pending phase transitions for that table are cancelled.

R48c. Each phase transition (DELETE_ONLY to WRITE_ONLY, WRITE_ONLY to PUBLIC) must have a configurable propagation timeout (default: 60 seconds). If epoch propagation confirmation (R42a, R44) is not received within the timeout, the catalog leader must log a warning and retry the propagation check. After three consecutive timeouts, the alteration must be aborted and the element reverted to its pre-alteration state.

### Interaction with F33 (Table Migration)

R49. During partition migration cutover (F33 R34-R38), the `MigrationCoordinator` must submit the ownership metadata update (new owning node, incremented ownership epoch) to the catalog leader as a catalog Raft entry.

R50. The catalog leader must validate the migration ID in the ownership update against the currently active migration for that partition (F33 R38). A mismatched migration ID must be rejected with an error response.

R51. The catalog ownership update must be atomic: the partition's owning node and ownership epoch must change in a single Raft log entry. The catalog epoch must also increment.

R52. After the catalog ownership update is committed, the catalog leader must respond to the `MigrationCoordinator` with a confirmation containing the new catalog epoch and the new ownership epoch.

R53. The ownership update must be propagated to all cluster nodes via the standard epoch dissemination mechanism (R36-R38). Nodes that receive the updated epoch must refresh their partition routing caches.

R54. Until the ownership update is committed in the catalog, the source node must continue serving reads for the migrating partition (F33 R36). The catalog serves as the single authority for partition ownership.

### Interaction with F36 (Remote Serialization)

R55. Atomic DDL operations must be encodable using the F36 remote serialization protocol. The protocol must define a new operation code for ATOMIC_DDL (reserved code 0x40) carrying a serialized batch of DDL operations.

R56. The ATOMIC_DDL request payload must begin with a 4-byte big-endian int32 operation count.

R56a. Each sub-operation in the ATOMIC_DDL payload must be encoded as: 1-byte operation type (0x01=CREATE, 0x02=DROP, 0x03=ALTER), followed by operation-specific fields. CREATE fields must match F36 R13 encoding. DROP fields must match F36 R14 encoding. ALTER fields must contain: table name (string per F36 R11), followed by the `SchemaUpdate` encoded as a sequence of field-level changes (each: 1-byte change type, field name string, and type-specific parameters per F36 R11-R12).

R57. The ATOMIC_DDL response must follow the F36 response envelope (F36 R27-R28). On success, the response must contain the new catalog epoch (8-byte int64). On failure, the error must carry the index of the failing operation and the specific error code.

R58. Catalog epoch queries must be encodable as a new operation code CATALOG_EPOCH (reserved code 0x41) with an empty request payload. The success response must contain: 8-byte int64 current catalog epoch, 8-byte int64 catalog leader node ID hash.

### Configuration

R59. The catalog group replication factor must be configurable via the engine builder (default: 3).

R60. The maximum catalog staleness (R35) must be configurable via the engine builder (default: 2 epochs).

R61. The DDL lock timeout (R13) must be configurable via the engine builder (default: 10 seconds).

R62. The delta threshold for full snapshot vs incremental refresh (R33) must be configurable (default: 1000 mutations).

R62a. The DDL commit timeout (R25a) must be configurable via the engine builder (default: 30 seconds).

R62b. The phased DDL propagation timeout (R48c) must be configurable via the engine builder (default: 60 seconds).

R63. Each configuration parameter must reject invalid values (non-positive, even replication factor, etc.) with an `IllegalArgumentException` identifying the parameter and constraint violated.

### Thread safety

R64. The `DdlBatch` builder must not be safe for concurrent use from multiple threads. A single batch must be built and executed from one thread. The engine's `atomicDdl()` may be called concurrently; the returned batches are thread-confined.

R65. The catalog Raft group state (epoch, table metadata, partition map) must be safe for concurrent reads from any thread on the node. Writes (DDL application) must be serialized through the Raft log.

R66. Catalog cache refresh (R32) must not block concurrent reads of the cache at the previous epoch. The refresh must use a copy-on-write or snapshot mechanism so readers see a consistent view.

### JPMS module boundaries

R67. `DdlBatch`, `SchemaUpdate`, the `CatalogEpoch` record, and the catalog query interfaces must reside in an exported package of the `jlsm-table` module.

R68. Internal catalog Raft group implementation, WAL-based batch recovery, and epoch dissemination mechanics must reside in `jlsm.table.internal` and must not be exported.

R69. The ATOMIC_DDL and CATALOG_EPOCH operation codes (R55, R58) must be registered in the F36 operation type registry.

---

## Design Narrative

### Intent

Define atomic multi-table DDL operations and the catalog replication protocol for distributing catalog metadata across cluster nodes. This spec resolves two deferred decisions from the table-catalog-persistence ADR: "atomic-multi-table-ddl" and "catalog-replication." It also provides the catalog authority protocol that F33 (Table Migration) R34-R38 reference, resolving obligation OB-F33-01.

### Why a dedicated catalog Raft group

The KB (catalog-replication-strategies.md) evaluates four approaches: single-leader, consensus-replicated (Raft/Paxos), gossip-based, and epoch-based. The recommendation is a Raft catalog group with epoch-based caching, and the reasoning is sound:

1. **Strong consistency for DDL.** DDL operations (create, drop, alter) have cluster-wide visibility requirements. A gossip-based approach would allow nodes to transiently disagree on whether a table exists, causing routing failures and potential data loss. Raft provides linearizable writes: once a DDL commit returns, every subsequent catalog read at any node will reflect it (after the next cache refresh).

2. **No external dependencies.** An external coordination service (etcd, ZooKeeper) would violate the project's no-external-dependencies constraint. The Raft implementation from F32 already exists; the catalog group reuses it with different data.

3. **Small, fixed group.** The catalog group is 3-5 nodes regardless of cluster size. This avoids the scalability concern of consensus-per-mutation in large clusters. Data Raft groups scale with partition count; the catalog group does not.

CockroachDB uses the same approach: "system ranges" are Raft groups that store catalog metadata, identical in protocol to user data ranges. TiDB separates concerns by using an external etcd-backed Placement Driver, but that introduces an external dependency. YugabyteDB uses Raft for tablet metadata. The pattern is well-established.

### Why F1-style phased DDL

Schema alterations in a distributed system are dangerous because different nodes may be running different schema versions simultaneously. Google's F1 protocol (VLDB 2013) solves this with the two-version invariant: at most two adjacent schema versions coexist at any time, with intermediate phases (DELETE_ONLY, WRITE_ONLY) that prevent data corruption.

The key insight is that a node on schema version V and a node on version V+1 must not perform conflicting operations. DELETE_ONLY ensures that the new element cannot receive orphaned writes from nodes still on the old schema. WRITE_ONLY ensures that the element receives writes before it becomes visible to reads, so no node reads stale data.

The alternative -- immediate schema switch -- works only if all nodes apply the change simultaneously, which is impossible in an asynchronous distributed system. Even a brief window where one node sees a new column and another does not can cause writes to the new column that are invisible to reads on the old-schema node. F1's phased approach eliminates this by construction.

The three-phase overhead (three Raft commits per alteration) is acceptable because DDL is infrequent relative to data operations. The KB notes that TiDB implements this pattern in production with acceptable latency.

### Why WAL-based batch intent for atomic DDL

Single-node atomic DDL (before catalog replication is involved) uses a write-ahead batch intent record. This is simpler than a two-phase commit or a shadow catalog approach:

1. **Write intent record** containing all operations.
2. **Apply operations** to the in-memory catalog and persist each change.
3. **Write completion record** confirming the batch.

On crash recovery, an intent without a completion record triggers rollback. An intent with a completion record is treated as applied. This is the same pattern used by F35's transaction WAL (write-ahead, then apply) adapted for DDL.

In clustered mode, the Raft log replaces the local WAL as the durability mechanism. The batch is committed as a single Raft log entry (R21), providing atomicity through Raft's all-or-nothing commit semantics.

### Why epoch-based caching with pull-on-miss

Every query routing decision requires catalog metadata (which table exists, which node owns which partition). Making every routing decision go through Raft consensus would add 1-2ms per operation. Instead, nodes cache the full catalog locally and check staleness via a single long comparison (local epoch vs cluster epoch).

The common path (no DDL in flight) is a single local memory read. When DDL occurs, the epoch bump propagates via SWIM piggybacking (O(log N) rounds, typically < 1 second for 1000 nodes), and stale nodes refresh. The pull-on-miss approach means nodes that discover staleness fetch the delta from any catalog group member, not necessarily the leader, distributing the read load.

The maximum staleness bound (R35, default: 2 epochs) prevents a node from serving stale data indefinitely. A node that misses two consecutive epochs is likely experiencing network issues and should stop serving until it catches up.

### Resolving OB-F33-01

F33 R34-R38 describe catalog metadata updates during partition migration but were written protocol-agnostically because catalog-replication was not yet specified. With this spec:

- **R34** ("update partition's catalog metadata") maps to R49: the `MigrationCoordinator` submits the update to the catalog leader as a Raft entry.
- **R35** ("committed through catalog authority") is satisfied by R17: the catalog Raft group IS the authority, and commits require majority acknowledgment.
- **R36** ("until confirmed, source continues serving") maps to R54.
- **R37** ("if update fails, rollback") -- catalog leadership loss during the update causes the migration to fail per R25; the `MigrationCoordinator` handles rollback per F33 R20.
- **R38** ("migration ID as causality token") maps to R50.

### What was ruled out

- **Gossip-based catalog replication.** Eventually consistent metadata is unacceptable for DDL correctness. A node that believes a table exists when it has been dropped will accept writes that are silently lost. The KB explicitly notes gossip is appropriate only for "systems tolerant of brief inconsistency" (Cassandra-style).

- **Immediate schema switch (no phased DDL).** Works for single-node but causes data corruption in distributed mode when nodes disagree on schema version. Every production distributed database that supports online DDL uses some form of phased protocol.

- **External coordination (etcd, ZooKeeper).** Violates the no-external-dependencies constraint. The Raft implementation from F32 provides identical guarantees without external processes.

- **Optimistic DDL (allow conflicts, reconcile later).** DDL conflicts are rare but catastrophic when they occur. Two concurrent `createTable("users")` calls must produce exactly one table, not two that need reconciliation. Raft's linearizable log provides this guarantee by construction.

- **Separate catalog transport.** Catalog replication messages use the same F19 multiplexed transport as data replication, with METADATA traffic class (F36 R41). A separate transport would add operational complexity for no benefit -- catalog messages are small and infrequent.

### Out of scope

- Online DDL with zero-downtime backfill for existing data (the phased protocol provides the framework; actual backfill orchestration is a separate concern)
- Schema versioning with time-travel queries (monotonic epoch enables this but the query interface is not defined here)
- Catalog group leader placement policy (which nodes serve in the catalog group)
- DDL rate limiting or quota management
