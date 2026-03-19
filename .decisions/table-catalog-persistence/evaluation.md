---
problem: "table-catalog-persistence"
evaluated: "2026-03-19"
candidates:
  - path: ".kb/systems/database-engines/catalog-persistence-patterns.md#pattern-1-append-only-manifest-log"
    name: "Append-Only Manifest Log (RocksDB-style)"
  - path: ".kb/systems/database-engines/catalog-persistence-patterns.md#pattern-2-per-table-metadata-directories"
    name: "Per-Table Metadata Directories"
  - path: ".kb/systems/database-engines/catalog-persistence-patterns.md#pattern-3-hierarchical-metadata-pointer-swap"
    name: "Hierarchical Metadata Pointer Swap (Iceberg-style)"
  - path: ".kb/systems/database-engines/catalog-persistence-patterns.md#pattern-4-superblock-with-reference-chains"
    name: "SuperBlock with Reference Chains (TigerBeetle-style)"
constraint_weights:
  scale: 3
  resources: 2
  complexity: 1
  accuracy: 2
  operational: 3
  fit: 2
---

# Evaluation — table-catalog-persistence

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used: see candidate sections below

## Constraint Summary
The engine must manage 100K+ tables in a resource-constrained container, with
seconds-to-first-table startup via lazy incremental recovery. Per-table failure
isolation is mandatory — a corrupt table cannot block the engine or other tables.
The solution must compose with Java NIO Path/SeekableByteChannel I/O and not
preclude future clustering where tables migrate between nodes.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 3 | 100K+ tables is the defining characteristic; drives all structural choices |
| Resources | 2 | Containerized with limited memory/disk but not the primary differentiator between patterns |
| Complexity | 1 | Expert team; complex solutions acceptable if justified |
| Accuracy | 2 | Catalog consistency required but all patterns can achieve it |
| Operational | 3 | Seconds-to-first-serve + per-table failure isolation are hard requirements |
| Fit | 2 | Java 25 + NIO compatibility needed but not a strong differentiator |

---

## Candidate: Append-Only Manifest Log (RocksDB-style)

**KB source:** [`.kb/systems/database-engines/catalog-persistence-patterns.md`](../../.kb/systems/database-engines/catalog-persistence-patterns.md)
**Relevant sections read:** `#pattern-1-append-only-manifest-log`, `#complexity-analysis`, `#tradeoffs`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 3 | 2 | 6 | Full replay of all edits required at startup; O(edits) startup cost grows with table count |
| Resources | 2 | 3 | 6 | Full state materialized on startup — all table metadata in memory |
| Complexity | 1 | 4 | 4 | Well-proven pattern (RocksDB ecosystem); version edit format is well-documented |
| Accuracy | 2 | 5 | 10 | Atomic multi-table operations via atomic groups; complete audit trail |
| Operational | 3 | 1 | 3 | Cannot serve until full replay completes; single manifest corruption blocks ALL tables |
| Fit | 2 | 4 | 8 | Straightforward to implement in Java NIO; append-only log is natural for SeekableByteChannel |
| **Total** | | | **37** | |

**Hard disqualifiers:** Full replay startup at 100K+ tables violates the seconds-to-first-serve requirement. Single manifest corruption blocking all tables violates per-table failure isolation.

**Key strengths for this problem:**
- Atomic multi-table operations (useful but not required)
- Complete audit trail of all state changes

**Key weaknesses for this problem:**
- Cannot lazy-load — must replay entire history before serving
- Single point of failure — corrupt manifest blocks all tables

---

## Candidate: Per-Table Metadata Directories

**KB source:** [`.kb/systems/database-engines/catalog-persistence-patterns.md`](../../.kb/systems/database-engines/catalog-persistence-patterns.md)
**Relevant sections read:** `#pattern-2-per-table-metadata-directories`, `#algorithm-steps`, `#complexity-analysis`, `#tradeoffs`, `#edge-cases-and-gotchas`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 3 | 5 | 15 | O(n) directory list for discovery, O(1) per-table lazy init; optional hash-prefix sharding for extreme scale |
| Resources | 2 | 5 | 10 | Lightweight handles (~200 bytes each) until loaded; ~20 MB for 100K tables |
| Complexity | 1 | 5 | 5 | Simple directory + JSON metadata; easy to reason about and debug |
| Accuracy | 2 | 4 | 8 | Directory structure is authoritative; catalog index is advisory and self-healing. No atomic multi-table DDL. |
| Operational | 3 | 5 | 15 | Lazy/incremental recovery; per-table failure isolation; tables come online independently |
| Fit | 2 | 5 | 10 | Natural fit for Java NIO Path; compatible with remote/object storage (directory = prefix); per-table dirs map directly to future cluster rebalancing |
| **Total** | | | **63** | |

**Hard disqualifiers:** None.

**Key strengths for this problem:**
- **Lazy recovery:** Tables come online one at a time, seconds to first serve
- **Failure isolation:** Corrupt table metadata only affects that table
- **Self-healing catalog:** Index rebuildable from directory scan if corrupted
- **Cluster-ready:** Per-table directories map naturally to per-table migration

**Key weaknesses for this problem:**
- No atomic multi-table DDL without external coordination (not required per brief)
- Advisory catalog index is a dual source of truth (mitigated by self-healing rebuild)

---

## Candidate: Hierarchical Metadata Pointer Swap (Iceberg-style)

**KB source:** [`.kb/systems/database-engines/catalog-persistence-patterns.md`](../../.kb/systems/database-engines/catalog-persistence-patterns.md)
**Relevant sections read:** `#pattern-3-hierarchical-metadata-pointer-swap`, `#complexity-analysis`, `#tradeoffs`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 3 | 4 | 12 | O(1) catalog read; designed for large datasets. But each metadata file is a full snapshot — DDL creates new file. |
| Resources | 2 | 3 | 6 | Full metadata per table in memory; metadata files are immutable snapshots (write amplification on DDL) |
| Complexity | 1 | 2 | 2 | Requires external catalog service for CAS; complex concurrent writer handling |
| Accuracy | 2 | 5 | 10 | Truly atomic commits via CAS; no replay needed |
| Operational | 3 | 4 | 12 | Instant recovery (no replay); but requires external catalog service for pointer management |
| Fit | 2 | 2 | 4 | External catalog service dependency conflicts with "pure library, no external runtime dependencies" |
| **Total** | | | **46** | |

**Hard disqualifiers:** Requires external catalog service for CAS operations — violates "no external runtime dependencies" constraint from project profile.

**Key strengths for this problem:**
- Truly atomic commits and instant recovery
- Designed for large-scale immutable workloads

**Key weaknesses for this problem:**
- External catalog service dependency breaks pure-library constraint
- Write amplification on DDL (full metadata file rewrite per table change)
- Over-engineered for a table registry use case (designed for data file tracking)

---

## Candidate: SuperBlock with Reference Chains (TigerBeetle-style)

**KB source:** [`.kb/systems/database-engines/catalog-persistence-patterns.md`](../../.kb/systems/database-engines/catalog-persistence-patterns.md)
**Relevant sections read:** `#pattern-4-superblock-with-reference-chains`, `#complexity-analysis`, `#tradeoffs`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 3 | 3 | 9 | Compact superblock + reference chains; but fixed-location assumption may not suit all backends |
| Resources | 2 | 4 | 8 | Compact metadata footprint; data in grid storage |
| Complexity | 1 | 2 | 2 | Complex quorum resolution; quad-redundancy logic; hash chain verification |
| Accuracy | 2 | 5 | 10 | Quad-redundancy with quorum; hash chain prevents regression |
| Operational | 3 | 3 | 9 | Fast fixed-location read; but still follows reference chains to reconstruct full state |
| Fit | 2 | 2 | 4 | Fixed-location writes don't suit object storage/remote backends; complex Zig-style patterns less natural in Java |
| **Total** | | | **42** | |

**Hard disqualifiers:** Fixed-location storage assumption conflicts with object storage compatibility requirement.

**Key strengths for this problem:**
- Extremely crash-safe (quad-redundancy)
- Fast superblock read at fixed offset

**Key weaknesses for this problem:**
- Fixed-location assumption incompatible with object storage backends
- Over-complex for catalog persistence (designed for consensus metadata)
- Must still follow reference chains — not truly lazy per-table

---

## Comparison Matrix

| Candidate | KB Source | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| [Manifest Log](../../.kb/systems/database-engines/catalog-persistence-patterns.md#pattern-1-append-only-manifest-log) | 6 | 6 | 4 | 10 | 3 | 8 | **37** |
| [Per-Table Dirs](../../.kb/systems/database-engines/catalog-persistence-patterns.md#pattern-2-per-table-metadata-directories) | 15 | 10 | 5 | 8 | 15 | 10 | **63** |
| [Pointer Swap](../../.kb/systems/database-engines/catalog-persistence-patterns.md#pattern-3-hierarchical-metadata-pointer-swap) | 12 | 6 | 2 | 10 | 12 | 4 | **46** |
| [SuperBlock](../../.kb/systems/database-engines/catalog-persistence-patterns.md#pattern-4-superblock-with-reference-chains) | 9 | 8 | 2 | 10 | 9 | 4 | **42** |

## Preliminary Recommendation
Per-Table Metadata Directories wins decisively (63 vs 46 for the runner-up) — it is the only pattern that satisfies all three binding constraints: lazy incremental recovery at 100K+ scale, per-table failure isolation, and resource-constrained container deployment without external dependencies.

## Risks and Open Questions
- **Filesystem directory listing at extreme scale:** At 100K+ entries, directory listing performance depends on filesystem (ext4 with dir_index is fine; NFS may not be). Mitigation: hash-prefix subdirectory sharding.
- **Catalog index consistency:** The advisory index is a second source of truth. Mitigation: directory structure is always authoritative; index is rebuilt on corruption.
- **No atomic multi-table DDL:** If future requirements need atomic cross-table operations, this pattern would need augmentation. Current brief explicitly excludes cross-table transactions.
