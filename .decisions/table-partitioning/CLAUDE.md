---
problem: "table-partitioning"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-03-16"
---

# Table Partitioning — Decision Index

**Problem:** Choose a partitioning strategy for distributed jlsm-table
**Status:** confirmed
**Current recommendation:** Range Partitioning with Per-Partition Co-located Indices
**Last activity:** 2026-03-16 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-03-16 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix | 2026-03-16 |
| [constraints.md](constraints.md) | Constraint profile | 2026-03-16 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-03-16 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Data Partitioning Strategies | Chosen (range partitioning) | [`.kb/distributed-systems/data-partitioning/partitioning-strategies.md`](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md) |
| Vector Search Partitioning | Chosen (co-located topology) | [`.kb/distributed-systems/data-partitioning/vector-search-partitioning.md`](../../.kb/distributed-systems/data-partitioning/vector-search-partitioning.md) |
| Hash Partitioning | Rejected — breaks range queries | [`.kb/distributed-systems/data-partitioning/partitioning-strategies.md`](../../.kb/distributed-systems/data-partitioning/partitioning-strategies.md) |
| Global IVF Sharding | Rejected — separates vectors from documents | [`.kb/distributed-systems/data-partitioning/vector-search-partitioning.md`](../../.kb/distributed-systems/data-partitioning/vector-search-partitioning.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-03-16 | active | Range partitioning with co-located indices |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
