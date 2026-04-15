# Networking — Category Index
*Topic: distributed-systems*
*Tags: transport, framing, multiplexing, connection, pooling, NIO, TCP, wire-protocol, correlation-id, length-prefix, message-framing*

Inter-node networking patterns for cluster communication. Covers wire protocol
design, connection management, and transport-level multiplexing for persistent
TCP connections between cluster peers.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [multiplexed-transport-framing.md](multiplexed-transport-framing.md) | Multiplexed Transport Framing | active | O(1) per-message dispatch | Implementing NIO ClusterTransport with mixed traffic |
| [transport-traffic-priority.md](transport-traffic-priority.md) | Transport Traffic Priority and QoS | active | DRR O(1) per-dequeue | Prioritizing heartbeats over bulk on single connection |
| [scatter-gather-backpressure.md](scatter-gather-backpressure.md) | Scatter-Gather Backpressure Strategies | active | Credit-based + Flow API | Memory-bounded distributed query fan-out |

## Recommended Reading Order
1. Start: [multiplexed-transport-framing.md](multiplexed-transport-framing.md) — framing, correlation, write serialization, connection lifecycle
2. Then: [transport-traffic-priority.md](transport-traffic-priority.md) — QoS scheduling, message chunking, flow control
3. Then: [scatter-gather-backpressure.md](scatter-gather-backpressure.md) — query fan-out memory budgets, slow partition handling

## Research Gaps
- TLS/mTLS integration patterns for NIO transports
- Connection pool sizing heuristics for cluster transports
