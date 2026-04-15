# Evaluation — Encryption Key Rotation

## Candidates

### A. Envelope Encryption with Compaction-Driven Re-Encryption
KEK wraps DEKs; version tag in ciphertext header. Compaction reads with old DEK, writes with current DEK. Key registry file alongside manifest.

| Dimension | Score | Rationale | KB Source |
|-----------|-------|-----------|-----------|
| Scale | 9 | No bulk migration; piggybacks on existing compaction I/O | [`.kb/systems/security/encryption-key-rotation-patterns.md#compaction-driven-re-encryption`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |
| Resources | 9 | Zero additional I/O beyond compaction; registry is a few hundred bytes | [`.kb/systems/security/encryption-key-rotation-patterns.md#key-metadata-storage`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |
| Complexity | 8 | Standard envelope pattern; version dispatch per record; registry atomicity | [CockroachDB RFC](https://github.com/cockroachdb/cockroach/blob/master/docs/RFCS/20171220_encryption_at_rest.md) |
| Accuracy | 9 | Version tags enable correct decryption during mixed-version window | — |
| Operational | 8 | Convergence takes a full compaction cycle; cold data retains old keys longer | [`.kb/systems/security/encryption-key-rotation-patterns.md#tradeoffs`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |
| Fit | 9 | Composes with per-field HKDF (derive from new master), compaction pipeline, WAL SEK rotation | — |
| **Total** | **52/60** | | |

### B. Full Table Rebuild on Rotation
Stop-the-world: read all data with old key, write new SSTable files with new key, swap atomically.

| Dimension | Score | Rationale | KB Source |
|-----------|-------|-----------|-----------|
| Scale | 3 | O(data size) write amplification; blocks queries during rebuild | — |
| Resources | 3 | Requires 2x storage during rebuild (old + new) | — |
| Complexity | 6 | Simple: read old, write new. No version tracking | — |
| Accuracy | 9 | Clean cut — no mixed versions | — |
| Operational | 3 | Downtime proportional to data size; unacceptable at scale | — |
| Fit | 5 | Bypasses compaction pipeline entirely | — |
| **Total** | **29/60** | | |

### C. Lazy Re-Encryption on Read
Decrypt on read, re-encrypt with new key, write back in place.

| Dimension | Score | Rationale | KB Source |
|-----------|-------|-----------|-----------|
| Scale | 5 | Concentrates on hot data; cold data never rotated | — |
| Resources | 4 | Write amplification on reads; violates SSTable immutability | — |
| Complexity | 4 | Read-modify-write on immutable SSTables contradicts append-only model | [`.kb/systems/security/encryption-key-rotation-patterns.md#lazy-re-encryption-on-read`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |
| Accuracy | 6 | Cold data retains old keys indefinitely | — |
| Operational | 4 | Unpredictable write load during reads | — |
| Fit | 3 | Violates SSTable immutability; not recommended for jlsm | [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |
| **Total** | **26/60** | | |

## Recommendation
**Candidate A — Envelope Encryption with Compaction-Driven Re-Encryption**. This is the pattern used by CockroachDB, and the KB confirms it is the architecturally correct approach for LSM-tree engines. Zero additional I/O, mixed-version safety, and clean composition with per-field keys and compaction.

## Falsification Check
- **What about convergence time?** Cold data may retain old keys for days. If a compliance requirement mandates immediate rotation, a priority compaction trigger can be added (mark old-key SSTables for urgent compaction). This is an operational extension, not a design flaw.
- **What about DET/OPE index invalidation?** DET rotation changes ciphertexts, invalidating index entries. The ADR must document that DET/OPE field rotation requires index rebuild. Non-indexed and Opaque fields rotate with zero secondary effects.
- **What about key registry corruption?** Atomic writes (temp + fsync + rename) provide crash safety. The registry should be backed up alongside the SSTable manifest.
