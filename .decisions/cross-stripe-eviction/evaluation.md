---
problem: "cross-stripe-eviction"
evaluated: "2026-03-17"
candidates:
  - path: "domain-knowledge"
    name: "Sequential loop"
  - path: "domain-knowledge"
    name: "Parallel stream"
  - path: "domain-knowledge"
    name: "All-locks-then-evict"
constraint_weights:
  scale: 1
  resources: 3
  complexity: 3
  accuracy: 3
  operational: 2
  fit: 2
---

# Evaluation — cross-stripe-eviction

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used: none (KB is empty — candidates evaluated from domain knowledge)

## Constraint Summary
Eviction is infrequent (compaction path), must remove all entries for an sstableId across all stripes,
must not block get/put on uninvolved stripes, and cannot rely on a thread pool. Simplicity is paramount.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 1 | Evict is infrequent — performance is not the bottleneck |
| Resources | 3 | No thread pool — hard constraint on what's available |
| Complexity | 3 | Must stay as simple as current single-lock evict |
| Accuracy | 3 | Must remove ALL entries — correctness is non-negotiable |
| Operational | 2 | Should not block other stripes but latency itself is not critical |
| Fit | 2 | Must work with existing LruBlockCache.evict() API |

---

## Candidate: Sequential loop

```java
void evict(long sstableId) {
    for (LruBlockCache stripe : stripes) {
        stripe.evict(sstableId);
    }
}
```

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 4 | 4 | Adequate for 2–16 stripes; not parallelized but evict is infrequent |
| Resources | 3 | 5 | 15 | No thread pool needed — plain for loop |
| Complexity | 3 | 5 | 15 | Two lines of code; delegates entirely to existing evict() |
| Accuracy | 3 | 5 | 15 | Visits every stripe — guaranteed to remove all entries |
| Operational | 2 | 4 | 8 | Holds one stripe lock at a time; other stripes remain unlocked for get/put |
| Fit | 2 | 5 | 10 | Direct delegation to LruBlockCache.evict() |
| **Total** | | | **67** | |

**Hard disqualifiers:** None
**Key strengths:** Maximum simplicity; one lock held at a time; guaranteed completeness
**Key weaknesses:** Sequential — total eviction time is sum of per-stripe eviction times (acceptable given infrequency)

---

## Candidate: Parallel stream

```java
void evict(long sstableId) {
    Arrays.stream(stripes).parallel().forEach(s -> s.evict(sstableId));
}
```

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | Parallel across stripes — fastest wall-clock time |
| Resources | 3 | 2 | 6 | Uses ForkJoinPool.commonPool — library code should not contend on shared pool; no control over pool sizing or saturation |
| Complexity | 3 | 3 | 9 | One line but introduces implicit parallelism; harder to reason about exception propagation |
| Accuracy | 3 | 5 | 15 | Visits every stripe — guaranteed completeness |
| Operational | 2 | 4 | 8 | Each stripe locked independently |
| Fit | 2 | 3 | 6 | ForkJoinPool.commonPool is shared with the application — library should not claim it |
| **Total** | | | **49** | |

**Hard disqualifiers:** Uses ForkJoinPool.commonPool which a library should not depend on
**Key strengths:** Fastest wall-clock eviction time
**Key weaknesses:** Implicit thread pool dependency; library code contending on shared common pool; exception handling is opaque

---

## Candidate: All-locks-then-evict

```java
void evict(long sstableId) {
    for (LruBlockCache stripe : stripes) stripe.lock();
    try {
        for (LruBlockCache stripe : stripes) stripe.removeIf(sstableId);
    } finally {
        for (LruBlockCache stripe : stripes) stripe.unlock();
    }
}
```

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 4 | 4 | Works for any stripe count |
| Resources | 3 | 5 | 15 | No thread pool |
| Complexity | 3 | 2 | 6 | Requires exposing lock/unlock on LruBlockCache; risk of deadlock if lock ordering is not consistent; three loops |
| Accuracy | 3 | 5 | 15 | Atomic eviction — all entries gone at once |
| Operational | 2 | 1 | 2 | Blocks ALL stripes simultaneously during eviction — defeats the purpose of striping |
| Fit | 2 | 2 | 4 | Requires breaking LruBlockCache encapsulation to expose lock/unlock |
| **Total** | | | **46** | |

**Hard disqualifiers:** Blocks all stripes simultaneously; breaks encapsulation
**Key strengths:** Atomic snapshot — all entries removed at the same instant
**Key weaknesses:** Holds all locks at once, blocking all get/put operations; requires exposing internal lock API

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| Sequential loop | 4 | 15 | 15 | 15 | 8 | 10 | 67 |
| Parallel stream | 5 | 6 | 9 | 15 | 8 | 6 | 49 |
| All-locks-then-evict | 4 | 15 | 6 | 15 | 2 | 4 | 46 |

## Preliminary Recommendation
**Sequential loop** wins decisively (67 vs 49 vs 46). It is the simplest approach, holds only one stripe lock at a time, guarantees completeness, and requires no thread pool or encapsulation breaks. The sequential cost is irrelevant given evict's infrequency.

## Risks and Open Questions
- Risk: None significant — sequential loop is the textbook approach for this problem
- Open: If eviction of very large caches becomes slow, a future optimization could evict stripes in parallel using a caller-provided executor. Not needed now.
