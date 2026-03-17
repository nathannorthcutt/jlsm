---
problem: "index-definition-api-simplification"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-03-17"
---

# Index Definition API Simplification — Decision Index

**Problem:** Remove vectorDimensions from IndexDefinition and derive from schema VectorType
**Status:** confirmed
**Current recommendation:** Derive dimensions from schema — remove vectorDimensions from IndexDefinition record
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
| (none) | Codebase-derived decision | — |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-03-17 | active | Derive dimensions from schema VectorType |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
