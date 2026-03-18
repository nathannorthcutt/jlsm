---
problem: "cross-stripe-eviction"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-03-17"
---

# Cross-Stripe Eviction — Decision Index

**Problem:** How evict(sstableId) traverses all stripes in StripedBlockCache
**Status:** confirmed
**Current recommendation:** Sequential loop — iterate stripes, call evict() on each
**Last activity:** 2026-03-17 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-03-17 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix | 2026-03-17 |
| [constraints.md](constraints.md) | Constraint profile | 2026-03-17 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-03-17 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Sequential loop | Chosen | Domain knowledge (no KB entry) |
| Parallel stream | Rejected — common pool dependency | Domain knowledge (no KB entry) |
| All-locks-then-evict | Rejected — blocks all stripes | Domain knowledge (no KB entry) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-03-17 | active | Sequential loop for cross-stripe eviction |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
