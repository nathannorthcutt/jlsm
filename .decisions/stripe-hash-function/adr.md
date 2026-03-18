---
problem: "stripe-hash-function"
date: "2026-03-17"
version: 1
status: "confirmed"
supersedes: null
---

# ADR — Stripe Hash Function

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Stafford variant 13 (splitmix64) | Chosen approach | Domain knowledge (no KB entry) |
| Long.hashCode mixing | Rejected candidate | Domain knowledge (no KB entry) |
| MurmurHash3 fmix64 | Rejected candidate | Domain knowledge (no KB entry) |
| Fibonacci hashing | Rejected candidate | Domain knowledge (no KB entry) |

---

## Problem
Choose a mixing function to map `(sstableId, blockOffset)` pairs to stripe indices in `StripedBlockCache`. The function is called on every cache operation and must be zero-allocation, sub-nanosecond overhead, and distribute sequential `blockOffset` values evenly across stripes.

## Constraints That Drove This Decision
- **Zero allocation, sub-nanosecond**: Eliminates any approach that allocates objects or arrays — must be pure arithmetic on primitive longs
- **Even distribution for sequential blockOffsets**: Eliminates naive modulo and weak mixers like `Long.hashCode` — sequential 4096-aligned offsets must not cluster
- **Two long inputs → int index**: Must naturally combine `sstableId` and `blockOffset` into a single well-distributed index

## Decision
**Chosen approach: Stafford variant 13 (splitmix64 finalizer)**

Use the splitmix64 finalizer (the bit-mixing function from `java.util.SplittableRandom`) to hash `(sstableId, blockOffset)` pairs to stripe indices. The golden-ratio multiply in the first step naturally combines both inputs, and the three-stage multiply-XOR-shift chain provides excellent avalanche properties — every input bit affects every output bit — ensuring sequential `blockOffset` values produce uncorrelated stripe indices.

```java
static int stripeIndex(long sstableId, long blockOffset, int stripeCount) {
    long h = sstableId * 0x9E3779B97F4A7C15L + blockOffset;
    h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
    h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
    h = h ^ (h >>> 31);
    return (int) ((h & 0x7FFFFFFFFFFFFFFFL) % stripeCount);
}
```

## Rationale

### Why Stafford variant 13 (splitmix64)
- **Distribution**: Best-in-class avalanche for sequential long inputs; constants are from JDK's own `SplittableRandom`, well-vetted by Guy Steele, Doug Lea, and Christine Flood
- **Performance**: ~2-3 ns — three dependent multiply-shift chains, zero allocation, no memory access
- **Two-input combining**: The golden-ratio multiply (`sstableId * 0x9E3779B97F4A7C15L + blockOffset`) naturally merges both inputs in the first line

### Why not Long.hashCode mixing
- **Poor avalanche**: `Long.hashCode` is `(int)(value ^ (value >>> 32))` — preserves low-bit patterns. Sequential `blockOffset` values (especially 4096-aligned) produce sequential hash outputs, causing pathological stripe clustering.

### Why not MurmurHash3 fmix64
- **Single-input design**: fmix64 is a finalizer for a single `long`. Combining two longs first requires an ad-hoc step, whereas splitmix64's golden-ratio multiply naturally serves as the combiner. Slightly less idiomatic.

### Why not Fibonacci hashing
- **Power-of-2 restriction**: Only works cleanly with power-of-2 stripe counts (uses bit shift instead of modulo). Weaker avalanche than splitmix64 — single multiply does not achieve full bit mixing.

## Implementation Guidance
- Constants: `0x9E3779B97F4A7C15L` (golden ratio), `0xBF58476D1CE4E5B9L`, `0x94D049BB133111EBL` — cite `SplittableRandom` in a comment
- For power-of-2 stripe counts, `& (stripeCount - 1)` can replace `% stripeCount` as a future optimization
- The `& 0x7FFFFFFFFFFFFFFFL` ensures a non-negative value before modulo

## What This Decision Does NOT Solve
- Does not guarantee perfectly uniform distribution (slight skew is acceptable)
- Does not optimize for power-of-2 stripe counts (modulo used for all counts)

## Conditions for Revision
This ADR should be re-evaluated if:
- Micro-benchmarking shows the ~2-3ns cost is measurable relative to stripe lock acquisition
- A use case requires non-long key types (would need a different combining strategy)
- Stripe counts grow beyond 64 where distribution uniformity matters more

---
*Confirmed by: user deliberation | Date: 2026-03-17*
*Full scoring: [evaluation.md](evaluation.md)*
