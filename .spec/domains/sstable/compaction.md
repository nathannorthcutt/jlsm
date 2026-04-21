---
{
  "id": "sstable.compaction",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "sstable"
  ],
  "requires": [],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "sstable-block-compression-format",
    "compression-codec-api-design",
    "codec-dictionary-support",
    "compaction-recompression"
  ],
  "kb_refs": [
    "algorithms/compression/block-compression-algorithms"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F02"
  ]
}
---

# sstable.compaction — Compaction

## Requirements

### Compaction integration

R1. The compactor must create output writers via an SSTableWriterFactory, not by direct constructor invocation. The factory receives the SSTable ID, target Level, and output Path, and returns a writer configured with the appropriate compression codec for that level. The compactor must not contain codec selection logic — it delegates writer creation entirely to the factory. [Amended by compaction-recompression ADR: was "must use the same compression codec as the tree".]

R2. The tree builder must support a per-level compression policy via a Function<Level, CompressionCodec> (or equivalent). When set, the policy determines the codec for each level. When not set, all levels use the single configured codec (backward compatible). The builder must wire the policy into the SSTableWriterFactory used by both flush and compaction paths. [Amended by compaction-recompression ADR: was "single codec for writing".]

R3. The compactor must not carry implicit state between output writers. Each writer created by the factory must be independent — no shared buffers, no cross-file dictionary references, no assumptions about prior writers in the same compaction run. This enables the factory to return different codec configurations per output file without coordination. [New: codifies assumption verified during compaction-recompression deliberation.]

---

## Design Narrative

### Intent

Generated during the 2026-04-20 spec migration. See `.spec/_archive/migration-2026-04-20/MIGRATION.md` for
the migration plan and `.spec/_archive/migration-2026-04-20/` for the
pre-migration source spec(s) this spec was derived from.
