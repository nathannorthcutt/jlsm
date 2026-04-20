---
title: "Unsafe This-Escape Via Listener Registration"
aliases: ["this-escape", "partially-constructed instance", "ctor listener leak", "inner-class capture before publication"]
topic: "patterns"
category: "concurrency"
tags: ["adversarial-finding", "concurrency", "publication", "shared-state", "resource-lifecycle", "constructor"]
type: "adversarial-finding"
research_status: "stable"
confidence: "high"
last_researched: "2026-04-20"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/ClusteredEngine.java"
related:
  - "non-atomic-lifecycle-flags"
  - "phantom-registration-after-lifecycle-transition"
  - "partial-init-no-rollback"
decision_refs: []
sources:
  - "audit run-001 f04-obligation-resolution--wd-03"
---

# Unsafe This-Escape Via Listener Registration

## Summary

A constructor registers an inner-class listener or handler (capturing `this`)
with an external collaborator before every final field has been assigned. If
the collaborator dispatches the listener synchronously during registration —
from another thread or via a re-entrant callback — the listener observes a
partially-constructed instance: any field assigned after the registration
point is not yet visible under the Java Memory Model. The bug composes three
lenses — **concurrency** (unsafe publication), **resource-lifecycle** (no
rollback of the registration on later ctor failure), and **shared-state**
(inner class captures `this` before publication).

## Problem

```java
public final class ClusteredEngine {
    private final Membership membership;
    private final Transport transport;
    private final LocalEngine local;          // assigned AFTER listener registration
    private final PartitionRouter router;     // assigned AFTER listener registration

    public ClusteredEngine(Membership membership, Transport transport, ...) {
        this.membership = membership;
        this.transport = transport;

        // BUG: listener captures `this`, and `membership` may dispatch
        // synchronously from another thread the instant we register.
        membership.addListener(new MembershipListener() {
            @Override public void onMemberJoin(NodeId id) {
                router.route(id);             // NPE — router is still null
            }
        });

        // BUG: if registerHandler throws, the listener above is NOT unwound.
        transport.registerHandler(QUERY_REQUEST, new QueryHandler() {
            @Override public void handle(QueryRequest req) {
                local.query(req);             // NPE — local is still null
            }
        });

        this.local  = new LocalEngine(...);   // assigned too late
        this.router = new PartitionRouter(...);
    }
}
```

Two independent defects live inside this same ctor:

1. **Unsafe publication** — the inner-class listener captures `this` before the
   JMM guarantees visibility of the `final` fields. A concurrent dispatch from
   the collaborator's thread may observe `null` for `local` and `router`.
2. **No rollback on partial init** — if `transport.registerHandler` throws,
   the already-registered membership listener remains wired to the half-built
   instance, which is then unreachable by any `close()` path.

## Symptoms

- `NullPointerException` inside listener/handler methods on a field that
  "cannot possibly be null" because it is `final` and assigned in the ctor
- Flaky in unit tests, reproducible under a stress harness that forces a
  concurrent membership event during construction
- Crash logs that show a freshly-constructed instance receiving a callback
  before its ctor completed
- On ctor failure mid-way: orphan listener still fires on the dead instance
  after GC has otherwise collected the enclosing object's strong references

## Root Cause

Two JMM and lifecycle issues composing at the same call site:

- **`final`-field guarantees only hold after the ctor completes.** Before the
  ctor returns, another thread observing the `this` reference may see the
  default value (`null`, `0`, `false`) for any field not yet assigned.
  Registering a listener that captures `this` with an external collaborator
  publishes `this` across the thread boundary *before* the ctor completes.
- **Registration with an external collaborator is an acquired resource.**
  Each `addListener` / `registerHandler` call is a resource that must be
  unwound on ctor failure. If the ctor throws after one registration succeeds
  but before the next, nothing deregisters the first listener — it is
  permanently wired to a dead instance.

## Fix Pattern

1. **Make listener/handler registration the last action of the ctor.**
   Every final field must be assigned before any collaborator can observe
   `this` via a captured reference.

2. **Wrap registrations in try/catch that unwinds on failure.** If step N+1
   throws, deregister steps 1..N before rethrowing. This turns the ctor
   into an all-or-nothing operation.

3. **Prefer two-phase construction over registration-in-ctor** when the
   listener logic genuinely depends on fully-initialised state. Construct
   the object, then call a separate `start()` method that wires listeners.
   `start()` can fail cleanly without leaving the ctor in an ambiguous
   partially-initialised state.

4. **Never let an inner class escape the ctor.** If a listener must capture
   state, make it capture an immutable snapshot of the required fields
   rather than the enclosing `this`.

```java
public final class ClusteredEngine implements AutoCloseable {
    private final Membership membership;
    private final Transport transport;
    private final LocalEngine local;
    private final PartitionRouter router;

    // inner-class listener references are retained so close() can deregister them
    private final MembershipListener membershipListener;
    private final QueryHandler queryHandler;

    public ClusteredEngine(Membership membership, Transport transport, ...) {
        this.membership = Objects.requireNonNull(membership);
        this.transport  = Objects.requireNonNull(transport);
        this.local      = new LocalEngine(...);
        this.router     = new PartitionRouter(...);

        // All final fields are assigned. Now we can safely publish `this`.
        this.membershipListener = this::onMemberJoin;
        this.queryHandler       = this::handleQuery;

        boolean registered = false;
        membership.addListener(membershipListener);
        try {
            transport.registerHandler(QUERY_REQUEST, queryHandler);
            registered = true;
        } finally {
            if (!registered) {
                membership.removeListener(membershipListener);   // rollback
            }
        }
    }

    @Override public void close() {
        transport.unregisterHandler(QUERY_REQUEST, queryHandler);
        membership.removeListener(membershipListener);
    }

    private void onMemberJoin(NodeId id) { router.route(id); }
    private void handleQuery(QueryRequest req) { local.query(req); }
}
```

## Test Guidance

Numbered steps to reproduce the unsafe-publication flavour:

1. Build a stub `Membership` collaborator whose `addListener` calls the
   listener **synchronously on a different thread** before returning. Gate
   that dispatch on a `CountDownLatch` held by the test.
2. In the test thread, invoke `new ClusteredEngine(membership, ...)`.
3. From a second thread, release the latch to fire the synchronous callback
   while the ctor is still running (i.e., before the later `final` fields
   are assigned).
4. Assert the callback thread observes an NPE (or, after fix, no NPE and
   correct behaviour against the fully-constructed instance).

Numbered steps to reproduce the rollback-missing flavour:

1. Build a stub `Transport` whose `registerHandler` throws
   `TransportException` unconditionally.
2. Build a stub `Membership` that records every `addListener` and
   `removeListener` call.
3. Attempt `new ClusteredEngine(membership, transport, ...)` and assert it
   throws.
4. Assert the membership stub recorded exactly one `addListener` followed
   by exactly one `removeListener` call (i.e., the rollback fired).

## Examples from Codebase

Identified in f04-obligation-resolution--wd-03 audit run-001
(`ClusteredEngine` ctor):

- **F-R1.concurrency.1.2** — membership listener registered before `local`
  and `router` are assigned; synchronous join-event dispatch observes
  `null` final fields.
- **F-R1.concurrency.1.3** — `QUERY_REQUEST` handler registered before
  `local` is assigned; re-entrant handler invocation observes `null`.
- **F-R1.resource_lifecycle.1.1** — if `registerHandler` throws, the
  already-registered membership listener is not deregistered, leaking
  the listener onto a half-built instance.

The same three findings were detected by three independent lens passes
(concurrency, resource-lifecycle, shared-state), all converging on the
same call sites — a strong signal that the root cause is structural, not
superficial.
