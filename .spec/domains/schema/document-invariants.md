---
{
  "id": "schema.document-invariants",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "schema"
  ],
  "requires": [
    "F13"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [],
  "kb_refs": [],
  "open_obligations": [],
  "_migrated_from": [
    "F14"
  ]
}
---

# schema.document-invariants — Document Invariants

## Requirements

### Pre-encrypted document support

R1. `values()` must be package-private. It must return a defensive clone of the internal `Object[]` array (via `values.clone()`), so that package-private callers cannot mutate document state through the returned reference. Vector and array contents inside the cloned top-level array are still shared by reference; callers that need mutable access must clone those element arrays themselves (e.g., `IndexRegistry.extractFieldValue` clones vector arrays before returning). `[EXPLICIT]`

### Internal access (DocumentAccess)

R2. `JlsmDocument` must register a `DocumentAccess.Accessor` in a static initializer block. The accessor must provide access to `values()`, `create(JlsmSchema, Object[])`, and `isPreEncrypted()` from the `jlsm.table.internal` package. `[EXPLICIT]`

R3. The `create` method on the accessor must invoke the two-argument package-private constructor, producing a non-pre-encrypted document. `[EXPLICIT]`

### Immutability and thread safety

R4. The `schema` and `preEncrypted` fields are `private final` and immutable after construction. `[EXPLICIT]`

R5. The `values` array reference is `private final`. Because `values()` returns a clone (R51), package-private callers cannot mutate the top-level array slot for a field through that accessor. Mutable element arrays (`float[]`/`short[]` vectors, nested `Object[]` arrays) remain reachable through the clone; callers that hold such references must not mutate them unless they own a copy. `[IMPLICIT]`

R6. `JlsmDocument` does not provide any synchronization. Concurrent read and write access to the same document instance (via package-private `values()` array mutation) is not thread-safe. `[IMPLICIT]`

R7. For documents constructed via `of()` and accessed only through public typed getters, the document is effectively immutable for primitive and String field values. Mutable field values (nested `JlsmDocument` from `getObject()`, the original `Object[]` for `ArrayType` fields held in `values`) can still be externally mutated. `[IMPLICIT]`

### Structural equality

R8. `JlsmDocument` must implement `equals()` based on `schema`, `preEncrypted`, and deep equality of the `values` array (via `Arrays.deepEquals`). `[EXPLICIT]`

R9. `JlsmDocument` must implement `hashCode()` consistent with `equals()`, incorporating `schema`, `preEncrypted`, and `Arrays.deepHashCode(values)`. `[EXPLICIT]`
