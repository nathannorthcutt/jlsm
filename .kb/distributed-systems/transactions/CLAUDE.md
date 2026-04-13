# Transactions — Category Index
*Topic: distributed-systems*
*Tags: 2pc, percolator, calvin, occ, mvcc, distributed-transactions, snapshot-isolation, serializable, cross-partition, atomic-commit*

Distributed transaction protocols for coordinating atomic multi-partition writes
in partitioned storage systems. Covers protocol mechanics, isolation levels,
WAL integration, and failure handling.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [cross-partition-protocols.md](cross-partition-protocols.md) | Cross-Partition Transaction Protocols | active | Percolator: no coordinator SPOF | Range-partitioned LSM with peer-to-peer topology |

## Comparison Summary
<!-- Narrative comparison — write once 2+ subjects exist -->

## Recommended Reading Order
1. Start: [cross-partition-protocols.md](cross-partition-protocols.md) — 2PC vs Calvin vs Percolator vs OCC

## Research Gaps
- Hybrid transaction protocols (local-fast + cross-partition-slow)
- Transaction conflict resolution strategies
- Distributed deadlock detection
