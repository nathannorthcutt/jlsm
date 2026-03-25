---
problem: "transport-abstraction-design"
evaluated: "2026-03-20"
candidates:
  - path: "general-knowledge"
    name: "Message-Oriented Transport"
  - path: "general-knowledge"
    name: "RPC-Style Transport"
  - path: "general-knowledge"
    name: "Channel-Per-Purpose Transport"
constraint_weights:
  scale: 1
  resources: 1
  complexity: 1
  accuracy: 2
  operational: 3
  fit: 3
---

# Evaluation — transport-abstraction-design

## References
- Constraints: [constraints.md](constraints.md)
- Related ADRs: cluster-membership-protocol, scatter-gather-query-execution

## Constraint Summary
The transport SPI must support both fire-and-forget (membership pings) and request-response
(query scatter) patterns, be consumer-agnostic (doesn't know about message types), and have
a trivial in-JVM implementation.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 1 | Transport handles connection management — not the bottleneck |
| Resources | 1 | Not constraining |
| Complexity | 1 | Simple SPI, complex implementations |
| Accuracy | 2 | Reliable delivery or explicit failure |
| Operational | 3 | Dual pattern support, trivial test impl, concurrent sends |
| Fit | 3 | Consumer-agnostic, shared by all clustering layers |

---

## Candidate: Message-Oriented Transport

Two core operations: `send(NodeAddress, Message)` for fire-and-forget and
`request(NodeAddress, Message) -> CompletableFuture<Message>` for request-response.
A listener/handler for incoming messages. Messages are opaque bytes with a type tag
for dispatch. All consumers share one transport, multiplexed by message type.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | Single transport instance shared by all consumers, connection pooling in impl |
| Resources | 1 | 5 | 5 | Minimal overhead — one connection per peer, multiplexed |
| Complexity | 1 | 4 | 4 | Two send methods + handler registration. Simple but needs type-based dispatch |
| Accuracy | 2 | 5 | 10 | send() = fire-and-forget with delivery exception on failure. request() = CompletableFuture that completes exceptionally on timeout/failure |
| Operational | 3 | 5 | 15 | Both patterns in one interface. In-JVM: direct handler invocation, no serialization. Concurrent sends via CompletableFuture |
| Fit | 3 | 5 | 15 | Consumer-agnostic — message type tag routes to the right handler. Rapid, proxy, state exchange all register handlers. Matches Rapid's IMessagingClient/IMessagingServer pattern |
| **Total** | | | **54** | |

**Hard disqualifiers:** none

**Key strengths:**
- Supports both fire-and-forget and request-response in one interface
- Consumer-agnostic — type tag dispatch handles routing
- Matches Rapid's reference implementation transport pattern (IMessagingClient/IMessagingServer)
- In-JVM impl: direct method call on the target node's handler — zero serialization

**Key weaknesses:**
- Type-based dispatch requires a message type registry or enum
- No built-in traffic isolation between membership and query traffic (shared transport)

---

## Candidate: RPC-Style Transport

Strictly request-response: `CompletableFuture<Response> request(NodeAddress, Request)`.
Fire-and-forget is simulated by ignoring the response.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | Same connection reuse |
| Resources | 1 | 4 | 4 | Every message generates a response — wasted for fire-and-forget membership pings |
| Complexity | 1 | 4 | 4 | Single method — simpler interface |
| Accuracy | 2 | 5 | 10 | CompletableFuture for everything |
| Operational | 3 | 3 | 9 | Forces request-response on fire-and-forget patterns. Membership pings don't need responses from the transport layer (Rapid handles acks at the protocol level) |
| Fit | 3 | 3 | 9 | Couples transport to request-response. Rapid expects fire-and-forget for pings with protocol-level acks — awkward fit |
| **Total** | | | **41** | |

**Hard disqualifiers:** none but poor fit

**Key strengths:**
- Single method — simplest possible interface

**Key weaknesses:**
- Forces request-response on fire-and-forget patterns — wasteful and awkward for membership pings
- Rapid's protocol-level acks don't align with transport-level responses

---

## Candidate: Channel-Per-Purpose Transport

Separate channels: `MembershipChannel`, `QueryChannel`, `MetadataChannel`.
Each has its own send/receive with purpose-specific types.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 4 | 4 | Multiple channels per peer — more connections |
| Resources | 1 | 3 | 3 | 3x connections per peer for 3 channels |
| Complexity | 1 | 2 | 2 | Three separate interfaces. Each impl must implement three channels |
| Accuracy | 2 | 5 | 10 | Same reliability per channel |
| Operational | 3 | 4 | 12 | Traffic isolation — membership can't be starved by queries. But complex SPI |
| Fit | 3 | 2 | 6 | Transport knows about its consumers — not consumer-agnostic. Adding a new consumer requires a new channel type in the SPI |
| **Total** | | | **37** | |

**Hard disqualifiers:** none but significant design issues

**Key strengths:**
- Traffic isolation between membership and query traffic

**Key weaknesses:**
- Not consumer-agnostic — SPI must change when new consumers are added
- Triple the implementation surface for each transport provider
- Traffic isolation can be achieved at a higher layer (priority queues) without baking it into the SPI

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| Message-Oriented Transport | 5 | 5 | 4 | 10 | 15 | 15 | **54** |
| RPC-Style Transport | 5 | 4 | 4 | 10 | 9 | 9 | **41** |
| Channel-Per-Purpose Transport | 4 | 3 | 2 | 10 | 12 | 6 | **37** |

## Preliminary Recommendation
Message-Oriented Transport wins (54). Supports both communication patterns naturally, is consumer-agnostic, matches Rapid's reference transport design, and has a trivial in-JVM implementation.

## Risks and Open Questions
- No built-in traffic isolation — membership pings could be delayed by large query results. Mitigated by priority dispatch in the handler or separate thread pools per message type
- Message type registry must be extensible — enum or sealed interface for known types, with a catch-all for future types
