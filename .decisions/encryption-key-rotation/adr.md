---
problem: "encryption-key-rotation"
date: "2026-04-14"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-core/src/main/java/jlsm/core/io/EncryptionKeyHolder.java"
  - "modules/jlsm-core/src/main/java/jlsm/compaction/internal/CompactionTask.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/FieldEncryptionDispatch.java"
---

# ADR — Encryption Key Rotation

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Encryption Key Rotation Patterns | Envelope encryption, compaction-driven re-encryption, key registry design | [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |
| JVM Key Handling Patterns | Off-heap key storage, zeroing discipline | [`.kb/systems/security/jvm-key-handling-patterns.md`](../../.kb/systems/security/jvm-key-handling-patterns.md) |

---

## Files Constrained by This Decision

- `EncryptionKeyHolder.java` — gains KeyRegistry with versioned DEK entries, envelope encryption support
- `CompactionTask.java` — re-encrypts records with current DEK during compaction merge
- `FieldEncryptionDispatch.java` — resolves DEK version from ciphertext header for decryption

## Problem
The parent ADR (`field-encryption-api-design`) documented key rotation as a known limitation: "requires rebuilding the table with new keys." For an LSM-tree engine, this is unnecessarily expensive. How should key rotation work without downtime or bulk rewrite?

## Constraints That Drove This Decision
- **No stop-the-world**: rotation must not block reads or writes
- **No bulk rewrite**: leverage existing compaction I/O for re-encryption
- **Mixed versions safe**: old and new key versions must coexist during rotation window

## Decision
**Chosen approach: Envelope Encryption with Compaction-Driven Re-Encryption**

Two-tier key hierarchy: KEK (Key Encryption Key, caller-provided) wraps DEKs (Data Encryption Keys, library-managed). Each ciphertext carries a 4-byte version tag identifying its DEK. Compaction reads with the tagged DEK and writes with the current DEK. A key registry file alongside the SSTable manifest tracks DEK versions and their wrapped key material.

## Rationale

### Why Envelope + Compaction-Driven
- **Zero additional I/O**: compaction already reads and rewrites SSTables. Re-encrypting during merge adds only the crypto cost (< 5% at AES-NI speeds).
- **KEK rotation is O(DEK count)**: re-wrapping a few hundred bytes of DEK registry, not the entire dataset.
- **Mixed versions are normal state**: version tags enable correct decryption at any point during rotation. No big-bang cutover.
- **Production-proven**: CockroachDB uses this exact model — weekly DEK rotation via compaction, store key (KEK) rotation on node restart.

### Why not Full Table Rebuild
O(data size) write amplification and 2x storage during rebuild. Blocks queries. Unacceptable at scale.

### Why not Lazy Re-Encryption on Read
Violates SSTable immutability (the core architectural invariant). Read-modify-write adds unpredictable write load during reads.

## Implementation Guidance

### Ciphertext format extension

```
[4B DEK version | 12B IV/nonce | encrypted payload | 16B GCM auth tag]
```

The 4-byte version prefix is plaintext — the reader uses it to look up the correct DEK before decryption. Total overhead: 4 bytes per encrypted field beyond existing IV + tag.

### Key Registry

```java
record KeyRegistry(
    int activeKekVersion,
    Map<Integer, WrappedDek> dekEntries,
    Set<Integer> retiredKekVersions
) {
    record WrappedDek(byte[] wrappedKeyMaterial, int kekVersion, Instant createdAt) {}
}
```

Storage: registry file alongside SSTable manifest. Atomic update via temp + fsync + rename. Format: JSON or binary — small enough that format choice is irrelevant.

### Compaction re-encryption

```java
// In CompactionTask.merge():
EncryptionKeyHolder.DekVersion currentDek = keyHolder.currentDek();
for (var record : mergedInput) {
    byte[] plaintext = decrypt(record, keyHolder.dekFor(record.dekVersion()));
    byte[] encrypted = encrypt(plaintext, currentDek);
    output.write(encrypted);
}
```

### DET/OPE index implications
Rotating keys for DET-encrypted indexed fields changes ciphertexts, invalidating existing index entries. After rotation completes (all SSTables compacted with new key), affected indices must be rebuilt. The library should:
1. Mark DET/OPE fields as "rotation-pending" during the rotation window
2. After convergence (no SSTables reference old DEK), trigger index rebuild
3. During the rotation window, queries on DET/OPE fields may return incomplete results — document this tradeoff

Non-indexed and Opaque fields rotate transparently with zero secondary effects.

### Rollback safety
1. Never delete old KEK until all DEK entries re-wrapped under new KEK
2. Never delete old DEK until zero live SSTables reference that version
3. Crash during registry write leaves old registry intact (atomic rename)
4. GC policy: prune DEK entries when SSTable manifest confirms no references

## What This Decision Does NOT Solve
- Forced immediate rotation (priority compaction scheduling) — operational concern
- External KMS integration — callers provide KEK; KMS wrapping is their responsibility
- Updatable encryption (re-encrypt without decrypting via update tokens) — theoretical optimization, not needed for v1

## Conditions for Revision
This ADR should be re-evaluated if:
- Compliance requires immediate rotation (not eventual via compaction) — would need priority compaction trigger
- Updatable encryption schemes become practical in JCA providers — would eliminate decrypt/re-encrypt overhead
- Forward-secure encryption is required (epoch-based key erasure after SSTable deletion)

---
*Confirmed by: architect agent (WD-09 batch, pre-accepted) | Date: 2026-04-14*
*Full scoring: [evaluation.md](evaluation.md)*
