# Consensus — Category Index
*Topic: distributed-systems*
*Tags: raft, paxos, epaxos, isr, consensus, leader-election, quorum, replication, log-replication, viewstamped*

Consensus and replication protocols for maintaining consistent copies of
partitioned data across cluster nodes. Covers leader-based, leaderless,
and ISR approaches with LSM-tree integration points.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [partition-replication-consensus.md](partition-replication-consensus.md) | Consensus Protocols for Partition Replication | active | Raft: WAL as log, SWIM triggers elections | Per-partition replication groups (3-5 replicas) |

## Comparison Summary
<!-- Narrative comparison — write once 2+ subjects exist -->

## Recommended Reading Order
1. Start: [partition-replication-consensus.md](partition-replication-consensus.md) — Raft vs Paxos vs ISR vs leaderless

## Research Gaps
- Raft optimizations (batching, pipelining, parallel commits)
- Read leases and follower reads
- Membership change protocols (joint consensus vs single-server)
