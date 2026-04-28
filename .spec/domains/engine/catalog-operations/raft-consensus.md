---
{
  "id": "engine.catalog-operations.raft-consensus",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [
    "engine"
  ],
  "requires": [],
  "invalidates": [],
  "decision_refs": [],
  "kb_refs": [],
  "parent_spec": "engine.catalog-operations",
  "_split_from": "engine.catalog-operations"
}
---

# engine.catalog-operations.raft-consensus — Catalog Raft Group, Bootstrap, Leader, and Follower

This spec was carved from `engine.catalog-operations` during a domain subdivision. The
parent retains cross-cutting requirements that span multiple sub-domains;
the requirements below are specific to this concern.

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

R70. On initial cluster formation, the engine builder must accept a list of initial catalog group member node IDs. The first node in the list must bootstrap the catalog Raft group as a single-member group and then add the remaining members one at a time via the Raft configuration change protocol (F32 R49-R51).

R71. When the replication factor is 1 (single-node mode), the single catalog replica must immediately transition to LEADER on startup without running election timers (consistent with F32 R80). All catalog mutations are committed after the local WAL append (quorum of 1). Epoch dissemination via SWIM (R31a) is inactive.

R72. If the catalog Raft group has no leader (e.g., during an election or when a majority of catalog replicas are unreachable), DDL operations must fail with an `IOException` indicating that the catalog is unavailable. The exception must include a retry-after hint based on the election timeout range (F32 R19).

### Catalog Raft group SWIM interaction

R73. When SWIM marks a catalog group member as DEAD and the rebalancing policy determines a replacement, the catalog leader must remove the dead member and add the replacement via sequential Raft configuration changes (F32 R49-R53).

R74. When SWIM marks a catalog group member as SUSPECTED, the catalog group must not remove the member. The Raft protocol's internal leader election handles temporary unavailability.

### Catalog leader operations



---

## Notes
