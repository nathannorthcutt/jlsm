---
type: adversarial-finding
title: "Silent-Fallthrough Integrity Defense Coupled to Unrelated Flag"
topic: "patterns"
category: "validation"
tags: ["integrity", "crc", "silent-else", "feature-flag-coupling", "state-truthfulness"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-22"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/TrieSSTableWriter.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/TrieSSTableReader.java"
related:
  - "else-branch-assumes-last-variant"
  - "silent-empty-result-dispatch"
  - "mutation-outside-rollback-scope"
decision_refs: []
source_audit: "implement-sstable-enhancements--wd-03"
sources: []
---

# Silent-Fallthrough Integrity Defense Coupled to Unrelated Flag

## What Happens

An integrity defense (CRC computation, validation guard, fallback rejection)
is gated on a condition that looks correct today but couples to an
unrelated feature flag or silently falls through when the guard's
precondition does not hold. Three observed variants:

1. **CRC branch gated on legacy-format flag.** A v5 writer's CRC branch
   reads `if (v3) { compute CRC; } else { skip; }` where the `v3` boolean
   is a legacy feature flag. A future refactor that clears the flag on a
   v5 writer silently disables CRC coverage while the v5 compression-map
   still records `checksum=0`, leaving the file with a valid-looking
   but integrity-free metadata entry.

2. **Silent `else` on absent metadata.** A recovery-scan block reader
   writes `if (entry.crc != null) verify(); else proceed;` — treating the
   absent-CRC state as "nothing to verify" when in fact the absence itself
   is a structural corruption signal.

3. **Counter-buffer state truthfulness.** A writer abandons a buffer
   (`dictBufferedBlocks = null`) but leaves the paired counter
   (`dictBufferedBytes`) carrying a stale value. A future reader of the
   counter outside the buffer's null-guard sees wrong state.

## Why It Happens

The defense's author assumes the gating condition is load-bearing today
and will remain so. Subsequent refactors — reorganizing format flags,
adding new versions, changing the "present" state of optional fields —
break the implicit invariant. Integrity coverage is silently lost; no
test fires because the surface guard still passes.

## Fix Patterns

- **Decouple integrity from legacy flags.** Express the CRC obligation
  as an invariant of the write path itself, not conditioned on a version
  flag. A v5 writer computes v5 CRCs unconditionally.
- **Replace silent fall-through with typed exceptions.** If a precondition
  the defense relies on is violated (e.g., compression-map entry present
  but crc absent), throw a `CorruptSectionException` identifying the
  inconsistent state — do not proceed as if nothing happened.
- **Update counter-buffer pairs atomically.** Any mutation that changes
  one must change the paired sibling in the same unit of work; comment
  the invariant inline.

## Detection

Contract-boundaries and data-transformation lenses sought adversarial
inputs that pass the surface guard while bypassing the intended defense:

- CRC gate probed by a reflective clearing of the legacy flag on a v5
  writer followed by a comparison of `compressionMap.checksum` against
  the actual CRC of the block payload.
- Silent `else` probed by structural fuzz producing "present entry with
  absent crc" — a state the happy-path code never constructs but which
  the file layout permits.

## Seen In

- `TrieSSTableWriter.compressAndWriteBlock` CRC gate (audit finding
  F-R1.data_transformation.1.4 — `if (v3)` skipped v5).
- `TrieSSTableReader.RecoveryScanIterator.readBlockAtCursor` (audit
  finding F-R1.contract_boundaries.03.01 — silent else when
  `compressionMap` entry present but `crc` field absent).
- `TrieSSTableWriter.flushCurrentBlock` abandon branch (audit finding
  F-R1.data_transformation.1.6 — `dictBufferedBytes` not reset alongside
  `dictBufferedBlocks`).

## Test Guidance

- Reflectively clear the legacy flag and assert CRC is still computed and
  recorded.
- Craft a file whose compression-map declares an entry present with a
  missing crc field and assert the reader throws `CorruptSectionException`.
- After an abandon branch, assert the counter reads as zero (or matches
  the live buffer exactly) via a reflective or package-private getter.
