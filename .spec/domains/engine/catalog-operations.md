---
{
  "id": "engine.catalog-operations",
  "version": 4,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "engine"
  ],
  "requires": [
    "engine.in-process-database-engine",
    "transport.multiplexed-framing",
    "partitioning.partition-replication",
    "serialization.remote-serialization"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "table-catalog-persistence"
  ],
  "kb_refs": [
    "systems/database-engines/catalog-persistence-patterns",
    "distributed-systems/replication/catalog-replication-strategies"
  ],
  "open_obligations": [
    "OB-catalog-lock-extraction-01: Extract engine.catalog-lock-lifecycle as a dedicated spec covering the file-lock handle protocol currently captured as the composite R78 in this spec. Findings F-R1.resource_lifecycle.2.1 / 2.2 / 2.3 / 2.4 / 2.5 / 2.6 from audit run-001 all live on the FileBasedCatalogLock construct, which has no dedicated spec. The composite R78 captures the protocol short-term; a follow-up extraction is appropriate when cross-engine handle semantics expand (e.g., a remote-backend lease lock for object-storage backends)."
  ],
  "_migrated_from": [
    "F37"
  ]
}
---

# engine.catalog-operations — Catalog Operations

## Requirements

### Atomic multi-table DDL
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

### Local-mode catalog mutation discipline (single-JVM and cross-process)

The requirements in this section govern catalog mutation discipline at the local (single-node, possibly multi-JVM-on-shared-storage) layer. They complement the Raft-replicated DDL discipline (R12–R34 above) and apply equally whether the engine runs in single-node mode (no Raft group) or in clustered mode (where they constrain each node's local catalog state machine inside the Raft-applied flow).

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

## Verification Notes

### Verified: v3 — 2026-04-25 (state: APPROVED — audit reconciliation amendment, source change required)

Audit reconciliation work (audit run `implement-encryption-lifecycle--wd-02/audit/run-001`) surfaced six gaps in the local-mode catalog mutation discipline that the previous spec did not cover. v3 adds five new requirements (R75–R78) under a new "Local-mode catalog mutation discipline" section, plus opens one obligation tracking a future spec extraction:

- **R75 (new) — paired-mutation rollback discipline.** When a non-batch catalog mutation requires two durable writes and the second fails, rollback must restore the first; rollback failure must propagate the original failure with the rollback failure attached via `addSuppressed`. Source: F-R1.shared_state.1.1 (`TableCatalog.updateEncryption` had no rollback when `setHighwater` failed after `writeMetadata` succeeded).

- **R76 (new) — stage-then-publish for table registration.** Register must stage a `LOADING` placeholder before any I/O, transition to `READY` via CAS only after disk durable, and use conditional-remove (not unconditional) on rollback. Source: F-R1.shared_state.1.2 (`TableCatalog.registerInternal` published in-memory entry before disk state existed).

- **R76a (new) — stage-then-publish for catalog index mutations.** Catalog-index mutations must encode-then-rename-then-publish, never publish-then-persist. Source: F-R1.shared_state.1.3 (`CatalogIndex.setHighwater` published new value to in-memory readers before durability).

- **R77 (new) — cross-process register-during-open race resolution.** The catalog `open()` scan must defer index-miss directories during iteration and re-check them against a fresh catalog-index re-read after iteration completes, all under the catalog file-lock. Source: F-R1.shared_state.1.4 (`TableCatalog.open` did not acquire any catalog-level mutex against concurrent registers from other JVMs).

- **R78 (new — composite, six parts) — catalog file-lock handle resource discipline.** Six distinct latent bugs on the same `FileBasedCatalogLock` construct, captured as one composite requirement: (a) close ordering — release before cleanup, never delete; (b) re-entrancy via in-process lock; (c) monotonic-time bounded reclaim; (d) platform-portable holder liveness probe (`ProcessHandle.of`); (e) holder-thread guard on close; (f) bounded per-table-name lock map. Source: F-R1.resource_lifecycle.2.1 / 2.2 / 2.3 / 2.4 / 2.5 / 2.6.

- **`OB-catalog-lock-extraction-01` (new open obligation) — extract `engine.catalog-lock-lifecycle` as a dedicated spec.** Captured in the front-matter `open_obligations` list. The composite R78 captures the protocol short-term; the file-lock handle deserves its own spec when cross-engine handle semantics expand (e.g., a remote-backend lease lock for object-storage backends). The audit reconciliation flagged this as a "coverage gap" — the construct surfaced six confirmed bugs but had no dedicated spec.

**Verification impact:**

- All six additions are tightening (gap closure), not scope changes. R75–R78 are additive to the existing R-numbering family (which extends to R74); no existing requirement is invalidated.
- R75 sits adjacent to R5 (atomic-batch rollback for `DdlBatch`) but applies to non-batch paired mutations. R76 / R76a / R77 / R78 cover terrain not previously addressed by the spec.
- Implementation impact: existing `TableCatalog`, `CatalogIndex`, and `FileBasedCatalogLock` source already incorporates the audit fixes (audit run-001 is complete on the code side). v3 captures the contract invariants those fixes enforce so future audit passes can detect drift.

**Overall: APPROVED — amendment with source changes already applied.** Audit run-001 fixed each of the six bugs in source; v3 captures the contract invariants those fixes enforce. The open obligation `OB-catalog-lock-extraction-01` is non-blocking — it tracks a future spec extraction, not a current contract gap.
