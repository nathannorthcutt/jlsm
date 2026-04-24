---
type: adversarial-finding
title: "Check-Then-Act Across Paired Acquire/Release Paths"
topic: "patterns"
category: "concurrency"
tags: ["concurrency", "check-then-act", "mutex", "lock-granularity", "straddle-window"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-22"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/TrieSSTableReader.java"
related:
  - "non-atomic-lifecycle-flags"
  - "torn-volatile-publish-multi-field"
  - "shared-rwlock-bracketing-facade-close-atomicity"
decision_refs: []
source_audit: "implement-sstable-enhancements--wd-03"
sources:
  - url: "https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html"
    title: "JLS Chapter 17 — Threads and Locks"
    accessed: "2026-04-22"
    type: "docs"
---

# Check-Then-Act Across Paired Acquire/Release Paths

## What Happens

Two cooperating code paths (e.g., a reader-slot acquire vs a recovery-scan
entry) each read a shared predicate and then modify a paired counter/flag.
The check-and-modify pair is not serialized under the same lock. The
straddle window between one side's check and its modify lets the other
side's symmetric check-and-modify enter concurrently, violating the mutex
invariant.

Canonical anti-pattern:

```java
// reader-slot acquire
if (recoveryInProgress) { return busy; }  // unlocked volatile read
activeReaderOps.incrementAndGet();        // straddle window above
// ...use the slot...
activeReaderOps.decrementAndGet();

// recovery-scan entry
if (activeReaderOps.get() != 0) throw busy;  // unlocked read
recoveryInProgress = true;                   // straddle window above
// ...run scan...
recoveryInProgress = false;
```

Two threads enter both paths concurrently: the reader's predicate read
observes `recoveryInProgress=false` AND the recovery-scan's predicate read
observes `activeReaderOps=0`, so both proceed. The mutex is violated.

## Why It Happens

Each individual atomic operation is correct. The bug lives in the composition:
the "check" on one side is a predicate read of a separate field managed by
the OTHER side, and nothing serializes the pair. `AtomicInteger.compareAndSet`
cannot help — CAS guards the counter on its own side but cannot observe the
peer's predicate atomically.

## Fix Pattern

Promote the check-and-modify on both sides to a single `synchronized` /
explicit-lock critical section so the predicate read and the state change
are atomic together:

```java
private final Object mutex = new Object();
private boolean recoveryInProgress;
private int activeReaderOps;

void acquireReaderSlot() {
    synchronized (mutex) {
        if (recoveryInProgress) throw busy;
        activeReaderOps++;
    }
}

void releaseReaderSlot() {
    synchronized (mutex) { activeReaderOps--; }
}

void recoveryScan() {
    synchronized (mutex) {
        if (activeReaderOps != 0) throw busy;
        recoveryInProgress = true;
    }
    try { /* scan */ }
    finally {
        synchronized (mutex) { recoveryInProgress = false; }
    }
}
```

Within a single critical section the check and the modify form an
indivisible unit from every peer's perspective.

## Detection

Concurrency lens harness: construct a deterministic interleaving that pins
one thread inside `acquireReaderSlot` between its predicate read and its
counter increment, while a second thread enters `recoveryScan` and observes
`activeReaderOps == 0`. A `CountDownLatch` or `Phaser` can force the
interleaving reliably.

## Seen In

- `TrieSSTableReader.acquireReaderSlot` / `recoveryScan` — audit finding
  F-R1.concurrency.1.1 (the R38 mutex between reader slots and recovery
  scans). `shared_state.01.03` observed the same defect from the
  multi-field-access angle; cross-domain composition XD-R1.3 confirmed the
  single root cause.

## Test Guidance

- Build a harness that pauses one thread inside the first half of the
  pair under reflection or test-only hooks.
- Launch a second thread targeting the peer path and assert it is
  rejected (or blocked) — NOT permitted to proceed.
- Run with both orderings (reader-slot-first and recovery-first) to cover
  the symmetric case.
