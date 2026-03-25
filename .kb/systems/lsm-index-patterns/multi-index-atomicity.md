---
title: "Multi-index atomicity"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/IndexRegistry.java"
  - "modules/jlsm-*/src/main/**"
research_status: active
last_researched: "2026-03-25"
---

# Multi-index atomicity

## What happens
When a table has multiple secondary indices, a write operation (insert or update)
that iterates indices sequentially can leave the system in an inconsistent state if
the Nth index operation fails after the first N-1 succeeded. The most common trigger
is multiple UNIQUE indices: if index A's unique check passes and inserts, but index
B's unique check fails, index A has an orphan entry (a key in the index with no
corresponding document in the document store).

The same pattern applies to updates: if index A is updated to new values but index B
rejects the update, index A has the new values while index B retains the old ones.

## Why implementations default to this
The single-index case is the common case, and sequential iteration is the simplest
approach. The atomicity bug only manifests when multiple unique-constrained indices
exist AND a constraint violation occurs on the non-first index. Standard TDD rarely
tests multi-unique-index failure scenarios.

## Test guidance
- Always test the "second unique index rejects" case when the table has 2+ unique indices
- After a failed insert/update, verify ALL indices are clean (no orphan entries)
- Separate validation from mutation: check all constraints first, then apply all changes

## Found in
- table-indices-and-queries (audit round 1, 2026-03-25): IndexRegistry.onInsert and onUpdate both had sequential unique check + insert, leaving orphan entries on partial failure
