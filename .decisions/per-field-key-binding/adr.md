---
problem: "per-field-key-binding"
date: "2026-04-14"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/FieldEncryptionDispatch.java"
  - "modules/jlsm-core/src/main/java/jlsm/core/io/EncryptionKeyHolder.java"
---

# ADR — Per-Field Key Binding

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Encryption Key Rotation Patterns | Per-field key derivation via HKDF | [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |
| Index Access Pattern Leakage | Per-field key isolation as primary mitigation | [`.kb/algorithms/encryption/index-access-pattern-leakage.md`](../../.kb/algorithms/encryption/index-access-pattern-leakage.md) |
| JVM Key Handling Patterns | Off-heap Arena key storage | [`.kb/systems/security/jvm-key-handling-patterns.md`](../../.kb/systems/security/jvm-key-handling-patterns.md) |

---

## Files Constrained by This Decision

- `FieldEncryptionDispatch.java` — holds per-field derived keys instead of a single table key
- `EncryptionKeyHolder.java` — gains `deriveFieldKey(masterKey, fieldName)` method using HKDF

## Problem
The parent ADR (`field-encryption-api-design`) uses one key per table. Different fields encrypted with the same key enables cross-field frequency correlation attacks. How should per-field keys be bound without requiring callers to manage N keys?

## Constraints That Drove This Decision
- **Zero caller configuration**: callers provide a single master key; per-field isolation should be automatic
- **Cryptographic independence**: derived keys must be provably independent (HKDF with distinct info)
- **Fit with key rotation**: per-field keys must rotate when the master key rotates

## Decision
**Chosen approach: HKDF Derivation from Master Key**

Automatically derive per-field encryption keys via `HKDF-SHA256(masterKey, info=fieldName)` at schema construction time. Every encrypted field gets a unique, cryptographically independent key without any caller configuration beyond providing the master key.

## Rationale

### Why HKDF Derivation
- **Zero configuration**: callers provide one master key. The library derives per-field keys deterministically. No key maps, no key IDs, no KeyVault.
- **Cryptographic independence**: HKDF with distinct `info` parameters produces provably independent keys under the HMAC-based extraction model. Cross-field frequency correlation is eliminated.
- **Composability**: rotating the master key automatically rotates all derived keys. Per-field key rotation is handled by compaction-driven re-encryption (see `encryption-key-rotation`).
- **KB alignment**: `index-access-pattern-leakage.md` recommendation #1 rates per-field HKDF as "low cost, high value — implement as default for all encrypted fields."

### Why not Explicit Key IDs
Adding `keyId` to EncryptionSpec records changes the sealed interface, breaks all existing switch expressions, and requires callers to manage key identity. This complexity is appropriate for the client-side encryption SDK (where callers DO manage keys) but not for library-managed encryption.

### Why not External Key Map
A `Map<String, byte[]>` routing table works but is error-prone — callers must keep the map in sync with schema changes. HKDF derivation achieves the same isolation automatically.

## Implementation Guidance

### EncryptionKeyHolder extension

```java
public final class EncryptionKeyHolder implements AutoCloseable {
    // Existing: holds master key in off-heap Arena
    
    // NEW: derive per-field key
    public MemorySegment deriveFieldKey(String fieldName) {
        // HKDF-Expand(PRK=masterKey, info=fieldName.getBytes(UTF_8), L=32)
        // Result stored in same Arena; zeroed on close()
    }
}
```

### FieldEncryptionDispatch changes

```java
// At schema construction time:
for (int i = 0; i < fields.size(); i++) {
    FieldDefinition fd = fields.get(i);
    if (fd.encryption() != EncryptionSpec.NONE) {
        MemorySegment fieldKey = keyHolder.deriveFieldKey(fd.name());
        fieldEncryptors[i] = buildEncryptor(fd.encryption(), fieldKey);
        fieldDecryptors[i] = buildDecryptor(fd.encryption(), fieldKey);
    }
}
```

### HKDF specification
- **Extract**: `PRK = HMAC-SHA256(salt=0x00...00, IKM=masterKeyBytes)` — or skip extract if master key is already high-entropy
- **Expand**: `OKM = HMAC-SHA256(PRK, info || 0x01)` truncated to 32 bytes (256-bit key)
- **Info parameter**: `"jlsm-field-key:" + tableName + ":" + fieldName` — table name prevents cross-table key collision

### Backward compatibility
Existing single-key callers who provide a master key now get automatic per-field derivation. The behavior changes (each field uses a different derived key instead of the master key directly), so this is a **wire-format-breaking change** — data encrypted with the old single-key model cannot be decrypted with per-field derived keys. This must be coordinated with `unencrypted-to-encrypted-migration`.

## What This Decision Does NOT Solve
- Client-side per-field key management (see `client-side-encryption-sdk`)
- Key rotation mechanics (see `encryption-key-rotation`)
- External KMS integration (out of scope for a library)

## Conditions for Revision
This ADR should be re-evaluated if:
- A use case requires non-deterministic key assignment (e.g., per-document keys)
- The HKDF info parameter scheme causes collisions (e.g., table name reuse)
- Client-side encryption SDK requires explicit key IDs, invalidating automatic derivation as the default

---
*Confirmed by: architect agent (WD-09 batch, pre-accepted) | Date: 2026-04-14*
*Full scoring: [evaluation.md](evaluation.md)*
