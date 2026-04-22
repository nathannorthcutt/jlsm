---
title: "Mutation Outside Rollback Scope (Builder Variant)"
aliases: ["commit-before-validate", "builder field mutation before gate", "pre-validation default write", "retry-poisoning builder"]
topic: "patterns"
category: "validation"
tags: ["adversarial-finding", "validation", "builder", "atomicity", "rollback", "retry"]
type: "adversarial-finding"
research_status: "stable"
confidence: "high"
last_researched: "2026-04-22"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableWriter.java"
related:
  - "partial-init-no-rollback"
  - "mutable-state-escaping-builder"
  - "reflective-bypass-of-builder-validation"
  - "../resource-management/mutation-outside-rollback-scope"
decision_refs: []
sources:
  - "audit run-001 implement-sstable-enhancements--wd-02"
---

# Mutation Outside Rollback Scope (Builder Variant)

## Summary

A Builder's `build()` method mutates a Builder field (usually to default a
missing value) **before** all of its validation gates run. When a later
gate throws, the Builder the caller still holds has been silently modified.
A retry after the caller addresses the validation failure therefore observes
the leaked default instead of the fresh state the caller expected — the
Builder no longer behaves like a re-enterable factory but like a stateful
one whose internal behaviour depends on prior failed invocations.

This is a focused variant of the broader "mutation outside rollback scope"
pattern. The scope here is not a try/catch block but the *logical
transaction* of `build()`: every caller expects `build()` to be atomic —
either it returns a fresh object and may have mutated internal state, or it
throws and has mutated nothing visible to the caller. Mutating Builder
fields before validation gates breaks that invariant.

## Problem

```java
public TrieSSTableWriter build() throws IOException {
    Objects.requireNonNull(level, "level must be set");
    Objects.requireNonNull(path, "path must be set");
    // BUG: this.bloomFactory is mutated here, before the later gates.
    if (bloomFactory == null) {
        bloomFactory = n -> new BlockedBloomFilter(n, 0.01);
    }
    // R5a: ISE if pool was closed between pool() and build()
    if (pool != null && pool.isClosed()) {
        throw new IllegalStateException("pool is closed");
    }
    // R15: IAE if non-default block size but no codec
    if (blockSizeExplicit && codec == null) {
        throw new IllegalArgumentException("non-default blockSize requires a codec");
    }
    return new TrieSSTableWriter(id, level, path, bloomFactory, codec,
            effectiveBlockSize, ...);
}
```

Caller sequence that exposes the bug:

```java
Builder b = TrieSSTableWriter.builder()
        .level(...)
        .path(...)
        .blockSize(16384);      // explicit — triggers R15 gate on build()

// build() fails at R15 — IAE thrown.
// BUT: this.bloomFactory was already overwritten to the default lambda.
try { b.build(); } catch (IllegalArgumentException expected) {}

// Caller fixes the R15 failure and retries, expecting a fresh writer.
b.codec(myCodec);
TrieSSTableWriter w = b.build();   // silently uses the leaked default
                                    //   bloomFactory rather than any
                                    //   factory the caller might now want.
```

The visible state of the Builder between the two `build()` calls is
different in a way the caller cannot see from the Builder's API. If the
caller later calls `b.bloomFactory(customFactory)`, they get the correct
writer; but if they assume the Builder is untouched after a throw, they get
silently wrong output.

## Symptoms

- A failed `build()` followed by a retry produces an object that differs
  from an equivalent fresh-Builder call — "my writer has a bloom filter I
  never configured."
- Property test "state after build-that-throws == state before build" fails
  on fields that are defaulted before the validation block.
- Reflective or test-only inspection of Builder fields after a failed
  `build()` shows mutated values.
- When `build()` has multiple validation gates, the bug is reproducible
  deterministically — no timing dependency.

## Root Cause

The developer treats the default-assignment as "a tidy normalisation of
input" and places it at the top of the method, before the validation block,
because that's the natural reading order for `build()` ("first fill in
defaults, then check, then construct"). But the default-assignment is a
mutation of Builder-observable state, and the validation block can throw.
Every mutation that happens before a gate that can throw is outside the
gate's implicit rollback scope.

`build()` does not naturally have a try/catch; the fix is not to add one,
but to replace `this.<field>` mutation with a method-local variable.

## Fix Pattern

Resolve defaults into method-local `final` variables **after** every
validation gate:

```java
public TrieSSTableWriter build() throws IOException {
    Objects.requireNonNull(level, "level must be set");
    Objects.requireNonNull(path, "path must be set");
    // R5a check ...
    // R15 check ...
    // Default is resolved into a local — never written back to this.<field> —
    // so a failed build() at any gate above or below cannot leak a silent
    // default into the Builder. Preserves the last-wins atomicity pattern
    // followed by every other setter on this builder.
    final BloomFilter.Factory effectiveBloomFactory =
            (bloomFactory != null) ? bloomFactory
                                   : n -> new BlockedBloomFilter(n, 0.01);
    return new TrieSSTableWriter(id, level, path, effectiveBloomFactory,
            codec, effectiveBlockSize, ...);
}
```

Rules of thumb for `build()`:

- Never assign to `this.<field>` inside `build()`. Resolve defaults into
  method-local variables.
- If a default must be exposed on the Builder itself for a later getter
  (rare), make it explicit: have the caller call `bloomFactory(defaultFactory)`
  before `build()` or add a separate `applyDefaults()` method that the
  caller invokes explicitly.
- Validation gates go in the order of cheapest-first. Defaults resolve
  last, as part of constructor-argument preparation.

## Detection

- **Shared-state lens**: enumerate every write to `this.<field>` inside
  `build()`; for each, check whether any validation gate below it can
  throw. If yes, the mutation is outside the atomicity scope.
- **Property test**: capture each Builder field value before `build()`,
  invoke `build()` with a configuration that triggers a specific gate,
  assert each field value after the throw equals the captured value.
- **Code review heuristic**: `build()` should be readable as "validate all,
  then construct." Any `this.x = …` line before the final `return new
  Writer(…)` is suspicious.

## Audit Findings

Identified in `implement-sstable-enhancements--wd-02` audit run-001:

- **F-R1.shared_state.1.1** — `TrieSSTableWriter.Builder.build()` mutated
  `this.bloomFactory` at the top of the method before the R5a pool-closed
  gate and the R15 codec-pairing gate. A `build()` that threw at R15 left
  the Builder's `bloomFactory` field overwritten with the default lambda,
  silently breaking the R11a atomicity pattern that every other setter on
  the Builder upholds.

The fix (replace `this.bloomFactory = default` with a method-local
`effectiveBloomFactory`) extended the R11a "last-wins, failed repeat is an
atomic no-op" contract from just the pool/blockSize slots to the full
Builder surface.
