---
{
  "id": "compression.zstd-codec",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "compression"
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

# compression.zstd-codec — Zstd Codec

## Requirements

### ZSTD codec and tiered detection

R1. The ZSTD codec (ID 0x03) must use a tiered runtime detection pattern: Tier 1 probes for native libzstd via Panama FFM Linker.nativeLinker() and SymbolLookup; Tier 2 provides a pure-Java ZSTD decompressor (decompression only, no compression); Tier 3 falls back to the DEFLATE codec for compression. Detection must occur once at class-load time and be cached. Detection must catch all exceptions and fall through gracefully to the next tier. [New: from codec-dictionary-support ADR.]

R2. The pure-Java ZSTD decompressor (Tier 2) must handle dictionary-compressed frames. It must parse the dictionary ID from the frame header, load pre-trained FSE and Huffman tables from dictionary bytes, pre-seed repeat offsets, and prepend dictionary content as match history. The frame-level decode loop must require zero changes — dictionary support is initialization-only. [New: from codec-dictionary-support ADR, verified by feasibility spike.]

R3. ZSTD CDict and DDict equivalents (native Tier 1) are read-only after creation and must be safely shareable across threads. The codec instance must hold the dictionary internally as constructor-time configuration. The compress() and decompress() methods must remain stateless per F02.R7/F17.R5. [New: from codec-dictionary-support ADR.]

### Dictionary-aware writer lifecycle

R4. When a dictionary-enabled codec is configured, the SSTable writer must buffer all uncompressed data blocks in memory before compressing any of them. After all blocks are buffered, the writer must sample blocks uniformly for dictionary training, train the dictionary (native Tier 1 only), create a dictionary-bound codec, and compress all buffered blocks. The dictionary training lifecycle must be fully encapsulated inside the writer — callers pass codec configuration at construction, the writer handles buffering and training internally. [New: from codec-dictionary-support ADR.]

R5. The trained dictionary must be stored as a meta-block in the SSTable file alongside the index and bloom filter. The dictionary meta-block must be loadable from on-disk metadata alone — no external dictionary file or registry. Readers must detect the presence of a dictionary meta-block and load it before decompressing any block. [New: from codec-dictionary-support ADR.]

R6. The writer's block buffering for dictionary training must be bounded by a configurable maximum (set via the writer builder). If the buffered data exceeds the maximum, the writer must abandon dictionary training, compress all previously buffered blocks using the configured non-dictionary codec, and continue writing subsequent blocks without further buffering. This graceful degradation prevents unbounded memory consumption while preserving data throughput when the input exceeds the dictionary training budget. [Amended v2 2026-04-16: was "must fail with an IOException"; graceful fallback is the better design — it prevents write failure on inputs larger than the training budget allows.]

R7. When native libzstd is unavailable (Tier 2 or 3), the writer must skip dictionary training and compress blocks as they arrive using the fallback codec. The writer must not fail at construction time due to missing native library — it must gracefully degrade. [New: from codec-dictionary-support ADR.]

---

## Design Narrative

### Intent

Generated during the 2026-04-20 spec migration. See `.spec/_archive/migration-2026-04-20/MIGRATION.md` for
the migration plan and `.spec/_archive/migration-2026-04-20/` for the
pre-migration source spec(s) this spec was derived from.
