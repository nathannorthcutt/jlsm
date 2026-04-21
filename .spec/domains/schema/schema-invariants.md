---
{
  "id": "schema.schema-invariants",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "schema"
  ],
  "requires": [],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [],
  "kb_refs": [],
  "open_obligations": [],
  "_migrated_from": [
    "F13"
  ]
}
---

# schema.schema-invariants — Schema Invariants

## Requirements

### Immutability and thread safety

R1. All fields of `JlsmSchema` are `private final`. The class is effectively immutable after construction. `[EXPLICIT]`

R2. The `fields` list is stored as an unmodifiable copy (`List.copyOf`). `[EXPLICIT]`

R3. The `fieldIndexMap` is stored as an unmodifiable copy (`Map.copyOf`). `[EXPLICIT]`

R4. `JlsmSchema` instances are safe for concurrent read access from multiple threads without synchronization. `[IMPLICIT]`

### Structural equality

R5. `JlsmSchema` must implement `equals()` based on `name`, `version`, `fields`, and `maxDepth`. Two schemas with identical structure must be equal. `[EXPLICIT]`

R6. `JlsmSchema` must implement `hashCode()` consistent with `equals()`, computed from `name`, `version`, `fields`, and `maxDepth`. `[EXPLICIT]`
