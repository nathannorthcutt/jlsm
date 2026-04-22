---
title: "Partial Initialization Without Rollback (Validation-Lens)"
aliases: ["partial-init leak validation variant", "commit-before-validate", "pre-validation field default", "ctor allocation loop no rollback"]
topic: "patterns"
category: "validation"
tags: ["adversarial-finding", "validation", "initialization", "rollback", "resource-management", "constructor", "builder"]
type: "adversarial-finding"
research_status: "stable"
confidence: "high"
last_researched: "2026-04-22"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/core/io/ArenaBufferPool.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableWriter.java"
related:
  - "mutation-outside-rollback-scope"
  - "mutable-state-escaping-builder"
  - "reflective-bypass-of-builder-validation"
  - "../resource-management/partial-init-no-rollback"
  - "../resource-management/mutation-outside-rollback-scope"
decision_refs: []
sources:
  - "audit run-001 implement-sstable-enhancements--wd-02"
---

# Partial Initialization Without Rollback (Validation-Lens)

## Summary

A multi-step construction sequence — a constructor allocation loop or a
Builder `build()` method — performs a mutation before the sequence's later
validation gates run. When a later step throws, the pre-validation mutation
is never rolled back. Two concrete shapes recur:

1. **Ctor off-heap variant.** A constructor claims an off-heap resource
   (e.g., `Arena.ofShared()`) and then allocates N slabs in a loop with no
   `try { … } catch (Throwable t) { resource.close(); throw t; }`. If
   allocation throws on slab K (including `OutOfMemoryError`), slabs 1..K-1
   are owned by a resource that no caller can reach — the partially-built
   object is never published, so nothing ever calls `close()`.
2. **Builder variant.** `build()` defaults a Builder field (`this.x =
   <default>`) at the top of the method, then fails a validation gate
   further down. The caller's Builder retains the silent mutation, so a
   retry after the caller fixes the validation issue still produces an
   object wired with the leaked default instead of the caller's later
   explicit choice.

Both shapes were confirmed in one adversarial audit on a single feature —
the root failure mode (mutation outside the rollback scope) is invariant
across the ctor- and builder-flavours.

## Problem

### Ctor off-heap variant

```java
public ArenaBufferPool(int poolSize, long bufferSize) {
    this.arena = Arena.ofShared();
    this.queue = new LinkedBlockingQueue<>(poolSize);
    // BUG: loop may throw on slab K; slabs 1..K-1 remain owned by the
    // Arena, and the partially-built `this` never escapes, so no caller
    // can ever invoke close() — the native slab memory leaks for the
    // JVM lifetime (GC does not release Arena-backed off-heap memory).
    for (int i = 0; i < poolSize; i++) {
        queue.offer(arena.allocate(bufferSize, 8));
    }
}
```

### Builder variant

```java
public TrieSSTableWriter build() throws IOException {
    Objects.requireNonNull(level, "level must be set");
    Objects.requireNonNull(path, "path must be set");
    // BUG: commit-before-validate — this.bloomFactory is mutated before
    // the R5a pool-closed gate and the R15 codec-pairing gate below.
    if (bloomFactory == null) {
        bloomFactory = n -> new BlockedBloomFilter(n, 0.01);
    }
    // ... R5a: ISE if pool is closed ...
    // ... R15: IAE if non-default block size but no codec ...
    return new TrieSSTableWriter(id, level, path, bloomFactory, codec, ...);
}
```

If the caller set `blockSize(16384)` without a codec (R15 failure path),
`build()` throws, but the caller's `Builder.bloomFactory` has already been
overwritten with the default lambda. A retry after the caller sets their
own factory still produces the wrong writer.

## Symptoms

- Ctor flavour: off-heap memory usage that cannot be reclaimed by GC —
  confirmed by JVM NMT (`-XX:NativeMemoryTracking=summary`) showing "Other"
  category reservation growing by ~27 MiB per failed build attempt, persisting
  to JVM exit.
- Builder flavour: retry after a failed `build()` silently inherits a
  default the caller never chose; behavior diverges from the "fresh Builder"
  path by the leaked field's value.
- Both: test passes for the happy-path `build()` but fails a "state after
  throw equals state before call" property test.

## Root Cause

The sequence is neither atomic nor transactional. A mutation step (slab
allocation, field default) is placed outside the try/catch scope that would
otherwise roll it back. The developer typically assumes the mutation
"cannot fail" (allocation from a freshly-created Arena; defaulting a null
Builder field) and so treats it as too cheap to protect — but **the cost of
the rollback is not what matters; what matters is that a later step in the
same logical operation can throw, and every mutation preceding that throw
is part of the operation's visible state.**

The ctor variant is specifically poisoned by `OutOfMemoryError` being a
`Throwable`, not an `Exception`: any `catch (Exception)` wrapper silently
misses it, and the default propagation path leaks the Arena's slabs to
process exit.

## Fix Pattern

### Ctor off-heap variant

Wrap the allocation loop in `try { … } catch (Throwable t) { resource.close();
throw t; }`:

```java
public ArenaBufferPool(int poolSize, long bufferSize) {
    this.arena = Arena.ofShared();
    this.queue = new LinkedBlockingQueue<>(poolSize);
    try {
        for (int i = 0; i < poolSize; i++) {
            queue.offer(arena.allocate(bufferSize, 8));
        }
    } catch (Throwable t) {
        try { arena.close(); }
        catch (Throwable closeFailure) { t.addSuppressed(closeFailure); }
        throw t;
    }
}
```

Two discipline points: catch `Throwable` (not `Exception`) because OOM is
the realistic failure here, and `addSuppressed` any cleanup failure so the
root cause propagates unmasked.

### Builder variant

Resolve defaults into a method-local variable **after** all validation
gates, not back onto `this.<field>`:

```java
public TrieSSTableWriter build() throws IOException {
    Objects.requireNonNull(level, "level must be set");
    Objects.requireNonNull(path, "path must be set");
    // ... R5a check ...
    // ... R15 check ...
    final BloomFilter.Factory effectiveBloomFactory =
            (bloomFactory != null) ? bloomFactory
                                   : n -> new BlockedBloomFilter(n, 0.01);
    return new TrieSSTableWriter(id, level, path, effectiveBloomFactory,
            codec, effectiveBlockSize, ...);
}
```

The caller's `this.bloomFactory` is never mutated by `build()` — a failed
`build()` leaves the Builder exactly as the caller left it, matching the
last-wins atomicity pattern followed by every other setter.

## Detection

- **Shared-state lens**: trace every mutation inside a multi-step operation
  (ctor, `build()`, `start()`); for each, ask "what state did the caller
  observe before the call, and what state would they observe now if the
  call throws?" If those two states differ, the mutation is outside the
  rollback scope.
- **Adversarial test**: inject a factory that throws on the K-th slab
  allocation (ctor variant) or a codec-pairing violation that makes `build()`
  fail after the default was written (Builder variant). Assert
  pre-call-observable-state equals post-exception-observable-state.
- **NMT for the ctor flavour**: enable `-XX:NativeMemoryTracking=summary`
  in the test JVM; assert the "Other" category does not grow after a failed
  build by more than a small threshold (a single leak is ~27 MiB on a
  100M-poolSize × 1-byte slab configuration — far above the <1 MiB NMT
  noise floor).

## Audit Findings

Identified in `implement-sstable-enhancements--wd-02` audit run-001:

- **F-R001.shared_state.2.3** — `ArenaBufferPool.<init>` allocation loop
  had no `try/catch(Throwable)` around the slab loop; failed builds leaked
  ~27 MiB of native memory per attempt.
- **F-R1.shared_state.1.1** — `TrieSSTableWriter.Builder.build()` wrote
  `this.bloomFactory = <default lambda>` at the top of the method before
  the R5a and R15 validation gates; a failed `build()` left the Builder
  mutated, so a retry observed the silently-leaked default.

Both findings landed on the same abstract pattern with two distinct
construct shapes, validating the cross-cutting classification of this
entry.
