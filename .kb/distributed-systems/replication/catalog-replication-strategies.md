---
title: "Catalog and Metadata Replication Strategies"
aliases: ["catalog replication", "metadata replication", "schema replication", "partition map"]
topic: "distributed-systems"
category: "replication"
tags: ["catalog", "metadata", "schema", "ddl", "replication", "gossip", "consensus", "epoch"]
complexity:
  time_build: "N/A"
  time_query: "O(1) local cache lookup"
  space: "O(tables × partitions) metadata"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "distributed-systems/data-partitioning/partitioning-strategies.md"
  - "distributed-systems/cluster-membership/cluster-membership-protocols.md"
  - "distributed-systems/networking/multiplexed-transport-framing.md"
decision_refs: ["catalog-replication", "atomic-multi-table-ddl", "table-migration-protocol"]
sources:
  - url: "https://medium.com/@adamprout/categorizing-how-distributed-databases-utilize-consensus-algorithms-492c8ff9e916"
    accessed: "2026-04-13"
    description: "CfM vs CfD categorization of consensus usage in distributed databases"
  - url: "https://github.com/cockroachdb/cockroach/blob/master/docs/design.md"
    accessed: "2026-04-13"
    description: "CockroachDB design doc — range metadata, leases, two-level indirection"
  - url: "https://www.pingcap.com/blog/effective-online-ddl-database-schema-changes-zero-downtime/"
    accessed: "2026-04-13"
    description: "TiDB online DDL implementation based on F1 protocol"
  - url: "https://dl.acm.org/doi/10.14778/2536222.2536230"
    accessed: "2026-04-13"
    description: "F1 online async schema change (VLDB 2013) — original paper reference"
  - url: "https://www.yugabyte.com/blog/how-does-consensus-based-replication-work-in-distributed-databases/"
    accessed: "2026-04-13"
    description: "YugabyteDB consensus replication overview"
  - url: "https://www.vldb.org/pvldb/vol16/p140-hu.pdf"
    accessed: "2026-04-13"
    description: "Hu et al. — Online Schema Evolution is (Almost) Free for Snapshot Databases (VLDB 2023)"
  - url: "https://arxiv.org/html/2503.02956"
    accessed: "2026-04-13"
    description: "TreeCat — Standalone Catalog Engine for Large Data Systems (2025)"
  - url: "https://link.springer.com/chapter/10.1007/978-981-97-5552-3_24"
    accessed: "2026-04-13"
    description: "SLSM — Efficient Strategy for Lazy Schema Migration on Shared-Nothing Databases (DASFAA 2024)"
---

# Catalog and Metadata Replication Strategies

A distributed database catalog tracks table schemas, partition-to-node maps,
index definitions, and DDL version history. This metadata is small in volume
but critical in correctness — a stale or inconsistent catalog causes
mis-routed queries, data loss on DDL, or split-brain partition ownership.

## Replication Approaches

### 1. Single-Leader Catalog

One designated node owns the catalog and replicates changes to followers via
log shipping or state-machine replication.

- **Pros**: Simple, linearizable reads from leader, easy conflict resolution.
- **Cons**: Single point of failure; leader election delay causes catalog
  unavailability; write throughput limited to one node.
- **Used by**: MySQL Group Replication (primary mode), Redis Sentinel.

### 2. Consensus-Replicated Catalog (Raft / Paxos)

A small Raft group (3-5 nodes) manages catalog state. All mutations go through
the consensus log. Any majority quorum can serve reads and accept writes.

- **Pros**: Strong consistency, automatic leader election, tolerates f failures
  in a 2f+1 group, well-understood protocol.
- **Cons**: Latency proportional to quorum round-trip; catalog Raft group is a
  bottleneck if DDL rate is high; adds protocol complexity.
- **Used by**: CockroachDB (system ranges are Raft groups), TiDB/PD (etcd,
  which is Raft-based), YugabyteDB (Raft for tablet metadata).

CockroachDB stores catalog in "system ranges" replicated identically to user
data — the same Raft machinery handles both. TiDB separates concerns: a
Placement Driver (PD) cluster backed by etcd manages metadata externally.

### 3. Gossip-Based Metadata

Nodes exchange metadata diffs via epidemic (gossip) protocol. No leader.
All nodes eventually converge to the same schema version.

- **Pros**: Fully peer-to-peer, no SPOF, scales to large clusters, tolerant of
  partitions (crdt-like convergence).
- **Cons**: Eventually consistent — nodes may transiently disagree on schema;
  conflict resolution for concurrent DDL is complex; convergence time is
  O(log N) gossip rounds but wall-clock delay depends on interval.
- **Used by**: Cassandra (schema gossip with timestamp-based conflict
  resolution), ScyllaDB (Raft for schema since 5.2, gossip for membership).

### 4. Epoch-Based Versioning

Catalog has a monotonic epoch number. On any DDL, the epoch increments. Nodes
cache the catalog locally and periodically pull or are pushed the latest epoch.
A node discovering its epoch is stale refreshes before serving queries.

- **Pros**: Simple protocol, compatible with any transport, nodes self-heal
  by pulling; no consensus needed if a single DDL coordinator exists.
- **Cons**: Stale reads possible between epoch checks; requires a reliable
  epoch source (which circles back to consensus or single-leader).
- **Used by**: CockroachDB (range lease epochs derived from node liveness
  table), Kafka (KRaft controller epoch), many internal systems.

## Partition Map Distribution

The partition map (which key ranges live on which nodes) is the hottest
metadata — every query routing decision depends on it.

| Strategy | Description | Trade-off |
|---|---|---|
| **Two-level indirection** | Meta1 range locates meta2 range, which locates data range (CockroachDB) | Scalable; 2 lookups on cold start, cached thereafter |
| **Centralized directory** | PD/coordinator holds full map, clients cache it (TiDB) | Simple; PD is bottleneck at extreme scale |
| **Gossip + token ring** | Each node knows its own ranges, gossips ring state (Cassandra) | No central bottleneck; convergence lag |
| **Pull-on-miss** | Nodes cache map, pull from authority on cache miss or stale hit | Low overhead; cold-start penalty |

For jlsm (up to 1000 nodes, no external deps), **pull-on-miss with a
consensus-backed authority** is the practical sweet spot. Nodes cache the
partition map locally and invalidate on epoch bump or routing failure.

## Online Schema Change (Atomic DDL)

Changing a schema in a distributed database while serving traffic is the
hardest catalog problem. The foundational protocol is from Google F1 (2013).

### F1 Two-Version Invariant

The key insight: if at most two adjacent schema versions coexist in the cluster
at any time, then intermediate states can be defined such that no data
corruption occurs. F1 defines these intermediate states:

1. **absent** — element does not exist in schema.
2. **delete-only** — element exists but only delete operations are allowed
   (prevents orphaned data if a node on the old schema writes).
3. **write-only** — element accepts writes but is not yet visible to reads
   (backfill happens here).
4. **public** — element is fully visible and operational.

Transitions happen one phase at a time. A schema lease mechanism ensures no
node is more than one version behind — if a node's lease expires without
renewal, it must stop serving until it catches up.

### TiDB Implementation

TiDB adapts F1 into three practical phases:

1. **Prepare**: DDL owner acquires metadata lease, installs shadow structures.
2. **Reorganize**: Background workers backfill across regions under MVCC; rate
   limits protect tail latency.
3. **Commit**: Atomic metadata switch activates the new structure; brief
   distributed metadata lock.

Nodes may lag by at most one DDL version and catch up without blocking traffic.

### Implications for jlsm

For a pure-Java peer-to-peer system without external coordination:

- A **Raft group for catalog** (3-5 nodes) provides the strong consistency
  needed for DDL correctness without external dependencies.
- **Epoch-based versioning** layered on top gives nodes a cheap staleness
  check — compare local epoch to catalog epoch on each query.
- **F1-style phased DDL** prevents corruption during schema transitions. The
  two-version invariant requires that the catalog Raft group can enforce
  schema leases (nodes that miss a lease window must re-sync before serving).
- **Partition map** should use pull-on-miss with epoch invalidation — nodes
  cache aggressively, re-fetch on routing errors or epoch mismatch.

## How Production Systems Compare

| System | Catalog Store | Partition Map | DDL Protocol | Consistency |
|---|---|---|---|---|
| CockroachDB | System ranges (Raft) | Two-level meta ranges | F1-derived, lease-based | Strong |
| TiDB | PD (etcd/Raft) | PD directory + cache | F1-derived, DDL owner | Strong |
| Cassandra | Gossip | Token ring gossip | Timestamp LWW | Eventual |
| YugabyteDB | Raft tablets | Master catalog (Raft) | F1-derived | Strong |
| Vitess | Topology service (etcd/ZK/Consul) | Topology + vtgate cache | gh-ost / pt-osc | Strong (DDL) |

## Design Recommendations for jlsm

1. **Catalog Raft group**: Dedicate a small Raft group (3-5 replicas) to
   catalog state. This avoids external dependencies and provides strong
   consistency for DDL. The same Raft implementation used for data replication
   can serve double duty.

2. **Local catalog cache with epoch**: Every node caches the full catalog
   locally. On each operation, compare the local epoch to the known cluster
   epoch. Refresh on mismatch. This makes the common path (no DDL in flight)
   a single local memory read.

3. **F1-style phased DDL**: Implement the four-phase state machine (absent →
   delete-only → write-only → public) with schema leases. The catalog Raft
   leader coordinates transitions and enforces the two-version invariant.

4. **Partition map as catalog data**: Store the partition map inside the
   catalog Raft group. Nodes pull on miss or epoch invalidation. For 1000
   nodes with typical partition counts, the full map fits in a few MB.

5. **Protocol separation**: Keep catalog replication protocol independent of
   data replication protocol. The catalog Raft group is a fixed small set;
   data replication may use different strategies per table or partition.

## Updates 2026-04-13

### Non-Blocking DDL Beyond F1

F1's four-phase protocol requires global synchronization barriers. Recent work relaxes this:

- **Lazy schema propagation** (SLSM, DASFAA 2024): nodes migrate rows on
  first access rather than via a blocking backfill phase:
  ```
  fn read(row, target_ver):
      if row.schema_ver < target_ver:
          row = migrate(row, row.schema_ver, target_ver)
          async_persist(row)
      return row
  ```
  Trade-off: no backfill downtime, but first-read latency spikes on unmigrated rows.

- **Snapshot-bound schema evolution** (Hu et al., VLDB 2023): DDL commits a
  new schema at timestamp T. MVCC snapshots < T see old schema, >= T see new.
  No intermediate states needed — the snapshot boundary enforces isolation.

### Disaggregated Metadata Catalogs

TreeCat (2025) extracts the catalog into a standalone gRPC service:

- **SnapshotStore + DeltaStore**: current state `path -> vid`, history
  `(start_vid, end_vid, path)`. Monotonic version IDs give global DDL order.
- **MVOCC + predicate locking**: serializable concurrent DDL; aborts only
  on true write-write conflicts via scan-range + precision locking.
- **Merge ops**: commutative updates (stats counters) skip conflict detection.

### Schema Versioning with Time-Travel

Monotonic schema version IDs enable point-in-time schema resolution:
```
fn resolve_schema(query_vid):
    if query_vid >= snapshot_store.current_vid: return current_schema
    return delta_store.lookup(query_vid)  // binary search on vid ranges
```
Supports audit queries, DDL rollback, and point-in-time recovery.

### Implications for jlsm

- Lazy migration piggybacks on LSM compaction — no dedicated backfill phase.
- A monotonic schema-version-ID in the catalog Raft group enables both
  epoch staleness checks and time-travel schema resolution.
