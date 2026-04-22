---
type: adversarial-finding
domain: validation
severity: confirmed
tags: [javadoc, interface-contract, documentation, spec-alignment, third-party-impl]
applies_to:
  - "modules/jlsm-*/src/main/**"
sources:
  - striped-block-cache audit round-001, 2026-04-21
---

# Interface contract missing from Javadoc

## Pattern

A spec requirement is documented in the Javadoc of the implementation class
but not promoted to the interface Javadoc that third-party implementers read.
Downstream implementers see an underspecified contract and implement
conflicting behavior (no use-after-close check, no null handling, no
monitor-collision warning, etc.). The requirement is effectively
implementation-internal even though the spec says it is part of the
behavioral contract.

## What happens

A developer writes a third-party implementation of `Interface`. They read
`Interface`'s Javadoc, implement the listed methods, and ship. They do not
realize the spec requires use-after-close to throw `IllegalStateException`
because that requirement was only written in the concrete impl's Javadoc.
Their implementation silently returns stale values after close. Callers that
treat the interface polymorphically (`Interface x = factory.create()`) hit
the stale-data path and observe divergent behavior across implementations —
a contract violation that is invisible until production.

## Fix pattern

Any spec requirement that governs behavior observable through the interface
must be written in the INTERFACE Javadoc, not just the impl. Concrete impls
may document implementation-specific behavior (e.g., lock granularity, memory
layout), but the behavioral contract — exceptions thrown, null handling,
thread-safety guarantees, lifecycle rules — belongs at the interface layer.

Audit rule: for every spec requirement tagged at the interface level, grep
the interface source for the requirement identifier or its key phrase. If it
is only present on the impl, promote it.

## Test guidance

Write contract tests against the interface layer — test methods that use
`Interface` polymorphically (e.g., `Interface cache = ...`) and assert the
required behavior. If a second implementation is added later, those tests
run against both and catch divergence immediately.

```java
abstract class BlockCacheContractTest {
    protected abstract BlockCache newCache();

    @Test
    void useAfterCloseThrows() {
        BlockCache c = newCache();
        c.close();
        assertThrows(IllegalStateException.class, () -> c.get(key));
    }
}
```

Subclass the contract test once per implementation. This is the canonical
structure for interface-contract coverage.

## Seen in

- striped-block-cache audit round-001, 2026-04-21:
  - F-R1.contract_boundaries.2.1 — `BlockCache.close` Javadoc omits the R31
    use-after-close requirement (only documented on `LruBlockCache.close`)
  - F-R1.contract_boundaries.2.2 — `BlockCache.getOrLoad` default-method
    Javadoc omits the monitor-collision warning for concurrent loader
    invocation
