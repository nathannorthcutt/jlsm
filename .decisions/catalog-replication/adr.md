---
problem: "catalog-replication"
date: "2026-04-15"
version: 2
status: "accepted"
decision_refs: ["table-catalog-persistence"]
spec_refs: ["F37"]
---

# Catalog Replication

## Problem

How should the table catalog (table metadata, schema, partition maps) be
replicated across cluster nodes for consistent DDL operations?

## Decision

**Dedicated Raft catalog group** — the catalog is replicated through a
separate Raft consensus group, distinct from data partition Raft groups.
All catalog mutations (table create, drop, alter, batch DDL, partition map
updates) go through the catalog Raft log. Nodes cache catalog state locally
with epoch-based invalidation.

Key design choices resolved by F37 R15-R17a, R23, R59, R71-R72:
- Dedicated catalog Raft group separate from data partition groups (R15)
- Configurable replica count (default 3, minimum 1, must be odd) (R16)
- All catalog mutations replicated via Raft consensus log (R17)
- Mutation committed only after majority acknowledgment (R17a)
- Epoch assigned by leader before proposal, included in Raft log entry (R23)
- Single-node mode: immediate leader transition, quorum of 1 (R71)
- Unavailability: DDL fails with IOException + retry-after hint (R72)
- Replication factor configurable via engine builder (R59)

## Context

Originally deferred during `table-catalog-persistence` decision (2026-03-30)
as future cluster work. Resolved by F37 (Catalog Operations) which specified
the full catalog replication protocol. KB research (catalog-replication-strategies.md)
evaluated four approaches: single-leader, Raft, gossip, and epoch-based.
Raft was selected for strong DDL consistency.

## Alternatives Considered

- **Gossip-based replication:** Eventually consistent. Rejected — a node
  that believes a table exists when it has been dropped will accept writes
  that are silently lost.
- **Single-leader without consensus:** Simple but no automatic failover.
  Rejected — catalog availability is critical for all DDL.
- **Separate catalog transport:** Additional operational complexity for no
  benefit. Catalog messages use F19 multiplexed transport with METADATA
  traffic class.

## Consequences

- Strong consistency for all DDL operations across the cluster
- Catalog mutations have consensus latency (majority ack required)
- Catalog group is a separate failure domain from data partitions
- Epoch-based local caching reduces read-path latency for catalog lookups
