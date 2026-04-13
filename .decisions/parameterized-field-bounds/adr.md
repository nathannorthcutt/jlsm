---
problem: "parameterized-field-bounds"
date: "2026-04-13"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-table/src/main/java/jlsm/table/FieldType.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/DocumentSerializer.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/JlsmDocument.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/FieldValueCodec.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/JsonValueAdapter.java"
---

# ADR — Parameterized Field Bounds

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Schema Type Systems | Parameterized bounds patterns, validation strategies | [`.kb/systems/database-engines/schema-type-systems.md`](../../.kb/systems/database-engines/schema-type-systems.md) |

---

## Files Constrained by This Decision

- `FieldType.java` — new `BoundedArray(FieldType elementType, int maxLength)` as 7th sealed permit
- `DocumentSerializer.java` — BoundedArray arms in encode/decode/measure (delegate to ArrayType with length validation)
- `JlsmDocument.java` — BoundedArray validation (element count <= maxLength at write time)
- `FieldValueCodec.java` — BoundedArray encoding for secondary index keys
- `JsonValueAdapter.java` — BoundedArray JSON conversion

## Problem
Extend the BoundedString parameterization pattern to other field types where
bounds affect storage layout or validation. Specifically: bounded arrays
(max element count) which affects memory budgeting and storage decisions.

## Constraints That Drove This Decision
- **Fit with sealed hierarchy (weight 3)**: Adding new sealed permits causes
  switch-site churn. Only add permits where bounds change layout behavior —
  not for pure validation concerns.
- **OPE 2-byte cap (Accuracy, weight 3)**: Current OPE only supports INT8/INT16.
  The byte-width already defines a tight domain. Custom numeric range bounds
  would micro-optimize an already-small domain — not worth the type system churn.
- **Layout-affecting vs validation-only**: Arrays are the only remaining type
  where bounds change how data is stored and memory is budgeted. Numerics and
  timestamps do not change layout based on range constraints.

## Decision
**Chosen approach: BoundedArray sealed permit only. Numeric bounds deferred.**

Add `record BoundedArray(FieldType elementType, int maxLength) implements
FieldType` as the 7th sealed permit. Factory: `FieldType.arrayOf(FieldType, int
maxLength)`. All switch sites add a `case BoundedArray` arm — most delegate to
ArrayType logic with a maxLength check at write time.

Numeric field bounds (INT32 min/max, FLOAT64 range, TIMESTAMP window) are
explicitly deferred until OPE supports wider types or a new encryption scheme
benefits from user-specified range metadata.

## Rationale

### Why BoundedArray only
- **Layout-affecting**: maxLength determines memory budgeting for array storage,
  similar to how BoundedString's maxLength determines OPE domain. The serializer
  and memory allocator need to know the upper bound.
- **Follows established precedent**: BoundedString (strings), Binary (bytes),
  BoundedArray (arrays) — each is a parameterized sealed permit for a type
  where the bound changes physical storage behavior.
- **Minimal churn**: 1 new permit, ~10 switch arms. Same scope as BoundedString.

### Why not per-type sealed permits for all types
- 6 new permits × ~10 switch sites = ~60 new arms. Most would be trivial
  delegation. Massive churn for marginal benefit on types where bounds don't
  affect layout.

### Why not numeric bounds now
- OPE is capped at 2 bytes (INT8/INT16 only). The byte-width already defines
  the OPE domain (256 for INT8, 65536 for INT16). Custom range bounds would
  narrow an already-tight domain — micro-optimization.
- Application-layer validation handles range constraints without type-system
  changes.

## Implementation Guidance

### FieldType.java
```
record BoundedArray(FieldType elementType, int maxLength) implements FieldType {
    BoundedArray {
        Objects.requireNonNull(elementType, "elementType must not be null");
        if (maxLength <= 0)
            throw new IllegalArgumentException("maxLength must be positive: " + maxLength);
    }
}

static FieldType arrayOf(FieldType elementType, int maxLength) {
    return new BoundedArray(elementType, maxLength);
}
```

### Switch site pattern
```
// Most sites — delegate to ArrayType logic:
case BoundedArray ba -> handleArray(ba.elementType(), value);

// Write-time validation (JlsmDocument, DocumentSerializer):
case BoundedArray ba -> {
    validateArrayLength(value, ba.maxLength());
    handleArray(ba.elementType(), value);
}
```

## What This Decision Does NOT Solve
- **Numeric field bounds** (min/max range for INT32/INT64/FLOAT*/TIMESTAMP) —
  deferred until OPE byte cap is raised or a new encryption scheme benefits
  from range metadata
- **Nested BoundedArray validation** — BoundedArray of BoundedArray; the
  maxLength check is at the outermost level only

## Conditions for Revision
This ADR should be re-evaluated if:
- OPE byte cap is raised beyond 2 bytes — wider numeric types would benefit
  from user-specified range bounds for OPE domain derivation
- A new encryption scheme (ORE, FHIPE) is added that uses range metadata
  for domain computation on numeric fields
- Application-layer validation proves insufficient and users demand schema-level
  numeric constraints

---
*Confirmed by: user deliberation | Date: 2026-04-13*
*Full scoring: [evaluation.md](evaluation.md)*
