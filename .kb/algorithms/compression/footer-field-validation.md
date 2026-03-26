---
title: "Footer field validation missing for corrupt on-disk data"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableReader.java"
research_status: active
last_researched: "2026-03-26"
---

# Footer field validation missing for corrupt on-disk data

## What happens

SSTable footer fields (offsets, lengths, entry count) are read from on-disk
binary data and used directly to allocate buffers and position reads. Without
validation, a corrupt footer with negative lengths causes `IllegalArgumentException`
from `ByteBuffer.allocate(negative)`, and negative offsets cause unexpected
`IOException` — neither includes SSTable context. Overlapping or out-of-range
offsets can cause reads of wrong file regions, producing silent data corruption.

## Why implementations default to this

Footer reading code focuses on parsing the binary format correctly, assuming the
writer produced valid data. Developers test with correctly-written files and don't
consider corrupt or adversarial file inputs.

## Test guidance

- For any footer/header parser reading offsets and lengths from disk:
  - Test with negative values for each field
  - Test with offsets exceeding file size
  - Test with overlapping regions (e.g., mapOffset + mapLength > idxOffset)
  - Verify all cases throw `IOException` with descriptive messages
- The safe pattern: add a `validate(fileSize)` method on the parsed footer record
  and call it before using any field values.

## Found in

- block-compression (audit round 2, 2026-03-26): `TrieSSTableReader.readFooter`
  and `readFooterV1` passed corrupt footer fields downstream. Fixed by adding
  `Footer.validate(fileSize)` with comprehensive field checks.
