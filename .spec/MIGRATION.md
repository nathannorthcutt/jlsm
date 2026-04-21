# Spec Migration Plan — Feature-Centric → Behavioral-Domain

**Status:** DRAFT — awaiting review before execution
**Created:** 2026-04-20
**Scope:** One-time reorganization of `.spec/domains/` from 48 feature-centric specs (FXX) under 9 ad-hoc domains → ~60 behavioral-domain specs under 12 canonical domains.

> **This document is itself temporary.** Once execution completes and the repo is clean, delete `.spec/MIGRATION.md`. While migration is in progress it is the source of truth for every spec file's destination.

---

## 1. Why migrate

The current layout puts specs into domain directories but keeps feature IDs (`F13-jlsm-schema.md`) as the primary identity. This has three recurring pain points:

1. **Research per domain is imprecise.** A domain like `engine/` holds 13 features (1027 requirements) spanning schema, lifecycle, transactions, and distributed query. "Research the engine" has no sharp scope.
2. **Large features are monolithic.** F13 (59 reqs, 13 natural sub-topics), F02 (51 reqs, 19 sub-topics), F17 (42 reqs, 11 sub-topics), F14 (69 reqs) are each single files that should be 4-8 smaller specs mirroring the KB article pattern.
3. **Cross-cutting contracts are duplicated.** F02 and F17 both describe the `CompressionCodec` contract. Today they co-exist in `serialization/` with subtle divergences. Post-migration, compression contract lives in one spec; F02/F17 reference it.

After migration:
- Specs are named by *what they constrain*, not *when they were added*.
- `@spec` annotations use `domain.slug.RN` — self-describing, greppable, dimensional.
- Kit tooling (`spec-resolve`, `spec-trace`, `spec-author`, `spec-verify`) operates on stable domain paths instead of feature IDs.

## 2. Current state (as of 2026-04-20)

**48 specs across 9 directories:**

| Domain | Specs | Requirements |
|---|---|---|
| cluster-membership | 2 | 74 |
| cluster-transport | 3 | 132 |
| encryption | 6 | 381 |
| engine | 13 | 1027 |
| partitioning | 9 | 513 |
| query | 2 | 242 |
| serialization | 9 | 347 |
| storage | 3 | 91 |
| vector-indexing | 2 | 90 |
| **Total** | **48** | **2897** |

**802 unique `(FXX.RN)` pairs** annotated across 1534 occurrences in `modules/`.

## 3. Target state

**12 canonical domains:**

| Domain | Scope — what it constrains |
|---|---|
| `schema` | Field types, document structure, schema construction, field lookup, nesting, immutability |
| `serialization` | Document encoders/decoders, wire formats, remote serialization, JSONL |
| `compression` | Codec contract, codec implementations, dictionary training, streaming decompression |
| `sstable` | On-disk block format, block cache, pool-aware buffers, sstable writer/reader, integrity |
| `wal` | Write-ahead log format, record encoding, recovery, compression integration |
| `vector` | Vector field types, float16/float32, similarity functions |
| `query` | SQL engine, query planning, full-text + vector + scan indices, aggregation, pagination, joins |
| `encryption` | Field-level encryption variants, key lifecycle, encrypted indices, client SDK, WAL encryption |
| `engine` | In-process database engine lifecycle, table partitioning, cross-table transactions, catalog, handles |
| `partitioning` | Partition data ops, rebalancing, partition replication, migration, corruption repair |
| `transport` | Multiplexed framing, traffic priority, scatter-gather, flow control |
| `membership` | Cluster discovery, continuous rediscovery, health, fault tolerance, recovery |

**Spec identity format change:**
- Filename: `storage/F09-striped-block-cache.md` → `sstable/striped-block-cache.md`
- Frontmatter id: `"F09"` → `"sstable.striped-block-cache"`
- Annotation: `@spec F09.R7` → `@spec sstable.striped-block-cache.R7`

Projected spec count: **~60 files** (48 existing + ~12 from splits).

## 4. Migration principles

**P1 — No requirement is lost or duplicated.** Every `(FXX.RN)` pair in the current repo maps to exactly one `(domain.slug.RN)` pair in the new repo. The mapping is invertible — scripts validate round-trip.

**P2 — Backups are mandatory.** Before any file is deleted, the original spec is copied to `.spec/_archive/migration-<date>/FXX.md`. This survives the migration commit and is removed only after post-migration validation passes.

**P3 — Every requirement has a declared destination.** Every feature gets a `.spec/_migration/FXX.map` file enumerating each original requirement's destination `(domain, slug, new-RN)`. The "simple move" is the degenerate case where all reqs map to one destination; the "split" is the general case where reqs distribute across multiple destinations (possibly across multiple domains). The migration moves **individual requirements**, not features-as-units — a feature that mixes primitive and application-specific reqs (e.g., F42 WAL encryption = WAL-specific behavior + general nonce-safety primitives) distributes its reqs across domains. No script guesses — it reads the map.

**P6 — Cross-domain associations are captured in two places: spec frontmatter and code annotations.**

*Spec frontmatter (`domains: [...]`).* Any spec whose behavior is relevant to more than one domain lists every applicable domain in its frontmatter `domains` field. This is a **general rule applied throughout migration**, not a case-by-case exception. Primary domain drives file location; secondary domains ensure discovery searches surface the spec from either direction, and spec-conflict detection flags contradictions regardless of which domain a conflicting new spec is authored in. Examples after migration: `sstable/striped-block-cache.md` → `["sstable", "compression"]` (compression integration); `sstable/writer.md` → `["sstable", "compression"]`; `wal/compression.md` → `["wal", "compression"]`; `query/encrypted-prefix-index.md` → `["query", "encryption"]`; `wal/encryption.md` → `["wal", "encryption"]`. Existing precedent: F10 `["query", "engine"]`, F42 `["wal", "encryption"]`.

*Code annotations (`@spec` reverse index).* Cross-domain associations are also directly visible in source code via `@spec` annotations. A code region that enforces multiple specs (e.g., `StripedBlockCache.java` with `@spec sstable.striped-block-cache.R5` *and* `@spec compression.codec-contract.R8` annotations) signals its cross-cutting nature without requiring the reader to consult spec frontmatter. `spec-trace.sh` provides the authoritative reverse lookup (code-region → specs). Migration preserves this property — the annotation rewrite (step 4) maintains one-to-one correspondence between code enforcement points and their specs.

Together, frontmatter tags give spec-centric discovery (find all specs touching domain X) and annotations give code-centric discovery (find all specs enforced at this code region).

**P4 — Execution is fully scripted.** Migration scripts commit no judgment. They read the mapping tables in this document and the `.map` files, then apply mechanical transformations. Re-runnable and reversible.

**P5 — One PR per stage, not one mega-commit.** Stage 3 execution lands as several commits (spec generation, annotation rewrite, cross-ref rewrite, validation, cleanup) so a broken stage doesn't corrupt everything.

## 5. Default destinations per feature

The table below lists each feature's **default destination** — the domain where most or all of its requirements are expected to land. Every feature still gets a `.map` file (§8) that assigns each requirement individually. For features where all requirements land in the default destination, the `.map` file is mechanically generated. For features where requirements distribute across multiple destinations (splits, primitive/application separation, etc.), the `.map` file is hand-reviewed before scripts run.

Features explicitly known to distribute across multiple destinations are flagged in §6.

| Current path | New path | New slug | Notes |
|---|---|---|---|
| `cluster-membership/F22-continuous-rediscovery.md` | `membership/continuous-rediscovery.md` | `membership.continuous-rediscovery` | |
| `cluster-membership/F23-cluster-health-and-recovery.md` | `membership/cluster-health-and-recovery.md` | `membership.cluster-health-and-recovery` | |
| `cluster-transport/F19-multiplexed-transport-framing.md` | `transport/multiplexed-framing.md` | `transport.multiplexed-framing` | |
| `cluster-transport/F20-transport-traffic-priority.md` | `transport/traffic-priority.md` | `transport.traffic-priority` | |
| `cluster-transport/F21-scatter-gather-flow-control.md` | `transport/scatter-gather-flow-control.md` | `transport.scatter-gather-flow-control` | |
| `encryption/F03-encrypt-memory-data.md` | `encryption/primitives-*.md` (split — see §6) | multiple | **Split** — 90 reqs distribute across 4 primitive specs |
| `encryption/F41-encryption-lifecycle.md` | `encryption/primitives-lifecycle.md` | `encryption.primitives-lifecycle` | Renamed for taxonomy consistency with F03 split |
| `encryption/F42-wal-encryption.md` | `wal/encryption.md` | `wal.encryption` | **Moves domain** — WAL-specific implementation details live in `wal/`; pure encryption primitives (keys, rotation, algorithms) stay in `encryption/` via F41. Dual-tag `domains: ["wal", "encryption"]` preserved |
| `encryption/F45-client-side-encryption-sdk.md` | `encryption/client-side-sdk.md` | `encryption.client-side-sdk` | |
| `encryption/F46-encrypted-prefix-index.md` | `query/encrypted-prefix-index.md` | `query.encrypted-prefix-index` | **Moves domain** — index behavior is query concern (F42 principle applied). `domains: ["query", "encryption"]` |
| `encryption/F47-encrypted-fuzzy-matcher.md` | `query/encrypted-fuzzy-matcher.md` | `query.encrypted-fuzzy-matcher` | **Moves domain** — index behavior is query concern (F42 principle applied). `domains: ["query", "encryption"]` |
| `engine/F04-engine-clustering.md` | `engine/clustering.md` | `engine.clustering` | |
| `engine/F05-in-process-database-engine.md` | `engine/in-process-database-engine.md` | `engine.in-process-database-engine` | |
| `engine/F11-table-partitioning.md` | `partitioning/table-partitioning.md` | `partitioning.table-partitioning` | **Moves domain** — partition mechanics generalize across LSM datasets (tables, indices); engine only routes |
| `engine/F13-jlsm-schema.md` | `schema/schema-*.md` (split — see §6) | multiple | **Split** — 59 reqs distribute across 5 schema specs |
| `engine/F14-jlsm-document.md` | `schema/document-*.md` (split — see §6) | multiple | **Split** — 69 reqs distribute across 4 document specs |
| `engine/F34-handle-lifecycle.md` | `engine/handle-lifecycle.md` | `engine.handle-lifecycle` | |
| `engine/F35-cross-table-transactions.md` | `engine/cross-table-transactions.md` | `engine.cross-table-transactions` | |
| `engine/F36-remote-serialization.md` | `serialization/remote-serialization.md` | `serialization.remote-serialization` | **Moves domain** — wire-format encoding is serialization's concern. `domains: ["serialization", "engine"]` |
| `engine/F37-catalog-operations.md` | `engine/catalog-operations.md` | `engine.catalog-operations` | |
| `engine/F38-aggregation-query-merge.md` | `query/aggregation-merge.md` | `query.aggregation-merge` | **Moves domain** — query concern |
| `engine/F39-distributed-pagination.md` | `query/distributed-pagination.md` | `query.distributed-pagination` | **Moves domain** — query concern |
| `engine/F40-distributed-join-strategy.md` | `query/distributed-join-strategy.md` | `query.distributed-join-strategy` | **Moves domain** — query concern |
| `engine/F44-scan-lease-gc-watermark.md` | `query/scan-lease-gc-watermark.md` | `query.scan-lease-gc-watermark` | **Moves domain** — query-path concern. `domains: ["query", "partitioning"]` — GC-watermark interaction straddles partition layer |
| `partitioning/F27-rebalancing-safety.md` | `partitioning/rebalancing-safety.md` | `partitioning.rebalancing-safety` | |
| `partitioning/F28-rebalancing-policy.md` | `partitioning/rebalancing-policy.md` | `partitioning.rebalancing-policy` | |
| `partitioning/F29-rebalancing-operations.md` | `partitioning/rebalancing-operations.md` | `partitioning.rebalancing-operations` | |
| `partitioning/F30-partition-data-operations.md` | `partitioning/partition-data-operations.md` | `partitioning.partition-data-operations` | |
| `partitioning/F31-cross-partition-transactions.md` | `partitioning/cross-partition-transactions.md` | `partitioning.cross-partition-transactions` | |
| `partitioning/F32-partition-replication.md` | `partitioning/partition-replication.md` | `partitioning.partition-replication` | |
| `partitioning/F33-table-migration.md` | `partitioning/table-migration.md` | `partitioning.table-migration` | |
| `partitioning/F43-sequential-insert-hotspot-mitigation.md` | `partitioning/sequential-insert-hotspot-mitigation.md` | `partitioning.sequential-insert-hotspot-mitigation` | |
| `partitioning/F48-corruption-repair-recovery.md` | `partitioning/corruption-repair-recovery.md` | `partitioning.corruption-repair-recovery` | |
| `query/F07-sql-query-support.md` | `query/sql-query-support.md` | `query.sql-query-support` | |
| `query/F10-table-indices-and-queries.md` | `query/*` (split — see §6) | multiple | **Split now** — 139 reqs distribute across 8 query specs |
| `serialization/F06-optimize-document-serializer.md` | `serialization/document-serializer.md` | `serialization.document-serializer` | |
| `serialization/F08-streaming-block-decompression.md` | `compression/streaming-decompression.md` | `compression.streaming-decompression` | **Moves domain** |
| `serialization/F02-block-compression.md` | `compression/* + sstable/*` (split — see §6) | multiple | **Split** — 51 reqs distribute across compression/codec-contract + compression/zstd-codec + sstable/format-v2 + sstable/writer + redistributions |
| `serialization/F15-json-only-simd-jsonl.md` | `serialization/simd-jsonl.md` | `serialization.simd-jsonl` | |
| `serialization/F16-sstable-v3-format-upgrade.md` | `sstable/v3-format-upgrade.md` | `sstable.v3-format-upgrade` | **Moves domain** |
| `serialization/F17-wal-compression-codec-api.md` | `compression/* + wal/* + sstable/*` (split — see §6) | multiple | **Split** — 42 reqs absorb into unified codec-contract + new per-codec impls + merged wal/compression + writer footnote |
| `serialization/F18-zstd-dictionary-compression.md` | `compression/zstd-dictionary.md` | `compression.zstd-dictionary` | **Moves domain** |
| `serialization/F26-sstable-end-to-end-integrity.md` | `sstable/end-to-end-integrity.md` | `sstable.end-to-end-integrity` | **Moves domain** |
| `storage/F09-striped-block-cache.md` | `sstable/striped-block-cache.md` | `sstable.striped-block-cache` | **Moves domain** |
| `storage/F24-pool-aware-block-size.md` | `sstable/pool-aware-block-size.md` | `sstable.pool-aware-block-size` | **Moves domain** |
| `storage/F25-byte-budget-block-cache.md` | `sstable/byte-budget-block-cache.md` | `sstable.byte-budget-block-cache` | **Moves domain** |
| `vector-indexing/F01-float16-vector-support.md` | `vector/float16-vector-support.md` | `vector.float16-vector-support` | |
| `vector-indexing/F12-vector-field-type.md` | `vector/field-type.md` | `vector.field-type` | |

**41 specs move 1:1** (all requirements to a single destination). Their `.map` files are mechanically generated; RNs are preserved (`F22.R7` → `membership.continuous-rediscovery.R7` — same R7).

**7 features distribute** (requirements split across multiple destinations — see §6): F02, F03, F10, F13, F14, F17, F42. Their `.map` files are hand-reviewed; RNs renumber consecutively from R1 within each destination spec.

## 6. Features known to distribute across destinations

Every feature gets a `.map` file, but for the ones below the requirements are **known** to distribute — either because the feature is large enough to warrant multiple specs in one domain, or because it mixes concerns spanning multiple domains (primitive vs. application-specific):

| Current | Target domain(s) | Reason |
|---|---|---|
| `engine/F13-jlsm-schema.md` (59 reqs, 13 sections) | `schema/*` | 13 natural section headers = 6–8 schema specs |
| `engine/F14-jlsm-document.md` (69 reqs) | `schema/*` | Document model parallels field/schema structure |
| `serialization/F02-block-compression.md` (51 reqs, 19 sections) | `compression/*` + `sstable/*` | Shared codec contract + sstable-specific writer/reader |
| `serialization/F17-wal-compression-codec-api.md` (42 reqs, 11 sections) | `compression/*` + `wal/*` + `sstable/*` | Shared codec API + WAL-specific record format + sstable migration |
| `encryption/F42-wal-encryption.md` (77 reqs) | `wal/*` + `encryption/*` | WAL-specific application (SEK placement, record format, replay) + encryption primitives (nonce-safety invariants, SEK derivation) |
| `encryption/F46-encrypted-prefix-index.md` (33 reqs) | `query/` | Index application. Resolved 2026-04-20: move to `query/encrypted-prefix-index.md`, `domains: ["query", "encryption"]`. Intact — no sub-section split this pass |
| `encryption/F47-encrypted-fuzzy-matcher.md` (33 reqs) | `query/` | Same pattern as F46. Resolved 2026-04-20: move to `query/encrypted-fuzzy-matcher.md`, `domains: ["query", "encryption"]`. Intact |
| `encryption/F03-encrypt-memory-data.md` (90 reqs) | `encryption/primitives-*` | Split into 4 primitive specs. Resolved 2026-04-20: variants / configuration / dispatch / key-holder |
| `query/F10-table-indices-and-queries.md` (139 reqs, 21 sections) | `query/*` | Split into 8 specs — tentative breakdown below |

**Each split spec gets a `.spec/_migration/FXX.map` file** enumerating every requirement's destination. Format in §8.

### F13 — split plan (59 reqs → 5 specs)

Boundaries chosen for clean search/conflict-detection identity — each spec answers one question without cross-file lookups. Audit-hardened requirements distribute to their originating concerns (not pulled into a separate bag). Files use `schema-` prefix for symmetry with F14's `document-` prefix.

| New spec | Sections absorbed | Est reqs |
|---|---|---|
| `schema/schema-construction.md` | Construction, Field management, Builder lifecycle, Field name validation, audit-hardened reqs applying to construction | ~20 |
| `schema/schema-field-access.md` | Field lookup, Accessors, Absent behaviors, audit-hardened reqs applying to access | ~13 |
| `schema/schema-nesting.md` | Nesting (object fields), audit-hardened reqs applying to nesting | ~6 |
| `schema/schema-invariants.md` | Immutability and thread safety, Structural equality, audit-hardened reqs applying to invariants | ~10 |
| `schema/schema-field-definition.md` | FieldDefinition (companion type), FieldType (companion type) | ~10 |

### F14 — split plan (69 reqs → 4 specs)

Mirrors F13's structure. Document is simpler than Schema (no companion types of its own), so 4 specs suffice. YAML-removed requirements are dropped — they describe a never-built feature, not a displaced one, so INVALIDATED status does not apply. Pre-encrypted document support is filed in `document-serialization.md` as a construction-path serialization concern.

| New spec | Sections absorbed | Est reqs |
|---|---|---|
| `schema/document-construction.md` | Construction, factory `of()`, factory `preEncrypted()`, Type validation, Defensive copying, audit-hardened reqs applying to construction | ~20 |
| `schema/document-field-access.md` | Typed getters, Schema accessor, Null field query, Vector getters, Absent behaviors, audit-hardened reqs applying to access | ~20 |
| `schema/document-serialization.md` (`domains: ["schema", "serialization"]`) | Serialization (JSON), Pre-encrypted document support (YAML section dropped) | ~8 |
| `schema/document-invariants.md` | Immutability and thread safety, Structural equality, Internal access (DocumentAccess), audit-hardened reqs applying to invariants | ~10 |

### F02 — split plan (51 reqs → distribute across compression/ + sstable/)

Boundaries chosen for clean search/conflict-detection identity. Abstract codec contract separated from ZSTD specifics. Integration reqs redistributed to the specs for the components they integrate with; those specs get multi-domain tags (P6) so compression searches still surface them.

| New spec | Sections absorbed | Est reqs |
|---|---|---|
| `compression/codec-contract.md` (`domains: ["compression"]`) | Compression codec contract, Input validation (abstract interface) | ~6 |
| `compression/zstd-codec.md` (`domains: ["compression"]`) | ZSTD codec, tiered detection, dictionary-aware writer lifecycle | ~6 |
| `sstable/format-v2.md` (`domains: ["sstable"]`) | SSTable file format v2, Backward compatibility, Self-describing format and codec resolution, File offset and length width, Footer validation, Footer section ordering, Key index validation, Compression map validation | ~20 |
| `sstable/writer.md` (`domains: ["sstable", "compression"]`) | Writer behavior, Silent failure documentation, Error handling, Concurrency, Tree builder integration | ~12 |
| → redistribute to `sstable/striped-block-cache.md` (add `domains: ["sstable", "compression"]`) | Block cache integration | ~3 |
| → redistribute to `sstable/compaction.md` (new spec if compaction reqs are coherent; otherwise merge into existing compaction spec) | Compaction integration | ~4 |

### F17 — split plan (42 reqs → consolidate into contract + per-codec impls + merged WAL compression)

Boundaries chosen for clean search identity. F17's MemorySegment API absorbs into the F02-split `compression/codec-contract.md` (one contract spec, not two variants). Each codec gets its own implementation spec. WAL format + behavior merge into a single `wal/compression.md`. SSTable migration is a footnote in the writer spec, not a standalone 2-req spec.

| Action | Target | Est reqs |
|---|---|---|
| Merge into `compression/codec-contract.md` (from F02 split — becomes unified contract spec; ByteBuffer reqs marked INVALIDATED, MemorySegment is authoritative) | `compression/codec-contract.md` | +7 |
| `compression/none-codec.md` (new, `domains: ["compression"]`) | NONE codec (MemorySegment) | ~5 |
| `compression/deflate-codec.md` (new, `domains: ["compression"]`) | DEFLATE codec (MemorySegment — zero-copy) | ~5 |
| `wal/compression.md` (new, `domains: ["wal", "compression"]`) | WAL compressed record format, WAL format versioning, WAL compression behavior, WAL recovery (compressed records), WAL write-path buffer management | ~23 |
| Redistribute to `sstable/writer.md` (already picking up F02 writer reqs) | SSTable codec migration | ~2 |

### F03 — split plan (90 reqs → 4 primitive specs)

F03 is the foundational encryption-primitive layer. Split to expose the taxonomic structure and to resolve the F41 layering (F41's key hierarchy operates on top of F03's KeyHolder primitive — different abstraction levels).

| New spec | Sections absorbed | Est reqs |
|---|---|---|
| `encryption/primitives-variants.md` | Encryption specification model, Deterministic (AES-SIV), Opaque (AES-GCM), Order-preserving (Boldyreva OPE), Distance-preserving (DCPE / Scale-And-Perturb) | ~55 |
| `encryption/primitives-configuration.md` | Schema-level field encryption configuration | ~15 |
| `encryption/primitives-dispatch.md` | Encryption dispatch | ~14 |
| `encryption/primitives-key-holder.md` | Key holder lifecycle (R12-R17) — low-level KeyHolder contract; `primitives-lifecycle.md` (from F41) requires this spec | ~6 |

### F10 — split plan (139 reqs → 8 query specs)

| New spec | Sections absorbed | Estimated reqs |
|---|---|---|
| `query/index-types.md` | IndexType enum, IndexDefinition record, Predicate sealed interface, Index-schema type compatibility | ~20 |
| `query/field-value-codec.md` | FieldValueCodec — sort-preserving binary encoding | ~10 |
| `query/table-query.md` | TableQuery fluent builder | ~12 |
| `query/field-index.md` | SecondaryIndex sealed interface, FieldIndex (equality, range, unique), Unique index constraint enforcement, FieldIndex key comparison | ~25 |
| `query/full-text-index.md` | FullTextFieldIndex | ~10 |
| `query/vector-index.md` | VectorFieldIndex | ~10 |
| `query/index-registry.md` | IndexRegistry — index lifecycle management, Index close and resource cleanup | ~18 |
| `query/query-executor.md` | QueryExecutor — query planning and execution, Scan-and-filter predicate evaluation, Null field value handling, Update atomicity, Thread safety, Audit-hardened requirements, JPMS module boundaries | ~34 |

**All split plans above are DRAFT.** The `.map` files (§8) are the authoritative record — once generated and reviewed, they override this table.

## 7. Cross-reference updates

Every spec has frontmatter fields that reference other specs by their old IDs:
- `requires: ["F16"]` → `requires: ["sstable.v3-format-upgrade"]`
- `invalidates: ["F09.R8", "F09.R15"]` → `invalidates: ["sstable.striped-block-cache.R8", "sstable.striped-block-cache.R15"]`
- `decision_refs: [...]` — unchanged (decisions are referenced by slug, not FXX)
- `amends`, `amended_by`, `displaced_by`, `revives`, `revived_by` — may reference FXX IDs, updated

Additionally:
- `.spec/registry/manifest.json` — regenerated from post-migration directory
- `.spec/registry/_obligations.json` — `spec_id` and `affects` fields updated from FXX→domain.slug
- `.work/*/manifest.md` and `.work/*/WD-*.md` — any FXX.RN references in acceptance criteria and artifact_deps updated

## 8. `FXX.map` file format

For every feature being split, produce `.spec/_migration/FXX.map` (JSON). Example for F13:

```json
{
  "source_spec": "F13",
  "source_path": ".spec/domains/engine/F13-jlsm-schema.md",
  "total_requirements": 59,
  "requirements": {
    "R1": { "new_spec": "schema.construction", "new_rn": "R1", "new_path": ".spec/domains/schema/construction.md" },
    "R2": { "new_spec": "schema.construction", "new_rn": "R2", "new_path": ".spec/domains/schema/construction.md" },
    "R13": { "new_spec": "schema.field-lookup", "new_rn": "R1", "new_path": ".spec/domains/schema/field-lookup.md" },
    "...": "..."
  },
  "destinations": [
    { "new_spec": "schema.construction", "new_path": ".spec/domains/schema/construction.md", "source_requirements": ["R1", "R2", "...", "R15"] },
    { "new_spec": "schema.field-lookup", "new_path": ".spec/domains/schema/field-lookup.md", "source_requirements": ["R13", "R14", "..."] }
  ]
}
```

**Invariants enforced by the validator:**
1. Every `R*` key from the source spec appears exactly once in `requirements`.
2. `destinations[].source_requirements` is a partition of `requirements` keys.
3. For each destination, the source RNs are renumbered consecutively starting at R1 (new_rn sequence).
4. `requirements[Rn].new_spec` == some `destinations[i].new_spec`.

Non-split specs do not need a `.map` file — the domain mapping table in §5 suffices.

## 9. Migration scripts (to be built)

All scripts live in `.spec/_migration/scripts/` during migration and are deleted after. They operate on the mapping table in this document and the `.map` files.

| Script | Reads | Writes |
|---|---|---|
| `00-backup.sh` | `.spec/domains/**/*.md` | `.spec/_archive/migration-2026-04-21/F*.md` (copies of all pre-migration specs) |
| `01-validate-maps.sh` | `.spec/_migration/*.map` | stdout — fails if any invariant broken |
| `02-generate-splits.sh` | `.map` files + original FXX spec files | New domain spec files with split requirements + frontmatter |
| `03-move-nonsplit.sh` | MIGRATION.md §5 table | New domain spec files (rename + frontmatter update) |
| `04-rewrite-annotations.sh` | `.map` files + §5 table + `modules/**/*.java` | Updated `@spec` comments across codebase |
| `05-rewrite-refs.sh` | new spec files + `.map` files + §5 table | Updated `requires`/`invalidates`/etc. fields |
| `06-rewrite-work-layer.sh` | `.work/**/*.md` + `.spec/registry/_obligations.json` | Updated FXX.RN → domain.slug.RN references |
| `07-regenerate-manifest.sh` | `.spec/domains/**/*.md` | `.spec/registry/manifest.json` |
| `08-validate-roundtrip.sh` | backup dir + new layout + `.map` files | stdout — see §10 |
| `09-cleanup.sh` | new layout + migration dir | removes old FXX files + `.spec/_migration/` (after user confirms validation) |

All scripts are bash, no new dependencies. Each is idempotent where possible (00, 01, 08 can re-run freely; 02-07 have explicit --force flag if re-running needed).

## 10. Round-trip validation

After 02-07 complete, `08-validate-roundtrip.sh` asserts:

**A. Requirement coverage.**
- Count `(FXX.RN)` pairs in backup (should be 802).
- For each, resolve through the mapping (table for non-split, `.map` for split) to a `(domain.slug.RN)`.
- Verify every new spec contains every mapped requirement (no lost reqs).
- Verify no new `(domain.slug.RN)` exists without a source in the backup (no phantom reqs).

**B. Annotation integrity.**
- Run `spec-trace.sh` on the post-migration codebase.
- Every resulting `domain.slug.RN` must resolve to a requirement in the new specs.
- Count must equal the pre-migration 802.

**C. Cross-reference integrity.**
- Parse `requires`, `invalidates`, `displaced_by`, `revives`, `amends`, `amended_by` fields.
- Every reference must resolve to a spec that exists in the new layout.

**D. Manifest integrity.**
- `.spec/registry/manifest.json` must list every spec file in `.spec/domains/`.
- No orphan manifest entries.
- `spec-validate.sh` must pass on every spec.

**E. Obligation integrity.**
- Every `spec_id` and `affects` field in `_obligations.json` resolves to a new spec ID.

**F. Work layer integrity.**
- Every `artifact_deps` ref in `.work/**/*.md` resolves.

If any check fails, migration is rolled back via `git reset --hard HEAD~N` where N = number of migration commits. Backups survive until `09-cleanup.sh` runs.

## 11. Execution order (Stage 3)

Each row is a separate commit. Between rows, run validation and pause for review if results are unexpected.

| Step | Commit | Command |
|---|---|---|
| 1 | `chore(spec): backup pre-migration specs` | `bash .spec/_migration/scripts/00-backup.sh` |
| 2 | (no commit) validate maps | `bash .spec/_migration/scripts/01-validate-maps.sh` |
| 3 | `refactor(spec): generate split specs from F02, F10, F13, F14, F17` | `02-generate-splits.sh` |
| 4 | `refactor(spec): relocate non-split specs to behavioral domains` | `03-move-nonsplit.sh` |
| 5 | `refactor(spec): update @spec annotations across codebase` | `04-rewrite-annotations.sh` |
| 6 | `refactor(spec): update cross-refs and work-layer references` | `05, 06, 07` |
| 7 | (no commit) validation | `08-validate-roundtrip.sh` |
| 8 | `chore(spec): clean up migration artifacts` | `09-cleanup.sh` |

Between steps 5 and 6, `modules/` should compile and tests should pass (annotations are comments; renaming them doesn't affect runtime).

## 12. Rollback

At any point before step 8, full rollback via:
```
git reset --hard <pre-migration-sha>
rm -rf .spec/_migration/  # if partial state remains
```

After step 8 (cleanup), `.spec/_archive/migration-2026-04-21/` still contains original FXX specs for a grace period (e.g., 2 weeks) before being removed in a follow-up commit.

## 13. Open decisions (require user review before execution)

1. **F11 (table-partitioning) domain**: ~~keep in `engine`~~ → **Resolved 2026-04-20: move to `partitioning`.** Partition mechanics generalize across LSM datasets (tables, indices will follow same patterns); engine's only role is query routing and partition pre-filtering.

2. **F42 (wal-encryption) domain**: encryption-of-wal vs wal-with-encryption. **Proposed: keep in `encryption`**, have `wal/` specs reference it. Alternative: move to `wal`.

3. **F10 (table-indices-and-queries) split**: ~~defer~~ → **Resolved 2026-04-20: split now.** 139 reqs → 8 query specs (index-types, field-value-codec, table-query, field-index, full-text-index, vector-index, index-registry, query-executor). Doing it in this migration avoids a second annotation-rewrite pass and directly serves the research/knowledge/testing drivers.

4. **Split detail plans (§6)**: F13 breakdown to 6 specs, F02 to 4, F17 to 5, F14 TBD. Are the sub-spec boundaries right? Each becomes a `.map` file and authoritative once approved.

5. **Migration commit volume**: 8 commits in order above. Acceptable or should some be squashed?

6. **Post-migration kit work** (Stage 4, separate from this doc): vallorcine tools need to accept `domain.slug.RN` format. That's a follow-up PR in vallorcine, not part of this plan.

---

## Appendix A — Annotation rewrite details

For each `@spec` annotation in `modules/`:

```
BEFORE:  // @spec F13.R24 — FieldDefinition must be immutable
AFTER:   // @spec schema.field-definition.R3 — FieldDefinition must be immutable
```

The new RN comes from the `.map` file's `requirements["R24"].new_rn` field. Multi-requirement annotations like `// @spec F12.R24,R25,R26` fan out: each requirement is resolved independently, and if all three map to the same destination spec, the rewritten annotation is `// @spec vector.field-type.R24,R25,R26`; if they span destinations, the annotation is split into multiple lines.

The rewrite script emits a report of every file touched, every substitution made, and any annotations it could not resolve. An unresolvable annotation is a mapping gap — execution halts until fixed.

## Appendix B — Directory structure after migration

```
.spec/
├── CLAUDE.md                       (updated)
├── MIGRATION.md                    (deleted after step 8)
├── _archive/
│   └── migration-2026-04-21/
│       └── F01.md ... F48.md       (48 backup files; removed after grace period)
├── _migration/                     (deleted in step 8)
│   ├── scripts/
│   │   ├── 00-backup.sh
│   │   ├── 01-validate-maps.sh
│   │   ├── ...
│   │   └── 09-cleanup.sh
│   ├── F13.map
│   ├── F14.map
│   ├── F02.map
│   └── F17.map
├── domains/
│   ├── schema/
│   │   ├── CLAUDE.md
│   │   ├── construction.md
│   │   ├── field-lookup.md
│   │   ├── nesting.md
│   │   ├── immutability.md
│   │   ├── field-definition.md
│   │   └── validation.md
│   ├── serialization/
│   │   ├── CLAUDE.md
│   │   ├── document-serializer.md
│   │   ├── simd-jsonl.md
│   │   └── remote-serialization.md
│   ├── compression/
│   │   ├── CLAUDE.md
│   │   ├── codec-contract.md
│   │   ├── memory-segment-api.md
│   │   ├── codec-implementations.md
│   │   ├── streaming-decompression.md
│   │   └── zstd-dictionary.md
│   ├── sstable/
│   │   ├── CLAUDE.md
│   │   ├── format-v2.md
│   │   ├── v3-format-upgrade.md
│   │   ├── striped-block-cache.md
│   │   ├── pool-aware-block-size.md
│   │   ├── byte-budget-block-cache.md
│   │   ├── writer-behavior.md
│   │   ├── integration.md
│   │   ├── end-to-end-integrity.md
│   │   └── codec-migration.md
│   ├── wal/
│   │   ├── CLAUDE.md
│   │   ├── compressed-record-format.md
│   │   └── compression-behavior.md
│   ├── vector/
│   │   ├── CLAUDE.md
│   │   ├── float16-vector-support.md
│   │   └── field-type.md
│   ├── query/
│   │   ├── CLAUDE.md
│   │   ├── sql-query-support.md
│   │   ├── table-indices-and-queries.md
│   │   ├── aggregation-merge.md
│   │   ├── distributed-pagination.md
│   │   ├── distributed-join-strategy.md
│   │   └── scan-lease-gc-watermark.md
│   ├── encryption/
│   │   ├── CLAUDE.md
│   │   ├── memory-data-encryption.md
│   │   ├── encryption-lifecycle.md
│   │   ├── wal-encryption.md
│   │   ├── client-side-sdk.md
│   │   ├── prefix-index.md
│   │   └── fuzzy-matcher.md
│   ├── engine/
│   │   ├── CLAUDE.md
│   │   ├── clustering.md
│   │   ├── in-process-database-engine.md
│   │   ├── table-partitioning.md
│   │   ├── handle-lifecycle.md
│   │   ├── cross-table-transactions.md
│   │   └── catalog-operations.md
│   ├── partitioning/
│   │   ├── CLAUDE.md
│   │   ├── rebalancing-safety.md
│   │   ├── rebalancing-policy.md
│   │   ├── rebalancing-operations.md
│   │   ├── partition-data-operations.md
│   │   ├── cross-partition-transactions.md
│   │   ├── partition-replication.md
│   │   ├── table-migration.md
│   │   ├── sequential-insert-hotspot-mitigation.md
│   │   └── corruption-repair-recovery.md
│   ├── transport/
│   │   ├── CLAUDE.md
│   │   ├── multiplexed-framing.md
│   │   ├── traffic-priority.md
│   │   └── scatter-gather-flow-control.md
│   └── membership/
│       ├── CLAUDE.md
│       ├── continuous-rediscovery.md
│       └── cluster-health-and-recovery.md
└── registry/
    ├── manifest.json               (regenerated)
    └── _obligations.json           (updated)
```

Final layout: **12 domain directories, ~60 spec files.**
