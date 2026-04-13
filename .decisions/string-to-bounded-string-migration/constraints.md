---
problem: "Schema migration policy when field types are tightened (e.g., STRING → BoundedString)"
slug: "string-to-bounded-string-migration"
captured: "2026-04-13"
status: "draft"
---

# Constraint Profile — string-to-bounded-string-migration

## Problem Statement
Define the migration policy when a schema evolves to tighten field constraints —
specifically STRING→BoundedString(N), but the pattern generalizes to any
constraint-tightening change (ArrayType→BoundedArray, future numeric bounds).
The serialization format already embeds schema version in the document header.
The question is: what happens to documents written under the old schema?

## Constraints

### Scale
Tables may contain millions of documents across many SSTable levels. Migration
must not require a full table scan or rewrite as a blocking operation.

### Resources
Pure Java 25. Migration should not require additional memory beyond normal
compaction budgets.

### Complexity Budget
Weight 1. Expert team. The compaction infrastructure already reads and rewrites
every document — piggyback migration is a natural fit.

### Accuracy / Correctness
- New writes MUST be validated eagerly against the new schema (established pattern)
- Old documents MUST NOT silently lose data or become unreadable
- Non-compliant documents (existing STRING values exceeding the new bound) must
  be handled explicitly — not silently truncated or silently passed through

### Operational Requirements
- Zero downtime — migration cannot require stopping reads or writes
- Migration should be progressive (not all-at-once)
- Must be observable — the system should report migration progress

### Fit
- DocumentSerializer already has schema version in header (2-byte short)
- Compaction already rewrites every document in affected SSTables
- JlsmSchema already has a version field

## Key Constraints (most narrowing)
1. **Zero downtime** — rules out stop-the-world rewrite
2. **Explicit non-compliance handling** — rules out silent pass-through and silent truncation
3. **Existing infrastructure** — schema version already in format, compaction already rewrites

## Unknown / Not Specified
None — the mechanism (version tag + compaction) is known; only the policy needs deciding.
