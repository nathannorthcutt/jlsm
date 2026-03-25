---
problem: "What should the pluggable transport interface look like for inter-node messaging?"
slug: "transport-abstraction-design"
captured: "2026-03-20"
status: "draft"
---

# Constraint Profile — transport-abstraction-design

## Problem Statement
Define the SPI interface for inter-node message transport. The transport carries membership protocol messages (Rapid), scatter-gather query traffic (proxy table), and piggybacked state exchange (node metadata). Must be pluggable: in-JVM direct call for testing, NIO sockets for production.

## Constraints

### Scale
Hundreds of nodes. Membership messages are small and frequent. Query traffic is larger and bursty. Must handle both patterns.

### Resources
Not constraining.

### Complexity Budget
High. But the SPI should be simple — complexity belongs in implementations.

### Accuracy / Correctness
- Messages must be delivered reliably or failures must be surfaced (no silent drops)
- Ordering is not required by the transport — higher layers handle it
- The transport must not silently corrupt messages

### Operational Requirements
- In-JVM implementation must be trivial (direct method calls, no serialization)
- NIO implementation is future work but the SPI must not preclude it
- Must support concurrent sends to multiple nodes
- Must support both fire-and-forget (membership pings) and request-response (query scatter)

### Fit
- Consumed by Rapid (membership), proxy table (scatter-gather), state exchange (metadata)
- Must work with the message types from all three consumers
- Pure Java 25, no external dependencies
- Pluggable via builder injection or ServiceLoader

## Key Constraints (most narrowing)
1. **Dual pattern support** — must handle both fire-and-forget and request-response
2. **In-JVM trivial** — test implementation should be a few lines
3. **Consumer-agnostic** — transport doesn't know about membership vs query vs metadata

## Unknown / Not Specified
None.
