---
problem: "transport-abstraction-design"
date: "2026-03-20"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-cluster/src/main/java/jlsm/cluster/ClusterTransport.java"
  - "modules/jlsm-cluster/src/main/java/jlsm/cluster/Message.java"
  - "modules/jlsm-cluster/src/main/java/jlsm/cluster/MessageHandler.java"
  - "modules/jlsm-cluster/src/main/java/jlsm/cluster/MessageType.java"
  - "modules/jlsm-cluster/src/main/java/jlsm/cluster/NodeAddress.java"
---

# ADR — Transport Abstraction Design

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| (general knowledge) | No direct KB entry — API design decision | — |

## Related ADRs
| ADR | Relationship |
|-----|-------------|
| [cluster-membership-protocol](../cluster-membership-protocol/adr.md) | Rapid uses send() for pings, request() for view changes |
| [scatter-gather-query-execution](../scatter-gather-query-execution/adr.md) | Proxy table uses request() for sub-queries |

---

## Files Constrained by This Decision
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/` — transport SPI and in-JVM implementation

## Problem
What should the pluggable transport interface look like for inter-node messaging, consumed by Rapid membership protocol, scatter-gather proxy, and state exchange?

## Constraints That Drove This Decision
- **Dual pattern support**: must handle fire-and-forget (membership pings) and request-response (query scatter)
- **Consumer-agnostic**: transport doesn't know about message types — type-tag dispatch routes to registered handlers
- **Threading model awareness**: CompletableFuture-based API introduces async code paths where virtual threads are natural for fan-out, but ThreadLocal-dependent code (encryption) must run on platform threads

## Decision
**Chosen approach: Message-Oriented Transport**

Shared transport with two send operations and type-based handler dispatch:

```java
interface ClusterTransport extends AutoCloseable {
    /** Fire-and-forget — used by membership protocol for pings, protocol messages. */
    void send(NodeAddress target, Message msg) throws IOException;

    /** Request-response — used by scatter-gather for sub-queries. */
    CompletableFuture<Message> request(NodeAddress target, Message msg);

    /** Register a handler for a message type. */
    void registerHandler(MessageType type, MessageHandler handler);
}

interface MessageHandler {
    /** Handle an incoming message. Return a response for request-type messages. */
    CompletableFuture<Message> handle(NodeAddress sender, Message msg);
}
```

All consumers (Rapid, proxy table, state exchange) share one transport instance, multiplexed by message type tag. Adding a new consumer means registering a new handler — no SPI change.

### Threading Model Constraint

The CompletableFuture-based API naturally invites virtual threads for concurrent scatter-gather fan-out and message handling. However:

**ThreadLocal + virtual threads is unsafe.** Encryption code uses ThreadLocal for cipher instances and key material. Virtual threads do not pin to a carrier thread, so ThreadLocal values are per-virtual-thread (unbounded proliferation) and carrier-thread locals don't propagate.

**Rule: encryption and any ThreadLocal-dependent code must execute on platform threads via a dedicated executor.** The transport and scatter-gather layers may use virtual threads freely for I/O and coordination. When a message handler needs to perform encryption (e.g., decrypting a query result), it must dispatch to a platform thread pool.

Implementation pattern:
```java
// Virtual thread executor for transport I/O and scatter-gather
var ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

// Platform thread pool for ThreadLocal-dependent work (encryption)
var cryptoExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

// In scatter-gather: fan out on virtual threads, decrypt on platform threads
CompletableFuture<Message> result = transport.request(target, query)
    .thenApplyAsync(response -> decrypt(response), cryptoExecutor);
```

This constraint applies to all code paths in the clustering layer, not just the transport. The work planner must account for it when designing the scatter-gather proxy and message handlers.

## Rationale

### Why Message-Oriented Transport
- **Both patterns**: `send()` for fire-and-forget (Rapid pings), `request()` for request-response (scatter-gather sub-queries)
- **Consumer-agnostic**: message type tag routes to registered handlers — transport doesn't know about membership vs queries
- **Matches Rapid**: Rapid's reference implementation uses IMessagingClient/IMessagingServer with the same pattern
- **Trivial in-JVM implementation**: direct method call on target node's handler, no serialization

### Why not RPC-Style Transport
- Forces request-response on fire-and-forget patterns. Rapid's pings use protocol-level acks — generating transport-level responses is wasteful and misaligned.

### Why not Channel-Per-Purpose Transport
- Transport must know about consumers — adding a new consumer means adding a channel type to the SPI. Triples implementation surface. Traffic isolation achievable at a higher layer.

## Implementation Guidance

**In-JVM implementation:**
```java
class InJvmTransport implements ClusterTransport {
    private static final Map<NodeAddress, InJvmTransport> instances = new ConcurrentHashMap<>();
    private final Map<MessageType, MessageHandler> handlers = new ConcurrentHashMap<>();
    private final NodeAddress self;

    public void send(NodeAddress target, Message msg) {
        InJvmTransport remote = instances.get(target);
        if (remote == null) throw new IOException("Node unreachable: " + target);
        remote.dispatch(self, msg);
    }

    public CompletableFuture<Message> request(NodeAddress target, Message msg) {
        InJvmTransport remote = instances.get(target);
        if (remote == null) return CompletableFuture.failedFuture(new IOException("unreachable"));
        return remote.handlers.get(msg.type()).handle(self, msg);
    }
}
```

**Message type extensibility:** use a sealed interface or enum for known types (PING, ACK, VIEW_CHANGE, QUERY, QUERY_RESPONSE, STATE_DIGEST, STATE_DELTA), with an UNKNOWN catch-all for forward compatibility.

**Timeout on request():** the transport implementation should enforce a configurable timeout on request(). CompletableFuture completes exceptionally with TimeoutException. Scatter-gather proxy treats this as partition unavailable.

## What This Decision Does NOT Solve
- Traffic priority/isolation between membership and query traffic (handler-level concern)
- Message serialization format (caller's responsibility — in-JVM skips it)
- Connection management and pooling (NIO implementation concern)
- Backpressure on high-volume scatter (implementation concern)

## Conditions for Revision
This ADR should be re-evaluated if:
- Traffic isolation between membership and query traffic proves necessary at the transport level (currently handled above)
- Streaming responses are needed (current model is single request → single response; streaming would need a different abstraction)
- The threading model constraint around virtual threads and ThreadLocal proves too restrictive (may need ScopedValue migration)

---
*Confirmed by: user deliberation | Date: 2026-03-20*
*Full scoring: [evaluation.md](evaluation.md)*
