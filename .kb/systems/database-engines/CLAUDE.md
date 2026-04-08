# database-engines — Category Index
*Topic: systems*

Persistence patterns, metadata management, and recovery strategies for database
engines. Covers catalog persistence, table lifecycle, and startup recovery.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [catalog-persistence-patterns.md](catalog-persistence-patterns.md) | Database Catalog Persistence Patterns | mature | O(1) lookup, O(n) lazy recovery | Multi-table engines needing fast startup |
| [in-process-database-engine.md](in-process-database-engine.md) | in-process-database-engine (feature footprint) | stable | feature audit record | Engine handle lifecycle + catalog overview |
| [hardcoded-invalidation-reason.md](hardcoded-invalidation-reason.md) | Hardcoded invalidation reason in handle validity check (adversarial) | active | data-integrity bug class | Any handle system with multiple invalidation causes |
| [assert-only-public-validation.md](assert-only-public-validation.md) | Assert-only validation on public API inputs (adversarial) | active | data-integrity bug class | Any builder/constructor accepting numeric config |

## Comparison Summary
<!-- Narrative comparison — write once 2+ subjects exist -->

## Recommended Reading Order
1. Start: [catalog-persistence-patterns.md](catalog-persistence-patterns.md) — foundational catalog patterns

## Research Gaps
- Table lifecycle management (open/close/compaction coordination)
- Multi-tenant resource isolation patterns
- Catalog migration and schema evolution strategies

## Shared References Used
@../../_refs/complexity-notation.md
