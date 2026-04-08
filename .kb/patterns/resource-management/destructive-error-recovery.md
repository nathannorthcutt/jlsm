---
title: "Destructive Error Recovery"
aliases: ["cleanup data loss", "delete-on-error", "aggressive cleanup"]
topic: "patterns"
category: "resource-management"
tags: ["error-handling", "data-loss", "cleanup", "recovery", "idempotency"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-07"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/table/TableCatalog.java"
related:
  - "partial-init-no-rollback"
  - "mutation-outside-rollback-scope"
decision_refs: []
sources: []
---

# Destructive Error Recovery

## Summary

Error-handling code that deletes data to "recover" from transient failures.
The cleanup path destroys more state than necessary, converting a recoverable
error into permanent data loss. This pattern is especially dangerous during
startup recovery where read errors are assumed to mean corruption.

## Problem

When a component encounters an error (e.g., IOException during metadata read),
the catch block deletes the on-disk directory or file to "clean up" the failed
state. If the error was transient (network hiccup, brief lock contention, OS
resource exhaustion), the data was never actually corrupt — but it is now gone.

A related variant occurs during concurrent registration: a TOCTOU race where
the loser of a create-or-fail check deletes the winner's directory in its
cleanup path, destroying successfully registered state.

```java
try {
    metadata = readMetadata(tableDir);
} catch (IOException e) {
    // WRONG: assumes corruption, deletes valid data
    deleteRecursive(tableDir);
    throw new CatalogException("corrupt table: " + name, e);
}
```

## Symptoms

- Data loss after transient I/O errors (network timeout, disk full momentarily)
- Tables or entries disappear during startup recovery
- Concurrent registrations cause one party's data to vanish
- Logs show "corrupt table" messages for data that was never actually corrupt

## Root Cause

The cleanup path conflates "I could not read this" with "this is corrupt and
must be removed." Transient read failures are indistinguishable from corruption
without additional verification (checksums, retry). The pessimistic assumption
that any failure means corruption leads to unnecessary data destruction.

In the TOCTOU variant, directory creation and metadata write are not atomic.
The loser sees the directory exists, assumes it owns it (or that it is stale),
and deletes it — destroying the winner's successfully written data.

## Fix Pattern

1. **Never delete on first failure.** Retry transient errors with backoff
   before concluding corruption.
2. **Verify corruption explicitly.** Use checksums or magic bytes to distinguish
   corrupt data from inaccessible data. Only delete after positive corruption
   confirmation.
3. **Quarantine instead of delete.** Move suspect data to a quarantine directory
   rather than deleting it. This preserves recovery options.
4. **Use atomic registration.** For concurrent creation, use a two-phase pattern:
   create with a temporary name, write metadata, then atomically rename to the
   final name. The loser's cleanup only removes its own temporary directory.

```java
try {
    metadata = readMetadata(tableDir);
} catch (IOException e) {
    // RIGHT: propagate without destroying data
    throw new CatalogException("failed to read table: " + name, e);
}
```

## Detection

- Search for `delete` or `deleteRecursive` calls inside `catch` blocks
- Look for cleanup paths in `open()` or `recover()` methods that remove
  directories or files on error
- Contract boundaries lens: inject transient IOException during metadata read
  and verify data survives
- Concurrency lens: race two registrations for the same name and verify neither
  deletes the other's data

## Audit Findings

Identified in in-process-database-engine audit run-001:
- `TableCatalog.open()` — deleted table directories on transient I/O error
  during metadata read
- `TableCatalog.register()` — TOCTOU race where loser deleted winner's directory
