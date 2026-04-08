---
title: "Non-Idempotent Close"
aliases: ["double close", "close re-delegation", "missing close guard"]
topic: "patterns"
category: "resource-management"
tags: ["close", "idempotency", "double-free", "resource-lifecycle", "AutoCloseable"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-07"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/partition/PartitionedTable.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/partition/InProcessPartitionClient.java"
related:
  - "partial-init-no-rollback"
  - "fan-out-iterator-leak"
decision_refs: []
sources: []
---

# Non-Idempotent Close

## Summary

Classes implementing Closeable or AutoCloseable delegate close() to underlying
resources without tracking whether close has already been called. A second
close() invocation re-executes the delegation, causing double-free errors,
redundant I/O, or exceptions from already-closed resources.

## Problem

The close() method directly delegates to child resources without an idempotency
guard. When called more than once — common in try-with-resources nested with
explicit close(), error-recovery paths, or framework lifecycle hooks — the
delegation re-executes on already-closed resources.

```java
@Override
public void close() throws IOException {
    // No guard — re-delegates on every call
    for (PartitionClient client : clients.values()) {
        client.close(); // throws on second invocation
    }
    underlying.close(); // double-close
}
```

## Symptoms

- `IllegalStateException` or `ClosedChannelException` on second close() call
- Double-free of off-heap memory (MemorySegment already closed)
- Redundant flush/sync I/O on already-closed file channels
- Intermittent failures in shutdown sequences where multiple components close
  the same shared resource
- Suppressed exceptions masking the original error when close() is called in
  a finally block after an earlier close() in the try block

## Root Cause

The method lacks a `volatile boolean closed` flag (or equivalent) checked at
entry. The AutoCloseable contract explicitly states that close() must be
idempotent — "if the stream is already closed then invoking this method has
no effect" — but this is not enforced by the compiler.

## Fix Pattern

1. **Add a volatile boolean flag.** Check it at method entry with an early
   return. Set it before performing any delegation.

```java
private volatile boolean closed;

@Override
public void close() throws IOException {
    if (closed) return;
    closed = true;

    IOException accumulated = null;
    for (PartitionClient client : clients.values()) {
        try {
            client.close();
        } catch (IOException e) {
            if (accumulated == null) accumulated = e;
            else accumulated.addSuppressed(e);
        }
    }
    if (accumulated != null) throw accumulated;
}
```

2. **Use the same flag for close-guard checks in other methods.** The `closed`
   flag can double as the guard for `read-method-missing-close-guard` — throw
   `IllegalStateException` on any operation after close.

3. **Test double-close explicitly.** A test that calls `close()` twice and
   verifies no exception is thrown catches regressions immediately.

## Detection

- Resource lifecycle lens: call close() twice on the object and verify the
  second call is a no-op (no exception, no re-delegation)
- Look for close() implementations that delegate to child resources without
  a boolean guard at method entry
- Check for close() methods that do not set any state — they are almost
  certainly non-idempotent

## Audit Findings

Identified in table-partitioning audit run-001:
- `PartitionedTable.close()` — re-delegated to all partition clients on every
  call without tracking prior invocation (resource_lifecycle.1.2)
- `InProcessPartitionClient.close()` — re-delegated to underlying table on
  every call (resource_lifecycle.1.3)
