---
type: adversarial-finding
domain: resource-management
severity: confirmed
tags: [close, resource-leak, deferred-exception, wrapper]
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/"
sources:
  - json-only-simd-jsonl audit run-001, 2026-04-12
---

# Multi-Resource Close Ordering and Deferred Exception Handling

## Pattern

When a component wraps an external resource (e.g., InputStream) with an internal
resource (e.g., BufferedReader), the `close()` method must close both in the
correct order using deferred exception handling. Two failure modes:

1. **Untracked internal closeable** — the internal resource is created in a
   method other than the constructor and stored only as a local variable, so
   `close()` has no reference to it. The wrapper's close delegates to the outer
   resource but the internal resource is never closed.
2. **Intermediate failure aborts close chain** — an intermediate operation
   (e.g., flush) throws before the underlying resource is closed, leaking it.
   The caller's try-with-resources only sees the flush exception and assumes
   close completed.

## Why It Happens

Wrapper components often create internal resources lazily (on first read/write)
or in a factory method rather than the constructor. If the internal resource is
stored as a local variable in that method rather than an instance field, the
`close()` method cannot reach it. Separately, `close()` implementations that
call `flush()` before `close()` on the underlying stream fail to use try-finally,
so a flush failure prevents the underlying close.

## Fix

Store all closeable resources as instance fields at creation time. Use
try-finally with deferred exception accumulation in `close()`:

```java
public void close() throws IOException {
    IOException deferred = null;
    // Close internal resources first (reverse creation order)
    try {
        if (bufferedReader != null) bufferedReader.close();
    } catch (IOException e) {
        deferred = e;
    }
    // Always close underlying resource
    try {
        underlying.close();
    } catch (IOException e) {
        if (deferred != null) deferred.addSuppressed(e);
        else deferred = e;
    }
    if (deferred != null) throw deferred;
}
```

## Detection

- Contract-boundaries and resource-lifecycle lenses: resource-field analysis
  showing closeables created but not stored as instance fields
- Adversarial test: use mock streams that throw on `flush()`; verify the
  underlying stream is still closed
- Adversarial test: verify `close()` after partial initialization still releases
  all resources created so far

## Scope

Applies to any component that wraps or layers resources: readers wrapping
streams, writers wrapping channels, connection pools wrapping sockets. The
pattern is especially common in streaming APIs where resources are created
lazily.

Affected constructs in json-only-simd-jsonl: JsonlReader (F-R1.cb.2.5),
JsonlWriter (F-R1.resource_lifecycle.2.2).
