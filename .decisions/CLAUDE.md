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
| Engine API Surface Design | engine-api-surface-design | 2026-03-19 | Interface-Based Handle Pattern with Tracked Lifecycle and Lease Eviction |
| Table Catalog Persistence | table-catalog-persistence | 2026-03-19 | Per-Table Metadata Directories — lazy recovery, per-table failure isolation |
| BoundedString Field Type Design | bounded-string-field-type | 2026-03-19 | BoundedString record as 5th sealed permit with STRING-delegating switch arms |
| Pre-Encrypted Document Signaling | pre-encrypted-document-signaling | 2026-03-19 | Factory method with boolean field — `JlsmDocument.preEncrypted(schema, ...)` |
| Encrypted Index Strategy | encrypted-index-strategy | 2026-03-18 | Static Capability Matrix with 3-tier full-text search (keyword, phrase, SSE) |

## Deferred
<!-- Topics recorded but not yet evaluated. Resume with /architect "<problem>" -->

| Problem | Slug | Deferred | Resume When |
|---------|------|----------|-------------|

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
| Table Partitioning | table-partitioning | 2026-03-16 | Range partitioning with per-partition co-located indices |
