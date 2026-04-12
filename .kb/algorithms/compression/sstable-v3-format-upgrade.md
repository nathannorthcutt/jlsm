---
title: "sstable-v3-format-upgrade"
type: feature-footprint
domains: ["per-block-checksums", "backend-optimal-block-size", "sstable-block-compression-format"]
constructs: ["CorruptBlockException", "SSTableFormat", "CompressionMap", "TrieSSTableWriter.Builder", "TrieSSTableReader"]
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/**"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/SSTableFormat.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/CompressionMap.java"
related:
  - ".kb/algorithms/compression/block-compression.md"
  - ".kb/algorithms/compression/streaming-block-decompression.md"
  - ".kb/algorithms/compression/block-compression-algorithms.md"
decision_refs: ["per-block-checksums", "backend-optimal-block-size", "sstable-block-compression-format"]
spec_refs: ["F16"]
research_status: stable
last_researched: "2026-04-11"
---

# sstable-v3-format-upgrade

## What it built
SSTable v3 on-disk format with per-block CRC32C integrity checksums and
configurable block size. Extends the v2 compression format (established by
the block-compression feature) with corruption detection and remote-backend
optimization. Full backward compatibility with v1 and v2 files preserved.

## Key constructs
- `CorruptBlockException` — IOException subclass with block index, expected/actual checksum diagnostics
- `SSTableFormat` — v3 constants (MAGIC_V3, FOOTER_SIZE_V3=72, entry size 21), block size constants (HUGE_PAGE=2MiB, REMOTE=8MiB), validateBlockSize()
- `CompressionMap.Entry` — gains checksum field (17->21 byte entries); 4-arg backward-compat constructor; serializeV3() and deserialize(data, version)
- `TrieSSTableWriter.Builder` — v3 format with blockSize() and codec() configuration; old constructors still produce v2
- `TrieSSTableReader` — v3 footer detection, CRC32C verification before decompression, cache hits skip verification

## Implementation patterns
- **Backward-compat via constructor overloading:** old public constructors produce v2; only Builder produces v3. Avoids breaking existing callers.
- **Sneaky throw for checked exceptions in Iterator:** CorruptBlockException (extends IOException) propagated through Iterator.next() via type-erasure rethrow, avoiding UncheckedIOException wrapping that hides the diagnostic type.
- **Reflection-safe refactoring:** 9-arg backward-compat private constructor added to TrieSSTableReader to preserve adversarial tests using reflection.
- **Footer record kept minimal:** blockSize validated inline in readFooter() rather than adding a field to the Footer record, preserving existing reflection-based tests.

## Adversarial findings
- No aTDD rounds or audit passes were run for this feature (hardening stage skipped).
- Spec authoring pass 2 caught 7 adversarial findings before implementation (NONE-fallback CRC32C ambiguity, v3-only overflow math, silent block size discard, mutable constant for v2 reads, compaction version propagation, cache-masked corruption).

## Cross-references
- ADR: .decisions/per-block-checksums/adr.md
- ADR: .decisions/backend-optimal-block-size/adr.md
- ADR: .decisions/sstable-block-compression-format/adr.md
- Spec: F16 (SSTable v3 format upgrade, 24 requirements)
- Prior feature: block-compression (established v2 format)
- Prior feature: streaming-block-decompression (lazy scan pattern)
