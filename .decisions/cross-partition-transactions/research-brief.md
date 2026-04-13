---
problem: "cross-partition-transactions"
requested: "2026-04-13"
status: "complete"
---

# Research Brief — cross-partition-transactions

## Context
The Architect is evaluating how to coordinate atomic multi-partition writes in
jlsm's distributed engine. The partition model is range-based with per-partition
co-located indices (table-partitioning ADR). The transport is multiplexed
single-connection-per-peer (connection-pooling ADR). Each partition operates
independently — there is currently no cross-partition coordination.

Binding constraints for this evaluation:
- Pure Java 25, no external dependencies (no ZooKeeper, etcd, external coordinators)
- Range-partitioned with per-partition co-located indices
- Up to 1000 nodes, Rapid membership protocol for failure detection
- Multiplexed NIO transport for inter-node communication
- Peer-to-peer, no leader requirement (from cluster-membership-protocol ADR)

## Subjects Needed

### Distributed Transaction Protocols for Partitioned Storage
- Requested path: agent-determined (likely `distributed-systems/transactions/`)
- Why needed: No KB coverage exists. The decision requires comparing 2PC, Calvin,
  Percolator, and OCC with understanding of their tradeoffs in a partitioned
  LSM-tree context.
- Key questions to answer:
  - **2PC (Two-Phase Commit)**: blocking behavior, coordinator failure, performance
    overhead. How does it interact with LSM-tree write-ahead logs? Can the WAL
    serve as the participant log?
  - **Calvin / deterministic databases**: pre-ordering transactions to eliminate
    coordination. How does this interact with range partitions? What are the
    latency tradeoffs?
  - **Percolator / timestamp-based**: BigTable-inspired, uses a timestamp oracle
    for serializable isolation. What's the single-point-of-failure risk? Can it
    work without a central oracle?
  - **OCC (Optimistic Concurrency Control)**: validation-phase abort rates under
    contention. When is OCC better than pessimistic approaches?
  - **Isolation levels**: does jlsm need serializable, or is snapshot isolation
    sufficient? What are the anomalies at each level?
  - **Integration with WAL**: can the existing WAL serve as a transaction log?
    What modifications are needed?
- Sections most important for this decision:
  - `## tradeoffs` — head-to-head comparison of protocols
  - `## implementation-notes` — integration with LSM-tree and WAL
  - `## practical-usage` — when to use each protocol

## Commands to run
/research "distributed transaction protocols for partitioned storage" context: "architect decision: cross-partition-transactions"
