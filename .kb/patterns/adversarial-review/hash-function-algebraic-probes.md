---
title: "Hash function algebraic pre-image probes (spec-authoring gap)"
type: adversarial-finding
tags: [hashing, spec-authoring, falsification, cryptographic-properties]
domain: [data_transformation, hashing, sharding]
applies_to:
  - "spec that mandates a specific hash / mixing function"
  - "spec that pins a combining rule between multiple inputs before a finalizer"
  - "stripe / shard / partition index functions"
---

# Hash function algebraic pre-image probes

## Pattern

When a spec pins a hash function or mixing rule (e.g. "use Splitmix64 Stafford
variant 13 with golden-ratio combining of X and Y"), the standard adversarial
falsification probes (degenerate values, concurrency, boundary, resource
lifecycle) do not systematically exercise **algebraic properties of the combine**
— specifically, whether the pre-finalizer input admits low-cost pre-image
collisions. A spec can pass two full falsification rounds with this weakness
intact because no probe is looking for it.

## What happens

A pinned combining rule like `x * GOLDEN_RATIO + y` is linear modulo 2^64. For
any target combined value, there exist 2^64 `(x, y)` pairs that map to it —
trivially: given `(x1, y1)`, solve for `y2 = y1 + GOLDEN_RATIO * (x1 - x2)`
and `(x2, y2)` collides. If x and y are caller-supplied and represent
routing/sharding coordinates, an adversary (or a workload pattern) can
concentrate load onto a single shard.

The Stafford finalizer that follows is designed to cause avalanche in the
OUTPUT, but it cannot recover information lost when two distinct inputs
collapse to the same combined value before avalanche runs.

This gap survived both Pass 2 (KB-adversarial-pattern, degenerate-value,
trust-boundary probes) and Pass 3 (fix-consequence) of `/spec-author`. It
was only caught by the `data_transformation` lens of `/audit`, which
explicitly looks for round-trip fidelity, encoding limits, and format
agreement — but that lens runs against implementation, not specs.

## Fix pattern

**Spec level:** when a spec pins a hash / mixing rule, add language that:
- mandates avalanche in the output (every output bit depends on every input bit)
- forbids low-cost algebraic pre-image collisions in the pre-finalizer combine
- explicitly permits a non-linear pre-avalanche step (multiply-XOR-shift round)
  to defeat linear combines

**Falsification probe (for `/spec-author` Pass 2):** for any requirement that
pins a combining rule between multiple inputs before a finalizer, construct
two specific input pairs `(x1, y1)` and `(x2, y2)` where `x1 != x2` and solve
algebraically for `y2` such that the combined pre-finalizer value is identical.
If such a pair exists and is computable in O(1) without breaking the hash itself,
the combine rule is broken.

**Test pattern:** for every hash function with a pinned combine, add an
adversarial test that constructs at least one explicit pre-image collision
and asserts `hash(x1, y1) != hash(x2, y2)`. The test should NOT just probe
random inputs — it must derive the collision from the combine's algebraic
structure.

## Test guidance

For a combine rule `combine(x, y) = x * C + y`, the collision test:

```java
@Test
void combineRejectsLinearPreImageCollisions() {
    long x1 = 0L, y1 = 0L;
    long x2 = 1L;
    // Solve: x1 * C + y1 == x2 * C + y2  →  y2 = y1 - C * (x2 - x1) = -C mod 2^64
    long y2 = -GOLDEN_RATIO_64;
    assertNotEquals(hashFn(x1, y1), hashFn(x2, y2),
        "Linear pre-image collision: finalizer cannot recover lost input distinction");
}
```

The test should pre-compute the colliding pair OUTSIDE any RNG — it's a
proof against a specific structural weakness, not a probabilistic search.

## Seen in

- jlsm audit round-001 (2026-04-21): `F-R1.data_transformation.1.1` — 
  `StripedBlockCache.splitmix64Hash` linear combine `sstableId * GOLDEN + blockOffset`
  admitted collision at `(0, 0)` / `(1, -GOLDEN)`. Surfaced by audit,
  required spec relaxation of `sstable.striped-block-cache.R11` to permit
  non-linear pre-avalanche. Fixed by inserting a multiply-XOR-shift round on
  `sstableId * GOLDEN` before combining with `blockOffset`.

## Why the pipeline missed it

- `/spec-author` Pass 2 checklist (14 mandatory probes) includes
  degenerate-values, boundary-validation, standards-compliance, resource-
  lifecycle, cross-construct-atomicity — but no probe for **algebraic
  properties of pinned combining rules**
- `/feature-harden` applies 6 domain lenses to contracts, not to specs
- `/audit` data_transformation lens did find it — but audit runs POST-IMPL,
  which means the spec is frozen and the fix requires a spec revision
- Net: this gap should be closed at spec-authoring time by adding a
  hash-combine algebraic probe to Pass 2's mandatory checklist

## Related

- `.kb/patterns/validation/reflective-bypass-of-builder-validation.md` —
  similar "spec-authoring missed it, audit caught it" pattern
- `.kb/data-structures/caching/capacity-truncation-on-sharding.md` —
  arithmetic properties of sharding that do get probed today
