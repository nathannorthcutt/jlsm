---
type: adversarial-finding
domain: concurrency
severity: confirmed
tags: [concurrency, close, idempotent, CAS]
applies_to: ["modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableReader.java"]
related:
  - "systems/lsm-index-patterns/iterator-use-after-close.md"
  - "systems/lsm-index-patterns/cache-mutation-after-close.md"
sources:
  - streaming-block-decompression audit R2, 2026-04-03
---

# Close Atomicity via VarHandle CAS

## Pattern

Non-atomic `close()` using volatile check-then-act allows double-close races.
Two threads can both read `closed == false` before either writes `true`, causing
channel double-close or resource cleanup running twice.

## Why It Happens

Developers use `volatile boolean closed` and assume single-threaded close. The
check-then-act looks safe but is not atomic.

## Fix

`VarHandle.compareAndSet(this, false, true)` — exactly one thread transitions
the flag; all others return immediately.

```java
private static final VarHandle CLOSED =
    MethodHandles.lookup().findVarHandle(MyReader.class, "closed", boolean.class);
private volatile boolean closed;

public void close() throws IOException {
    if (!(boolean) CLOSED.compareAndSet(this, false, true)) return;
    // cleanup here — guaranteed single execution
}
```

## Test Guidance

Race two virtual threads on `close()` with a CyclicBarrier, 10,000 iterations.
Use a spy channel with an AtomicInteger counting `close()` calls; assert
count == 1.

## Found In

- streaming-block-decompression (audit R2, 2026-04-03): `TrieSSTableReader.close()`
