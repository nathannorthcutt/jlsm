---
problem: "scatter-backpressure"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-13"
---

# Scatter Backpressure — Decision Index

**Problem:** How to prevent coordinator OOM when merging scatter-gather responses from multiple partitions
**Status:** confirmed
**Current recommendation:** Credit-Based + Flow API composite — credits map to ArenaBufferPool slabs, Flow API provides non-blocking demand signaling, continuation tokens for stateless partition paging
**Last activity:** 2026-04-13 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-13 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (5 candidates incl. composite) | 2026-04-13 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-13 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-13 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Scatter-Gather Backpressure Strategies | Chosen approach (composite) | [`.kb/distributed-systems/networking/scatter-gather-backpressure.md`](../../.kb/distributed-systems/networking/scatter-gather-backpressure.md) |
| Distributed Scan Cursor Management | Informed deliberation | [`.kb/distributed-systems/query-execution/distributed-scan-cursors.md`](../../.kb/distributed-systems/query-execution/distributed-scan-cursors.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-13 | active | Credit-Based + Flow API composite |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
| Hierarchical Query Memory Budget | hierarchical-query-memory-budget | deferred | Concurrent queries exceed credit budget |
| Scan Snapshot Binding | scan-snapshot-binding | deferred | Implementation reaches token encoding |
| Scan Lease GC Watermark | scan-lease-gc-watermark | deferred | After scan-snapshot-binding resolved |
