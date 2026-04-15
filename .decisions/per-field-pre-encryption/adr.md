---
problem: "per-field-pre-encryption"
date: "2026-04-14"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-table/src/main/java/jlsm/table/JlsmDocument.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/DocumentSerializer.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/FieldEncryptionDispatch.java"
---

# ADR — Per-Field Pre-Encryption

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Client-Side Encryption Patterns | Per-field encryption in MongoDB CSFLE/AWS DB SDK | [`.kb/systems/security/client-side-encryption-patterns.md`](../../.kb/systems/security/client-side-encryption-patterns.md) |
| Pre-Encrypted Document Signaling (ADR) | Parent all-or-nothing design being extended | [`.decisions/pre-encrypted-document-signaling/adr.md`](../pre-encrypted-document-signaling/adr.md) |

---

## Files Constrained by This Decision

- `JlsmDocument.java` — `boolean preEncrypted` becomes `long preEncryptedBitset`; new factory method `preEncrypted(schema, preEncryptedFieldNames, nameValuePairs...)`
- `DocumentSerializer.java` — `SchemaSerializer.serialize()` checks bitset per field instead of single boolean
- `FieldEncryptionDispatch.java` — ciphertext validation called only for fields with their bit set

## Problem
The parent ADR (`pre-encrypted-document-signaling`) established all-or-nothing pre-encryption: if the flag is set, every encrypted field is pre-encrypted. This prevents mixed scenarios where some fields are encrypted client-side and others by the library. How should JlsmDocument support partial (per-field) pre-encryption?

## Constraints That Drove This Decision
- **Zero overhead on unaffected path**: must not add cost when no fields are pre-encrypted (the common case)
- **Backward compatibility**: existing `JlsmDocument.preEncrypted(schema, ...)` must continue to work unchanged
- **Fit**: must compose with FieldDefinition, EncryptionSpec dispatch table, and DocumentAccess accessor pattern

## Decision
**Chosen approach: Bitset Flag** — replace `boolean preEncrypted` with `long preEncryptedBitset`

The `boolean preEncrypted` field in JlsmDocument becomes a `long preEncryptedBitset` where each bit corresponds to a field index. Bit N set means field N is pre-encrypted by the caller. A new factory method accepts a `Set<String>` of pre-encrypted field names. The existing `preEncrypted(schema, ...)` factory sets all bits for encrypted fields (backward compatible).

## Rationale

### Why Bitset
- **Zero overhead**: `preEncryptedBitset == 0L` is the fast path — a single comparison, trivially predicted. Per-field check is `(bitset & (1L << fieldIndex)) != 0` — sub-nanosecond.
- **Minimal API surface**: one new factory method. No new types, no wrappers.
- **Backward compatible**: `preEncrypted(schema, ...)` sets all encrypted field bits. `of(schema, ...)` leaves bitset at 0.

### Why not Per-Field Wrapper Type
A `PreEncryptedValue` wrapper record provides type safety but requires callers to wrap values, changes the `Object[]` contract, and forces instanceof checks in the serializer. Every downstream consumer (IndexRegistry, QueryExecutor, JsonWriter) would need to handle the wrapper.

### Why not Metadata Map
The parent ADR explicitly rejected generic metadata maps as "architecturally inconsistent with JlsmDocument's design as a tight, schema-validated value holder."

## Implementation Guidance

### JlsmDocument changes

```java
public final class JlsmDocument {
    private final JlsmSchema schema;
    private final Object[] values;
    private final long preEncryptedBitset;  // CHANGED from boolean

    // Backward-compatible: all encrypted fields pre-encrypted
    public static JlsmDocument preEncrypted(JlsmSchema schema, Object... nameValuePairs) {
        long bitset = computeAllEncryptedBitset(schema);
        return new JlsmDocument(schema, resolveValues(schema, nameValuePairs), bitset);
    }

    // NEW: specific fields pre-encrypted
    public static JlsmDocument preEncrypted(JlsmSchema schema,
                                            Set<String> preEncryptedFieldNames,
                                            Object... nameValuePairs) {
        long bitset = computeBitset(schema, preEncryptedFieldNames);
        return new JlsmDocument(schema, resolveValues(schema, nameValuePairs), bitset);
    }

    // Package-private for DocumentAccess
    boolean isFieldPreEncrypted(int fieldIndex) {
        return (preEncryptedBitset & (1L << fieldIndex)) != 0;
    }
    boolean isPreEncrypted() {
        return preEncryptedBitset != 0L;
    }
}
```

### Validation rules
- Fields named in `preEncryptedFieldNames` must have `EncryptionSpec != NONE` — throw `IllegalArgumentException` if a plaintext field is marked pre-encrypted
- Pre-encrypted field values must be `byte[]` (raw ciphertext); unencrypted fields in the same document are type-validated normally
- Field index must be < 64 — if a schema exceeds 64 fields, extend to `long[]` or `BitSet`

### Serializer dispatch

```java
// In SchemaSerializer.serialize(), per field:
if (doc.isFieldPreEncrypted(fieldIndex)) {
    // Skip encryption, validate ciphertext structure
    encryptionDispatch.validateCiphertextLength(fieldIndex, ciphertextBytes.length);
    writeCiphertextDirect(cursor, ciphertextBytes);
} else if (fieldDef.encryption() != EncryptionSpec.NONE) {
    // Library encrypts
    encryptAndWrite(cursor, plainValue, fieldDef.encryption());
} else {
    // No encryption
    encodePlainField(cursor, plainValue, fieldDef.type());
}
```

## What This Decision Does NOT Solve
- Client-side encryption SDK — how clients discover field encryption requirements (see `client-side-encryption-sdk`)
- Per-field key binding — which key encrypts which field (see `per-field-key-binding`)

## Conditions for Revision
This ADR should be re-evaluated if:
- Schemas exceed 64 fields and the bitset needs extension
- A richer per-field metadata mechanism is introduced that subsumes the bitset

---
*Confirmed by: architect agent (WD-09 batch, pre-accepted) | Date: 2026-04-14*
*Full scoring: [evaluation.md](evaluation.md)*
