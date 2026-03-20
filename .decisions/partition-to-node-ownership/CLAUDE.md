---
problem: "partition-to-node-ownership"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-03-20"
---

# Partition-to-Node Ownership — Decision Index

**Problem:** How should tables and partitions be mapped to cluster members?
**Status:** confirmed
**Current recommendation:** Rendezvous Hashing (HRW) — stateless pure function, O(K/N) minimal movement, deterministic from membership view
**Last activity:** 2026-03-20 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-03-20 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (3 candidates) | 2026-03-20 |
| [constraints.md](constraints.md) | Constraint profile | 2026-03-20 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-03-20 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Consistent Hashing & LSH Partitioning | Chosen (rendezvous hashing layer) | [`.kb/systems/vector-partitioning/consistent-hashing.md`](../../.kb/systems/vector-partitioning/consistent-hashing.md) |
| Data Partitioning Strategies | Rejected — consistent hashing ring (unnecessary complexity), modulo (catastrophic movement) | [`.kb/distributed-systems/data-partitioning/partitioning-strategies.md`](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-03-20 | active | Rendezvous Hashing (HRW) |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
