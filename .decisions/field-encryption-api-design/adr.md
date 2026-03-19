---
problem: "field-encryption-api-design"
date: "2026-03-18"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-table/src/main/java/jlsm/table/FieldDefinition.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/JlsmSchema.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/DocumentSerializer.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/FieldType.java"
---

# ADR — Field Encryption API Design

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Searchable Encryption Schemes | Informed EncryptionSpec variants (DET, OPE, SSE families) | [`.kb/algorithms/encryption/searchable-encryption-schemes.md`](../../.kb/algorithms/encryption/searchable-encryption-schemes.md) |
| Vector Encryption Approaches | Informed DistancePreserving spec (DCPE/SAP) | [`.kb/algorithms/encryption/vector-encryption-approaches.md`](../../.kb/algorithms/encryption/vector-encryption-approaches.md) |
| JVM Key Handling Patterns | Informed key holder design (Arena-backed, zeroing) | [`.kb/systems/security/jvm-key-handling-patterns.md`](../../.kb/systems/security/jvm-key-handling-patterns.md) |

---

## Files Constrained by This Decision

- `FieldDefinition.java` — extended from `record(name, type)` to `record(name, type, encryptionSpec)`
- `JlsmSchema.java` — builder gains `.field(name, type, encryptionSpec)` overloads
- `DocumentSerializer.java` — dispatch table incorporates encrypt/decrypt per-field
- `FieldType.java` — unchanged (encryption is orthogonal to field type)

## Problem
How should field-level encryption be expressed in JlsmSchema, how should keys bind to fields, and how should DocumentSerializer integrate encryption/decryption into its serialize/deserialize path?

## Constraints That Drove This Decision
- **Fit with existing type system**: must compose with sealed FieldType hierarchy, FieldDefinition record, JlsmSchema builder, DocumentSerializer dispatch table, and secondary indices without breaking the unencrypted API
- **Type-aware encryption**: different FieldTypes need different encryption schemes — the API must express this per-field with compile-time type safety
- **Scale + latency**: encryption dispatch at millions of QPS must be resolvable at schema-construction time, not per-document

## Decision
**Chosen approach: Schema Annotation (FieldDefinition carries EncryptionSpec)**

Extend `FieldDefinition` to carry an optional `EncryptionSpec` — a sealed interface whose permits match the encryption families researched in the KB. Encryption configuration is expressed in the schema, keys are held separately in an Arena-backed `EncryptionKeyHolder`, and DocumentSerializer builds per-field encrypt/decrypt functions at construction time using the same dispatch table pattern already in use.

## Rationale

### Why Schema Annotation
- **Fit**: FieldDefinition is a record — adding a third component with a default (`EncryptionSpec.NONE`) is natural and backward-compatible. The sealed FieldType hierarchy is untouched. DocumentSerializer's existing dispatch table pattern (just optimized) accommodates encrypt/decrypt steps trivially.
- **Scale**: encryption dispatch is resolved at schema construction, not per-document. The dispatch table is built once and reused for every serialize/deserialize call — zero per-document overhead beyond the crypto operations.
- **Accuracy**: `EncryptionSpec` is a sealed interface — the compiler enforces exhaustive handling. Each variant maps to a specific encryption family, preventing misconfiguration (e.g., DCPE on a boolean field).

### Why not Encrypted FieldType Variants
- **Fit**: expanding the sealed FieldType permits list means every `switch(type)` across DocumentSerializer, QueryExecutor, FieldIndex, SqlTranslator, and all encode/decode methods needs new arms. Encryption is a cross-cutting storage concern, not a data type property.

### Why not Pluggable FieldEncryptor Interface
- **Fit**: encryption becomes invisible to the schema — secondary indices can't adapt behavior, schema serialization doesn't capture it. Map lookup per field per document adds overhead at scale. No compile-time safety against misconfiguration.

## Implementation Guidance

### EncryptionSpec sealed interface

```java
public sealed interface EncryptionSpec permits
        EncryptionSpec.None,
        EncryptionSpec.Deterministic,
        EncryptionSpec.OrderPreserving,
        EncryptionSpec.DistancePreserving,
        EncryptionSpec.Opaque {

    record None() implements EncryptionSpec {}
    record Deterministic() implements EncryptionSpec {}      // AES-SIV
    record OrderPreserving() implements EncryptionSpec {}     // Boldyreva OPE
    record DistancePreserving() implements EncryptionSpec {}  // DCPE/SAP
    record Opaque() implements EncryptionSpec {}              // AES-GCM

    EncryptionSpec NONE = new None();

    static EncryptionSpec none() { return NONE; }
    static EncryptionSpec deterministic() { return new Deterministic(); }
    static EncryptionSpec orderPreserving() { return new OrderPreserving(); }
    static EncryptionSpec distancePreserving() { return new DistancePreserving(); }
    static EncryptionSpec opaque() { return new Opaque(); }
}
```

### FieldDefinition extension

```java
public record FieldDefinition(String name, FieldType type, EncryptionSpec encryption) {
    public FieldDefinition(String name, FieldType type) {
        this(name, type, EncryptionSpec.NONE);
    }
}
```

### Schema builder usage

```java
JlsmSchema schema = JlsmSchema.builder("users", 1)
    .field("id", FieldType.Primitive.INT64)                                          // unencrypted
    .field("email", FieldType.Primitive.STRING, EncryptionSpec.deterministic())       // equality search
    .field("salary", FieldType.Primitive.INT64, EncryptionSpec.orderPreserving())     // range queries
    .field("embedding", FieldType.vector(FLOAT32, 128), EncryptionSpec.distancePreserving()) // ANN
    .field("ssn", FieldType.Primitive.STRING, EncryptionSpec.opaque())                // no search
    .build();
```

### Key handling (from [`jvm-key-handling-patterns.md#algorithm-steps`](../../.kb/systems/security/jvm-key-handling-patterns.md#algorithm-steps))
- Keys held in `EncryptionKeyHolder` using confined/shared `Arena` (off-heap)
- Caller passes `byte[]` key material to table builder — library zeros the input after import
- One key per table initially; per-field keys can be added as `EncryptionSpec` parameters later

### DocumentSerializer integration
- At `SchemaSerializer` construction, for each field with `encryption != NONE`, build a `FieldEncryptor` lambda in the dispatch table (same pattern as `FieldDecoder`)
- `serialize()`: encode field value, then encrypt the encoded bytes
- `deserialize()`: decrypt the encrypted bytes, then decode the field value
- `EncryptionSpec.NONE` fields skip encrypt/decrypt entirely — zero overhead

Known edge cases from [`searchable-encryption-schemes.md#edge-cases-and-gotchas`](../../.kb/algorithms/encryption/searchable-encryption-schemes.md#edge-cases-and-gotchas):
- DET leaks frequency — document as a security tradeoff
- OPE leaks approximate value — not suitable for high-sensitivity numeric fields
- DCPE recall degrades with stronger perturbation — tunable tradeoff

## What This Decision Does NOT Solve
- Key rotation — requires rebuilding the table with new keys (documented limitation)
- Encryption of WAL entries — separate concern, can layer on later
- Schema migration from unencrypted to encrypted — requires data rewrite
- Per-field key binding — initial implementation uses one key per table

## Conditions for Revision
This ADR should be re-evaluated if:
- Per-field keys become a requirement (adds key routing to EncryptionSpec)
- A new encryption family is needed that doesn't fit the sealed permits (e.g., FHE becomes practical)
- The FieldDefinition 3-component record causes friction in downstream API consumers
- Performance benchmarks show the dispatch table overhead is measurable at target QPS

---
*Confirmed by: user deliberation | Date: 2026-03-18*
*Full scoring: [evaluation.md](evaluation.md)*
