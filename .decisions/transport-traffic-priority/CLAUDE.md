---
problem: "transport-traffic-priority"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-13"
---

# Transport Traffic Priority — Decision Index

**Problem:** How to schedule outgoing messages by priority class on the multiplexed single-connection transport
**Status:** confirmed
**Current recommendation:** DRR + Strict-Priority Bypass — O(1) deficit round robin with strict bypass for CONTROL (heartbeats), 5 traffic classes, configurable weights
**Last activity:** 2026-04-13 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-13 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (4 candidates) | 2026-04-13 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-13 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-13 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Transport Traffic Priority and QoS | Chosen approach | [`.kb/distributed-systems/networking/transport-traffic-priority.md`](../../.kb/distributed-systems/networking/transport-traffic-priority.md) |
| Multiplexed Transport Framing | Foundation layer | [`.kb/distributed-systems/networking/multiplexed-transport-framing.md`](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-13 | active | DRR + Strict-Priority Bypass |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
| Adaptive Weight Tuning | adaptive-weight-tuning | deferred | Static weights prove insufficient under real workloads |
