---
type: adversarial-finding
domain: data-integrity
severity: confirmed
tags: [rollback, partial-failure, compensating-transaction, fan-out]
applies_to: ["modules/jlsm-table/src/main/java/jlsm/table/internal/IndexRegistry.java"]
sources:
  - table-indices-and-queries audit R1, 2026-04-03
---

# Multi-Index Partial Failure Rollback

## Pattern

When an operation must propagate across N indices (insert, update, delete),
failure at index K leaves indices 1..K-1 in a mutated state and indices K+1..N
in the original state. Without compensating rollback, the system enters a
split-brain state where some indices reflect the mutation and others do not.

## Why It Happens

Fan-out mutation loops naturally iterate and break on failure. The break exits
the loop but does not undo prior mutations. Error handling focuses on reporting
the failure, not reversing it.

## Fix

Track the mutation count and apply compensating operations on failure:
```java
int mutated = 0;
try {
    for (Index idx : indices) {
        idx.insert(key, value);
        mutated++;
    }
} catch (Exception e) {
    // Reverse mutations in LIFO order
    for (int i = mutated - 1; i >= 0; i--) {
        try { indices.get(i).delete(key); }
        catch (Exception suppressed) { e.addSuppressed(suppressed); }
    }
    throw e;
}
```

## Scope

Applies to any fan-out mutation pattern in jlsm:
- IndexRegistry insert/update/delete across secondary indices
- Compaction writing to multiple SSTables
- WAL + MemTable dual-write
- Future multi-partition operations

## Found In

- table-indices-and-queries (audit R1, 2026-04-03): IndexRegistry
  (F-R1.conc.2.6, shared_state.2.3, 2.4, resource_lifecycle.1.3, 1.4)
