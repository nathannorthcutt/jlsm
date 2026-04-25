---
type: adversarial-finding
title: "Dispatch-Discriminant Corruption and Speculative Cross-Version Verification"
topic: "patterns"
category: "validation"
tags: ["dispatch", "magic-number", "bit-flip", "format-version", "integrity-bypass"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-22"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/TrieSSTableReader.java"
related:
  - "version-discovery-self-only-no-external-cross-check"
  - "silent-fallthrough-integrity-defense-coupled-to-flag"
  - "systems/database-engines/format-version-deprecation-strategies"
decision_refs: []
source_audit: "implement-sstable-enhancements--wd-03"
sources: []
---

# Dispatch-Discriminant Corruption and Speculative Cross-Version Verification

## What Happens

A multi-version file-format reader dispatches on a "magic" trailing byte
sequence to select a version-specific branch. Single-bit corruption of the
discriminant (e.g., v5's LSB flipped from `0x05` to `0x04`) lands the file
in a legacy branch that lacks the newer version's integrity defenses. A
crafted file can therefore bypass the newer branch's CRC gate by corrupting
only the discriminant: the legacy branch has its own, weaker footer format
and its own, weaker (or absent) self-checksum.

Concrete v5 example: a file with a valid v5 body and a v5 footer checksum
over all 104 in-scope bytes, but with the trailing magic corrupted
`V5 → V4` (LSB flip). The legacy v4 branch reads its own 8-byte magic,
interprets the preceding footer bytes as the v4 layout, and never reaches
the v5 footer self-checksum.

## Why It Happens

Format-dispatch typically follows the pattern "read magic, switch on
version, hand off to version-specific parser." The v5 footer checksum
protects v5-format bytes; nothing verifies that the file is not a corrupted
v5 masquerading as a legacy version.

## Fix Pattern

At dispatch time, for any non-current-version magic on a file at least
`FOOTER_SIZE_CURRENT` bytes long, compute a speculative current-version
footer self-checksum using hypothesis-substitution: read the tail of the
file as if it were a current-version footer, replace the observed magic
with the intact current-version magic constant, recompute CRC32C over the
in-scope bytes, compare against the stored footer checksum. A match proves
the file is the current version with a corrupted discriminant and must be
rejected as `CorruptSectionException`:

```java
long magic = readTrailingMagic(ch);
if (magic != MAGIC_V5 && fileSize >= FOOTER_SIZE_V5) {
    ByteBuffer v5footer = readTail(ch, FOOTER_SIZE_V5);
    int stored = v5footer.getInt(FOOTER_CHECKSUM_OFFSET);
    // substitute MAGIC_V5 into the checksum scope
    v5footer.putLong(MAGIC_OFFSET, MAGIC_V5);
    int recomputed = crc32c(v5footer, in-scope-ranges);
    if (recomputed == stored) throw new CorruptSectionException(SECTION_FOOTER,
        "v5 magic corrupted to 0x" + Long.toHexString(magic));
}
// else: legacy-branch dispatch is safe — the bytes do NOT form a valid
// v5 footer with a mutated magic
```

Performance: the speculative check runs only on a magic mismatch (rare),
so happy-path legacy reads and v5 reads pay nothing. The only cost is on
genuinely-legacy files, where one extra CRC32C pass over 104 bytes is
negligible.

## Detection

Dispatch-routing lens enumerated every single-bit flip of `MAGIC_V5`
that lands on a known legacy magic (V4, V3, V2, V1), crafted a file for
each, and asserted the reader surfaces `CorruptSectionException` rather
than silently reinterpreting the bytes.

## Seen In

- `TrieSSTableReader.readFooter` — audit findings
  F-R1.dispatch_routing.1.1 (V5 → V4 LSB flip bypass) and
  F-R1.dispatch_routing.1.2 (V5 → V1 LSB + bit-2 bypass; already-fixed
  by .1.1). Cross-domain composition XD-R1.7 showed the single fix
  closes two attack patterns.

## Test Guidance

- Enumerate every single-bit flip of `MAGIC_V5`; for each flip that lands
  on a known legacy magic, craft a file whose v5 body is valid, whose
  footer checksum is valid over the intact-magic scope, but whose trailing
  magic carries the flip. Assert `CorruptSectionException(SECTION_FOOTER)`.
- Cover genuine legacy files: open a real v3 SSTable and assert the
  legacy-branch dispatch proceeds correctly (the speculative check must
  not false-positive on legitimate legacy files).

## Scope

Pattern applies to any multi-version file-format reader in jlsm (and
elsewhere) that performs magic-based dispatch: SSTable, WAL, manifest,
snapshot files. The speculative-hypothesis pattern generalizes to any
discriminant corruption where the newer version has stronger integrity.
