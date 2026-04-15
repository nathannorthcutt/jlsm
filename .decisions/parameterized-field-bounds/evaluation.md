---
problem: "parameterized-field-bounds"
evaluated: "2026-04-13"
candidates:
  - path: "design-approach"
    name: "Per-type sealed permits"
  - path: "design-approach"
    name: "Generic Bounded wrapper"
  - path: "design-approach"
    name: "Split: BoundedArray permit + FieldDefinition bounds for numerics"
constraint_weights:
  scale: 1
  resources: 1
  complexity: 1
  accuracy: 3
  operational: 1
  fit: 3
---

# Evaluation — parameterized-field-bounds

## References
- Constraints: [constraints.md](constraints.md)
- KB source: [`.kb/systems/database-engines/schema-type-systems.md`](../../.kb/systems/database-engines/schema-type-systems.md) §parameterized-type-bounds
- Related ADR: [bounded-string-field-type](../bounded-string-field-type/adr.md), [binary-field-type](../binary-field-type/adr.md)

## Constraint Summary
Layout-affecting bounds (arrays) need sealed permits because they change storage
behavior. Validation/encryption bounds (numerics, timestamp) should not add new
permits because they don't change how the type is stored, serialized, or indexed —
only validation and OPE domain derivation differ.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 1 | Schema metadata, no per-document cost |
| Resources | 1 | Negligible memory |
| Complexity | 1 | Expert team |
| Accuracy | 3 | OPE domain derivation must work; validation must be eager |
| Operational | 1 | Write-time only |
| Fit | 3 | Switch-site impact is the dominant design concern |

---

## Candidate: Per-type sealed permits

**Design:** Add `BoundedInt32(int min, int max)`, `BoundedInt64(long min, long max)`,
`BoundedFloat32(float min, float max)`, `BoundedFloat64(double min, double max)`,
`BoundedTimestamp(Instant min, Instant max)`, `BoundedArray(FieldType elementType, int maxLength)`
as new sealed permits. 6 new permits total.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 1 | 4 | 4 | Schema metadata only |
| Resources | 1 | 4 | 4 | No runtime cost |
| Complexity | 1 | 2 | 2 | 6 new permits × ~10 switch sites = ~60 new arms |
| Accuracy | 3 | 5 | 15 | Pattern matching: `case BoundedInt32 b -> b.min()..b.max()` |
|  |  |  |  | **Would be a 2 if:** the 6 new permits created confusion about which to use |
| Operational | 1 | 4 | 4 | Write-time validation only |
| Fit | 3 | 2 | 6 | 6 new sealed permits is severe churn. Most arms delegate trivially to the unbounded version. BoundedString was 1 permit; this is 6 more. |
| **Total** | | | **35** | |

**Hard disqualifiers:** None, but Fit score of 2 is near-disqualifying.
The switch-site explosion is the dominant problem.

---

## Candidate: Generic Bounded wrapper

**Design:** `record Bounded<T extends FieldType>(T inner, Constraint constraint)` as
a single new sealed permit. `Constraint` is a sealed interface with `Range(Number min, Number max)`
and `MaxLength(int max)` implementations.

`FieldType.int32().bounded(0, 150)` → `Bounded(Primitive.INT32, Range(0, 150))`
`FieldType.arrayOf(string()).bounded(100)` → `Bounded(ArrayType(STRING), MaxLength(100))`

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 1 | 4 | 4 | Schema metadata only |
| Resources | 1 | 4 | 4 | No runtime cost |
| Complexity | 1 | 3 | 3 | 1 new permit, but generic pattern matching is awkward |
| Accuracy | 3 | 3 | 9 | `case Bounded(Primitive p, Range r) -> ...` works but nested pattern is verbose |
|  |  |  |  | OPE dispatch needs `Bounded(Primitive.INT32, Range)` — can't just match on `Bounded` |
| Operational | 1 | 4 | 4 | Write-time only |
| Fit | 3 | 3 | 9 | 1 new permit is minimal churn. But every switch site must now handle `case Bounded b` AND unwrap `b.inner()` to get the original type behavior. Effectively doubles switch logic. |
| **Total** | | | **33** | |

**Hard disqualifiers:** None.
**Key weakness:** Every switch site becomes: match Bounded, unwrap inner, re-dispatch.
This is more complex than per-type permits at each individual site.

---

## Candidate: Split — BoundedArray permit + FieldDefinition bounds

**Design:** Two mechanisms for the two groups:

1. **Layout-affecting:** `record BoundedArray(FieldType elementType, int maxLength)`
   as a new sealed permit (like BoundedString for strings, Binary for bytes).
   Only 1 new permit — arrays are the only remaining type where bounds change layout.

2. **Validation/encryption:** Move bounds to `FieldDefinition` as optional metadata.
   `FieldDefinition(String name, FieldType type, EncryptionSpec encryption, Optional<ValueBounds> bounds)`.
   `ValueBounds` is a sealed interface: `Range(Number min, Number max)` for numerics/timestamp.
   No new FieldType permits needed. FieldEncryptionDispatch reads bounds from the
   FieldDefinition context, not from the type itself.

Factory: `FieldDefinition.of("age", int32(), bounds(0, 150))`
         `FieldDefinition.of("tags", arrayOf(string(), 100))` — BoundedArray
         `FieldDefinition.of("lat", float64(), bounds(-90.0, 90.0))`

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 1 | 4 | 4 | Schema metadata only |
| Resources | 1 | 4 | 4 | No runtime cost |
| Complexity | 1 | 4 | 4 | 1 new permit (BoundedArray) + ValueBounds on FieldDefinition |
|  |  |  |  | **Would be a 2 if:** FieldDefinition already had too many parameters |
| Accuracy | 3 | 5 | 15 | OPE dispatch already receives FieldDefinition context — reads bounds from there. BoundedArray provides maxLength for array layout decisions. |
|  |  |  |  | **Would be a 2 if:** some code path had FieldType without FieldDefinition context |
| Operational | 1 | 4 | 4 | Write-time validation at FieldDefinition level |
|  |  |  |  | **Would be a 2 if:** validation needed to happen at a layer that only sees FieldType |
| Fit | 3 | 5 | 15 | Only 1 new sealed permit (BoundedArray). Zero switch-site changes for numeric bounds — they stay as Primitive. FieldDefinition already carries EncryptionSpec alongside type; bounds is a natural extension. |
|  |  |  |  | **Would be a 2 if:** many code paths need bounds but only have FieldType, not FieldDefinition |
| **Total** | | | **46** | |

**Hard disqualifiers:** None.

**Key strengths:**
- Minimal switch-site churn (1 new permit, same as BoundedString was)
- Clean separation: layout bounds → FieldType, validation bounds → FieldDefinition
- FieldDefinition already carries per-field metadata (encryption); bounds is natural
- OPE dispatch already receives FieldDefinition; no plumbing changes

**Key weaknesses:**
- Bounds for numerics are not in the type system — code with only FieldType can't
  see them. This is acceptable because no code path needs numeric bounds without
  the FieldDefinition context (validation and OPE dispatch both operate on fields).

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Total |
|-----------|-------|-----------|------------|----------|-------------|-----|-------|
| Per-type permits | 4 | 4 | 2 | 15 | 4 | 6 | **35** |
| Generic Bounded | 4 | 4 | 3 | 9 | 4 | 9 | **33** |
| Split: BoundedArray + FieldDef bounds | 4 | 4 | 4 | 15 | 4 | 15 | **46** |

## Preliminary Recommendation
Split approach wins decisively (46 vs 35 vs 33). BoundedArray as a sealed permit
for layout-affecting array bounds, ValueBounds on FieldDefinition for
validation/encryption bounds on numerics and timestamp.
