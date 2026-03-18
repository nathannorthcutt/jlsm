---
problem: "stripe-hash-function"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-03-17"
---

# Stripe Hash Function — Decision Index

**Problem:** Which mixing function to map (sstableId, blockOffset) pairs to stripe indices in StripedBlockCache
**Status:** confirmed
**Current recommendation:** Stafford variant 13 (splitmix64 finalizer) — zero-allocation, excellent avalanche
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
| Stafford variant 13 (splitmix64) | Chosen | Domain knowledge (no KB entry) |
| Long.hashCode mixing | Rejected — poor avalanche | Domain knowledge (no KB entry) |
| MurmurHash3 fmix64 | Rejected — single-input design | Domain knowledge (no KB entry) |
| Fibonacci hashing | Rejected — power-of-2 only | Domain knowledge (no KB entry) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-03-17 | active | Splitmix64 finalizer for stripe hashing |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
