---
problem: "non-vector-index-type-review"
date: "2026-04-13"
version: 1
status: "closed"
---

# Non-Vector Index Type Review — Closed (Non-Issue)

## Problem
Review whether EQUALITY, RANGE, UNIQUE, FULL_TEXT index types need changes after the index-definition-api-simplification.

## Decision
**Will not pursue.** The existing index types are correctly implemented with a well-defined compatibility matrix. No architectural changes needed.

## Reason
Audit of `IndexRegistry`, `FieldIndex`, `FullTextFieldIndex`, and `VectorFieldIndex` confirms:
- All five index types (EQUALITY, RANGE, UNIQUE, FULL_TEXT, VECTOR) are correctly implemented
- The FieldType-to-IndexType compatibility matrix is complete and enforced at schema validation time
- Predicate-to-index-type matching is correct in `FieldIndex.supports()`
- Encryption compatibility checks are in place in `validateEncryptedIndex()`

The only needed updates are adding Binary and BoundedArray to the compatibility matrix — these are implementation tasks covered by the binary-field-type and parameterized-field-bounds ADRs, not a separate architecture decision.

New index types (COMPOSITE, PREFIX, PARTIAL) would be genuinely new features requiring their own architecture decisions, not a review of existing types.

## Context
Deferred during `index-definition-api-simplification` (2026-03-17) with the note "may need their own review." The review is now complete — no issues found.

## Conditions for Reopening
- A new field type is added that doesn't fit the existing compatibility matrix
- A user reports incorrect index behavior on a specific field type
- COMPOSITE, PREFIX, or PARTIAL index types are requested (separate decisions)
