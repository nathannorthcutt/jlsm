---
title: "Stub Client Data Loss"
aliases: ["structural stub", "payload-dropping stub", "silent data loss"]
topic: "patterns"
category: "distributed-systems"
tags: ["distributed-systems", "stubs", "data-loss", "contracts"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-06"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/RemotePartitionClient.java"
related:
  - "partial-init-no-rollback"
decision_refs: []
sources: []
---

# Stub Client Data Loss

## Summary

Remote partition client methods implemented as structural stubs — they send the
correct message type but omit the operation's payload (document content, update
mode, scan results). Operations appear to succeed but silently discard data.
This pattern occurs when stub implementations satisfy the type system but not
the behavioral contract.

## Problem

A stub implementation returns the right type and does not throw, but drops the
actual data:

```java
@Override
public Document get(String id) {
    var response = transport.send(new GetRequest(id));
    return new Document(id);  // BUG: ignores response payload
}
```

The caller receives a `Document` (satisfying the return type) but the document
contains none of the stored fields. The type system is satisfied; the behavioral
contract is violated.

## Symptoms

- Round-trip tests fail: write a document, read it back, fields are missing
- Integration tests pass if they only check for non-null returns
- Data appears to be written successfully but reads return empty/default values
- No exceptions thrown — failures are completely silent

## Root Cause

Stub implementations written to satisfy compilation and basic structural tests
without implementing the full behavioral contract. Common during incremental
development when the focus is on message routing rather than payload fidelity.

## Fix Pattern

1. **Round-trip integration tests** — every client method must have a test that
   writes data through the client, reads it back, and asserts field-level
   equality. This catches payload-dropping stubs immediately.

2. **Payload assertion in serialization** — verify that serialized messages
   contain the expected payload bytes, not just the correct message type header.

3. **Contract-first development** — define the behavioral contract (including
   payload requirements) before writing the stub. The stub should fail loudly
   (`throw new UnsupportedOperationException("not yet implemented")`) rather
   than silently dropping data.

## Detection

- Contract boundaries lens: compare method signatures against actual payload
  content in the implementation
- Round-trip data integrity tests: serialize-send-receive-deserialize and
  assert equality
- Look for `new <Type>(id)` or `new <Type>()` returns where the constructor
  does not include data from the response

## Audit Findings

Identified in engine-clustering audit run-001:
- `RemotePartitionClient.create` — document content omitted from request
- `RemotePartitionClient.get` — response payload ignored in deserialization
- `RemotePartitionClient.update` — update mode not transmitted
- `RemotePartitionClient.getRange` — scan results not deserialized from response
