---
problem: "connection-pooling"
requested: "2026-04-13"
status: "pending"
---

# Research Brief — connection-pooling

## Context
The Architect is evaluating connection pooling for the NIO implementation of
`ClusterTransport`. The recommended approach is Single-Connection Multiplexing —
one TCP connection per peer, all messages multiplexed via correlation IDs over a
framing protocol. This decision subsumes the deferred `message-serialization-format`
decision.

Binding constraints for this evaluation:
- Pure Java 25, no external dependencies (no Netty, gRPC)
- Up to 1000 nodes (999 peer connections per node)
- Virtual threads for I/O (blocking SocketChannel)
- Expert team — complexity is not a narrowing constraint

## Subjects Needed

### Message Framing for Multiplexed Transports
- Requested path: `.kb/distributed-systems/networking/multiplexed-transport-framing.md`
  (or agent-determined path)
- Why needed: The recommended approach requires a framing protocol to delimit and
  multiplex messages on a single TCP connection. Need to understand proven patterns
  from production systems.
- Key questions to answer:
  - Length-prefix vs varint framing: what do Cassandra CQL native protocol, Kafka,
    and other pure-Java systems use? Tradeoffs?
  - Correlation ID encoding: monotonic counter, UUID, or structured (sender+sequence)?
    How do production systems handle ID exhaustion/wraparound?
  - Write serialization: how do multiplexed transports handle concurrent writers on
    a single channel? Queued writes vs per-frame locking? Impact on virtual threads?
  - Head-of-line blocking mitigation: any patterns beyond "keep messages small"?
  - How do systems handle connection lifecycle (health check, reconnect, drain)?
- Sections most important for this decision:
  - `## framing-formats` — length-prefix vs varint with real-world examples
  - `## correlation-dispatch` — correlation ID patterns and their tradeoffs
  - `## write-serialization` — concurrent writer patterns for single-channel transports
  - `## connection-lifecycle` — health checking, reconnection, graceful drain

## Commands to run
/research "message framing for multiplexed transports" context: "architect decision: connection-pooling"
