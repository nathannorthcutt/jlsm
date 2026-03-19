---
problem: "pre-encrypted-document-signaling"
date: "2026-03-19"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-table/src/main/java/jlsm/table/JlsmDocument.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/DocumentSerializer.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/FieldEncryptionDispatch.java"
---

# ADR — Pre-Encrypted Document Signaling

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Field Encryption API Design (ADR) | Established EncryptionSpec, FieldEncryptionDispatch, and serializer integration pattern | [`.decisions/field-encryption-api-design/adr.md`](../field-encryption-api-design/adr.md) |

---

## Files Constrained by This Decision

- `JlsmDocument.java` — gains a `boolean preEncrypted` field, `preEncrypted()` package-private getter, and `JlsmDocument.preEncrypted(schema, nameValuePairs...)` static factory method
- `DocumentSerializer.java` — `SchemaSerializer.serialize()` checks the pre-encrypted flag; if set, skips encryption and validates ciphertext structure instead
- `FieldEncryptionDispatch.java` — gains a `validateCiphertextLength(int fieldIndex, int actualLength)` method for structural validation of pre-encrypted fields

## Problem
How should JlsmDocument signal that its fields are already encrypted so the serializer can skip encryption on write while still validating ciphertext structural integrity?

## Constraints That Drove This Decision
- **Zero overhead on unencrypted path**: the signaling mechanism must not add per-document cost when pre-encryption is not used (the overwhelmingly common case)
- **All-or-nothing semantics**: if the flag is set, every field with `EncryptionSpec != NONE` must already be encrypted; no partial pre-encryption
- **Backward compatibility**: existing `JlsmDocument.of(schema, ...)` API must remain unchanged
- **Fit with existing design**: JlsmDocument is a final class with package-private value access; the mechanism must compose with DocumentAccess, the serializer dispatch table, and all downstream consumers (JSON, YAML, IndexRegistry, QueryExecutor)

## Decision
**Chosen approach: Factory method with boolean field** — `JlsmDocument.preEncrypted(schema, nameValuePairs...)`

Add a `private final boolean preEncrypted` field to JlsmDocument, defaulting to `false`. Provide a new static factory method `JlsmDocument.preEncrypted(schema, nameValuePairs...)` that constructs a document with the flag set to `true`. The serializer reads this flag via the existing `DocumentAccess` accessor pattern and dispatches accordingly.

## Rationale

### Why a boolean field + factory method
- **Zero overhead**: a boolean field occupies zero additional bytes on 64-bit JVMs (fits in existing object alignment padding). The branch `if (doc.isPreEncrypted())` is trivially predicted on the fast path (false).
- **Minimal API surface**: one new static factory method. No new types, no new interfaces, no new packages.
- **Self-documenting call site**: `JlsmDocument.preEncrypted(schema, "email", ciphertext, ...)` clearly communicates intent without needing to consult documentation.
- **Fit**: JlsmDocument is already a final class with constructor-time immutability. Adding a boolean field and a factory method is the smallest possible change. The `DocumentAccess` accessor can expose the flag to the serializer without adding a public method.

### Why not a wrapper type
A `PreEncryptedDocument` wrapper provides stronger compile-time safety (the type system distinguishes encrypted from unencrypted documents). However, JlsmDocument is `final` and deeply embedded throughout the codebase — every consumer (DocumentSerializer, QueryExecutor, IndexRegistry, JsonWriter, YamlWriter) operates on `JlsmDocument` directly. Introducing a wrapper or shared interface would require pervasive API changes for a binary signal. The cost-benefit ratio is unfavorable.

### Why not a metadata map
A generic `Map<String, Object>` metadata field is architecturally inconsistent with JlsmDocument's design as a tight, schema-validated value holder. It introduces an untyped escape hatch, invites scope creep, and adds allocation overhead to every document (even those that never use metadata).

## Implementation Guidance

### JlsmDocument changes

```java
public final class JlsmDocument {
    private final JlsmSchema schema;
    private final Object[] values;
    private final boolean preEncrypted;  // NEW

    // Existing package-private constructor gains a boolean parameter
    JlsmDocument(JlsmSchema schema, Object[] values, boolean preEncrypted) { ... }

    // Backward-compatible constructor delegates
    JlsmDocument(JlsmSchema schema, Object[] values) {
        this(schema, values, false);
    }

    // Existing factory — unchanged behavior
    public static JlsmDocument of(JlsmSchema schema, Object... nameValuePairs) { ... }

    // NEW factory — constructs a pre-encrypted document
    public static JlsmDocument preEncrypted(JlsmSchema schema, Object... nameValuePairs) {
        // Same validation as of(), except type validation is SKIPPED for encrypted fields
        // (ciphertext is byte[] regardless of the declared FieldType)
        ...
    }

    // Package-private getter for serializer access
    boolean isPreEncrypted() { return preEncrypted; }
}
```

### Type validation for pre-encrypted documents
The `preEncrypted()` factory method must relax type validation for fields that have `EncryptionSpec != NONE`. When the pre-encrypted flag is set, encrypted field values are expected to be `byte[]` (raw ciphertext), not the declared field type. Unencrypted fields in the same document (those with `EncryptionSpec.NONE`) are still type-validated normally.

### DocumentAccess extension

```java
// Add to the Accessor interface:
boolean isPreEncrypted(JlsmDocument doc);

// Implementation in JlsmDocument static initializer:
@Override
public boolean isPreEncrypted(JlsmDocument doc) {
    return doc.isPreEncrypted();
}
```

### Serializer dispatch

In `SchemaSerializer.serialize()`:
```java
final boolean preEnc = doc.isPreEncrypted(); // via DocumentAccess
if (preEnc) {
    // Skip encryption loop; write ciphertext bytes directly
    // Validate ciphertext length per field via FieldEncryptionDispatch
    encodeFieldsPreEncrypted(fields, values, cursor);
} else {
    // Existing encryption path — unchanged
    if (!hasEncryptedFields) {
        encodeFields(fields, values, cursor);
    } else {
        encodeFieldsWithEncryption(fields, values, encryptedPayloads, cursor);
    }
}
```

### Ciphertext structural validation
`FieldEncryptionDispatch` gains a validation method that checks the expected ciphertext size for each encryption scheme, given the serialized plaintext size for that field type:

- **AES-GCM (Opaque)**: ciphertext = 12 (IV) + plaintext_len + 16 (tag)
- **AES-SIV (Deterministic)**: ciphertext = plaintext_len + 16 (synthetic IV)
- **Boldyreva OPE (OrderPreserving)**: ciphertext = 8 bytes (always, regardless of plaintext)
- **DCPE/SAP (DistancePreserving)**: ciphertext = dimensions * 4 bytes (same as plaintext float array)

Validation failure throws `IllegalArgumentException` with the field name, scheme, expected length, and actual length.

### Deserialization
No changes. The reader always decrypts. The pre-encrypted flag is not persisted in the binary format — it is purely a write-side signal.

## What This Decision Does NOT Solve
- Per-field pre-encryption (partial) — explicitly out of scope; all-or-nothing only
- Persistence of the pre-encrypted flag in the serialized format — it is a write-side signal only
- Client-side encryption SDK — clients use the encryption primitives directly

## Conditions for Revision
This ADR should be re-evaluated if:
- Partial pre-encryption (per-field) becomes a requirement — the boolean flag would need to become a bitset or per-field marker
- The pre-encrypted signal needs to survive serialization (persisted in the binary format) for audit or provenance purposes
- A shared document interface is introduced that makes the wrapper type approach viable

---
*Confirmed by: user instruction | Date: 2026-03-19*
*Full scoring: [evaluation.md](evaluation.md)*
