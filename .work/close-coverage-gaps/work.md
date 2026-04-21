---
group: close-coverage-gaps
goal: Bring four partial-coverage DRAFT specs to APPROVED by closing implementation gaps and filling test-annotation coverage.
status: active
created: 2026-04-21
---

## Goal

Bring four partial-coverage DRAFT specs to APPROVED by closing implementation gaps and filling test-annotation coverage.

## Scope

### In scope
- engine.clustering (27/114 traced pre-migration) + engine.in-process-database-engine (89/91) gap closure
- query.index-types (28/31) + query.query-executor (20/22) gap closure
- Annotation sweep on each spec to reach 'All traced requirements have both impl + test annotations'
- Promotion to APPROVED with Verification Notes

### Out of scope
- New query / engine features beyond what the spec currently describes
- Cross-module wiring (tracked by prior cross-module-integration group)
- Spec refactoring / domain re-sharding

## Ordering Constraints

WD-01 (engine) and WD-02 (query) can run in parallel. Neither depends on WG2-5.

## Shared Interfaces

None — each WD touches its own domain's module surface.
