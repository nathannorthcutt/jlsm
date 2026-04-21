---
{
  "id": "encryption.primitives-configuration",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "encryption"
  ],
  "requires": [],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "field-encryption-api-design",
    "encrypted-index-strategy",
    "pre-encrypted-document-signaling"
  ],
  "kb_refs": [
    "algorithms/encryption/searchable-encryption-schemes",
    "algorithms/encryption/vector-encryption-approaches",
    "systems/security/jvm-key-handling-patterns"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F03"
  ]
}
---

# encryption.primitives-configuration — Primitives Configuration

## Requirements

### Schema-level field encryption configuration

R1. Every field definition must carry an encryption specification. The specification must never be null; a compact constructor must reject null with a NullPointerException.

R2. Field definitions constructed without an explicit encryption specification must default to the none variant. This default must not require callers to import or reference the encryption specification type.

R3. The schema builder must accept a three-argument field method that takes a field name, field type, and encryption specification. All three arguments must be validated as non-null at the call site before the field definition is created.

R4. The schema builder must continue to accept the two-argument field method (name and type) for backward compatibility. The two-argument overload must produce a field definition with the none encryption specification.
