---
{
  "id": "encryption.primitives-key-holder",
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

# encryption.primitives-key-holder — Primitives Key Holder

## Requirements

### Key holder lifecycle

R1. The key holder must store key material in off-heap memory allocated from a shared arena. The caller's byte array must be zeroed immediately after the key is copied to the off-heap segment.

R2. The key holder must accept only 32-byte or 64-byte key material. Any other length must be rejected at construction time with an IllegalArgumentException that states the required lengths without revealing the actual key bytes.

R3. The key holder must provide a method to obtain a temporary on-heap byte array copy of the key. Callers must zero this copy after use; the key holder's documentation must state this obligation.

R4. The key holder's close method must zero the off-heap key segment before releasing the arena. Close must be idempotent: a second close call must have no effect and must not throw.

R5. Any method call on a closed key holder must throw IllegalStateException. The closed check must use an atomic boolean, not a plain boolean, to be safe under concurrent close and read.

R6. The key holder must not implement toString, and must not include key material in any exception message. If a custom toString is present, it must return a fixed string that does not vary with key content.

### Key material hygiene

R7. No encryption component may include raw key bytes in exception messages, log output, or toString representations. Exception messages must describe the error condition (e.g., "key length mismatch") without revealing key content.

R8. Intermediate key arrays created during key derivation (e.g., splitting a 512-bit key into two 256-bit halves) must be zeroed in a finally block immediately after use. The zeroing must not be deferred to garbage collection.

R9. The key holder must not implement Serializable. Serialization of key material to disk or network would defeat the purpose of off-heap storage with explicit zeroing.

R10. The key holder's getKeyBytes method must return a fresh copy on each call. Two sequential calls must return distinct array objects (not the same reference) so that zeroing one copy does not affect the other.

---

## Design Narrative

### Intent

Generated during the 2026-04-20 spec migration. See `.spec/_archive/migration-2026-04-20/MIGRATION.md` for
the migration plan and `.spec/_archive/migration-2026-04-20/` for the
pre-migration source spec(s) this spec was derived from.
