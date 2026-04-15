# Evaluation — Per-Field Key Binding

## Candidates

### A. HKDF Derivation from Master Key (Automatic Per-Field Keys)
Derive per-field keys automatically via HKDF(masterKey, fieldName) at schema construction time. Every encrypted field gets a unique key without caller configuration.

| Dimension | Score | Rationale | KB Source |
|-----------|-------|-----------|-----------|
| Scale | 9 | Key derivation at schema construction; dispatch table holds derived keys | [`.kb/systems/security/encryption-key-rotation-patterns.md#per-field-key-binding`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |
| Resources | 9 | HKDF is a single HMAC call per field at construction; off-heap Arena storage | [`.kb/systems/security/jvm-key-handling-patterns.md`](../../.kb/systems/security/jvm-key-handling-patterns.md) |
| Complexity | 9 | Zero caller configuration — automatic from master key + field name | — |
| Accuracy | 9 | HKDF produces cryptographically independent keys; cross-field correlation eliminated | [`.kb/algorithms/encryption/index-access-pattern-leakage.md#per-field-key-isolation`](../../.kb/algorithms/encryption/index-access-pattern-leakage.md) |
| Operational | 9 | Backward compatible — existing single-key callers get automatic derivation | — |
| Fit | 9 | Composes with EncryptionKeyHolder (add derivation method), dispatch table, key rotation | [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |
| **Total** | **54/60** | | |

### B. Explicit Per-Field Key IDs in EncryptionSpec
Add a `keyId` parameter to each EncryptionSpec variant. Callers configure key IDs per field; a KeyVault resolves IDs to key material.

| Dimension | Score | Rationale | KB Source |
|-----------|-------|-----------|-----------|
| Scale | 8 | KeyVault lookup per field at construction; cached | — |
| Resources | 7 | Requires KeyVault interface + implementation; additional API surface | [`.kb/systems/security/client-side-encryption-patterns.md#envelope-encryption`](../../.kb/systems/security/client-side-encryption-patterns.md) |
| Complexity | 5 | Significant API change — every EncryptionSpec gains keyId; callers must manage key IDs | — |
| Accuracy | 9 | Full control over key assignment | — |
| Operational | 6 | Breaking API — existing EncryptionSpec factories gain mandatory parameter | — |
| Fit | 6 | Changes sealed EncryptionSpec record definitions; KeyVault is a new SPI dependency | — |
| **Total** | **41/60** | | |

### C. Key Routing Table (External Map<FieldName, Key>)
Callers provide a `Map<String, byte[]>` mapping field names to keys. Library uses the map at construction time.

| Dimension | Score | Rationale | KB Source |
|-----------|-------|-----------|-----------|
| Scale | 8 | Map lookup at construction | — |
| Resources | 6 | Caller manages N keys instead of one; key material on heap until imported | — |
| Complexity | 6 | Manual key management per field; error-prone | — |
| Accuracy | 8 | Correct if caller provides consistent keys | — |
| Operational | 5 | Every schema change requires key map update | — |
| Fit | 7 | Does not change EncryptionSpec but adds a parallel configuration channel | — |
| **Total** | **40/60** | | |

## Recommendation
**Candidate A — HKDF Derivation from Master Key**. Automatic per-field key isolation with zero caller configuration. Callers provide a single master key; the library derives per-field keys deterministically. This matches the KB recommendation from `index-access-pattern-leakage.md` (recommendation #1: "per-field key derivation (HKDF) — implement as default for all encrypted fields") and composes cleanly with key rotation (rotate master key, all derived keys rotate).

## Falsification Check
- **What if callers need different master keys per field?** This is the client-side encryption SDK use case — callers who pre-encrypt with their own keys bypass library derivation entirely. For library-managed encryption, a single master key + HKDF is sufficient.
- **What if HKDF derivation creates a performance bottleneck?** HKDF is a single HMAC-SHA256 call (~100ns). Derivation happens once at schema construction, not per document. No bottleneck.
- **What if field names change?** Field renames change the HKDF info parameter, producing a new key. This requires re-encryption — but field renames already require schema migration in jlsm-table, so this is expected behavior.
