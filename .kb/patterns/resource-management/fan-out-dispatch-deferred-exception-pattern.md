---
type: adversarial-finding
domain: resource-management
severity: confirmed
tags: [fan-out, dispatch, deferred-exception, stripe, shard, partial-failure]
applies_to:
  - "modules/jlsm-*/src/main/**"
sources:
  - striped-block-cache audit round-001, 2026-04-21
---

# Fan-out dispatch deferred-exception pattern

## Pattern

Operations that fan out to multiple siblings (per-stripe, per-shard,
per-partition) must use the deferred-exception pattern: iterate all siblings,
catch exceptions per-sibling, throw the first exception once iteration
completes with subsequent exceptions attached as suppressed. Short-circuiting
on the first failure leaves siblings partially processed and the caller
unable to distinguish complete success from partial progress.

## What happens

The method iterates siblings in order and calls each sibling's action. If
sibling 3 of 8 throws, the call returns with 3 processed, 5 untouched.
Callers assume the whole fan-out completed (that is the contract) and
proceed to the next operation. Silently skipped siblings retain stale data;
later reads return wrong values. A related failure mode: a sibling that has
been concurrently closed throws `IllegalStateException`, which the caller
does not translate into the fan-out operation's documented exception type,
so the failure surfaces as a generic runtime error rather than a contractually
meaningful signal.

## Fix pattern

Standard deferred-exception iteration — iterate every sibling, capture the
first failure, attach the rest as suppressed, throw once at the end:

```java
Throwable deferred = null;
for (var sibling : siblings) {
    try {
        sibling.action();
    } catch (Throwable t) {
        if (deferred == null) deferred = t;
        else deferred.addSuppressed(t);
    }
}
if (deferred != null) {
    // wrap or rethrow with the appropriate contract exception type
    throw deferred;
}
```

Apply uniformly — every fan-out method, not just `close()`. Translate
delegate-specific exceptions (e.g., `IllegalStateException` from a closed
sibling) into the operation's documented exception type before throwing.

## Test guidance

Inject a stub sibling that throws. Assert that:

1. All other siblings were still called (verify via per-sibling counters or
   mock interaction records)
2. The thrown exception is the first failure encountered
3. Subsequent failures appear as `getSuppressed()` entries on the thrown
   exception, in iteration order

For concurrent-close scenarios, close one sibling mid-iteration on a parallel
thread and assert the fan-out operation surfaces the documented contract
exception, not the raw delegate exception.

## Seen in

- striped-block-cache audit round-001, 2026-04-21:
  - F-R1.dispatch_routing.1.1 — `StripedBlockCache.evict` aborts on the first
    stripe failure, leaving later stripes un-evicted
  - F-R1.contract_boundaries.1.1 — `StripedBlockCache.size` does not translate
    a delegate `IllegalStateException` (from a concurrently closed stripe)
    into the `BlockCache` contract's use-after-close signal
