---
problem: "membership-view-stall-recovery"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-13"
---

# Membership View Stall Recovery — Decision Index

**Problem:** How to recover when view changes stall due to quorum loss or partitions
**Status:** confirmed
**Current recommendation:** Tiered Escalation — piggyback catch-up → anti-entropy sync → forced rejoin, with operator API for quorum loss
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
| Membership View Stall Recovery | Chosen (tiered) | [`.kb/distributed-systems/cluster-membership/view-stall-recovery.md`](../../.kb/distributed-systems/cluster-membership/view-stall-recovery.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-13 | active | Tiered Escalation |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
