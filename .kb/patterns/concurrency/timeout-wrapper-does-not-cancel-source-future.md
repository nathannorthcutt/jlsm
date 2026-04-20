---
title: "Timeout Wrapper Does Not Cancel Source Future"
aliases: ["orTimeout leak", "dangling request after timeout", "uncancelled source future", "pinned vthread on sync call"]
topic: "patterns"
category: "concurrency"
tags: ["adversarial-finding", "concurrency", "cancellation", "resource-leak", "completablefuture", "timeout"]
type: "adversarial-finding"
research_status: "stable"
confidence: "high"
last_researched: "2026-04-20"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/RemotePartitionClient.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/table/ClusteredTable.java"
related:
  - "fan-out-iterator-leak"
  - "non-atomic-lifecycle-flags"
decision_refs: []
sources:
  - "audit run-001 f04-obligation-resolution--wd-03"
---

# Timeout Wrapper Does Not Cancel Source Future

## Summary

Using `CompletableFuture.orTimeout(...)` (or an equivalent wrapper) to enforce
a per-request timeout completes the **wrapper** future with
`TimeoutException` but leaves the **source** future — the one the transport
returned and is still observing — uncancelled. The transport never sees a
cancellation signal and therefore cannot release per-request server-side
state, producing a server-side resource leak on every client-side timeout.
The corresponding pattern on the server side is failing to interrupt a
blocked synchronous call when the fanout is cancelled, leaving the virtual
or platform thread pinned.

## Problem

```java
public CompletableFuture<Response> getRangeAsync(Request req) {
    CompletableFuture<Response> source = transport.request(req);   // transport owns this
    return source.orTimeout(5, TimeUnit.SECONDS);                  // wrapper
    // BUG: when the wrapper times out, `source` is never cancelled.
    // The transport's request-in-flight state lives forever on the server.
}
```

`orTimeout` returns a future that will complete exceptionally after the
deadline, **but it does not propagate cancellation backwards to the source**.
The source future is owned by the transport and observed by the server-side
request tracker; nothing tells the transport "the client no longer cares."
Every timeout leaks one request's worth of server-side state.

The related fanout pattern:

```java
// ClusteredTable.scan scatter supplier — one supplier per partition
CompletableFuture.supplyAsync(() -> {
    // BUG: transport.request is a BLOCKING SYNC call that does not react
    // to Thread.interrupt(). When the parent scan is cancelled, this
    // vthread is pinned until the transport naturally returns.
    return transport.request(req);
}, virtualExecutor);
```

Even if the parent fanout signals cancellation, the per-supplier thread is
stuck inside `transport.request` — no interrupt wakes it up.

## Symptoms

- Server-side heap growth correlated with client-side timeout rate
- Server-side "in-flight request" metrics grow without bound while
  client-side metrics show timeouts completing successfully
- Thread dumps show virtual threads pinned inside `transport.request`
  long after the originating scan was cancelled
- Close of the client does not promptly release threads — they drain
  only when the transport's own internal deadline fires (often much
  later than the client's deadline)
- Tests that measure "time until all threads exit after close()" time out
  even though `close()` returned

## Root Cause

`CompletableFuture`'s cancellation model is asymmetric:

- A wrapper produced by `orTimeout`, `thenApply`, `applyToEither`, etc.
  does not hold a cancellation link to the source future. Completing the
  wrapper with a timeout does not call `source.cancel(true)`.
- `CompletableFuture.cancel(true)` on a JDK 21+ future does not interrupt
  any thread blocked in `get()` on that future — it simply transitions
  the future to a cancelled state. If the transport is waiting on an
  external I/O primitive, only that primitive's own interruption path
  (closing the channel, sending a cancel frame) releases it.

For a fanout supplier blocked inside a sync call, the supplier thread is
oblivious to the parent's cancellation unless the sync call is
interrupt-aware — and most custom transport APIs are not.

## Fix Pattern

1. **Chain an explicit cancel on timeout.** Schedule a
   `source.cancel(true)` via `delayedExecutor` at the same deadline the
   wrapper uses, so the source gets a cancellation signal even if the
   transport does not react to the wrapper's timeout.

2. **Make the transport's request-in-flight table reactive to
   cancellation.** When a request's tracking future is cancelled, the
   transport must free the per-request server-side slot (remove from
   the outstanding map, send a cancel frame if the protocol supports one).

3. **Track the servicing thread for each fanout supplier.** Store the
   `Thread` reference on supplier entry; on fanout cancel / close, call
   `thread.interrupt()` on each tracked thread so the blocked sync call
   can observe interruption.

4. **Prefer async transport APIs over sync-inside-supplyAsync.** A
   genuinely async transport returns its own `CompletableFuture` and
   needs no surrounding supplier. That future can be cancelled directly.

```java
public CompletableFuture<Response> getRangeAsync(Request req, Duration timeout) {
    CompletableFuture<Response> source = transport.request(req);

    // Schedule an explicit cancel on timeout so the transport can release
    // per-request state even though orTimeout does not propagate.
    ScheduledFuture<?> cancelTask = scheduler.schedule(
        () -> source.cancel(true),
        timeout.toMillis(), TimeUnit.MILLISECONDS);

    return source
        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .whenComplete((r, e) -> cancelTask.cancel(false));  // dont double-fire on success
}

// Fanout supplier — track the servicing thread
private static final class ScatterSupplier implements Supplier<Response> {
    private volatile Thread servicingThread;

    @Override public Response get() {
        servicingThread = Thread.currentThread();
        try {
            return transport.request(req);
        } finally {
            servicingThread = null;
        }
    }

    void interruptIfRunning() {
        Thread t = servicingThread;
        if (t != null) t.interrupt();
    }
}
```

On parent fanout cancel / close, walk the suppliers and call
`interruptIfRunning()` on each.

## Test Guidance

Numbered steps to reproduce the uncancelled-source flavour:

1. Build a `Transport` stub whose `request(req)` returns a
   `CompletableFuture` that **never completes** and records cancel
   observations via a latch/counter.
2. Configure the client with a short timeout (e.g., 50 ms).
3. Call `getRangeAsync(req)` and await the returned future — it completes
   exceptionally with `TimeoutException`.
4. Assert the transport stub observed `source.cancel(true)` (it does NOT
   today — the test fails before the fix).
5. After the fix, assert the stub observed exactly one cancel, and that
   its per-request state map is empty.

Numbered steps to reproduce the pinned-vthread flavour:

1. Build a `Transport` stub whose sync `request(req)` blocks in a
   non-interruptible way (e.g., spin on `LockSupport.parkNanos` ignoring
   interruption) until released by the test.
2. Launch a `ClusteredTable.scan` that fans out to N partitions served
   by that stub.
3. Call `close()` on the table or cancel the scan future.
4. Assert all N fanout-supplier threads have exited within a small
   grace period (currently they do not — they remain pinned).
5. After the fix, assert each supplier thread observed
   `InterruptedException` or `Thread.currentThread().isInterrupted() == true`
   before exiting.

## Examples from Codebase

Identified in f04-obligation-resolution--wd-03 audit run-001:

- **F-R1.concurrency.1.10** — `RemotePartitionClient.getRangeAsync`:
  `source.orTimeout(...)` leaves the source future uncancelled; the
  transport's per-request state leaks on every client-side timeout.
- **F-R1.shared_state.2.3** — `ClusteredTable.scan` scatter supplier:
  the per-supplier `transport.request` is a synchronous blocking call
  that does not observe `Thread.interrupt()`, so the vthread is pinned
  past cancel because `close()` does not interrupt the servicing thread.

The two findings come from different lens passes (concurrency vs
shared-state) but share the same root-cause structure: a wrapper that
*looks* like it enforces a deadline, while the underlying resource
remains held.
