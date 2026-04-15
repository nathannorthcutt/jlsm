---
problem: "piggybacked-state-exchange"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-13"
---

# Piggybacked State Exchange — Decision Index

**Problem:** How to distribute node metadata on heartbeat messages
**Status:** confirmed
**Current recommendation:** Fixed-Field Heartbeat Metadata — 10 bytes (version + p99_query + p99_replication + lhm), O(1) fixed-offset parsing
**Last activity:** 2026-04-13 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-13 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (4 candidates) | 2026-04-13 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-13 |
| [log.md](log.md) | Full decision history | 2026-04-13 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Cluster Membership Protocols | Piggybacked state section | [`.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-13 | active | Fixed-Field with Version Byte |
