---
title: "Static Mutable State Test Pollution"
aliases: ["test pollution", "static registry leak", "cross-test contamination"]
topic: "patterns"
category: "testing"
tags: ["testing", "isolation", "static-state", "test-pollution"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-06"
applies_to:
  - "modules/jlsm-engine/src/test/java/jlsm/engine/cluster/InJvmTransport.java"
  - "modules/jlsm-engine/src/test/java/jlsm/engine/cluster/InJvmDiscoveryProvider.java"
related:
  - "wall-clock-dependency-in-duration-logic"
  - "stale-test-after-exception-type-tightening"
decision_refs: []
sources: []
---

# Static Mutable State Test Pollution

## Summary

Test-oriented infrastructure classes that use static `ConcurrentHashMap` fields
for cross-instance communication. The static state persists across test method
invocations in the same JVM, causing stale registrations from a previous test
to produce spurious failures or incorrect routing in subsequent tests. The fix
is per-instance registration tracking with `AutoCloseable` cleanup or enhanced
`clearRegistry()` that closes all resources before clearing.

## Problem

```java
public class InJvmTransport implements Transport {
    private static final Map<NodeId, InJvmTransport> REGISTRY =
        new ConcurrentHashMap<>();

    public InJvmTransport(NodeId id) {
        REGISTRY.put(id, this);
    }

    public void send(NodeId target, Message msg) {
        REGISTRY.get(target).receive(msg);  // stale entry from previous test
    }
}
```

Test A creates transports for nodes 1, 2, 3. Test B creates transports for
nodes 1 and 2 only. When test B sends to node 3, it finds test A's stale
transport in the registry — messages route to a defunct instance.

## Symptoms

- Tests pass individually but fail when run together (order-dependent failures)
- Messages routed to wrong/defunct instances
- `NullPointerException` or unexpected behavior from stale registry entries
- Tests that "fix themselves" when run in isolation with `--tests`

## Root Cause

`static` fields in Java persist for the lifetime of the classloader. In test
frameworks (JUnit), all test methods in a run share the same classloader. Static
mutable state from one test leaks into subsequent tests unless explicitly
cleared.

## Fix Pattern

### Option 1: Per-instance tracking with AutoCloseable

```java
public class InJvmTransport implements Transport, AutoCloseable {
    private static final Map<NodeId, InJvmTransport> REGISTRY =
        new ConcurrentHashMap<>();

    public InJvmTransport(NodeId id) {
        this.id = id;
        REGISTRY.put(id, this);
    }

    @Override
    public void close() {
        REGISTRY.remove(id);
    }
}
```

Tests use try-with-resources or `@AfterEach` to close transports.

### Option 2: Enhanced clearRegistry with resource cleanup

```java
public static void clearRegistry() {
    REGISTRY.values().forEach(t -> {
        try { t.close(); } catch (Exception ignored) {}
    });
    REGISTRY.clear();
}
```

Called in `@BeforeEach` to ensure clean state for every test.

### Option 3: Instance-scoped registry (no static state)

```java
public class TransportFabric {
    private final Map<NodeId, InJvmTransport> registry = new ConcurrentHashMap<>();
    // Each test creates its own fabric — zero cross-test contamination
}
```

## Detection

- Grep for `static.*Map|static.*Set|static.*List` in test infrastructure classes
- Look for `ConcurrentHashMap` fields with `put` but no test-scoped cleanup
- Order-dependent test failures (pass alone, fail in suite)

## Audit Findings

Identified in engine-clustering audit run-001:
- `InJvmTransport` — `REGISTRY` static field persisted across tests
- `InJvmDiscoveryProvider` — `REGISTERED` static field persisted across tests
