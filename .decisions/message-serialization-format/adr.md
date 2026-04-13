---
problem: "message-serialization-format"
date: "2026-04-13"
version: 1
status: "closed"
---

# Message Serialization Format — Closed (Subsumed)

## Problem
Message serialization format — caller's responsibility; in-JVM mode skips it entirely.

## Decision
**Subsumed by `connection-pooling`.** The connection-pooling ADR adopted Single-Connection
Multiplexing with a Kafka-style framing protocol (4-byte length prefix + type tag + int32
stream ID + flags + body). This framing protocol IS the message serialization format for
the NIO transport layer.

## Reason
The connection-pooling deliberation established that framing is baseline infrastructure
required by any NIO transport implementation. The framing protocol design was researched
and confirmed as part of that decision. A separate message-serialization-format decision
would duplicate the same design space.

## Context
Originally deferred from `transport-abstraction-design` as "caller's responsibility."
During `connection-pooling` evaluation, the user correctly identified that framing cannot
be deferred — any transport needs it. The decision was resolved structurally by incorporating
the framing design into the connection-pooling ADR.

## Conditions for Reopening
If the framing protocol needs to evolve beyond the current design (e.g., adding frame-level
compression like CQL v5, or supporting streaming/chunked responses), revisit via
`/decisions revisit "connection-pooling"` — the framing protocol is documented there.
