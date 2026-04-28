---
{
  "id": "engine.catalog-operations.cache-and-propagation",
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

# engine.catalog-operations.cache-and-propagation — Epoch-Based Catalog Cache and Schema Change Propagation

This spec was carved from `engine.catalog-operations` during a domain subdivision. The
parent retains cross-cutting requirements that span multiple sub-domains;
the requirements below are specific to this concern.

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



---

## Notes
