---
title: "Lock-Held Side Effects"
aliases: ["I/O under lock", "callback under lock", "lock-held network I/O"]
topic: "patterns"
category: "concurrency"
tags: ["concurrency", "deadlock", "locks", "side-effects"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-06"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/RapidMembership.java"
related:
  - "non-atomic-lifecycle-flags"
decision_refs: []
sources:
  - url: "https://docs.oracle.com/javase/tutorial/essential/concurrency/deadlock.html"
    title: "Oracle — Deadlock tutorial"
    accessed: "2026-04-06"
    type: "docs"
---

# Lock-Held Side Effects

## Summary

Protocol-level locks held during operations with unbounded latency — network
I/O (`transport.send`) and listener notification callbacks. This creates
deadlock risk (if a callback acquires the same lock), starvation risk (slow
network blocks all protocol operations), and prevents concurrent protocol
progress. The fix is to collect state changes under the lock and execute side
effects after releasing it.

## Problem

```java
synchronized (viewLock) {
    view = newView;
    transport.send(viewChangeMessage);     // network I/O under lock
    for (var listener : listeners) {
        listener.onViewChange(newView);    // callback under lock
    }
}
```

While the lock is held:
- `transport.send` may block for seconds on network timeout
- A listener callback may attempt to acquire `viewLock`, causing deadlock
- All other threads waiting on `viewLock` are starved

## Symptoms

- Intermittent deadlocks under load (thread dump shows lock cycle)
- Protocol progress stalls when network is slow
- Listener callbacks that interact with the locked component hang
- Throughput drops to single-threaded under contention

## Root Cause

Lock scope is too wide — it encompasses both state mutation (which needs
atomicity) and side effects (which do not). The lock was likely written to
protect the state change and the side effects were added later inside the
existing synchronized block without reconsidering the lock scope.

## Fix Pattern

Collect state changes under the lock; execute side effects after release:

```java
View snapshot;
List<ViewChangeListener> listenersCopy;
synchronized (viewLock) {
    view = newView;
    snapshot = newView;
    listenersCopy = List.copyOf(listeners);
}
// Side effects outside lock
transport.send(buildViewChangeMessage(snapshot));
for (var listener : listenersCopy) {
    listener.onViewChange(snapshot);
}
```

Key principles:
1. Copy any mutable state needed by side effects while holding the lock
2. Release the lock before any I/O or callback invocation
3. Side effects operate on the snapshot, not on live mutable state

## Detection

- Concurrency lens: identify lock acquisition spans containing I/O calls or
  external callback invocations
- Slow/blocking listeners and transports in tests demonstrate the deadlock
  window
- Thread dumps during load tests showing threads blocked on the same lock with
  different call stacks (one in I/O, one waiting to enter)

## Audit Findings

Identified in engine-clustering audit run-001:
- `RapidMembership.propagateViewChange` — network I/O under viewLock
- `RapidMembership` view-mutating methods — listener notification under viewLock
