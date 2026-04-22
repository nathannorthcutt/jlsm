---
title: "Pool-aware SSTable block size (implement-sstable-enhancements WD-02)"
type: feature-footprint
tags: [sstable, block-size, arena-buffer-pool, pool-aware, adversarial-hardening]
feature_slug: implement-sstable-enhancements--wd-02
work_group: implement-sstable-enhancements
shipped: 2026-04-22
domains: [sstable, io-resources]
constructs: ["TrieSSTableWriter.Builder", "ArenaBufferPool"]
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableWriter.java"
  - "modules/jlsm-core/src/main/java/jlsm/core/io/ArenaBufferPool.java"
related:
  - ".kb/systems/database-engines/pool-aware-sstable-block-sizing.md"
  - ".kb/patterns/validation/partial-init-no-rollback.md"
  - ".kb/patterns/validation/mutation-outside-rollback-scope.md"
  - ".kb/patterns/resource-management/pool-close-with-outstanding-acquires.md"
  - ".kb/patterns/concurrency/non-atomic-lifecycle-flags.md"
  - ".kb/patterns/resource-management/non-idempotent-close.md"
  - ".kb/architecture/feature-footprints/implement-sstable-enhancements--wd-01.md"
decision_refs:
  - "automatic-backend-detection"
  - "backend-optimal-block-size"
spec_refs:
  - "sstable.pool-aware-block-size"
research_status: stable
last_researched: "2026-04-22"
---

# Pool-aware SSTable block size

## Shipped outcome

Added a `pool(ArenaBufferPool)` method to `TrieSSTableWriter.Builder` that
derives the writer's block size from the pool's buffer size, eliminating a
misconfiguration vector where writer block size and pool slot size could drift
apart silently. Before: callers had to remember to call `blockSize(int)` with
the same value used to construct the pool; any drift produced either wasted
pool capacity (writer block smaller than slot) or pool contention and
fragmentation (writer block larger than slot, forcing re-acquire). After:
handing the pool to the builder derives the block size exactly, and `build()`
verifies the pool is still open before using it.

One spec involved:
- `sstable.pool-aware-block-size` — authored v2 DRAFT → v5 APPROVED

Two accessors added to `ArenaBufferPool` support this:
- `isClosed()` — build-time liveness re-check (R5a)
- `bufferSize()` — block-size source (R6/R11)

## Key constructs

Two production files modified, no new production files.

- `modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableWriter.java` —
  `Builder.pool(ArenaBufferPool)` method (R1), `blockSizeExplicit` flag
  distinguishing explicit `blockSize(int)` from pool-derived (R11), R5a
  build-time pool-liveness re-check, R7 interaction with default block-size
  fallback, R8 `Integer.MAX_VALUE` overflow gate on the `long` value BEFORE
  narrowing cast, R11a repeat-`pool()` transactional validation, R11b
  Builder-wide transactional `build()` (no `this.<field>` mutation before all
  gates pass; defaults resolved into method-local `final` variables)
- `modules/jlsm-core/src/main/java/jlsm/core/io/ArenaBufferPool.java` — added
  `isClosed()` and `bufferSize()` accessors; audit round-001 hardened
  `close()` to two-phase (`AtomicBoolean closing` atomic-claim +
  `volatile boolean closed` post-teardown ack) for idempotent CAS close,
  added ctor `try/catch` cleanup of partial init, and an outstanding-acquire
  `IllegalStateException` guard refusing close when
  `poolSize - queue.size() > 0`

Three test-only files added / modified:
- `modules/jlsm-core/src/test/java/jlsm/core/io/ArenaBufferPoolTest.java` —
  10 new tests covering `isClosed()`, `bufferSize()`, two-phase close, and
  outstanding-acquire ISE
- `modules/jlsm-core/src/test/java/jlsm/sstable/TrieSSTableWriterPoolAwareBlockSizeTest.java`
  (new) — 33 tests in 11 `@Nested` classes, one per requirement group
- `modules/jlsm-core/src/test/java/jlsm/core/io/SharedStateAdversarialTest.java`
  (new) — 3 audit regression tests (two-phase close, partial-init cleanup,
  outstanding-acquire guard)
- `modules/jlsm-core/src/test/java/jlsm/core/io/ConcurrencyAdversarialTest.java`
  (new) — 1 audit regression test (concurrent close CAS)
- `modules/jlsm-core/src/test/java/jlsm/sstable/SharedStateAdversarialTest.java`
  (modified) — 1 audit regression test for Builder transactional build()

## API change the caller sees

```java
// New fluent API
TrieSSTableWriter.builder()
    .id(1).level(Level.L0).path(out)
    .codec(CompressionCodec.deflate(6))
    .pool(arenaBufferPool)   // NEW — derives blockSize from pool.bufferSize()
    .build();
```

`pool(ArenaBufferPool)` and `blockSize(int)` are mutually exclusive signals
for the block-size source — last-wins semantics via `blockSizeExplicit`.
Calling `blockSize(n)` after `pool(p)` promotes to explicit and the pool's
lifecycle is no longer the writer's concern (R5a is skipped). Calling
`pool(p2)` after `pool(p1)` replaces the source only if p2 passes R8/R9
validation — failed replacement is a no-op on builder state (R11a).

## Cross-references

**ADRs consulted:**
- [`automatic-backend-detection`](../../../.decisions/automatic-backend-detection/adr.md)
  — confirmed; the pool slot size is the proxy for backend-optimal block
  size, so deriving from the pool implicitly picks up whatever detection
  the pool was sized for
- [`backend-optimal-block-size`](../../../.decisions/backend-optimal-block-size/adr.md)
  — accepted; underpins why the pool slot size is meaningful as a block-size
  default

**KB entries used / created:**
- Used during authoring:
  [`pool-aware-sstable-block-sizing`](../../systems/database-engines/pool-aware-sstable-block-sizing.md)
  — the underlying research rationale
- Created during audit round-001:
  [`partial-init-no-rollback`](../../patterns/validation/partial-init-no-rollback.md),
  [`mutation-outside-rollback-scope`](../../patterns/validation/mutation-outside-rollback-scope.md),
  [`pool-close-with-outstanding-acquires`](../../patterns/resource-management/pool-close-with-outstanding-acquires.md)
- Updated during audit round-001:
  [`non-atomic-lifecycle-flags`](../../patterns/concurrency/non-atomic-lifecycle-flags.md),
  [`non-idempotent-close`](../../patterns/resource-management/non-idempotent-close.md)

## Adversarial pipeline summary

| Phase | Findings | Applied |
|-------|----------|---------|
| `/spec-author` Pass 2 (falsification) | 13 | 13 |
| `/spec-author` Pass 3 (fix-consequence) | 9 | 9 |
| `/spec-author` Pass 4 (focused depth) | 0 | — |
| `/feature-harden --lite` | 0 | — |
| `/spec-verify` | 0 violations (22/22 SATISFIED) | 1 test-gap fix |
| `/audit` round-001 | 7 | 5 CONFIRMED_AND_FIXED, 2 Phase-0 short-circuits |

Audit round-001 surfaced five confirmed gaps in `ArenaBufferPool` that the
spec's own 22 requirements did not cover (they were orthogonal to pool-aware
block sizing but exposed as `isClosed()` and `bufferSize()` newly widened
the pool's public surface): non-idempotent close, non-atomic `closed` flag,
partial-init leaks in the ctor, and close-with-outstanding-acquires as a
silent resource leak. Two Phase-0 findings short-circuited as already-
resolved by the spec's R11b transactional build.

## Noteworthy constraints and pitfalls

- **R8 ordering — overflow comparison on `long` BEFORE narrowing cast:**
  `candidate > Integer.MAX_VALUE` MUST run on the `long` value returned by
  `pool.bufferSize()`. The narrowing cast to `int` happens only after R8
  passes. Any intermediate arithmetic on `pool.bufferSize()` must use
  `Math.addExact` / `Math.subtractExact` to preserve exact-fail semantics.
- **R11a repeat-`pool()` atomicity:** on a second `pool(p2)` call after
  `pool(p1)`, validate `p2.bufferSize()` against R8/R9 BEFORE replacing
  `this.pool` / `this.derivedBlockSizeCandidate`. Failed validation must
  leave the builder as if `pool(p2)` was never called — the retained p1
  state is observable and usable.
- **R11b Builder-wide transactional `build()`:** `build()` must not mutate
  any `this.<field>` before all validation gates pass. Default values are
  resolved into method-local `final` variables, then passed to the ctor.
  A later gate failing cannot leave the Builder in a partially-defaulted
  state that a retry would observe.
- **R5a scoping — pool-liveness only when pool is the active source:** the
  build-time `isClosed()` re-check is gated on `pool != null &&
  !blockSizeExplicit`. Once explicit `blockSize(int)` has been called, the
  pool is no longer the active block-size source (per R11) and its
  lifecycle is not the writer's concern — R5a is skipped.
- **ArenaBufferPool.close() is now two-phase:** `AtomicBoolean closing`
  (atomic claim) + `volatile boolean closed` (post-teardown ack).
  `isClosed()` returns `closed`. The spin-wait in the losing CAS branch
  preserves the contract that "close() returns with isClosed() observable
  as true from any thread" even across concurrent callers.
- **ArenaBufferPool.close() refuses with ISE when outstanding acquires
  exist:** specifically when `poolSize - queue.size() > 0`. Callers must
  release all acquired segments before close. Pre-existing callers
  (`LocalWriteAheadLog`, `RemoteWriteAheadLog`, `SpookyCompactor`) already
  honor this via try-finally release patterns — the guard is defensive for
  future callers, not a regression for current ones.

## Prior art displaced (from this feature group)

None. This feature is purely additive: `blockSize(int)` still works exactly
as before; `pool(ArenaBufferPool)` is a new orthogonal signal with
last-wins precedence. No existing `@spec` annotations were invalidated.

## Related work definitions (same work group)

- WD-01 `sstable.byte-budget-block-cache` — COMPLETE (shipped 2026-04-21,
  PR #44). Independent; no overlap with pool-aware block size semantics.
  See [WD-01 footprint](implement-sstable-enhancements--wd-01.md).
- WD-03 `sstable.end-to-end-integrity` — READY, not yet implemented.
  Independent; no interaction expected with pool-aware block size.
