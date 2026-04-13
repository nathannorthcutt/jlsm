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
| [wal-group-commit.md](wal-group-commit.md) | WAL Group Commit Patterns | active | Batch fsync amortization | Improving WAL write throughput |
| [corruption-detection-repair.md](corruption-detection-repair.md) | Corruption Detection and Repair Strategies | active | Per-layer checksums + scrubbing | Data integrity verification and recovery |
| [wal-recovery-patterns.md](wal-recovery-patterns.md) | WAL Recovery Patterns | active | 4 recovery modes (strict→skip-all) | Crash recovery, tail corruption, remote WAL replay |
| [handle-lifecycle-patterns.md](handle-lifecycle-patterns.md) | Database Handle Lifecycle and Resource Budgeting | active | O(1) handle acquire | TTL, priority dispatch, cross-table budgets |
| [schema-type-systems.md](schema-type-systems.md) | Schema Type Systems for Document Databases | active | O(1) per field validation | Binary types, bounded fields, schema migration |
| [cross-table-transaction-patterns.md](cross-table-transaction-patterns.md) | Cross-Table Transaction Patterns (Single-Node) | active | Shared WAL or WriteBatch | Multi-table atomicity without distributed coordination |

## Comparison Summary
<!-- Narrative comparison — write once 2+ subjects exist -->

## Recommended Reading Order
1. Start: [catalog-persistence-patterns.md](catalog-persistence-patterns.md) — foundational catalog patterns
2. Then: [wal-group-commit.md](wal-group-commit.md) — batch fsync, latency/throughput tradeoffs
3. Then: [corruption-detection-repair.md](corruption-detection-repair.md) — checksums, scrubbing, repair strategies

## Research Gaps
- Table lifecycle management (open/close/compaction coordination)
- Multi-tenant resource isolation patterns
- Catalog migration and schema evolution strategies

## Shared References Used
@../../_refs/complexity-notation.md
