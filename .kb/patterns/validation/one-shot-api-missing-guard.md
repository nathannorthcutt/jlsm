---
type: adversarial-finding
domain: validation
severity: confirmed
tags: [one-shot, builder, lifecycle, state-guard, single-use]
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/"
sources:
  - json-only-simd-jsonl audit run-001, 2026-04-12
---

# One-Shot API Methods Missing Single-Use Enforcement Guards

## Pattern

APIs documented as single-use (builders, stream factories) lack runtime
enforcement. Without a guard flag, callers can invoke the method multiple times,
creating duplicate resources (competing readers on the same stream) or building
from stale/mutated state. The Javadoc says "call once" but nothing prevents a
second call.

## Why It Happens

The single-use contract feels obvious to the author — a builder produces one
object, a stream factory returns one stream. Documentation captures the intent
but the implementation does not enforce it. In practice, callers may:

- Call `build()` twice, getting two objects that share mutable internal state
- Call `stream()` twice, getting two streams that compete for the same
  underlying I/O resource
- Call a factory method after the source has been partially consumed

Without a guard, these produce subtle bugs (duplicate processing, corrupted
reads) rather than immediate failures.

## Fix

Add a boolean flag checked-and-set on first invocation:

```java
private boolean built;

public Foo build() {
    if (built) {
        throw new IllegalStateException("build() already called");
    }
    built = true;
    // ... construction logic ...
}
```

For thread-safe contexts, use `AtomicBoolean` or `VarHandle` CAS:

```java
private static final VarHandle BUILT;
static {
    try {
        BUILT = MethodHandles.lookup().findVarHandle(
            Builder.class, "built", boolean.class);
    } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
    }
}
private volatile boolean built;

public Foo build() {
    if (!BUILT.compareAndSet(this, false, true)) {
        throw new IllegalStateException("build() already called");
    }
    // ... construction logic ...
}
```

## Detection

- Contract-boundaries lens: lifecycle state analysis showing documented
  single-use contract without runtime enforcement
- Adversarial test: call the method twice in sequence; verify
  `IllegalStateException` on the second call
- Adversarial test: call from two threads concurrently; verify exactly one
  succeeds

## Scope

Applies to builders, stream/iterator factories, one-time initialization methods,
and any API whose documentation says "must be called at most once." Common in
streaming APIs where the underlying resource cannot be rewound.

Affected constructs in json-only-simd-jsonl: JsonlReader.stream()
(F-R1.cb.2.4), JsonObject.Builder.build() (F-R1.cb.1.3).
