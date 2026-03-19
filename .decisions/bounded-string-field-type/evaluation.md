---
problem: "bounded-string-field-type"
evaluated: "2026-03-19"
candidates:
  - path: "codebase-analysis"
    name: "New Sealed Permit (BoundedString record)"
  - path: "codebase-analysis"
    name: "BoundedString extends Primitive semantics (wrapper record)"
constraint_weights:
  scale: 1
  resources: 1
  complexity: 3
  accuracy: 3
  operational: 1
  fit: 3
---

# Evaluation — bounded-string-field-type

## References
- Constraints: [constraints.md](constraints.md)

## Constraint Summary
The decision must minimize churn across ~10 switch expression sites while ensuring BoundedString is compile-time distinguishable from unbounded STRING for OPE domain derivation. Backward compatibility with existing Primitive.STRING code is mandatory.

## Weighted Constraint Priorities
| Constraint | Weight (1-3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 1 | Not a runtime concern |
| Resources | 1 | Both candidates are pure Java records |
| Complexity | 3 | ~10 switch sites affected; dominant maintenance cost |
| Accuracy | 3 | Must distinguish bounded from unbounded at compile time |
| Operational | 1 | Zero runtime impact either way |
| Fit | 3 | Integration with sealed hierarchy is the core question |

---

## Candidate: New Sealed Permit (BoundedString record)

**Source:** Codebase analysis of FieldType.java and 10 usage sites

Add `FieldType.BoundedString(int maxLength)` as a 5th permit of the sealed interface:

```java
public sealed interface FieldType permits Primitive, ArrayType, VectorType, ObjectType, BoundedString {
    record BoundedString(int maxLength) implements FieldType { ... }
}
```

| Constraint | Weight | Score (1-5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 1 | 5 | 5 | No runtime difference |
| Resources | 1 | 5 | 5 | Pure Java record |
| Complexity | 3 | 2 | 6 | Every switch on FieldType needs a new arm (~10 sites); most will duplicate STRING logic |
| Accuracy | 3 | 5 | 15 | Compile-time distinct type; maxLength directly accessible |
| Operational | 1 | 5 | 5 | No runtime difference |
| Fit | 3 | 2 | 6 | Breaks existing switch exhaustiveness; contradicts ADR field-encryption-api-design which states FieldType.java unchanged |
| **Total** | | | **42** | |

**Hard disqualifiers:** None absolute, but contradicts existing ADR guidance.

**Key strengths:**
- Clean compile-time distinction; pattern matching works naturally
- maxLength is a direct record component

**Key weaknesses:**
- ~10 switch expressions need new `case BoundedString` arms, most duplicating STRING logic
- BoundedString IS a string semantically -- it behaves identically to STRING everywhere except OPE domain derivation. Adding a separate type arm forces every consumer to handle two string types
- Contradicts ADR field-encryption-api-design: "FieldType.java -- unchanged"

---

## Candidate: BoundedString wrapper record (STRING-compatible)

**Source:** Codebase analysis of FieldType.java and existing VectorType pattern

Add `FieldType.BoundedString(int maxLength)` as a 5th permit BUT make it behave like STRING in all switch expressions by providing a `primitive()` accessor that returns `Primitive.STRING`. Alternatively, use a simpler approach: keep BoundedString as a sealed permit, but have all existing switch arms handle it via a shared method or by matching `BoundedString` alongside `Primitive` with a guard.

Actually, re-evaluating: the cleanest approach given Java 25 sealed interfaces is to add the record but ensure the ~10 switch sites can handle it with minimal code. In Java 25 pattern matching, `case BoundedString _` can fall through or share logic with STRING.

However, there's a third approach that avoids the churn entirely:

---

## Candidate: Parameterized STRING via optional maxLength on FieldType interface

**Source:** Codebase analysis

Add a `default int maxStringLength()` method to FieldType that returns -1 (unbounded) by default. Override in a new `BoundedString` record to return the actual value. But then create `FieldType.string(int maxLength)` to return a `BoundedString`, and have the existing `FieldType.string()` continue returning `Primitive.STRING`.

The key insight: BoundedString should serialize/deserialize/validate identically to STRING. The ONLY consumer of the distinction is FieldEncryptionDispatch and IndexRegistry. So BoundedString can be treated as Primitive.STRING in all pattern matches except those two.

In the switch expressions, `case BoundedString bs` is added alongside `case Primitive p`, and the BoundedString arm delegates to the same STRING handling. This is ~10 one-line additions, not ~10 new code blocks.

| Constraint | Weight | Score (1-5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 1 | 5 | 5 | No runtime difference |
| Resources | 1 | 5 | 5 | Pure Java record |
| Complexity | 3 | 4 | 12 | ~10 switch sites need a new arm, but each is a one-liner delegating to STRING logic |
| Accuracy | 3 | 5 | 15 | Compile-time distinct; maxLength accessible; IndexRegistry can distinguish |
| Operational | 1 | 5 | 5 | No runtime difference |
| Fit | 3 | 4 | 12 | New permit is mechanical; each site adds `case BoundedString _ -> <same as STRING>` |
| **Total** | | | **54** | |

**Hard disqualifiers:** None.

**Key strengths:**
- Clean compile-time distinction for encryption dispatch
- maxLength directly accessible as record component
- Existing switch arms get a trivial one-line addition
- `FieldType.string(maxLength)` factory is natural API
- `FieldType.string()` still returns Primitive.STRING -- backward compatible

**Key weaknesses:**
- Still requires touching ~10 files (but minimal changes per file)
- Technically a new sealed permit, but the handling is trivially STRING-compatible

---

## Comparison Matrix

| Candidate | Complexity | Accuracy | Fit | Weighted Total |
|-----------|------------|----------|-----|----------------|
| New Sealed Permit (naive) | 6 | 15 | 6 | 42 |
| BoundedString record (STRING-delegating) | 12 | 15 | 12 | 54 |

## Preliminary Recommendation
**BoundedString record as a new sealed permit with STRING-delegating switch arms.** The record carries `maxLength` as a component, `FieldType.string(int)` is the factory, and all existing switch sites add a trivial `case BoundedString _` arm that delegates to STRING handling. The distinction is only meaningful in FieldEncryptionDispatch and IndexRegistry.

## Risks and Open Questions
- Risk: future FieldType consumers may forget to handle BoundedString (mitigated by sealed exhaustiveness checks)
- Risk: JSON/YAML schema serialization needs to represent maxLength (minor extension)
