---
title: "Mutable State Escaping Builder"
aliases: ["builder reference leak", "missing defensive copy", "discarded builder config"]
topic: "patterns"
category: "validation"
tags: ["builder", "defensive-copy", "encapsulation", "mutable-state", "construction"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-07"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/partition/PartitionedTable.java"
related:
  - "assert-only-public-api-validation"
  - "incomplete-serialization-round-trip"
decision_refs: []
sources: []
---

# Mutable State Escaping Builder

## Summary

A builder accumulates state in mutable collections (maps, lists) and passes
them directly to the constructed object's constructor without defensive copying
or wrapping in an unmodifiable view. The builder — or any code retaining a
reference to the mutable collection — can mutate the object's internal state
after construction. A related variant: the builder collects a configuration
value but the constructed object has no field or accessor for it, silently
discarding the configured state.

## Problem

Two failure modes:

**Reference leak:** The builder stores partition clients in a mutable
LinkedHashMap and passes it directly to the constructed object. After
build(), the builder (or any code that called builder methods) still holds
a reference to the same map and can add, remove, or replace entries.

```java
class PartitionedTable {
    private final Map<String, PartitionClient> clients;

    // WRONG: caller's mutable map is used directly
    PartitionedTable(Map<String, PartitionClient> clients) {
        this.clients = clients; // no defensive copy
    }
}
```

**Silent discard:** The builder accepts a schema via `.schema(schema)` and
stores it internally, but the constructed object has no `schema` field.
The configured value is silently dropped during construction, and the
caller has no indication that their configuration was ignored.

```java
class Builder {
    private Schema schema;
    public Builder schema(Schema s) { this.schema = s; return this; }
    public PartitionedTable build() {
        // schema is never passed to PartitionedTable
        return new PartitionedTable(clients);
    }
}
```

## Symptoms

- External mutation of internal state after construction — adding or removing
  partitions via the original builder reference
- `ConcurrentModificationException` when the object iterates its internal map
  while external code modifies it
- Silent configuration loss — caller sets schema but queries return results
  without schema enforcement
- Difficult-to-reproduce bugs that depend on whether the builder reference
  is retained or garbage-collected

## Root Cause

**Reference leak** is a missing defensive copy. The builder passes its own
mutable collection to the constructor, transferring a live reference instead
of an immutable snapshot.

**Silent discard** is a builder-constructor mismatch. The builder API accepts
a value that the constructor does not consume, creating a false contract —
the caller believes the value is used, but it is dropped.

## Fix Pattern

1. **Defensive copy at construction.** Wrap mutable collections in
   `Collections.unmodifiableMap(new LinkedHashMap<>(source))` or use
   `Map.copyOf()` (for null-free maps) in the constructor.

```java
PartitionedTable(Map<String, PartitionClient> clients) {
    this.clients = Collections.unmodifiableMap(new LinkedHashMap<>(clients));
}
```

2. **Clear the builder's reference after build().** Set the builder's
   internal collection to null after constructing the object, so any
   post-build mutation attempt fails fast with NullPointerException.

3. **Propagate all builder state.** Every value the builder accepts must
   appear as a constructor parameter and a field in the constructed object.
   If a builder method exists, the value must be used. If the value is
   intentionally optional, document it and provide a default.

4. **Test post-build mutation.** After calling `build()`, mutate the
   builder's collection and verify the constructed object is unaffected.

## Detection

- Shared state lens: after build(), add an entry to the builder's map and
  check whether the constructed object sees it
- Contract boundaries lens: configure every builder method, then verify
  each value is accessible on the constructed object
- Look for constructors that assign collection parameters directly to
  fields without `Map.copyOf()`, `List.copyOf()`, or
  `Collections.unmodifiable*()` wrapping

## Audit Findings

Identified in table-partitioning audit run-001:
- `PartitionedTable` — mutable LinkedHashMap passed from builder without
  defensive copy, allowing post-construction mutation (shared_state.1.3)
- `PartitionedTable` — builder accepted schema but constructed object had
  no schema field, silently discarding the configured value (cb.2.1)
