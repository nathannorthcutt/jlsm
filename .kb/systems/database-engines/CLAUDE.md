# database-engines — Category Index
*Topic: systems*

Persistence patterns, metadata management, and recovery strategies for database
engines. Covers catalog persistence, table lifecycle, and startup recovery.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [catalog-persistence-patterns.md](catalog-persistence-patterns.md) | Database Catalog Persistence Patterns | mature | O(1) lookup, O(n) lazy recovery | Multi-table engines needing fast startup |

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
