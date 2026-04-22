# Specifications — Root Index

> **Managed by vallorcine agents. Use slash commands to modify this file.**
> To bootstrap: `/spec-init`
> To resolve context: `/spec-resolve "<feature description>"`
> To author a spec: `/spec-write "<id>" "<title>"`
> To verify a spec: `/spec-verify "<id>"`

> Pull model. Agents resolve specs via `spec-resolve.sh`, not by scanning.
> Do not read `.spec/` recursively. Use the resolver for context bundles.
> Structure: .spec/domains/<domain>/<spec>.md

## Domain Taxonomy

| Domain | Path | Description | Specs |
|--------|------|-------------|-------|
| vector-indexing | domains/vector-indexing/ | vector search hnsw ivf-flat ann similarity float16 precision encoding | 2 |
| serialization | domains/serialization/ | encoding decoding sstable document serializer binary format footer integrity checksum varint | 6 |
| storage | domains/storage/ | memtable wal flush compaction object-store block cache manifest block-size byte-budget | 4 |
| encryption | domains/encryption/ | aes gcm kms tmk sek key-derivation cipher block-encryption sdk prefix fuzzy | 6 |
| query | domains/query/ | sql query plan statistics join index scan filter | 2 |
| engine | domains/engine/ | database engine table catalog schema partition transaction | 15 |
| cluster-membership | domains/cluster-membership/ | membership protocol discovery rediscovery health stall recovery slow-node detection heartbeat metadata | 2 |
| cluster-transport | domains/cluster-transport/ | transport framing multiplexing connection NIO TCP wire-protocol stream-id backpressure | 3 |
| partitioning | domains/partitioning/ | partition rebalancing ownership transfer drain catch-up epoch state-machine safety trigger policy weighted capacity affinity takeover priority throttling hotspot compaction-scheduling vector-pruning cross-partition transactions replication raft consensus leader-election quorum snapshot migration cutover rollback write-distributor prefix-hash corruption repair quarantine scrubbing anti-entropy | 9 |

## Recently Added (last 10)

| Date | ID | Domain | Title |
|------|-----|--------|-------|
| 2026-04-22 | sstable.pool-aware-block-size | sstable | Pool-Aware Block Size Configuration (v4 APPROVED) |
| 2026-04-15 | F48 | partitioning, storage | Corruption Repair and Recovery |
| 2026-04-15 | F47 | encryption | Encrypted Fuzzy Matcher |
| 2026-04-15 | F46 | encryption | Encrypted Prefix Index |
| 2026-04-15 | F45 | encryption, engine | Client-Side Encryption SDK |
| 2026-04-15 | F44 | engine | Scan Lease GC Watermark |
| 2026-04-15 | F43 | partitioning | Sequential Insert Hotspot Mitigation |
| 2026-04-14 | F42 | encryption, storage | WAL Encryption |
| 2026-04-14 | F41 | encryption, engine | Encryption Lifecycle |
| 2026-04-14 | F40 | engine | Distributed Join Strategy |
| 2026-04-14 | F39 | engine | Distributed Pagination |
| 2026-04-14 | F38 | engine | Aggregation Query Merge |
| 2026-04-14 | F37 | engine | Catalog Operations |
| 2026-04-14 | F36 | engine | Remote Serialization |
| 2026-04-14 | F35 | engine | Cross-Table Transactions |
| 2026-04-14 | F32 | partitioning | Partition Replication |
| 2026-04-14 | F31 | partitioning | Cross-Partition Transactions |
| 2026-04-14 | F30 | partitioning | Partition Data Operations |
| 2026-04-14 | F29 | partitioning | Rebalancing Operations |
| 2026-04-14 | F28 | partitioning | Rebalancing Policy |
| 2026-04-14 | F27 | partitioning | Rebalancing Safety |
| 2026-04-14 | F26 | serialization, storage | SSTable End-to-End Integrity |
| 2026-04-14 | F25 | storage | Byte-Budget Block Cache |
| 2026-04-14 | F24 | storage | Pool-Aware Block Size Configuration |
| 2026-04-14 | F23 | cluster-membership | Cluster Health & Recovery |
| 2026-04-14 | F22 | cluster-membership | Continuous Rediscovery |
| 2026-04-14 | F21 | cluster-transport | Scatter-Gather Flow Control |
| 2026-04-14 | F20 | cluster-transport | Transport Traffic Priority |
| 2026-04-14 | F19 | cluster-transport | Multiplexed Transport Framing |
| 2026-04-12 | F18 | serialization, storage | ZSTD Dictionary Compression with Per-Level Codec Policy |
| 2026-04-12 | F17 | serialization, storage | WAL Compression with MemorySegment Codec API |
| 2026-04-11 | F16 | serialization, storage | SSTable v3 Format Upgrade |
| 2026-04-10 | F15 | serialization, engine | JSON-Only SIMD On-Demand Parser with JSONL Streaming |
| 2026-04-02 | F14 | engine | JlsmDocument (extracted) |
| 2026-04-02 | F13 | engine | JlsmSchema (extracted) |
| 2026-04-02 | F12 | vector-indexing | Vector Field Type |
| 2026-04-02 | F11 | engine | Table Partitioning |
| 2026-04-02 | F10 | query | Table Indices and Queries |
| 2026-04-02 | F09 | storage | Striped Block Cache |
| 2026-04-02 | F08 | serialization | Streaming Block Decompression |
| 2026-04-02 | F07 | query | SQL Query Support |
| 2026-04-02 | F05 | engine | In-Process Database Engine |

## Spec File Format Reference

Spec files use JSON front matter (between `---` delimiters), a machine-readable
requirements section, and a human narrative section separated by a bare `---` line.

```
---
{ "id": "F01", "version": 1, "status": "ACTIVE", "state": "DRAFT",
  "domains": [...], "requires": [...], "invalidates": [...],
  "decision_refs": [...], "kb_refs": [...], ... }
---

# F01 — Title

## Requirements
R1. Single falsifiable claim with explicit subject.
R2. ...

---

## Design Narrative
...
```

**Front matter fields:**
- `id` — feature identifier (F01, F02, ...)
- `version` — integer, incremented on revision
- `status` — lifecycle: ACTIVE | STABLE | DEPRECATED
- `state` — verification: DRAFT | APPROVED | INVALIDATED
- `domains` — array of domain slugs this spec belongs to
- `amends` / `amended_by` — cross-feature amendment links
- `requires` — feature IDs this spec depends on at runtime
- `invalidates` — specific FXX.RN references this spec supersedes
- `decision_refs` — ADR slugs from .decisions/ (cross-reference, not duplication)
- `kb_refs` — KB paths from .kb/ (topic/category/subject)
- `open_obligations` — work items that must be addressed

**Requirement writing rules:**
- One falsifiable claim per requirement
- Explicit subject: "The MemTable must..." not "Must..."
- Measurable condition where applicable
- No compound requirements (no "and" joining two obligations)
- Present tense, active voice
- Unverified claims annotated: `[UNVERIFIED: assumes X]`

**Registry:** `.spec/registry/manifest.json` — machine-readable index.
**Obligations:** `.spec/registry/_obligations.json` — cross-feature work items.
