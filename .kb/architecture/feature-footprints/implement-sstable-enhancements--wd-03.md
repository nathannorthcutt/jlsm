---
title: "SSTable end-to-end integrity v5 (implement-sstable-enhancements WD-03)"
type: feature-footprint
tags: [sstable, integrity, checksums, crc32c, recovery, atomic-commit, adversarial-hardening]
feature_slug: implement-sstable-enhancements--wd-03
work_group: implement-sstable-enhancements
shipped: 2026-04-22
domains: [sstable, per-block-checksums, sstable-end-to-end-integrity, corruption-repair-recovery]
constructs:
  - "TrieSSTableWriter"
  - "TrieSSTableReader"
  - "CorruptSectionException"
  - "IncompleteSSTableException"
  - "FsyncSkipListener"
  - "VarInt"
  - "V5Footer"
  - "SSTableFormat"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableWriter.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableReader.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/CorruptSectionException.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/IncompleteSSTableException.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/FsyncSkipListener.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/VarInt.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/V5Footer.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/SSTableFormat.java"
related:
  - ".kb/systems/database-engines/corruption-detection-repair.md"
  - ".kb/patterns/validation/version-discovery-self-only-no-external-cross-check.md"
  - ".kb/patterns/validation/dispatch-discriminant-corruption-bypass.md"
  - ".kb/patterns/validation/silent-fallthrough-integrity-defense-coupled-to-flag.md"
  - ".kb/patterns/resource-management/atomic-move-vs-fallback-commit-divergence.md"
  - ".kb/patterns/resource-management/unbounded-zero-progress-channel-read-loop.md"
  - ".kb/patterns/resource-management/iterator-without-close-holds-coordination.md"
  - ".kb/patterns/concurrency/check-then-act-across-paired-acquire-release.md"
  - ".kb/patterns/concurrency/torn-volatile-publish-multi-field.md"
  - ".kb/architecture/feature-footprints/implement-sstable-enhancements--wd-02.md"
decision_refs:
  - "sstable-end-to-end-integrity"
  - "per-block-checksums"
  - "corruption-repair-recovery"
spec_refs:
  - "sstable.end-to-end-integrity"
  - "sstable.per-block-checksums"
research_status: stable
last_researched: "2026-04-22"
---

# SSTable end-to-end integrity v5

## Shipped outcome

Implemented v5 of the SSTable on-disk format: per-block CRC32C trailers +
per-section CRC32C + footer self-checksum + recovery scan + atomic commit +
reader `FAILED` terminal state. Corruption is now distinguishable from partial
writes (`CorruptSectionException` vs `IncompleteSSTableException`), and a
reader that encounters corruption mid-operation transitions to `FAILED` rather
than returning stale data. Writer commits atomically via
`<final>.partial.<uuid>` + `Files.move ATOMIC_MOVE` with a content-addressed
fallback for filesystems that do not support atomic rename; a
`FsyncSkipListener` surfaces degraded-durability events without silently
swallowing them.

One spec involved:
- `sstable.end-to-end-integrity` — v4 → v5 DRAFT (13 new Rs, 4 refinements,
  2 open obligations from audit: OB-01 writer FAILED ratification, OB-02
  writer-internal counter invariant). Promotion via `/spec-verify` pending.

## Key constructs

**New (5):** 3 exported in `jlsm.sstable`, 2 internal in `jlsm.sstable.internal`.

- `jlsm.sstable.CorruptSectionException` — thrown when a section-level CRC32C
  mismatch is detected (distinguishes from a truncated/partial write)
- `jlsm.sstable.IncompleteSSTableException` — thrown when the file ends before
  the footer (partial write from prior crash; recoverable in some paths)
- `jlsm.sstable.FsyncSkipListener` — emitted when the atomic-commit fallback
  path is taken or a declared fsync boundary is skipped; surfaces degraded-
  durability events instead of swallowing them
- `jlsm.sstable.internal.VarInt` — variable-length integer encoding prefix used
  by v5 entries; `public` visibility in non-exported internal package
  (rationale: writer and reader both need it and neither lives in
  `jlsm.sstable.internal`; module-info keeps the package un-exported)
- `jlsm.sstable.internal.V5Footer` — record + encode/decode for the v5 footer
  layout including self-checksum; `public` for the same reason as `VarInt`

**Extended (3):**

- `TrieSSTableWriter` — VarInt entry prefix, 3-fsync sequence (data → index →
  footer), per-section CRC32C, atomic commit via `<final>.partial.<uuid>` +
  `Files.move ATOMIC_MOVE`, content-addressed fallback, `FsyncSkipListener`
  plumbing, Builder opt-in `formatVersion(int)` preserving legacy v3/v4 test
  coverage while defaulting codec paths to v5
- `TrieSSTableReader` — magic-first dispatch (MAGIC_V5 vs legacy), `FAILED`
  terminal state (R43), `recoveryScan` with `RecoveryScanIterator`
  (AutoCloseable, serialized under `recoveryLock`), tight-pack validation
  lower bound, R38 mutex covering close coordination, lazy-mode section CRC
  verified eagerly at `openLazy()` (stricter than R28 requires; simpler)
- `SSTableFormat` — added `MAGIC_V5` constant + `FOOTER_SIZE_V5`

## API change the caller sees

```java
// Default writer path is now v5 — opt-in legacy for v3/v4 coverage
TrieSSTableWriter.builder()
    .id(1).level(Level.L0).path(out)
    .codec(CompressionCodec.deflate(6))
    // .formatVersion(4)          // NEW — opt-in for legacy coverage
    .fsyncSkipListener(listener)   // NEW — surface degraded durability
    .build();

// Reader now dispatches on magic; FAILED is a terminal state
try (var reader = TrieSSTableReader.open(path)) {
    reader.get(key);
} catch (CorruptSectionException e) {   // distinct from partial write
    // reader now in FAILED; do not reuse
} catch (IncompleteSSTableException e) { // partial-write from prior crash
    // recoverable via recoveryScan()
}
```

Caller-visible behavior changes:
- v5 files produced by default (Builder `formatVersion(int)` opts into v3/v4)
- Readers detect corruption eagerly and emit `CorruptSectionException` once,
  then refuse further operations (`FAILED` state)
- Atomic commit means readers never observe a partially-written SSTable at the
  final path; `FsyncSkipListener` events indicate when the content-addressed
  fallback was used

## Cross-references

**ADRs consulted:**
- [`sstable-end-to-end-integrity`](../../../.decisions/sstable-end-to-end-integrity/adr.md)
  v2 — governs the overall integrity model
- [`per-block-checksums`](../../../.decisions/per-block-checksums/adr.md)
  v1 — per-block CRC32C trailer format
- [`corruption-repair-recovery`](../../../.decisions/corruption-repair-recovery/adr.md)
  v3 — recovery scan semantics and `FAILED` state rationale

**KB entries used / created:**
- Used during authoring:
  [`corruption-detection-repair`](../../systems/database-engines/corruption-detection-repair.md)
- Created during audit (2026-04-22):
  [`version-discovery-self-only-no-external-cross-check`](../../patterns/validation/version-discovery-self-only-no-external-cross-check.md),
  [`dispatch-discriminant-corruption-bypass`](../../patterns/validation/dispatch-discriminant-corruption-bypass.md),
  [`silent-fallthrough-integrity-defense-coupled-to-flag`](../../patterns/validation/silent-fallthrough-integrity-defense-coupled-to-flag.md),
  [`atomic-move-vs-fallback-commit-divergence`](../../patterns/resource-management/atomic-move-vs-fallback-commit-divergence.md),
  [`unbounded-zero-progress-channel-read-loop`](../../patterns/resource-management/unbounded-zero-progress-channel-read-loop.md),
  [`iterator-without-close-holds-coordination`](../../patterns/resource-management/iterator-without-close-holds-coordination.md),
  [`check-then-act-across-paired-acquire-release`](../../patterns/concurrency/check-then-act-across-paired-acquire-release.md),
  [`torn-volatile-publish-multi-field`](../../patterns/concurrency/torn-volatile-publish-multi-field.md)

## Adversarial pipeline summary

| Phase | Findings | Applied |
|-------|----------|---------|
| `/spec-author` Passes 2-4 | 13 new Rs + 4 refinements | all |
| `/feature-harden` | — | baseline |
| `/audit` | 20 | 20 CONFIRMED_AND_FIXED |

Audit-surfaced findings (20), grouped by domain lens:
- **concurrency (6):** `recoveryScan` check-then-act race (serialized via
  `recoveryLock`), `RecoveryScanIterator` missing AutoCloseable, torn volatile
  publish across multiple fields, close coordination gap, R43 FAILED
  transition was unimplemented (!), ctor-failure unwind missing
- **contract_boundaries (3):** `writeFooterV5` producer-side guards absent,
  tight-pack lower-bound missing, recovery path silently bypassed CRC
- **data_transformation (8):** ATOMIC_MOVE vs fallback commit divergence,
  v5 CRC gated on legacy-coupled flag, oversized-entry error not descriptive,
  `dictBufferedBytes` not reset across entries, `mapLength > 2^31` narrowing
  bug, empty-bloom-filter handling, `readBytes` unbounded zero-progress spin,
  negative-length decode
- **dispatch_routing (3):** v5→legacy single-bit magic bypass (a flipped bit
  would have been silently treated as legacy — speculative self-checksum
  added), legacy branch did not raise `CorruptSectionException` at all,
  `expectedVersion` opt-in missing

Tests:
- 137 WU-1+WU-2 feature tests (test-first, TDD)
- ~25 audit adversarial regression tests
- Full `jlsm-core` suite: 1491 tests green

## Noteworthy constraints and pitfalls

- **Magic-first dispatch is the single point of version discovery.** Readers
  MUST NOT infer version from footer size, length prefixes, or any other
  field before validating the magic. A speculative self-checksum on the
  magic+version bytes prevents a single-bit flip from silently downgrading
  a v5 file into the legacy decode path.
- **Lazy-mode section CRC is verified eagerly at `openLazy()`.** R28 permits
  deferred verification, but the implementation chose the stricter contract
  (simpler state machine, no half-validated reader). Callers should not
  depend on the laxer spec reading.
- **R3 atomicity invariant is annotated UNTESTABLE in the verification note.**
  It governs an internal writer state machine; no failure-injection
  framework exists in jlsm to exercise mid-commit crashes deterministically.
  This is recorded explicitly rather than claimed as SATISFIED.
- **Atomic commit path has two variants that MUST produce equivalent visible
  state.** `<final>.partial.<uuid>` + `Files.move ATOMIC_MOVE` is the
  preferred path; on filesystems that do not support atomic rename, the
  fallback is content-addressed (hash-derived final name) with a
  `FsyncSkipListener` event. Callers watching the final directory must not
  assume rename semantics.
- **`FAILED` is a terminal reader state — not a transient mode.** Once a
  `CorruptSectionException` is raised, the reader refuses all further
  operations (including `close()` idempotent calls beyond the first). This
  is what distinguishes corruption from partial-write recovery.
- **`VarInt` / `V5Footer` are `public` in a non-exported package.** Module-
  info does NOT export `jlsm.sstable.internal`, so these types are reachable
  only via reflection/breakage from outside the module. The `public`
  visibility was chosen over package-private because writer and reader both
  live outside `jlsm.sstable.internal` and package-private was insufficient.
- **Four `@Disabled` tests remain for NIO-provider and channel-factory
  injection scenarios.** These require Jimfs / a channel-factory seam that
  jlsm does not yet provide. The scenarios are documented in the test files
  with TODOs referencing OB-01/OB-02.

## Prior art displaced (from this feature group)

- **v3/v4 default writer path** — superseded by v5 default. v3/v4 remain
  supported via Builder `formatVersion(int)` opt-in; all existing v3/v4
  tests pass unchanged. No `@spec` annotations from prior work were
  invalidated (the v3/v4 `@spec` annotations remain valid against those
  versions of the format).
- **Legacy reader dispatch** — previously size/length-based; replaced with
  magic-first dispatch. Legacy readers on legacy files are unchanged;
  mixed-version reads now go through the magic gate first.

## Related work definitions (same work group)

- WD-01 `sstable.byte-budget-block-cache` — COMPLETE (2026-04-21, PR #44).
  Independent; no overlap. See
  [WD-01 footprint](implement-sstable-enhancements--wd-01.md).
- WD-02 `sstable.pool-aware-block-size` — COMPLETE (2026-04-22). Independent;
  block-size derivation from pool does not interact with v5 on-disk integrity.
  See [WD-02 footprint](implement-sstable-enhancements--wd-02.md).

## Follow-up

- Spec v5 is DRAFT; `/spec-verify` needed to promote to APPROVED
- 2 open obligations added by audit:
  - OB-01: writer FAILED ratification (writer-side terminal-state symmetry
    with reader FAILED)
  - OB-02: writer-internal counter invariant (producer-side guard for v5
    section CRC counting)
- 4 `@Disabled` tests need Jimfs + channel-factory-injection infrastructure
