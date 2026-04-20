---
title: "Engine Clustering"
slug: engine-clustering
domain: distribution
status: active
type: core
tags: ["clustering", "distributed", "SWIM", "HRW", "scatter-gather", "rebalancing"]
features:
  - slug: engine-clustering
    role: core
    description: "Cluster membership, table/partition ownership via rendezvous hashing, scatter-gather queries, and automatic rebalancing"
  - slug: f04-obligation-resolution--wd-03
    role: core
    description: "Remote dispatch payload format (table name + partition id) + parallel scatter via virtual-thread fanout — first end-to-end working scatter-gather (F04.R68, F04.R77)"
composes: []
spec_refs: ["F04"]
decision_refs: ["transport-abstraction-design", "discovery-spi-design", "scatter-gather-query-execution", "rebalancing-grace-period-strategy", "partition-to-node-ownership", "cluster-membership-protocol"]
kb_refs: ["distributed-systems/cluster-membership", "distributed-systems/data-partitioning"]
depends_on: ["distribution/database-engine", "distribution/table-partitioning"]
enables: []
---

# Engine Clustering

Multi-engine coordination for distributed operation. Multiple engine
instances discover each other via a pluggable SPI, form a cluster with
split-brain detection, and automatically balance table and partition
ownership across members. Queries are scattered to owning nodes and
gathered into unified results.

## What it does

Clustering extends the single-node database engine with peer-to-peer
coordination. A pluggable discovery SPI resolves cluster members. A
message-oriented transport abstraction delivers messages between nodes.
Rendezvous hashing (HRW) assigns partition ownership statelessly with
minimal movement on membership changes. When a node becomes unavailable,
the cluster serves partial results from surviving nodes and rebalances
ownership after a configurable grace period.

## Features

**Core:**
- **engine-clustering** — SWIM + phi accrual membership, HRW ownership, scatter-gather, rebalancing

## Key behaviors

- Peer-to-peer — no leaders, no external coordination service
- Discovery SPI: pluggable seed provider with optional registration
- Transport: message-oriented send + request with type dispatch, virtual/platform thread split
- Partition ownership: rendezvous hashing (HRW) — stateless, O(K/N) minimal movement
- Scatter-gather: partition-aware proxy table, transparent Table interface, k-way merge
- Rebalancing: eager HRW recompute on membership change, grace period controls cleanup
- Split-brain detection via SWIM + phi accrual composite protocol
- Partial results from surviving nodes when a member is unavailable

## Related

- **Specs:** F04 (engine clustering)
- **Decisions:** transport-abstraction-design (message-oriented), discovery-spi-design (seed provider), scatter-gather-query-execution (partition-aware proxy), rebalancing-grace-period-strategy (eager + grace), partition-to-node-ownership (HRW), cluster-membership-protocol (SWIM + phi accrual)
- **KB:** distributed-systems/cluster-membership (SWIM, failure detection), distributed-systems/data-partitioning (ownership strategies)
- **Depends on:** distribution/database-engine (single-node engine), distribution/table-partitioning (partitioned tables for ownership distribution)
- **Deferred work:** continuous-rediscovery, authenticated-discovery, table-ownership-discovery, transport-traffic-priority, message-serialization-format, connection-pooling, scatter-backpressure, membership-view-stall-recovery, slow-node-detection, piggybacked-state-exchange
