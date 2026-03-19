---
problem: "table-catalog-persistence"
date: "2026-03-19"
version: 1
status: "confirmed"
supersedes: null
files: []
---

# ADR — Table Catalog Persistence

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Catalog Persistence Patterns | All candidates evaluated from this entry | [`.kb/systems/database-engines/catalog-persistence-patterns.md`](../../.kb/systems/database-engines/catalog-persistence-patterns.md) |

---

## Files Constrained by This Decision
<!-- Key source files this decision affects. Populated as engine module is created. -->

## Problem
How should the jlsm-engine persist its table registry (names, schemas, metadata) so that tables survive engine restarts, support lazy incremental recovery, isolate per-table failures, and remain compatible with future clustered distribution?

## Constraints That Drove This Decision
- **Lazy incremental recovery at 100K+ tables**: The catalog must support discovering and enumerating tables without fully loading each one, enabling seconds-to-first-serve startup
- **Per-table failure isolation**: A corrupt or unreadable table must not block catalog operations or affect other tables
- **Resource-constrained containers**: Catalog overhead must be bounded and proportional — no full materialization of all table metadata at once

## Decision
**Chosen approach: [Per-Table Metadata Directories](../../.kb/systems/database-engines/catalog-persistence-patterns.md#pattern-2-per-table-metadata-directories)**

Each table gets its own subdirectory under the engine's root directory, containing a metadata file (schema, config, creation timestamp). Table discovery is via directory listing of the root path. An optional lightweight catalog index file enables fast enumeration without directory scanning; it is advisory and can be rebuilt from the directory structure if corrupted. Tables are lazily initialized on first access — the in-memory catalog holds lightweight handles (~200 bytes) until a table is actually used.

## Rationale

### Why Per-Table Metadata Directories
- **Scale (100K+ tables):** O(n) directory listing for discovery, O(1) per-table lazy init. Hash-prefix subdirectory sharding available at extreme scale. ~20 MB memory for 100K lightweight handles.
- **Operational (seconds-to-first-serve):** Tables come online independently during recovery. First tables available immediately; rest load incrementally. Creation/deletion blocked only during initial catalog loading phase.
- **Failure isolation:** Corrupt table metadata only affects that table. Directory structure is authoritative and self-healing — catalog index rebuilt from directory scan if corrupted.
- **Fit (clustering):** Per-table directories are the natural unit of distribution. Table migration between cluster nodes is "move the directory." No shared mutable catalog state requiring distributed coordination.

### Why not [Append-Only Manifest Log (RocksDB-style)](../../.kb/systems/database-engines/catalog-persistence-patterns.md#pattern-1-append-only-manifest-log)
- **Operational disqualifier:** Must replay entire edit history before serving any table — violates seconds-to-first-serve at 100K+ scale. Corrupt manifest blocks all tables.

### Why not [Hierarchical Metadata Pointer Swap (Iceberg-style)](../../.kb/systems/database-engines/catalog-persistence-patterns.md#pattern-3-hierarchical-metadata-pointer-swap)
- **Fit disqualifier:** Requires external catalog service for atomic CAS operations — violates "no external runtime dependencies" project constraint.

### Why not [SuperBlock with Reference Chains (TigerBeetle-style)](../../.kb/systems/database-engines/catalog-persistence-patterns.md#pattern-4-superblock-with-reference-chains)
- **Fit disqualifier:** Fixed-location storage assumption incompatible with object storage backends. Over-complex for catalog persistence (designed for consensus metadata).

## Implementation Guidance
Key parameters from [`catalog-persistence-patterns.md#key-parameters`](../../.kb/systems/database-engines/catalog-persistence-patterns.md#key-parameters):
- Table count: design for 100K+; consider hash-prefix sharding if directory listing becomes a bottleneck
- Catalog index: advisory file for fast enumeration; directory structure is always authoritative
- Handle memory: ~200 bytes per unloaded table handle; budget ~20 MB at 100K tables

Known edge cases from [`catalog-persistence-patterns.md#edge-cases-and-gotchas`](../../.kb/systems/database-engines/catalog-persistence-patterns.md#edge-cases-and-gotchas):
- Partial table creation: crash between directory creation and metadata write → recovery must detect and clean up incomplete tables (directory exists but no valid metadata file)
- Concurrent DDL: table creation and deletion must be serialized or use CAS-style operations in the ConcurrentHashMap
- Filesystem directory listing at extreme scale: ext4 with dir_index handles ~10M entries; XFS scales better; object storage uses prefix scan

Clustering considerations (future, does not block current implementation):
- Engine API (createTable/dropTable) should be structured so a future cluster layer can intercept and route through consensus
- Per-table directories map naturally to table migration between nodes
- A cluster-level catalog service (table name → owning node) layers on top of the per-node local catalog

Full implementation detail: [`.kb/systems/database-engines/catalog-persistence-patterns.md`](../../.kb/systems/database-engines/catalog-persistence-patterns.md)
Code scaffold: [`catalog-persistence-patterns.md#code-skeleton`](../../.kb/systems/database-engines/catalog-persistence-patterns.md#code-skeleton)

## What This Decision Does NOT Solve
- Atomic multi-table DDL operations (create table A and drop table B atomically)
- Cross-table transaction coordination
- Catalog replication to other cluster nodes (future cluster work)
- Table migration protocol between cluster nodes (future cluster work)

## Conditions for Revision
This ADR should be re-evaluated if:
- Atomic cross-table DDL becomes a requirement
- Directory listing performance becomes a bottleneck at >1M tables despite hash-prefix sharding
- A distributed catalog service is introduced for clustering that could subsume local catalog persistence
- Review at 6-month mark regardless

---
*Confirmed by: user deliberation | Date: 2026-03-19*
*Full scoring: [evaluation.md](evaluation.md)*
