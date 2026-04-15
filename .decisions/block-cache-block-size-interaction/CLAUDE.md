---
problem: "block-cache-block-size-interaction"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-14"
---

# Block Cache / Block Size Interaction — Decision Index

**Problem:** How should cache capacity account for variable block sizes (4 KiB–8 MiB)?
**Status:** confirmed
**Current recommendation:** Per-entry byte-budget eviction — track cached bytes via MemorySegment.byteSize(), evict when over budget
**Last activity:** 2026-04-14 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-14 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (5 candidates) | 2026-04-14 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-14 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-14 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Concurrent Cache Eviction Strategies | Context — RocksDB byte-budget model | [`.kb/data-structures/caching/concurrent-cache-eviction-strategies.md`](../../.kb/data-structures/caching/concurrent-cache-eviction-strategies.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | — | 2026-04-11 | superseded | Deferred — block size variability not yet observed |
| v2 | [adr.md](adr.md) | 2026-04-14 | active | Per-entry byte-budget eviction |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
