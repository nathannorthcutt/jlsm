---
title: "Local Failure Masquerading As Remote Outage"
aliases: ["failure misattribution", "uniform catch on remote path", "node-down impersonation", "blurred failure taxonomy"]
topic: "patterns"
category: "distributed-systems"
tags: ["adversarial-finding", "distributed-systems", "error-handling", "data-transformation", "contract-boundaries"]
type: "adversarial-finding"
research_status: "stable"
confidence: "high"
last_researched: "2026-04-20"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/RemotePartitionClient.java"
related:
  - "stub-client-data-loss"
decision_refs: []
sources:
  - "audit run-001 f04-obligation-resolution--wd-03"
---

# Local Failure Masquerading As Remote Outage

## Summary

A remote-dispatch path catches all exceptions uniformly and completes the
per-partition future as "node unavailable," erasing the distinction between
a genuine remote-side failure (unreachable node, network timeout) and a
local-origin failure (encoding bug, malformed schema, null input). Callers
observe "node X down" when the real cause was a local bug — producing
misleading partial-result metadata and turning a deterministic local bug
into an intermittent-looking distributed-system fault. The pattern
typically appears at multiple points on the same code path: request
encoding, response decoding, schema binding.

## Problem

```java
public CompletableFuture<PartitionResult> getRangeAsync(PartitionId pid, Range range) {
    var future = new CompletableFuture<PartitionResult>();
    try {
        byte[] encoded = encodeRangeRequest(pid, range);        // local — may throw
        transport.sendAsync(pid.owner(), encoded).whenComplete((resp, err) -> {
            if (err != null) {
                future.complete(PartitionResult.unavailable(pid, err));
                return;
            }
            try {
                future.complete(decodeRangeResponse(resp));     // local — may throw
            } catch (Throwable t) {
                // BUG: local decode failure reported as node-unavailable
                future.complete(PartitionResult.unavailable(pid, t));
            }
        });
    } catch (Throwable t) {
        // BUG: local encode failure reported as node-unavailable
        future.complete(PartitionResult.unavailable(pid, t));
    }
    return future;
}
```

Every throw site inside this method executes in the **local** process.
Collapsing all of them into `PartitionResult.unavailable(pid, ...)` tells
the caller "the remote node is down" when the real cause was an
`IllegalArgumentException` from our own encoder.

The same pattern appears in the response path:

```java
private Iterator<Entry> decodeRangeResponsePayload(Response resp) {
    Schema schema = resp.schema();
    if (schema == null) {
        return Collections.emptyIterator();  // BUG: silent empty result on null schema
    }
    return resp.entries().iterator();
}
```

A null schema is a local binding bug, but the method silently substitutes
an empty iterator — indistinguishable from "partition is genuinely empty."

## Symptoms

- Partial-result metadata shows nodes as unavailable when those nodes are
  actually healthy (verify by pinging the node directly)
- Client-side logs show `TimeoutException` / `NodeUnavailableException`
  while server-side logs show no corresponding request arriving
- Deterministic bugs (a specific input always fails to encode) surface as
  "flaky network" reports
- Silent empty results — scans return zero rows when the partition contains
  data, with no error indication
- Retries succeed when the client reconnects to a "different" replica —
  because the new replica is still the same local code, and the retry
  coincidentally uses a different input that does encode

## Root Cause

A single `try`/`catch` scope that wraps both local preparation (encoding,
validation, schema binding) and remote dispatch. The catch cannot
distinguish where the exception originated, so it applies the remote-path
failure taxonomy to errors that came from the local path.

This composes with the **contract-boundary** lens: the method's public
contract advertises two outcomes — "success, with entries" or "partition
unavailable, retry elsewhere." A local-origin failure is neither of these;
it should surface as a *programming error* (or validation error) that the
caller cannot paper over by retrying against another replica.

## Fix Pattern

1. **Split the try scope.** Wrap only the transport-level dispatch in the
   "node unavailable" catch. Encoding, decoding, and schema binding run
   outside that scope — they propagate directly to the caller.

2. **Add a third failure outcome to the contract.** "Local error" is not
   the same as "partition unavailable." Expose it as a distinct result
   (e.g., `PartitionResult.localFailure(pid, cause)`) or, preferably,
   propagate the original exception — a local bug should not be swallowed
   by a retry loop.

3. **Never substitute an empty iterator for an error.** If the schema is
   null, the response is malformed; throw a descriptive exception
   (`IllegalStateException` or a protocol-specific exception) so the
   caller can log and surface the failure. Silent empty iterators erase
   bug evidence.

4. **Classify failures at the throw site.** When a method genuinely must
   translate local exceptions for API uniformity, wrap them in a distinct
   subclass (`LocalDispatchException`) so upstream filters can route them
   to the programmer-error path.

```java
public CompletableFuture<PartitionResult> getRangeAsync(PartitionId pid, Range range) {
    // Local encoding — failures propagate as IllegalArgumentException
    byte[] encoded = encodeRangeRequest(pid, range);

    var future = new CompletableFuture<PartitionResult>();
    transport.sendAsync(pid.owner(), encoded).whenComplete((resp, err) -> {
        if (err != null) {
            // Only transport-level errors become partition-unavailable
            future.complete(PartitionResult.unavailable(pid, err));
            return;
        }
        try {
            future.complete(decodeRangeResponse(resp));
        } catch (Throwable t) {
            // Local decode failure — surface directly
            future.completeExceptionally(t);
        }
    });
    return future;
}

private Iterator<Entry> decodeRangeResponsePayload(Response resp) {
    Schema schema = resp.schema();
    if (schema == null) {
        throw new IllegalStateException(
            "response schema is null — protocol violation on " + resp.origin());
    }
    return resp.entries().iterator();
}
```

## Test Guidance

Numbered steps to reproduce:

1. Build a `Transport` stub that always succeeds — i.e., its `sendAsync`
   completes normally with a fixed response. This isolates remote failures
   out of the picture.
2. Feed the method an input that forces a local encode failure — e.g., a
   `Range` whose bounds violate the encoder's preconditions, or a `null`
   schema in the response.
3. Call `getRangeAsync(pid, range)` and await the result.
4. Assert the future completes **exceptionally** with the local error
   (e.g., `IllegalArgumentException`). Assert it does **not** complete
   normally with `PartitionResult.unavailable(pid, ...)`.
5. Repeat with a malformed response (null schema). Assert the method
   throws a descriptive exception rather than returning an empty iterator.

Complementary positive test — verify that a genuine transport failure
*does* still produce `PartitionResult.unavailable`:

1. Build a `Transport` stub whose `sendAsync` completes exceptionally
   with `TransportException`.
2. Use a valid input that encodes cleanly.
3. Call the method and assert the future completes normally with
   `PartitionResult.unavailable(pid, TransportException)`.

## Examples from Codebase

Identified in f04-obligation-resolution--wd-03 audit run-001
(`RemotePartitionClient`):

- **F-R1.data_transformation.1.7** — `getRangeAsync`: encode failure is
  caught by the same scope that handles transport failures and reported
  as a node outage.
- **F-R1.data_transformation.1.6** — `decodeRangeResponsePayload`:
  `schema == null` returns a silent empty iterator instead of raising a
  protocol-violation exception.
- **F-R1.data_transformation.1.1** — `doGet`: three distinct failure
  modes (transport error, decode error, null response) all collapse into
  `Optional.empty`. Status **FIX_IMPOSSIBLE** pending a protocol
  extension that gives the method a richer return type — the pattern is
  real but the fix needs an ADR first.

Composed cross-domain finding **XD-R1.3** flagged that the failure
taxonomy on `getRangeAsync` was systematically blurred — three
independent throw sites, all producing the same degraded signal, none
of them transport failures.
