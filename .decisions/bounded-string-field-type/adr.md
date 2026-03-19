---
problem: "bounded-string-field-type"
date: "2026-03-19"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-table/src/main/java/jlsm/table/FieldType.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/DocumentSerializer.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/JlsmDocument.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/FieldEncryptionDispatch.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/IndexRegistry.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/JsonParser.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/JsonWriter.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/YamlParser.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/YamlWriter.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/FieldValueCodec.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/QueryExecutor.java"
---

# ADR — BoundedString Field Type Design

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |
| Related ADR | [field-encryption-api-design](../field-encryption-api-design/adr.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| None | Codebase analysis only — no algorithm research required | N/A |

---

## Files Constrained by This Decision

- `FieldType.java` — new `BoundedString(int maxLength)` record added as 5th sealed permit
- `DocumentSerializer.java` — `case BoundedString _` arms added, delegating to STRING logic
- `JlsmDocument.java` — `case BoundedString _` arm in validation, delegating to STRING logic
- `FieldEncryptionDispatch.java` — uses `BoundedString.maxLength()` for OPE domain derivation
- `IndexRegistry.java` — distinguishes BoundedString from Primitive.STRING for OrderPreserving validation
- `JsonParser.java`, `JsonWriter.java`, `YamlParser.java`, `YamlWriter.java` — `case BoundedString _` arms delegating to STRING logic
- `FieldValueCodec.java` — instanceof check extended to accept BoundedString as STRING-equivalent
- `QueryExecutor.java` — instanceof check extended to accept BoundedString as STRING-equivalent

## Problem
How should parameterized string length be represented in the FieldType sealed hierarchy so that OPE encryption can derive type-aware domain/range bounds while minimizing churn across the ~10 existing switch expression sites?

## Constraints That Drove This Decision
- **Fit with existing switch expressions (~10 sites)**: a new sealed permit forces compiler-enforced exhaustiveness, but each new arm is a trivial STRING delegation -- mechanical, not complex
- **Compile-time distinguishability**: FieldEncryptionDispatch and IndexRegistry must tell BoundedString from unbounded STRING to derive OPE bounds and reject invalid configurations
- **Backward compatibility**: existing `Primitive.STRING` and `FieldType.string()` usage must not change

## Decision
**Chosen approach: BoundedString record as a new sealed permit with STRING-delegating switch arms**

Add `record BoundedString(int maxLength) implements FieldType` as a 5th permit of the sealed `FieldType` interface. The factory `FieldType.string(int maxLength)` returns a `BoundedString`; the existing `FieldType.string()` continues returning `Primitive.STRING`. All switch sites outside encryption dispatch add a trivial `case BoundedString _` arm that delegates to STRING handling.

## Rationale

### Why BoundedString record as sealed permit
- **Compile-time safety**: sealed exhaustiveness ensures no switch site forgets to handle BoundedString. The compiler catches missing arms.
- **maxLength as record component**: directly accessible via `boundedString.maxLength()` for OPE domain calculation. No side-channel or map lookup needed.
- **Minimal churn**: each of ~10 switch sites adds a one-line arm delegating to STRING logic. The changes are mechanical and identical in pattern.
- **Natural API**: `FieldType.string(64)` reads clearly; `FieldType.string()` remains unchanged.
- **IndexRegistry distinction**: `instanceof BoundedString` vs `instanceof Primitive` cleanly separates the two for OrderPreserving validation.

### Why not keep STRING as enum-only with side-channel maxLength
- **No compile-time safety**: maxLength would need to be stored in a Map or on FieldDefinition, invisible to the type system. Easy to forget to check. OPE domain derivation would need runtime lookups instead of pattern matching.

### Why not replace Primitive.STRING with a parameterized type
- **Breaking change**: Primitive is an enum; removing STRING breaks all existing enum references, switch arms on `Primitive.STRING`, and serialization format.

## Implementation Guidance

### FieldType.java changes

```java
public sealed interface FieldType permits Primitive, ArrayType, VectorType, ObjectType, BoundedString {

    /**
     * A string with a declared maximum byte length.
     * Behaves identically to Primitive.STRING in all contexts except
     * encryption dispatch (OPE domain derivation) and index validation
     * (OrderPreserving compatibility).
     *
     * @param maxLength the maximum byte length; must be positive
     */
    record BoundedString(int maxLength) implements FieldType {
        public BoundedString {
            if (maxLength <= 0) {
                throw new IllegalArgumentException(
                    "maxLength must be positive, got: " + maxLength);
            }
        }
    }

    /** Returns {@link Primitive#STRING} (unbounded). */
    static FieldType string() {
        return Primitive.STRING;
    }

    /** Returns a {@link BoundedString} with the given max byte length. */
    static FieldType string(int maxLength) {
        return new BoundedString(maxLength);
    }
}
```

### Switch site pattern (all non-encryption sites)

```java
// Before:
case FieldType.Primitive p -> handlePrimitive(p, value);

// After:
case FieldType.Primitive p -> handlePrimitive(p, value);
case FieldType.BoundedString _ -> handlePrimitive(FieldType.Primitive.STRING, value);
```

### Encryption-aware sites (FieldEncryptionDispatch, IndexRegistry)

```java
// FieldEncryptionDispatch — OPE domain derivation:
case FieldType.BoundedString bs -> {
    long domain = (long) Math.pow(256, Math.min(bs.maxLength(), 6));
    long range = domain * 10;
    // construct OPE with these bounds
}

// IndexRegistry — OrderPreserving validation:
case FieldType.Primitive p when p == Primitive.STRING ->
    throw new IllegalArgumentException("OrderPreserving requires bounded string; use FieldType.string(maxLength)");
case FieldType.BoundedString _ -> { /* allowed */ }
```

### Default STRING max length convention
Per the brief, `Primitive.STRING` has no implicit cap. Users wanting OrderPreserving encryption must explicitly use `FieldType.string(maxLength)`. The conventional default is 255 (VARCHAR(255)), but this is the caller's choice, not enforced by the type system.

## What This Decision Does NOT Solve
- Binary field type (separate feature, explicitly deferred)
- Parameterized bounds for other field types (e.g., bounded arrays, bounded integers)
- Schema migration from `Primitive.STRING` to `BoundedString` for existing data

## Conditions for Revision
This ADR should be re-evaluated if:
- More than 3 additional parameterized field types are needed (suggests a different pattern, e.g., type parameters on Primitive)
- The ~10-site switch churn becomes a maintenance burden (would suggest extracting a `isStringLike()` helper)
- JSON/YAML schema format cannot cleanly represent BoundedString (would need format extension)

---
*Confirmed by: sub-agent invocation from Domain Scout | Date: 2026-03-19*
*Full scoring: [evaluation.md](evaluation.md)*
