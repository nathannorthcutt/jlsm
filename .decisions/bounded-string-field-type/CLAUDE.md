# BoundedString Field Type Design — Decision Index

**Problem:** How to represent parameterized string length in the FieldType sealed hierarchy for OPE domain derivation
**Status:** confirmed
**Current recommendation:** BoundedString record as 5th sealed permit with STRING-delegating switch arms
**Last activity:** 2026-03-19 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-03-19 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix | 2026-03-19 |
| [constraints.md](constraints.md) | Constraint profile | 2026-03-19 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-03-19 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| None | Codebase analysis only | N/A |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-03-19 | active | BoundedString record as new sealed permit |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
