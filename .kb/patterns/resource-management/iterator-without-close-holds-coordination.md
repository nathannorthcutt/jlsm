---
type: adversarial-finding
title: "Iterator Without close() Holding Shared Coordination Resource"
topic: "patterns"
category: "resource-management"
tags: ["iterator", "AutoCloseable", "lock-leak", "coordination", "starvation"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-22"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/TrieSSTableReader.java"
related:
  - "fan-out-iterator-leak"
  - "non-idempotent-close"
decision_refs: []
source_audit: "implement-sstable-enhancements--wd-03"
sources:
  - url: "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/AutoCloseable.html"
    title: "AutoCloseable — JavaDoc"
    accessed: "2026-04-22"
    type: "docs"
---

# Iterator Without close() Holding Shared Coordination Resource

## What Happens

A reader-side iterator acquires a shared coordination resource (lock,
semaphore, reader-slot, channel position) in its constructor or first
`hasNext()` call but provides no `close()` method. Callers that abandon
the iterator mid-stream — exception during consumption, early `break`
from a for-each, reference dropped without explicit close — leak the
resource forever, blocking every subsequent acquire.

Canonical anti-pattern:

```java
class RecoveryScanIterator implements Iterator<Block> {
    RecoveryScanIterator(Reader r) {
        r.recoveryLock.lock();      // acquired — never released
        ...
    }
    public boolean hasNext() { ... }
    public Block next() { ... }
    // no close() method!
}

// caller
for (Block b : new RecoveryScanIterator(r)) {
    if (predicate(b)) break;        // abandons iterator — lock leaked
}
```

After the early `break` the GC may reclaim the iterator, but
`recoveryLock` remains held by the abandoned iterator's owning thread
until the process exits. Any future recovery-scan attempt blocks forever.

## Why It Happens

`Iterator` is not `AutoCloseable` and does not participate in try-with-
resources. A common mistake is to treat iterator construction as a
transient, GC-cleanable operation and to overlook that resource
acquisition inside the constructor escapes the GC's reach (the GC cannot
unlock a lock).

## Fix Pattern

Make the iterator `AutoCloseable`, release the resource in `close()`,
ensure `close()` is idempotent, and transition to an exhausted state so
post-close `hasNext()` returns false without further I/O:

```java
class RecoveryScanIterator implements Iterator<Block>, AutoCloseable {
    private final ReentrantLock recoveryLock;
    private boolean closed;

    RecoveryScanIterator(Reader r) {
        this.recoveryLock = r.recoveryLock;
        recoveryLock.lock();
    }

    public boolean hasNext() {
        if (closed) return false;
        return ...;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (recoveryLock.isHeldByCurrentThread()) recoveryLock.unlock();
    }
}

// caller — must use try-with-resources
try (var it = new RecoveryScanIterator(r)) {
    while (it.hasNext()) {
        Block b = it.next();
        if (predicate(b)) break;
    }
}  // close() always runs — lock released
```

Return an `AutoCloseable` iterator type (e.g., `CloseableIterator<T>`
extending both interfaces) so the factory method's signature communicates
the close obligation to the caller at compile time.

## Detection

Concurrency lens drain-partial-then-abandon test: iterate a few times,
then drop the reference without closing; a second recovery-scan attempt
must succeed within a bounded timeout. Before the fix, the second
attempt blocks on `recoveryLock.lock()` indefinitely.

## Seen In

- `TrieSSTableReader.RecoveryScanIterator` — audit finding
  F-R1.concurrency.1.3 (no `close()`; abandoned iterator held
  `recoveryLock` forever). `shared_state.01.04` observed the same defect
  from the lock-holding angle; cross-domain composition XD-R1.4 confirmed
  the single root cause.

## Test Guidance

- Spawn two threads: thread A starts the iterator and abandons it
  (iterates once, then drops the reference without `close()`). Thread B
  waits for A to drop, then attempts the same scan and MUST succeed.
- Repeat with `try-with-resources` in thread A and assert thread B still
  succeeds (regression guard against someone removing close()).
- Cover the "iterator threw during consumption" path: exception inside
  the for-each body must still release the resource.
