---
problem: "connection-pooling"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-13"
---

# Connection Pooling — Decision Index

**Problem:** How should the NIO implementation of ClusterTransport manage TCP connections to peer nodes?
**Status:** confirmed
**Current recommendation:** Single-Connection Multiplexing — one TCP connection per peer, Kafka-style framing (4-byte length prefix + int32 stream ID), ReentrantLock write serialization, virtual thread reader loop
**Last activity:** 2026-04-13 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-13 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (4 candidates, 3 revision rounds) | 2026-04-13 |
| [constraints.md](constraints.md) | Constraint profile (with falsification additions) | 2026-04-13 |
| [research-brief.md](research-brief.md) | Research Agent commission | 2026-04-13 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-13 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Multiplexed Transport Framing | Chosen approach | [`.kb/distributed-systems/networking/multiplexed-transport-framing.md`](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-13 | active | Single-Connection Multiplexing with Kafka-style framing |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|

## Notes

This decision subsumes the deferred `message-serialization-format` decision — the framing
protocol IS the message serialization format for the transport layer.
