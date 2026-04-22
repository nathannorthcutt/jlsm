---
{
  "id": "sstable.pool-aware-block-size",
  "version": 5,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "sstable"
  ],
  "requires": [
    "sstable.v3-format-upgrade"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "automatic-backend-detection",
    "backend-optimal-block-size"
  ],
  "kb_refs": [
    "systems/database-engines/pool-aware-sstable-block-sizing"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F24"
  ]
}
---
# sstable.pool-aware-block-size — Pool-Aware Block Size Configuration

## Requirements

### Prerequisite changes to ArenaBufferPool (R0–R2 govern ArenaBufferPool, not the writer)

This spec amends the contract of `jlsm.core.io.ArenaBufferPool` — which does
not yet have its own spec file — with R0, R0a, R1, R1a, and R2. Promotion of
this spec to APPROVED is contingent on these five requirements being binding
on ArenaBufferPool in the same change-set as the writer-side changes.
Implementations of ArenaBufferPool that do not satisfy R0–R2 are non-conforming
to this spec even if the writer-side requirements (R3 onward) are met.

R0. ArenaBufferPool must expose a `public boolean isClosed()` method that returns `true` if and only if `close()` has been invoked at least once on this pool instance.

R0a. To prevent subclass spoofing of closure state, `ArenaBufferPool` must either (a) be declared `public final class`, or (b) if the class is ever made non-final, `isClosed()` must be declared `public final`. Subclass override of `isClosed()` is prohibited. If `ArenaBufferPool` is refactored to an interface in the future, that refactor must introduce a separate closure-truthfulness invariant on implementations before this spec's R5/R5a retain their intended meaning; this spec must be re-reviewed at that point.

R1. ArenaBufferPool must expose a `public long bufferSize()` method that returns the value passed to `Builder.bufferSize(long)`.

R1a. The field backing `bufferSize()` must be declared `final` and initialized in the ArenaBufferPool constructor before the pool reference escapes the constructor. `volatile`, `synchronized` access, or any other safe-publication mechanism is not permitted — `bufferSize()` must be a constant-time, lock-free field read so it can be called from concurrent builder threads without introducing memory-barrier or contention costs.

R2. `bufferSize()` must return the configured value and must not throw any exception regardless of whether `close()` has been invoked. `isClosed()` is the canonical observer for closure state; `bufferSize()` must not be overloaded to signal closure via a sentinel return value or via an exception.

R0b. `ArenaBufferPool.close()` must be thread-safe and idempotent under concurrent invocation. If two or more threads invoke `close()` concurrently, exactly one of them must perform the underlying `Arena` teardown; the others must observe that the teardown has already been performed (or will be, by the winning thread) and return cleanly. Concrete implementations include `AtomicBoolean.compareAndSet(false, true)` or equivalent atomic state-transition primitives; `synchronized(this)` is also permitted. Plain `volatile` check-then-act is insufficient (it admits multiple threads into `arena.close()`, and `Arena.ofShared().close()` throws `IllegalStateException` on the second invocation).

R0c. After `close()` has returned to any caller, all subsequent calls to `isClosed()` from any thread must observe the value `true`, and this observation must carry a happens-before edge to the completion of the underlying `Arena` teardown. That is, a thread that reads `isClosed() == true` must also observe the effects of `arena.close()` (segments invalid, native memory released). Implementations must therefore publish the `closed==true` flag only after `arena.close()` has returned, not before; threads that claimed the close transition but have not yet completed teardown must not expose `isClosed() == true` to observers.

R0d. `ArenaBufferPool`'s constructor must be transactional: if any allocation inside the pre-allocation loop fails (including `OutOfMemoryError` or any other `Throwable` from `arena.allocate`), the constructor must invoke `arena.close()` on the partially-initialized `Arena` before propagating the failure. No off-heap native memory may be leaked through a failed constructor. This applies to any `Throwable` from the allocation loop, not just checked exceptions.

R0e. `ArenaBufferPool.close()` must refuse to tear down the `Arena` while any acquired segments remain outstanding (i.e., `poolSize - queue.size() > 0`). Instead of invalidating in-use segments by closing the `Arena` underneath them, `close()` must throw `IllegalStateException` with a message identifying the outstanding count, leaving the pool intact so the caller can `release(segment)` and retry. The check must be consistent with R0b's atomic transition: the outstanding check must happen *before* the close transition is claimed, so that a failed `close()` leaves the pool in a reusable state (not in a stuck "closing" state).

### Builder pool method (R3–R5a govern TrieSSTableWriter.Builder)

R3. TrieSSTableWriter.Builder must expose a `pool(ArenaBufferPool)` method that accepts a non-null pool reference.

R4. `TrieSSTableWriter.Builder.pool(null)` must throw `NullPointerException`.

R4a. The null check in R4, the closed-pool check in R5, and the closed-pool re-check in R5a must be implemented as runtime checks (e.g., `Objects.requireNonNull`, explicit `if`/`throw`). `assert` statements may supplement these checks for development-time invariants but must never be the sole enforcement mechanism — assertions are disabled in production and would permit null or closed pools to slip past the builder.

R5. `TrieSSTableWriter.Builder.pool(ArenaBufferPool pool)` must throw `IllegalStateException` if `pool.isClosed()` returns `true` at the moment `pool()` is invoked. The exception message must state that the pool is closed and identify this as the `pool()`-call-time check.

R5a. At `build()` time, if a pool reference was supplied via `pool()`, that reference has not been displaced by a subsequent `pool()` call, **and** `blockSize(int)` has not been invoked (i.e., the pool is still the active block-size source), `build()` must re-check `pool.isClosed()`. If the pool is closed at build() time under these conditions, `build()` must throw `IllegalStateException` with a message that distinguishes this from the R5 case (e.g., "pool was closed between pool() and build()"). A pool whose block size has been displaced by an explicit `blockSize(int)` is not re-checked — once explicitly overridden, the pool's lifecycle is no longer the writer's concern.

### Block size derivation (R6–R10 govern TrieSSTableWriter.Builder)

R6. The builder must track whether `blockSize(int)` has been explicitly invoked, independently of the block-size field's current value. A single boolean flag, or equivalent, must be set true on any call to `blockSize(int)`.

R7. When a pool has been supplied via `pool()` and `blockSize(int)` has never been called, the writer must use the value returned by `pool.bufferSize()` as the block size. Narrowing of this `long` value to the writer's internal `int`-typed block-size field must occur only after R8 has been evaluated against the `long` candidate.

R7a. `pool()` must eagerly invoke `pool.bufferSize()` at the time of the call. If `blockSize(int)` has not been invoked prior to this `pool()` call, the builder must eagerly evaluate R8 and R9 against the returned `long` value. Validation failures throw at the `pool()` call site (not deferred to build()). This makes the pool-derivation path fail-fast in symmetry with the explicit `blockSize(int)` setter, which already validates eagerly per F16.R10–R11. If `blockSize(int)` is later invoked per R11 or R11a, the derived value and its validation are discarded and replaced.

R8. Before any narrowing conversion to the writer's internal `int`-typed block-size field, the candidate `long` value — whether `pool.bufferSize()` directly or any `long`-typed computation derived from `pool.bufferSize()` — must be compared against `Integer.MAX_VALUE` using strict inequality (`candidate > Integer.MAX_VALUE`). Any arithmetic that precedes this comparison must itself be performed with overflow-checked `long` semantics (e.g., `Math.addExact`, `Math.subtractExact`) so that a `long` overflow cannot silently produce a small, in-range value that passes R8 spuriously. If the comparison is true, the validating call (R7a-eager at `pool()` time, or build() if R7a was not applicable) must throw `IllegalArgumentException` with a message that includes the actual `long` candidate and, where it differs, the original `pool.bufferSize()`. The boundary case `candidate == Integer.MAX_VALUE` is not rejected by R8; it is passed unchanged to R9 and will be rejected there (Integer.MAX_VALUE is not a power of two and also exceeds the SSTableFormat maximum).

R9. Any block size derived from the pool must pass `SSTableFormat.validateBlockSize(int)`. This is the same validator used by the explicit `blockSize(int)` setter per F16.R10–R11; the derived and explicit paths must share the validator with no behavioral divergence.

R10. If the pool-derived block size fails R9, the validating call (R7a-eager at `pool()` time, or build() if R7a was not applicable) must throw `IllegalArgumentException` that surfaces the `SSTableFormat.validateBlockSize` diagnostic message — consistent with the behavior of an explicit `blockSize(int)` call.

### Explicit override and call cardinality (R11–R11a govern TrieSSTableWriter.Builder)

R11. If `blockSize(int)` has been invoked at any point during Builder configuration, the explicitly set value must be used regardless of whether `pool()` was also invoked, and regardless of the order in which `pool()` and `blockSize(int)` appear. A later `blockSize(int)` call must discard any previously derived (pool-originated) block-size value.

R11a. Repeated `blockSize(int)` calls are permitted: the most recent call's argument is the value used. Repeated `pool(ArenaBufferPool)` calls are permitted: the most recent non-null pool reference is the one retained, and if no `blockSize(int)` call has yet occurred, R7a re-fires against the new pool's `bufferSize()`. **Atomicity on repeated pool() calls:** R7a's validation (R8 and R9) must be evaluated against the *candidate* new pool *before* that pool replaces the currently retained reference. If R7a throws for the candidate pool, the builder's retained pool reference, the derived block-size value, and the R6 flag must be unchanged — a failed repeat `pool()` call is a no-op with respect to all builder state. Only after R7a validation succeeds may the candidate pool replace the previously retained reference and the derived block-size value be updated.

R11b. `TrieSSTableWriter.Builder.build()` must be transactional with respect to all caller-visible Builder state: if any validation inside `build()` throws (R5a closed-pool re-check, F16.R16 non-default-blockSize-without-codec check, or any other failure mode), every caller-visible Builder field — including `bloomFactory`, `pool`, `blockSize`, `blockSizeExplicit`, `derivedBlockSizeCandidate`, and any other field settable via the fluent API — must be unchanged from its value immediately before `build()` was invoked. A failed `build()` must be re-invokable by the caller after correcting the offending configuration, with no hidden state carried over from the failed attempt. This requirement extends R11a's atomicity guarantee (which is scoped to `pool()` call state) to the full builder-to-writer construction path.

### Default behavior (no pool; R13–R14 govern TrieSSTableWriter.Builder)

R13. When no pool has been supplied via `pool()` and `blockSize(int)` has never been invoked, the writer must use `SSTableFormat.DEFAULT_BLOCK_SIZE` (4096).

R14. Existing `build()` behavior at call sites that do not invoke `pool()` must be preserved. **Acceptance criterion, anchored at F16 v2 (the APPROVED state of `sstable.v3-format-upgrade` at the time this spec is promoted):** every pre-existing test method in the F16 v2 test suite whose builder configuration does not invoke `pool()` must pass unchanged. No pre-existing assertion in that scope may be weakened, removed, or relaxed to accommodate R7a or any other requirement in this spec. Test additions or strengthenings to the F16 suite after this spec's APPROVED date are permitted; what is forbidden is weakening a previously-enumerated assertion solely to accommodate this spec's changes. If F16 is itself revised to vN>2 before this spec is implemented, the scope of R14 shifts to the F16 vN test suite state at the time of this spec's implementation merge — the invariant is "no weakening of non-pool test assertions," not a literal pin to v2 test method identities.

### F16 interaction (R15–R16 govern TrieSSTableWriter.Builder)

R15. Pool-derived block sizes are governed by F16.R16 identically to explicit block sizes. No rule in this spec overrides or modifies F16.R16: a non-default block size (whether explicit or pool-derived) without a configured compression codec must cause `build()` to throw `IllegalArgumentException`.

R16. The effective block size (after any pool derivation and all validation) must be the value written to the SSTable footer per F16.R15. Pool derivation must not introduce a divergence between the "validated block size" and the "block size stored on disk".

### Non-goals

N1. The writer does not retain the `ArenaBufferPool` reference for runtime buffer acquisition. The pool is queried for `bufferSize()` eagerly at `pool()` call time (per R7a), re-queried for `isClosed()` at build() time (per R5a, when still the active block-size source), and is not stored on the constructed writer instance. Runtime buffer acquisition is a separate concern that would require a distinct, future spec.

N2. Integration with `StripedBlockCache` (or any other block-cache implementation) is out of scope for this spec. Cache sizing uses `sstable.byte-budget-block-cache`'s `byteBudget(long)` API independently and is governed by that spec.

N3. This spec assumes `ArenaBufferPool.Builder.bufferSize(long)` accepts values up to `Long.MAX_VALUE` and does not itself impose an upper bound at or below `Integer.MAX_VALUE`. R8 is the gate that catches values exceeding `Integer.MAX_VALUE` at the pool→writer boundary. If `ArenaBufferPool` subsequently adopts an internal upper bound at or below `Integer.MAX_VALUE`, R8 becomes unreachable for that failure mode and the combined coverage surface must be re-evaluated; this spec must be re-reviewed at that point.

N4. If `pool()` is provided with a live pool whose `bufferSize()` equals `SSTableFormat.DEFAULT_BLOCK_SIZE` (4096), and the pool remains open through `build()`, derivation proceeds normally and the constructed writer and its output SSTable are byte-identical to the no-pool path. This equivalence holds only in the success path — closed-pool throws per R5/R5a, and any other error behaviors on the pool-supplied path, remain observable and distinguish this path from the no-pool path.

---

## Design Narrative

### Intent

Block size is a deployment-level resource concern — local deployments use 4 KiB blocks
aligned to the OS page, remote deployments use megabyte-scale blocks to amortize
per-request latency. The `ArenaBufferPool` already centralizes the off-heap memory
budget; coupling the SSTable writer's block size to the pool's buffer size eliminates
a misconfiguration vector where the two values drift out of sync and the writer
either strands pool slots or cannot stage a block in a single `acquire()`.

The user configures a single deployment profile on the pool. The writer inherits it
unless the user explicitly overrides via `blockSize(int)`. There is no auto-detection
and no silent fallback — pool-derived block sizes are validated identically to
explicit ones, with symmetric fail-fast timing at the setter call site.

### Why this approach

- **Fail-fast symmetry.** The existing `blockSize(int)` setter validates eagerly
  (F16.R10–R11). R7a extends the same property to `pool()`: if the pool's
  `bufferSize()` is invalid, the user learns at the `pool()` call, not at build().
  The two setters are ergonomically interchangeable.
- **Single source of truth.** The pool's bufferSize is the authoritative block
  size unless explicitly overridden. This collapses a family of misconfiguration
  cases (pool slot size ≠ writer block size) into a single, policy-driven
  configuration point.
- **Minimal ArenaBufferPool surface.** The additions to `ArenaBufferPool` are
  two accessors (`long bufferSize()`, `boolean isClosed()`) and two structural
  requirements (`final` class / final isClosed(), `final` backing field for
  bufferSize). All are standard additions for any `AutoCloseable`/`Pool` type
  and can be verified independently of this spec.
- **Builder atomicity on repeat pool().** R11a's failed-pool()-is-a-no-op
  semantics means a catch-and-retry pattern works predictably: if
  `pool(poolB)` throws on a repeated call, the builder is still configured
  with `poolA`, and the caller can continue or recover. Without this, two
  conforming implementations could leave the builder in contradictory states
  after a caught R7a throw.

### Prerequisite scoping — cross-construct dependency

R0, R0a, R1, R1a, R2 are requirements on `ArenaBufferPool`, not on
`TrieSSTableWriter.Builder`. `ArenaBufferPool` does not currently have its
own spec file — its behavior is governed implicitly by its source code and
by the `io-internals.md` project rule. This spec therefore bundles the
ArenaBufferPool contract changes inline. If a future `.spec/domains/io/arena-buffer-pool.md`
spec is authored, R0/R0a/R1/R1a/R2 should migrate there and this spec should
adopt a `requires: ["io.arena-buffer-pool"]` dependency in place of the inline
requirements. Until then, implementations must treat R0–R2 as binding on
ArenaBufferPool.

### What was ruled out

- **Retaining the pool reference for runtime acquisition (R12 in v2):** struck
  in v3 as it directly contradicted N1. The writer's runtime buffer strategy is
  out of scope for this spec.
- **Deferring all validation to build():** rejected in v3 because it introduced
  a user-visible timing asymmetry with the existing `blockSize(int)` setter.
- **Auto-detecting backend type inside the library:** addressed by the
  `automatic-backend-detection` ADR.
- **Permissive safe-publication for `bufferSize()`:** R1a mandates `final`
  specifically (v4 tightening). Permitting `volatile` or `synchronized` would
  let implementations add contention to a cold-path constant read.
- **Permitting subclass override of `isClosed()`:** R0a forbids it (v4
  tightening). A subclass that lies about closure state can bypass R5/R5a,
  defeating the purpose of the closure check.

### Relation to F16 / sstable.v3-format-upgrade

This spec layers a new derivation path on top of the existing block-size
plumbing from F16. It does not change F16.R10–R24; it only adds a second way
to populate the `blockSize` field on `TrieSSTableWriter.Builder`. F16.R16
(non-default block size requires a codec) applies unconditionally — R15
makes this explicit rather than duplicating the rule.

### Known limitations

- **R7a eager-throws on pool-first invalid configurations.** A user who
  calls `pool(invalidPool)` first, intending to override via a subsequent
  `blockSize(int)`, cannot — the `pool()` call throws before the override
  can run. This is consistent with the existing `blockSize(int)` setter,
  which also cannot be "walked back" from an invalid value. Users must
  either call `blockSize(int)` before `pool()`, or validate the pool
  externally before passing it.

### Adversarial Review Notes

**v1 → v2:** 8 reqs / 7 failures. R4 silent fallback replaced by R10
throw-on-invalid; R1 split into R3–R5; derivation cast and overflow explicit;
R5 ordering resolved; F16 interaction added; non-goals added.

**v2 → v3:** 13 findings applied — R12 deleted (contradiction with N1);
R7/R8 tightened against silent-narrowing; R5a + R0 for closed-pool race;
R1a for safe publication; R4a for runtime-check mechanism; R7a for fail-fast
symmetry; R11a for last-wins; R14 pinned to F16 test suite; N3/N4 for coverage
and silent-equivalence observability.

### Amended: v4 → v5 — 2026-04-22 (adversarial audit feedback)

The audit pipeline (run-001) surfaced 5 CONFIRMED_AND_FIXED findings on the ArenaBufferPool and TrieSSTableWriter.Builder constructs. The fixes implemented behaviors that exceeded the written v4 spec; v5 adds those behaviors as explicit requirements so they are auditable going forward:

- **R0b** — close() thread-safe and idempotent under concurrent invocation (from finding F-R001.shared_state.2.1 / F-R1.concurrency.1.1).
- **R0c** — isClosed()==true implies Arena teardown complete (happens-before), from F-R001.shared_state.2.2.
- **R0d** — ctor transactional on allocation failure (Throwable-catching cleanup), from F-R001.shared_state.2.3.
- **R0e** — close() refuses with ISE when acquires outstanding (pool stays reusable), from F-R1.concurrency.1.2.
- **R11b** — Builder.build() transactional across the full field set (extends R11a beyond pool/blockSize to all caller-visible fields), from F-R1.shared_state.1.1.

All 5 requirements are already implemented in the current codebase (applied by the audit's prove-fix phase). The v4→v5 bump makes the spec describe-what-is, closing the gap between behavior and contract. These requirements will likely migrate to a future `io.arena-buffer-pool` spec (R0b/c/d/e) and remain in this spec (R11b).

Audit run record: `.feature/implement-sstable-enhancements--wd-02/audit/run-001/audit-report.md` (7 findings analyzed, 5 fixed, 2 phase-0 short-circuits).

### Verified: v4 — 2026-04-22

| Req | Verdict | Evidence |
|-----|---------|----------|
| R0 | SATISFIED | `ArenaBufferPool.java:72` — returns volatile `closed` flag |
| R0a | SATISFIED | `ArenaBufferPool.java:14` — class declared `public final`; verified by reflection test `arenaBufferPool_classIsFinal` |
| R1 | SATISFIED | `ArenaBufferPool.java:86` — returns `bufferSize` field |
| R1a | SATISFIED | `ArenaBufferPool.java:19` — `private final long bufferSize`; verified by reflection test `bufferSize_backingField_isFinal` |
| R2 | SATISFIED | `ArenaBufferPool.java:86` — simple field read, no closure check, no throw path |
| R3 | SATISFIED | `TrieSSTableWriter.java:782` — `Builder.pool(ArenaBufferPool)` returns `Builder` for chaining |
| R4 | SATISFIED | `TrieSSTableWriter.java:783` — `Objects.requireNonNull(pool, ...)` first line of the method |
| R4a | SATISFIED | runtime `Objects.requireNonNull` (R4) and explicit `if (pool.isClosed()) throw ISE` (R5); no `assert`-only guards in this path |
| R5 | SATISFIED | `TrieSSTableWriter.java:785-788` — `pool.isClosed()` check with descriptive ISE identifying call-time |
| R5a | SATISFIED | `TrieSSTableWriter.java:902-907` — build-time re-check gated on `pool != null && !blockSizeExplicit`; distinct "closed between pool() and build()" message |
| R6 | SATISFIED | `TrieSSTableWriter.java:744` — `this.blockSizeExplicit = true;` set on every invocation of `blockSize(int)` |
| R7 | SATISFIED | `TrieSSTableWriter.java:918-920` — `effectiveBlockSize = (int) derivedBlockSizeCandidate` when pool retained and not displaced by explicit |
| R7a | SATISFIED | `TrieSSTableWriter.java:791-810` — eager evaluation of R8 + R9 inside `if (!this.blockSizeExplicit)` block |
| R8 | SATISFIED | `TrieSSTableWriter.java:795-800` — `candidate > Integer.MAX_VALUE` on `long` before any narrowing cast; strict inequality; message includes the long value |
| R9 | SATISFIED | `TrieSSTableWriter.java:804` — `SSTableFormat.validateBlockSize(derivedInt)` after R8 passes |
| R10 | SATISFIED | `TrieSSTableWriter.java:804` — `validateBlockSize` throws IAE whose message surfaces min/max/power-of-two diagnostic |
| R11 | SATISFIED | `TrieSSTableWriter.java:918-920` — build() prefers `this.blockSize` over `derivedBlockSizeCandidate` when `blockSizeExplicit` is true, regardless of call order |
| R11a | SATISFIED | `TrieSSTableWriter.java:785-811` — validation (R5, R8, R9) completes BEFORE `this.pool = pool;` and `this.derivedBlockSizeCandidate = candidate;`; failed validation throws with builder state unchanged (atomicity) |
| R13 | SATISFIED | `TrieSSTableWriter.java:920` — `effectiveBlockSize = this.blockSize` default path; `blockSize` initializer is `SSTableFormat.DEFAULT_BLOCK_SIZE` |
| R14 | SATISFIED | Full `./gradlew :modules:jlsm-core:check` + `:tests:jlsm-remote-integration:test` passed: 1349 tests green, 0 failures, 0 errors, 1 pre-existing `@Disabled`. All F16-suite tests that do not invoke `pool()` pass unchanged. |
| R15 | SATISFIED | `TrieSSTableWriter.java:925-929` — existing F16.R16 check `effectiveBlockSize != DEFAULT_BLOCK_SIZE && codec == null → IAE` applies uniformly to explicit and pool-derived values |
| R16 | SATISFIED | `TrieSSTableWriter.java:931` — ctor receives `effectiveBlockSize`; existing F16.R15 plumbing writes it to the v3 footer |

**Overall: PASS**

Amendments applied: 0 (all code matches spec text; no stale wording)
Code fixes applied: 0 (all 22 requirements SATISFIED on first verify against the implementation)
Regression tests added: 0 (no violations to regress)
Test-gap fixes: 1 (added `@spec R6` annotation to `explicitBlockSize_lastWins_whenCalledMultipleTimes` — existing test already verifies the behavior)
Obligations deferred: 0
Undocumented behavior: 0

**Trace coverage (via `spec-trace.sh`):** 21/21 requirements with code-level annotations. R14 is a regression invariant verified by the full test-suite run, not a code-level annotation target. R0a / R1a are structural invariants (class-final, field-final); they are verified only via reflection tests (no per-method impl location to annotate).

---

## v3 → v4 adversarial notes (retained)

**v3 → v4 (this version):** 9 fix-consequence findings applied —
- R11a tightened with atomicity-on-repeat-pool (F15): failed `pool()` is a
  no-op with respect to all builder state.
- ArenaBufferPool dependency scoping (F16): R0/R0a/R1/R1a/R2 explicitly
  bundled as prerequisite changes with a named migration path to a future
  ArenaBufferPool spec.
- R2 tightened from "returns" to "returns and must not throw" (F17).
- R14 anchored to F16 v2 as of this spec's APPROVED date (F18).
- R0a added to forbid subclass override of isClosed() (F19).
- R8 broadened from "narrowing of `pool.bufferSize()`" to any candidate
  `long` computed from `pool.bufferSize()`, with overflow-checked arithmetic
  mandated (F20).
- R5a scoped to active-block-size pools only (F21): a pool whose bufferSize
  has been displaced by explicit `blockSize(int)` is no longer re-checked
  at build().
- N4 narrowed to "byte-identical output on successful build with live pool"
  (F22).
- R1a tightened from "prefer final" to "must be final, no other safe
  publication permitted" (F23).
