---
title: "engine-clustering"
type: feature-footprint
domains: ["cluster-membership", "data-partitioning", "failure-detection"]
constructs: ["ClusteredEngine", "ClusteredTable", "RapidMembership", "PhiAccrualFailureDetector", "RendezvousOwnership", "GracePeriodManager", "InJvmTransport", "InJvmDiscoveryProvider", "RemotePartitionClient"]
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/**"
research_status: stable
last_researched: "2026-03-26"
---

# engine-clustering

## What it built
Cluster membership and table ownership for jlsm-engine. Multiple engine instances
discover each other via pluggable SPI, form a cluster with phi-accrual failure
detection, and balance table/partition ownership via rendezvous hashing. Queries
scatter to owning nodes and gather results with a k-way merge iterator.

## Key constructs
- `ClusteredEngine` — wraps LocalEngine + membership + ownership; rebalances on view changes
- `ClusteredTable` — partition-aware proxy with scatter-gather scan and CRUD routing
- `RapidMembership` — Rapid protocol: expander graph overlay, multi-process cut detection, 75% consensus
- `PhiAccrualFailureDetector` — sliding window heartbeat statistics, phi = -log10(P(late))
- `RendezvousOwnership` — deterministic HRW hashing with FNV-1a + Stafford mix13 finalization
- `GracePeriodManager` — tracks departed nodes with configurable grace window
- `InJvmTransport` / `InJvmDiscoveryProvider` — in-process implementations for testing
- `RemotePartitionClient` — serializes CRUD as QUERY_REQUEST over transport

## Adversarial findings
- nan-at-phi-threshold: NaN bypasses `<= 0.0` validation → [KB entry](nan-at-phi-threshold.md)
- ismember-ignores-state: `isMember()` returns true for DEAD members, breaking departure detection → [KB entry](ismember-ignores-state.md)

## Cross-references
- ADR: .decisions/cluster-membership-protocol/adr.md
- ADR: .decisions/partition-to-node-ownership/adr.md
- ADR: .decisions/rebalancing-grace-period-strategy/adr.md
- ADR: .decisions/scatter-gather-query-execution/adr.md
- ADR: .decisions/discovery-spi-design/adr.md
- ADR: .decisions/transport-abstraction-design/adr.md
- Related features: in-process-database-engine, table-partitioning
