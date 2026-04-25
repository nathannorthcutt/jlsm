# Engine — Spec Index

> Shard index for the engine domain.
> Split this file when it exceeds ~50 entries.

## Feature Registry

| ID | Title | Status | Amends | Decision Refs |
|----|-------|--------|--------|---------------|
| F15 | JSON-Only SIMD On-Demand Parser with JSONL Streaming | ACTIVE | invalidates F14.R48, F14.R49 | — |
| F34 | Handle Lifecycle | ACTIVE | — | engine-api-surface-design |
| F35 | Cross-Table Transactions | ACTIVE | — | engine-api-surface-design |
| F36 | Remote Serialization | ACTIVE | — | engine-api-surface-design, connection-pooling |
| F37 | Catalog Operations | ACTIVE | — | table-catalog-persistence |
| F38 | Aggregation Query Merge | ACTIVE | — | aggregation-query-merge, scatter-backpressure, scatter-gather-query-execution |
| F39 | Distributed Pagination | ACTIVE | — | limit-offset-pushdown, scatter-backpressure, scatter-gather-query-execution |
| F40 | Distributed Join Strategy | ACTIVE | — | distributed-join-execution, scatter-backpressure, scatter-gather-query-execution, table-partitioning |
| F41 | Encryption Lifecycle | ACTIVE | — | per-field-pre-encryption, per-field-key-binding, encryption-key-rotation, unencrypted-to-encrypted-migration, index-access-pattern-leakage |
| F44 | Scan Lease GC Watermark | ACTIVE | — | scan-lease-gc-watermark, scatter-backpressure |
| engine.clustered-table-construction | Clustered Table Construction Contract | ACTIVE | amends engine.clustering R60 | scatter-gather-query-execution, transport-abstraction-design |
