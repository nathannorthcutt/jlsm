---
problem: "BoundedString field type design — how to represent parameterized string length in the FieldType sealed hierarchy"
slug: "bounded-string-field-type"
captured: "2026-03-19"
status: "draft"
---

# Constraint Profile — bounded-string-field-type

## Problem Statement
How should `FieldType.string(maxLength)` be implemented? The OPE encryptor needs the maximum byte length of a string field to derive domain/range bounds. Two options: (A) add a new record `FieldType.BoundedString(int maxLength)` to the sealed hierarchy, or (B) keep `Primitive.STRING` and add a parameterized factory `FieldType.string(int maxLength)` that returns a new type distinguishable from unbounded STRING.

## Constraints

### Scale
Not applicable — this is a compile-time type system design, not a runtime data path.

### Resources
Pure Java 25, no external dependencies. Must use sealed interfaces and records.

### Complexity Budget
There are ~10 switch expressions and pattern matches on FieldType across the codebase (DocumentSerializer encode/decode, JlsmDocument validation, JsonParser, JsonWriter, YamlParser, YamlWriter, IndexRegistry, QueryExecutor, FieldValueCodec). A new sealed permit requires updating every one. The project is maintained by a small team; minimizing churn is important.

### Accuracy / Correctness
- BoundedString must be distinguishable from unbounded STRING at compile time for OPE domain derivation in FieldEncryptionDispatch
- Existing `FieldType.Primitive.STRING` usage must remain backward compatible
- The maxLength must be accessible for OPE domain calculation
- IndexRegistry must be able to reject OrderPreserving on unbounded STRING but accept it on BoundedString

### Operational Requirements
Zero overhead for existing unencrypted STRING usage. The type distinction is only consulted during encryption dispatch construction, not per-document.

### Fit
- FieldType is a sealed interface with 4 permits: Primitive (enum), ArrayType, VectorType, ObjectType
- Primitive is an enum — enum constants cannot carry parameters
- All serialization/deserialization, parsing, and validation code pattern-matches on the 4 permits
- The existing `FieldType.string()` factory returns `Primitive.STRING`
- jlsm-sql module does not pattern-match on FieldType (no impact there)

## Key Constraints (most narrowing)
1. **Fit with existing switch expressions** — ~10 sites pattern-match on 4 FieldType permits; a 5th permit adds arms everywhere
2. **Backward compatibility** — existing `Primitive.STRING` code must not break
3. **Compile-time distinguishability** — OPE dispatch needs to know maxLength at schema construction time

## Unknown / Not Specified
None — full profile captured from codebase analysis.
