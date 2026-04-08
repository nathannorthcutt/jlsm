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
| serialization | domains/serialization/ | encoding decoding sstable document serializer binary format footer | 3 |
| storage | domains/storage/ | memtable wal flush compaction object-store block cache manifest | 1 |
| encryption | domains/encryption/ | aes gcm kms tmk sek key-derivation cipher block-encryption | 1 |
| query | domains/query/ | sql query plan statistics join index scan filter | 2 |
| engine | domains/engine/ | database engine table catalog schema partition | 5 |

## Recently Added (last 10)

| Date | ID | Domain | Title |
|------|-----|--------|-------|
| 2026-04-02 | F14 | engine | JlsmDocument (extracted) |
| 2026-04-02 | F13 | engine | JlsmSchema (extracted) |
| 2026-04-02 | F12 | vector-indexing | Vector Field Type |
| 2026-04-02 | F11 | engine | Table Partitioning |
| 2026-04-02 | F10 | query | Table Indices and Queries |
| 2026-04-02 | F09 | storage | Striped Block Cache |
| 2026-04-02 | F08 | serialization | Streaming Block Decompression |
| 2026-04-02 | F07 | query | SQL Query Support |
| 2026-04-02 | F05 | engine | In-Process Database Engine |
| 2026-04-02 | F04 | engine | Engine Clustering |
| 2026-04-02 | F03 | encryption | Field-Level In-Memory Encryption |

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
