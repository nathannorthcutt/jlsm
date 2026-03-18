---
problem: "stripe-hash-function"
evaluated: "2026-03-17"
candidates:
  - path: "domain-knowledge"
    name: "Long.hashCode mixing"
  - path: "domain-knowledge"
    name: "Stafford variant 13 (splitmix64 finalizer)"
  - path: "domain-knowledge"
    name: "MurmurHash3 fmix64"
  - path: "domain-knowledge"
    name: "Fibonacci hashing"
constraint_weights:
  scale: 2
  resources: 3
  complexity: 2
  accuracy: 2
  operational: 3
  fit: 2
---

# Evaluation — stripe-hash-function

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used: none (KB is empty — candidates evaluated from domain knowledge)

## Constraint Summary
The hash function runs on every cache operation (tens of millions/sec), must be zero-allocation
and sub-nanosecond, and must distribute sequential `blockOffset` values evenly across stripes.
The function takes two `long` inputs and produces an `int` index.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | Millions of ops/s but all candidates handle this |
| Resources | 3 | Zero allocation is a hard requirement — any allocation is disqualifying |
| Complexity | 2 | Internal detail but should be readable |
| Accuracy | 2 | Even distribution matters but perfect uniformity not required |
| Operational | 3 | Sub-nanosecond target — this is the primary performance constraint |
| Fit | 2 | All candidates work in Java; power-of-2 optimization is a bonus |

---

## Candidate: Long.hashCode mixing

**KB source:** Domain knowledge (no KB entry)

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 2 | 5 | 10 | Pure arithmetic, handles any call rate |
| Resources | 3 | 5 | 15 | Zero allocation — primitive ops only |
| Complexity | 2 | 5 | 10 | One line of code, instantly readable |
| Accuracy | 2 | 2 | 4 | Poor avalanche — sequential inputs produce sequential outputs; `Long.hashCode` is `(int)(value ^ (value >>> 32))` which preserves low-bit patterns |
| Operational | 3 | 5 | 15 | ~1 ns — XOR and shift |
| Fit | 2 | 5 | 10 | JDK standard, works with any stripe count |
| **Total** | | | **64** | |

**Hard disqualifiers:** None
**Key strengths:** Maximum simplicity, zero overhead
**Key weaknesses:** Poor distribution for sequential blockOffsets — low bits dominate, causing stripe clustering for 4096-aligned block offsets

---

## Candidate: Stafford variant 13 (splitmix64 finalizer)

**KB source:** Domain knowledge (no KB entry)
**Reference:** Guy Steele, Doug Lea, Christine Flood — used in `java.util.SplittableRandom`

```java
static int stripe(long sstableId, long blockOffset, int stripeCount) {
    long h = sstableId * 0x9E3779B97F4A7C15L + blockOffset;
    h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
    h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
    h = h ^ (h >>> 31);
    return (int) ((h & 0x7FFFFFFFFFFFFFFFL) % stripeCount);
}
```

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 2 | 5 | 10 | Pure arithmetic, handles any call rate |
| Resources | 3 | 5 | 15 | Zero allocation — three multiplies, three XOR-shifts |
| Complexity | 2 | 4 | 8 | Magic constants need a one-line comment citing SplittableRandom, but the pattern is well-known |
| Accuracy | 2 | 5 | 10 | Excellent avalanche — every input bit affects every output bit; sequential inputs produce uncorrelated outputs |
| Operational | 3 | 5 | 15 | ~2-3 ns — three dependent multiply-shift chains; well within sub-5ns |
| Fit | 2 | 5 | 10 | Pure Java, works with any stripe count; constants from JDK source |
| **Total** | | | **68** | |

**Hard disqualifiers:** None
**Key strengths:** Best-in-class avalanche for sequential long inputs; used in JDK's own SplittableRandom; zero allocation
**Key weaknesses:** Three magic constants require a source comment

---

## Candidate: MurmurHash3 fmix64

**KB source:** Domain knowledge (no KB entry)
**Reference:** Austin Appleby — MurmurHash3 64-bit finalizer

```java
static long fmix64(long h) {
    h ^= h >>> 33;
    h *= 0xFF51AFD7ED558CCDL;
    h ^= h >>> 33;
    h *= 0xC4CEB9FE1A85EC53L;
    h ^= h >>> 33;
    return h;
}
```

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 2 | 5 | 10 | Pure arithmetic |
| Resources | 3 | 5 | 15 | Zero allocation |
| Complexity | 2 | 4 | 8 | Similar to splitmix64 — magic constants, well-documented origin |
| Accuracy | 2 | 5 | 10 | Excellent avalanche — designed specifically as a hash finalizer |
| Operational | 3 | 5 | 15 | ~2-3 ns — two dependent multiply-shift chains |
| Fit | 2 | 4 | 8 | Pure Java; project already uses Murmur3 in bloom filter (consistency); needs combining step for two inputs |
| **Total** | | | **66** | |

**Hard disqualifiers:** None
**Key strengths:** Proven avalanche properties; project already uses Murmur3 in bloom filter
**Key weaknesses:** Designed for single input — needs an extra combining step to merge sstableId and blockOffset before finalizing

---

## Candidate: Fibonacci hashing

**KB source:** Domain knowledge (no KB entry)

```java
static int stripe(long combined, int stripeCount) {
    return (int) ((combined * 0x9E3779B97F4A7C15L) >>> (64 - Integer.numberOfTrailingZeros(stripeCount)));
}
```

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 2 | 5 | 10 | Pure arithmetic |
| Resources | 3 | 5 | 15 | Zero allocation — single multiply and shift |
| Complexity | 2 | 5 | 10 | One line, one constant (golden ratio), widely known |
| Accuracy | 2 | 3 | 6 | Good but not excellent — single multiply does not achieve full avalanche; adequate for power-of-2 stripe counts |
| Operational | 3 | 5 | 15 | ~1 ns — single multiply |
| Fit | 2 | 3 | 6 | Only works with power-of-2 stripe counts (uses bit shift); needs fallback for non-power-of-2 |
| **Total** | | | **62** | |

**Hard disqualifiers:** None, but power-of-2 restriction is a significant limitation
**Key strengths:** Fastest option — single multiply
**Key weaknesses:** Restricted to power-of-2 stripe counts; weaker avalanche than splitmix64 or fmix64

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| Long.hashCode mixing | 10 | 15 | 10 | 4 | 15 | 10 | 64 |
| Stafford v13 (splitmix64) | 10 | 15 | 8 | 10 | 15 | 10 | 68 |
| MurmurHash3 fmix64 | 10 | 15 | 8 | 10 | 15 | 8 | 66 |
| Fibonacci hashing | 10 | 15 | 10 | 6 | 15 | 6 | 62 |

## Preliminary Recommendation
**Stafford variant 13 (splitmix64 finalizer)** wins on weighted total (68). It has the best avalanche properties for sequential inputs, zero allocation, sub-5ns cost, and works with any stripe count. The combining step (`sstableId * golden_ratio + blockOffset`) is built into the first line, making it a natural fit for two-input hashing.

## Risks and Open Questions
- Risk: The three magic constants reduce readability slightly, but a one-line comment citing `SplittableRandom` mitigates this
- Risk: The `% stripeCount` modulo for non-power-of-2 counts adds a division; for power-of-2 counts, `& (stripeCount - 1)` is faster — could optimize with a branch
- Open: No KB research exists for any candidate — all scores are from domain knowledge. A micro-benchmark could validate the ~2-3ns estimate
