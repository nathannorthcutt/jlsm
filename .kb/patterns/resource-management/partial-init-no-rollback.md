---
title: "Partial Initialization Without Rollback"
aliases: ["partial init leak", "no-rollback init", "half-initialized state"]
topic: "patterns"
category: "resource-management"
tags: ["resource-management", "initialization", "rollback", "cleanup"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-06"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/RapidMembership.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/ClusteredEngine.java"
related:
  - "unbounded-collection-growth"
  - "non-atomic-lifecycle-flags"
decision_refs: []
sources: []
---

# Partial Initialization Without Rollback

## Summary

Multi-step initialization sequences (`start`, `createTable`) where failure at
step N leaves steps 1..N-1 completed but the overall operation reports failure.
The caller sees an exception but the component is in a half-initialized state
with leaked resources. The fix is wrapping the sequence in try-catch with
explicit rollback of all completed steps.

## Problem

```java
public void start() {
    registerHandler(messageHandler);        // step 1
    discoveryService.register(this);        // step 2
    scheduler = createScheduledExecutor();  // step 3 — throws!
}
```

If step 3 throws, step 1 and step 2 have already completed. The handler is
registered, the discovery service knows about this node, but the scheduler was
never created. The component is in a state that was never designed or tested.

## Symptoms

- Resource leaks after failed initialization (handlers registered, connections
  opened, entries in registries)
- Subsequent `close()` calls fail because they assume full initialization
- Retry of `start()` after failure causes duplicate registrations
- State corruption when half-initialized components receive messages

## Root Cause

Multi-step initialization without compensating actions. Each step assumes all
previous steps succeeded, but no step knows how to undo itself if a later step
fails. The initialization sequence is neither atomic nor transactional.

## Fix Pattern

Wrap multi-step initialization with ordered rollback:

```java
public void start() {
    registerHandler(messageHandler);
    try {
        discoveryService.register(this);
    } catch (Exception e) {
        unregisterHandler(messageHandler);  // rollback step 1
        throw e;
    }
    try {
        scheduler = createScheduledExecutor();
    } catch (Exception e) {
        discoveryService.unregister(this);  // rollback step 2
        unregisterHandler(messageHandler);  // rollback step 1
        throw e;
    }
}
```

For complex sequences, use a rollback stack:

```java
Deque<Runnable> rollback = new ArrayDeque<>();
try {
    registerHandler(messageHandler);
    rollback.push(() -> unregisterHandler(messageHandler));

    discoveryService.register(this);
    rollback.push(() -> discoveryService.unregister(this));

    scheduler = createScheduledExecutor();
    rollback.push(scheduler::shutdown);
} catch (Exception e) {
    while (!rollback.isEmpty()) {
        try { rollback.pop().run(); } catch (Exception suppressed) {
            e.addSuppressed(suppressed);
        }
    }
    throw e;
}
```

## Detection

- Resource lifecycle lens: trace multi-step initialization and identify missing
  rollback paths
- Inject exceptions at each step and verify cleanup of prior steps
- Check whether `close()` handles partially-initialized state gracefully

## Audit Findings

Identified in engine-clustering audit run-001:
- `RapidMembership.start` — handler registration, discovery registration,
  scheduler creation with no rollback on failure
- `ClusteredEngine.createTable` — local table creation, ClusteredTable
  construction with no rollback on failure
