---
title: "Deferred close loops catch only IOException"
type: adversarial-finding
domain: "memory-safety"
severity: "confirmed"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/PartitionedTable.java"
research_status: active
last_researched: "2026-03-25"
---

# Deferred close loops catch only IOException

## What happens
Deferred-close loops (close all resources, accumulate exceptions) typically catch only IOException since Closeable.close() declares IOException. But if a close() implementation throws RuntimeException (e.g., IllegalStateException from a state check, NullPointerException from a corrupt reference), the catch block misses it and remaining resources are never closed — a direct resource leak. Similarly, static cleanup helpers that swallow exceptions instead of propagating them as suppressed lose diagnostic information.

## Why implementations default to this
The Closeable interface declares `throws IOException`, so developers naturally write `catch (IOException)`. RuntimeException is unexpected from close(), so it's not considered. Static cleanup methods used in catch blocks avoid throwing to prevent masking the original exception, but the correct pattern is to add close exceptions as suppressed to the original cause.

## Test guidance
- Create a resource that throws RuntimeException on close()
- Verify remaining resources in the deferred-close loop are still closed
- For builder cleanup: inject failure on Nth resource creation
- Verify close exceptions from resources 0..N-1 appear as suppressed on the thrown exception
- Check that cleanup helpers pass the original exception as the suppression target

## Found in
- table-partitioning (round 2, 2026-03-25): PartitionedTable.close() caught only IOException — RuntimeException from one client skipped remaining clients; closeAllClients() silently swallowed all exceptions during builder cleanup
