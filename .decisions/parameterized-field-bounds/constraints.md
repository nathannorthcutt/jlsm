---
problem: "Parameterized bounds for field types beyond BoundedString"
slug: "parameterized-field-bounds"
captured: "2026-04-13"
status: "draft"
---

# Constraint Profile — parameterized-field-bounds

## Problem Statement
Extend the BoundedString pattern to other field types: bounded arrays (max
element count), bounded numerics (min/max range for OPE encryption and
validation), and bounded timestamps (validity windows). Two motivations:
(1) layout-affecting bounds that determine storage strategy (arrays, binary),
(2) validation/encryption bounds that narrow OPE domain (numerics, timestamp).

## Constraints

### Scale
Same as general jlsm-table usage. Bounds are schema-level metadata, not
per-document overhead. Validation is O(1) per field per write.

### Resources
Pure Java 25. No external deps. Bounds metadata stored in schema definition
— negligible memory.

### Complexity Budget
Weight 1. Expert team. BoundedString pattern established. The question is
generic vs per-type, not whether to do it.

### Accuracy / Correctness
Bounds must be enforced eagerly at write time (established pattern from
BoundedString). OPE encryption must derive domain from the bound parameters.
Round-trip: bounds are schema metadata, not serialized per-document.

### Operational Requirements
No impact on read path — bounds are write-time validation only. Schema
evolution: adding bounds to an existing field is a migration (covered by
string-to-bounded-string-migration decision).

### Fit
Must integrate with sealed FieldType hierarchy. Key question: does adding
bounds to numerics/timestamp require new sealed permits (BoundedInt32,
BoundedFloat64, etc.) or can it be done without new permits?

## Key Constraints (most narrowing)
1. **Fit with sealed hierarchy** — adding N new sealed permits for N numeric
   types creates O(N) switch-site churn. A mechanism that avoids new permits
   for validation-only bounds is strongly preferred.
2. **Layout-affecting vs validation-only** — arrays need a new permit (maxLength
   changes storage). Numerics may not.
3. **OPE domain derivation** — FieldEncryptionDispatch must access the bounds
   to compute OPE domain/range.

## Constraint Falsification — 2026-04-13

Checked: F07 (SQL), F10 (indices), F14 (document), bounded-string-field-type ADR,
binary-field-type ADR, schema-type-systems.md

No additional implied constraints found beyond the stated profile.

## Unknown / Not Specified
None — full profile captured from existing codebase context.
