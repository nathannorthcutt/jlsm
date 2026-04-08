---
title: "Incomplete Serialization Round-Trip"
aliases: ["silent field omission", "partial deserialization", "lossy persist-recover"]
topic: "patterns"
category: "validation"
tags: ["serialization", "round-trip", "data-loss", "schema", "recovery"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-07"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/table/TableCatalog.java"
related:
  - "destructive-error-recovery"
decision_refs: []
sources: []
---

# Incomplete Serialization Round-Trip

## Summary

Serialization and deserialization methods that silently omit fields or enum
values, producing structurally valid but semantically incomplete objects on
recovery. The deserialized object has the correct type but is missing critical
data (empty schema, default state enum). This creates a time-bomb: the data
appears recovered but is unusable or misleading.

## Problem

A persist method writes a subset of an object's fields. The corresponding read
method reconstructs the object from the persisted data, filling omitted fields
with defaults (null, empty collection, first enum constant). The reconstructed
object passes type checks and null guards but carries silently wrong data.

```java
void writeMetadata(TableMetadata meta, Path file) {
    writer.writeString(meta.name());
    writer.writeInt(meta.version());
    // WRONG: omits schema and state — they default to null/ACTIVE on read
}

TableMetadata readMetadata(Path file) {
    String name = reader.readString();
    int version = reader.readInt();
    return new TableMetadata(name, version, Schema.EMPTY, TableState.ACTIVE);
}
```

## Symptoms

- Tables recovered after restart have empty schemas despite having data
- State enum resets to default (e.g., ACTIVE) after recovery, losing DROPPED
  or COMPACTING state
- Queries against recovered tables return wrong results or fail schema validation
- Tests that check only non-null pass; tests that check field-level equality fail

## Root Cause

The serialization method was written (or modified) without a corresponding
update to the deserialization method, or vice versa. There is no compile-time
or runtime check that all fields are round-tripped. The object constructor
accepts defaults for omitted fields, masking the data loss.

## Fix Pattern

1. **Round-trip test as a gate.** For every serializable type, write a test
   that constructs a fully populated instance, serializes it, deserializes it,
   and asserts field-level equality. This test must cover all fields, including
   enums and nested objects.
2. **Exhaustive serialization.** Ensure the write method serializes every field.
   If a field is intentionally transient, document it explicitly and exclude it
   from the round-trip equality check.
3. **Version-aware deserialization.** When evolving the format, include a version
   marker. The reader should fail loudly if it encounters a version with fields
   it does not know how to deserialize, rather than silently defaulting.
4. **Sealed type coverage.** For enums and sealed hierarchies, ensure every
   variant is handled in both write and read paths. Use exhaustive switch
   expressions to get a compile-time error when a new variant is added.

```java
@Test
void roundTripPreservesAllFields() {
    var original = new TableMetadata("t1", 3, schema, TableState.COMPACTING);
    writeMetadata(original, path);
    var recovered = readMetadata(path);
    assertEquals(original.name(), recovered.name());
    assertEquals(original.version(), recovered.version());
    assertEquals(original.schema(), recovered.schema());
    assertEquals(original.state(), recovered.state());
}
```

## Detection

- Contract boundaries lens: round-trip test comparing pre-persist and
  post-recovery metadata, asserting field-level equality
- Grep for serialization methods (`write`, `serialize`, `persist`) and compare
  the set of fields written to the set of fields on the record/class
- Look for constructors or factory methods that supply defaults for fields that
  should come from persisted data

## Audit Findings

Identified in in-process-database-engine audit run-001:
- `TableCatalog.writeMetadata()` — omitted schema fields (cb.2.2)
- `TableCatalog.writeMetadata()` — omitted TableState (cb.2.3)
