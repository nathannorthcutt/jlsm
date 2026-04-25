---
title: "Database Catalog Persistence Patterns"
aliases: ["catalog metadata", "table registry persistence", "manifest patterns"]
topic: "systems"
category: "database-engines"
tags: ["catalog", "metadata", "persistence", "recovery", "manifest"]
complexity:
  time_build: "O(1) per table registration"
  time_query: "O(1) catalog lookup (hash map)"
  space: "O(n) where n = number of tables"
research_status: "mature"
last_researched: "2026-03-19"
applies_to: []
related:
  - "systems/database-engines/format-version-deprecation-strategies.md"
sources:
  - url: "https://github.com/facebook/rocksdb/wiki/MANIFEST"
    title: "RocksDB MANIFEST Wiki"
    accessed: "2026-03-19"
    type: "docs"
  - url: "https://deepwiki.com/tigerbeetle/tigerbeetle/2.4-superblock-and-persistence"
    title: "TigerBeetle SuperBlock and Persistence"
    accessed: "2026-03-19"
    type: "docs"
  - url: "https://olake.io/blog/2025/10/03/iceberg-metadata/"
    title: "Apache Iceberg Metadata Explained: Snapshots & Manifests"
    accessed: "2026-03-19"
    type: "blog"
---

# Database Catalog Persistence Patterns

## summary

Database engines must persist a registry of tables (names, schemas, metadata)
so they survive restarts and support recovery. Three dominant patterns exist:
(1) single append-only manifest log (RocksDB MANIFEST), (2) per-table metadata
directories with lightweight catalog index, and (3) hierarchical metadata with
atomic pointer swap (Apache Iceberg). The choice depends on table count, startup
latency requirements, failure isolation needs, and whether lazy/incremental
recovery is required.

## how-it-works

### pattern-1-append-only-manifest-log

Used by RocksDB and systems built on it (CockroachDB, TiKV). A single
append-only log file records all state changes as "Version Edit" records.

**Architecture:**
- `MANIFEST-{seqnum}` — rolling log files containing version edit records
- `CURRENT` — pointer file naming the active manifest log
- Version Edits record file additions, deletions, schema changes, metadata

**Recovery:** Read CURRENT → open manifest log → replay all version edits
sequentially to reconstruct full state. When manifest exceeds size threshold,
a new file is created with a complete state snapshot + subsequent edits.
Old manifests purged after CURRENT is atomically updated.

**Version Edit record format:**
```
+-------------+------ ......... ----------+
| Record ID   | Variable size record data |
+-------------+------ .......... ---------+
<-- Var32 --->|<-- varies by type       -->
```

Record types: comparator, log numbers, sequence info, column family config,
deleted files, new files (with level, size, key ranges), atomic groups.

### pattern-2-per-table-metadata-directories

Used by many embedded databases and multi-tenant systems. Each table has its
own subdirectory containing a metadata file (schema, config, creation time).
Discovery is via directory listing of the root path.

**Architecture:**
- `<root>/` — engine root directory
- `<root>/<table-name>/` — per-table directory
- `<root>/<table-name>/metadata.json` — table schema, config, creation timestamp
- `<root>/_catalog/` — optional lightweight index for fast enumeration

**Recovery:** List directories → for each, read metadata file → initialize
table lazily. Tables can come online independently. Corrupt table metadata
only affects that table.

**Optional catalog index:** A lightweight file listing table names and paths
for fast enumeration without directory scanning. Rebuilt from directory contents
if corrupted or missing (self-healing).

### pattern-3-hierarchical-metadata-pointer-swap

Used by Apache Iceberg and modern lakehouse formats. Metadata is organized in
a tree: catalog → metadata file → manifest list → manifest files → data files.
Atomic commits via pointer swap at the catalog level.

**Architecture:**
- Catalog: maps table names → current metadata file location (atomic CAS)
- Metadata file (metadata.json): immutable, versioned; contains schema,
  partition specs, snapshot history
- Manifest list: aggregates manifest files for a snapshot; stores partition
  boundary statistics
- Manifest files: index individual data files with per-file statistics

**Recovery:** Read catalog → follow pointer to current metadata file → already
consistent. No replay needed — each metadata file is a complete snapshot.

### pattern-4-superblock-with-reference-chains

Used by TigerBeetle. A fixed-location superblock with quad-redundancy stores
compact metadata pointers. Full state recovered by following reference chains
into grid storage.

**Architecture:**
- SuperBlock: 4-8 redundant copies at fixed file offset
- CheckpointState: references to manifest blocks, free set, client sessions
- Hash chain: `parent` field links sequential checkpoints, preventing regression

**Recovery:** Read all superblock copies → quorum resolution (highest sequence
with majority agreement) → follow manifest/grid references to reconstruct
state. Repairs minority copies after quorum selection.

### key-parameters

| Parameter | Description | Typical Range | Impact on Accuracy/Speed |
|-----------|-------------|---------------|--------------------------|
| Table count | Number of tables in catalog | 1–1M | Drives choice of pattern |
| Startup budget | Time to first table available | ms–minutes | Favors lazy patterns at scale |
| Failure isolation | Per-table vs global | per-table/global | Per-table requires pattern 2 or 3 |
| Write frequency | Catalog mutation rate | Low (DDL) | All patterns handle low DDL rate |
| Recovery model | Full replay vs lazy init | varies | Pattern 1 = full replay; 2,3 = lazy |

## algorithm-steps

### per-table-directory-with-catalog-index (recommended for high table count + lazy recovery)

1. **Engine open:** Read catalog index file if present; if absent or corrupt,
   fall back to directory listing of root path
2. **Build in-memory registry:** ConcurrentHashMap of table name → table handle
   (initially unloaded)
3. **Lazy table init:** On first access to a table, read its metadata.json,
   initialize WAL/MemTable/SSTable components, mark handle as loaded
4. **Table create:** Create subdirectory, write metadata.json, append entry to
   catalog index, add to in-memory registry
5. **Table drop:** Remove from in-memory registry, delete subdirectory
   (or mark as tombstoned), remove from catalog index
6. **Catalog index sync:** Periodically or on DDL, rewrite catalog index from
   in-memory registry state. Index is advisory — can always be rebuilt from
   directory scan

## implementation-notes

### data-structure-requirements

- **In-memory catalog:** ConcurrentHashMap for O(1) thread-safe lookups
- **Table handle:** Wrapper holding table name, schema, loaded/unloaded state,
  reference to underlying table instance (null until lazy-loaded)
- **Catalog index file:** Simple line-oriented or binary format listing
  (name, path, schema-version) tuples for fast enumeration

### edge-cases-and-gotchas

- **Directory listing at 100K+ entries:** Some filesystems degrade with very
  large directory listings. Sharding into subdirectories (e.g., by hash prefix
  of table name) mitigates this. Ext4 with `dir_index` handles ~10M entries;
  XFS scales better.
- **Partial table creation:** If the process crashes between creating the
  directory and writing metadata.json, the table directory exists but is
  invalid. Recovery must detect and clean up incomplete tables (directory
  exists but no valid metadata.json).
- **Concurrent DDL:** Table creation and deletion must be serialized or use
  CAS-style operations to prevent races (two threads creating the same table).
- **Catalog index corruption:** The index is advisory. If corrupted, rebuild
  from directory scan. Never trust the index as the sole source of truth —
  the directory structure is authoritative.

## complexity-analysis

### build-phase

| Pattern | Startup Cost | Notes |
|---------|-------------|-------|
| Manifest log | O(edits) full replay | Must replay entire log before serving |
| Per-table dirs | O(n) directory list | Lazy: O(1) per table on first access |
| Pointer swap | O(1) catalog read | Metadata file is pre-resolved snapshot |
| SuperBlock | O(copies) quorum read | Fast fixed-location read + reference follow |

### query-phase

All patterns: O(1) catalog lookup after initialization (in-memory hash map).

### memory-footprint

| Pattern | Catalog Memory | Notes |
|---------|---------------|-------|
| Manifest log | O(total state) | Full state materialized on startup |
| Per-table dirs | O(n × handle_size) | Handle is lightweight until loaded |
| Pointer swap | O(n × metadata_size) | Full metadata per table |
| SuperBlock | O(references) | Compact; data in grid |

At 100K tables with ~200 bytes per lightweight handle: ~20 MB catalog overhead.

## tradeoffs

### strengths

**Pattern 1 (Manifest log):**
- Atomic multi-table operations (atomic groups)
- Complete audit trail of all state changes
- Well-proven by RocksDB ecosystem

**Pattern 2 (Per-table directories):**
- Natural failure isolation — corrupt table doesn't affect catalog
- Lazy/incremental recovery — tables come online independently
- Self-healing — catalog index rebuildable from directory structure
- Simple to reason about and debug (each table is a visible directory)
- Compatible with remote/object storage (directory = prefix)

**Pattern 3 (Pointer swap):**
- Truly atomic commits via CAS
- No replay needed — instant recovery
- Excellent for immutable/append-only workloads

**Pattern 4 (SuperBlock):**
- Extremely fast recovery (fixed location, quorum)
- Crash-safe by design (quad-redundancy)
- Compact metadata footprint

### weaknesses

**Pattern 1 (Manifest log):**
- Full replay required on startup — latency grows with history
- Single point of failure — corrupt manifest blocks all tables
- Not naturally lazy — all state must be materialized

**Pattern 2 (Per-table directories):**
- No atomic multi-table operations without external coordination
- Directory listing can be slow on some filesystems at extreme scale
- Catalog index is advisory (dual source of truth)

**Pattern 3 (Pointer swap):**
- Requires external catalog service for CAS
- Each metadata file is a full snapshot — write amplification for DDL
- Complex to implement correctly with concurrent writers

**Pattern 4 (SuperBlock):**
- Fixed-location assumption doesn't suit all storage backends
- Quad-redundancy has storage overhead
- Complex quorum resolution logic

### compared-to-alternatives

- Pattern 2 vs Pattern 1: Per-table dirs trade atomic multi-table ops for
  failure isolation and lazy recovery — strong win at 100K+ tables
- Pattern 2 vs Pattern 3: Per-table dirs are simpler and don't need an
  external catalog service; pointer swap wins for concurrent-writer scenarios
- Pattern 1 vs Pattern 4: SuperBlock is faster to recover but more complex
  to implement and assumes fixed storage layout

## current-research

### key-papers

- Dong et al. "RocksDB: Evolution of Development Priorities in a Key-Value
  Store Serving Large-scale Applications." ACM TODS, 2021.
- "Apache Iceberg: An Open Table Format for Huge Analytic Datasets."
  iceberg.apache.org/spec/

### active-research-directions

- Hybrid approaches: lightweight manifest for DDL + per-table metadata for
  data-path state, combining atomic DDL with lazy data recovery
- Object-storage-native catalogs that avoid filesystem directory listing
  entirely (prefix-scan + marker files)

## practical-usage

### when-to-use

**Pattern 2 (Per-table directories)** when:
- Table count is high (10K+)
- Startup latency must be seconds, not minutes
- Per-table failure isolation is required
- Tables are independently managed (no cross-table atomic DDL)
- Future clustering requires per-table migration/rebalancing

**Pattern 1 (Manifest log)** when:
- Table count is moderate (<1K)
- Atomic multi-table operations are required
- Full state must be consistent before serving any request

### when-not-to-use

- Pattern 2: When atomic cross-table DDL is mandatory
- Pattern 1: When 100K+ tables need lazy recovery
- Pattern 3: When no external catalog service is available
- Pattern 4: When storage backend doesn't support fixed-location writes

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| RocksDB | C++ | github.com/facebook/rocksdb | Active (Meta) |
| TigerBeetle | Zig | github.com/tigerbeetle/tigerbeetle | Active |
| Apache Iceberg | Java | github.com/apache/iceberg | Active (ASF) |

## code-skeleton

```java
// Pattern 2: Per-table directory with catalog index
class TableCatalog implements Closeable {
    private final Path rootDir;
    private final ConcurrentHashMap<String, TableHandle> tables;

    TableCatalog open(Path rootDir) {
        // 1. Try reading catalog index; fall back to directory scan
        // 2. Build lightweight handle map (no table data loaded)
        // 3. Return catalog ready for lazy table access
    }

    TableHandle createTable(String name, Schema schema) {
        // 1. Validate name uniqueness (CAS in map)
        // 2. Create subdirectory: rootDir/name/
        // 3. Write metadata.json (schema, created timestamp)
        // 4. Append to catalog index
        // 5. Return handle (loaded, ready to accept writes)
    }

    TableHandle getTable(String name) {
        // 1. Lookup in ConcurrentHashMap
        // 2. If unloaded: lazy-init (read metadata, open WAL/MemTable/SSTable)
        // 3. Return loaded handle
    }

    void dropTable(String name) {
        // 1. Remove from map
        // 2. Close table resources
        // 3. Delete subdirectory (or tombstone)
        // 4. Update catalog index
    }
}
```

## sources

1. [RocksDB MANIFEST Wiki](https://github.com/facebook/rocksdb/wiki/MANIFEST) — authoritative documentation of RocksDB's manifest-based catalog persistence, version edit record format, and recovery protocol
2. [TigerBeetle SuperBlock and Persistence](https://deepwiki.com/tigerbeetle/tigerbeetle/2.4-superblock-and-persistence) — detailed analysis of quad-redundant superblock pattern with quorum recovery and hash-chain integrity
3. [Apache Iceberg Metadata Explained](https://olake.io/blog/2025/10/03/iceberg-metadata/) — hierarchical metadata architecture with atomic pointer swap commits and manifest-based file tracking

---
*Researched: 2026-03-19 | Next review: 2026-09-19*
