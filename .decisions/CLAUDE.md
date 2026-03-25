# Architecture Decisions — Master Index

> **Managed by vallorcine agents. Use slash commands to modify this file.**
> To start a decision: `/architect "<problem>"`
> To review a decision: `/decisions review "<slug>"`

> Pull model. Load on demand only.
> Structure: .decisions/<problem-slug>/adr.md
> Full history: [history.md](history.md)

## Active Decisions
<!-- Proposed or in-progress only. Confirmed/superseded rows move to history.md. -->

| Problem | Slug | Date | Status | Recommendation |
|---------|------|------|--------|----------------|

## Recently Accepted (last 5)
<!-- Once this section exceeds 5 rows, oldest row moves to history.md -->

| Problem | Slug | Accepted | Recommendation |
|---------|------|----------|----------------|
| Transport Abstraction Design | transport-abstraction-design | 2026-03-20 | Message-Oriented Transport — send + request with type dispatch, virtual/platform thread split |
| Discovery SPI Design | discovery-spi-design | 2026-03-20 | Minimal Seed Provider with Optional Registration — discoverSeeds + default register/deregister |
| Scatter-Gather Query Execution | scatter-gather-query-execution | 2026-03-20 | Partition-Aware Proxy Table — transparent Table interface, k-way merge, partition pruning |
| Rebalancing & Grace Period Strategy | rebalancing-grace-period-strategy | 2026-03-20 | Eager Reassignment with Deferred Cleanup — immediate HRW recompute, grace controls cleanup |
| Partition-to-Node Ownership | partition-to-node-ownership | 2026-03-20 | Rendezvous Hashing (HRW) — stateless pure function, O(K/N) minimal movement |

## Deferred
<!-- Topics recorded but not yet evaluated. Resume with /architect "<problem>" -->

| Problem | Slug | Deferred | Resume When |
|---------|------|----------|-------------|
| Distributed Join Execution | distributed-join-execution | 2026-03-20 | Joins enter scope (jlsm-sql extended or cross-table distributed queries) |

## Closed
<!-- Topics explicitly ruled out. Won't be raised again unless reopened. -->

| Problem | Slug | Closed | Reason |
|---------|------|--------|--------|

## Archived
Decisions older than the 5 most recent: [history.md](history.md)

| Problem | Slug | Accepted | Recommendation |
|---------|------|----------|----------------|
| Compression Codec API Design | compression-codec-api-design | 2026-03-17 | Open interface + explicit codec list — non-sealed, reader takes varargs codecs |
| VectorType Serialization Encoding | vector-type-serialization-encoding | 2026-03-17 | Flat Vector Encoding — contiguous d×sizeof(T) bytes, no per-vector metadata |
| Stripe Hash Function | stripe-hash-function | 2026-03-17 | Stafford variant 13 (splitmix64) — zero-allocation, sub-nanosecond |
| Cluster Membership Protocol | cluster-membership-protocol | 2026-03-20 | Rapid + Phi Accrual Composite — leaderless consistent membership with adaptive failure detection |
| Engine API Surface Design | engine-api-surface-design | 2026-03-19 | Interface-Based Handle Pattern with Tracked Lifecycle and Lease Eviction |
| Table Catalog Persistence | table-catalog-persistence | 2026-03-19 | Per-Table Metadata Directories — lazy recovery, per-table failure isolation |
| BoundedString Field Type Design | bounded-string-field-type | 2026-03-19 | BoundedString record as 5th sealed permit with STRING-delegating switch arms |
| Pre-Encrypted Document Signaling | pre-encrypted-document-signaling | 2026-03-19 | Factory method with boolean field — `JlsmDocument.preEncrypted(schema, ...)` |
| Encrypted Index Strategy | encrypted-index-strategy | 2026-03-18 | Static Capability Matrix with 3-tier full-text search (keyword, phrase, SSE) |
| Table Partitioning | table-partitioning | 2026-03-16 | Range partitioning with per-partition co-located indices |
