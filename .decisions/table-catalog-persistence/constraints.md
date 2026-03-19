---
problem: "Table catalog persistence model for jlsm-engine"
slug: "table-catalog-persistence"
captured: "2026-03-19"
status: "draft"
---

# Constraint Profile — table-catalog-persistence

## Problem Statement
How should the jlsm-engine persist its table registry (names, schemas, metadata) so that tables survive engine restarts, support lazy incremental recovery at startup, isolate per-table failures, and remain architecturally compatible with future clustered distribution?

## Constraints

### Scale
- Hundreds of thousands of tables per engine instance (vector DB pattern: per-user tables)
- Millions of QPS and inserts concurrently across tables
- Growth nearly unlimited with object storage backing
- Tables will eventually be partitioned across engine instances in a cluster

### Resources
- Constrained memory and CPU — containerized environments
- Limited temporary disk
- Must fit within existing resource budgets; no unbounded memory growth
- Object storage (S3/GCS) as durable backing store

### Complexity Budget
- High — team has expert-level experience with distributed data systems and high-volume workloads
- Complex solutions acceptable if justified by operational requirements

### Accuracy / Correctness
- Catalog must be consistent: a table that exists on disk must be discoverable, and vice versa
- Partial failure of a single table must not corrupt or block the catalog for other tables
- No tolerance for silent data loss of catalog metadata

### Operational Requirements
- Engine startup to first-table-available: seconds
- Lazy/incremental table initialization: tables come online one at a time during recovery
- Table creation and deletion blocked during initial catalog loading phase
- Partial table failure isolated — does not affect engine or other tables
- Graceful rejection of traffic under overload; backpressure and per-table rate limiting/throttling
- Engine must never go down or slow to a crawl under high load
- High uptime for cluster; individual node failure degrades capacity but doesn't stop the engine

### Fit
- Java 25 (Amazon Corretto), JPMS modules
- Pure library — no external runtime dependencies
- Containerized deployment target
- Must compose with existing jlsm-core I/O patterns (NIO Path + SeekableByteChannel)
- Must not preclude future network protocol or cluster distribution layers

## Key Constraints (most narrowing)
1. **Lazy incremental recovery at 100K+ table scale** — the catalog must support discovering and enumerating tables without fully loading each one, enabling seconds-to-first-serve startup
2. **Per-table failure isolation** — a corrupt or unreadable table must not block catalog operations or affect other tables
3. **Resource-constrained containerized deployment** — catalog overhead (memory, disk, CPU) must be bounded and proportional, not requiring full materialization of all table metadata at once

## Unknown / Not Specified
None — full profile captured. Accuracy/correctness inferred from the requirement for consistency and failure isolation.
