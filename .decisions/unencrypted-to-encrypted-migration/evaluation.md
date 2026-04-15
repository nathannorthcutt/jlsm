# Evaluation — Unencrypted-to-Encrypted Migration

## Candidates

### A. Compaction-Driven Migration (Same as Key Rotation)
On schema update, new writes are encrypted. Compaction reads unencrypted SSTables and writes encrypted output with current DEK. SSTables carry a schema version tag.

| Dimension | Score | Rationale | KB Source |
|-----------|-------|-----------|-----------|
| Scale | 9 | Piggybacks on existing compaction I/O; no additional passes | [`.kb/systems/security/encryption-key-rotation-patterns.md#compaction-driven-re-encryption`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |
| Resources | 9 | No 2x storage; in-place replacement during compaction | — |
| Complexity | 8 | Compaction must detect unencrypted records and encrypt them; schema version tag on SSTable | — |
| Accuracy | 9 | Mixed reads work: reader checks schema version, decrypts if encrypted, passes through if not | — |
| Operational | 8 | Convergence depends on compaction schedule; cold data migrates slowly | — |
| Fit | 9 | Same mechanism as key rotation; composes with per-field keys, index rebuild | [`.decisions/encryption-key-rotation/adr.md`](../encryption-key-rotation/adr.md) |
| **Total** | **52/60** | | |

### B. Background Migration Task
Dedicated background thread reads all SSTables, encrypts, writes new files, swaps atomically.

| Dimension | Score | Rationale | KB Source |
|-----------|-------|-----------|-----------|
| Scale | 6 | Dedicated I/O pass; doubles write amplification during migration | — |
| Resources | 5 | 2x storage during migration window (old + new files) | — |
| Complexity | 6 | Separate migration subsystem; must handle concurrent writes during migration | — |
| Accuracy | 8 | Complete migration — no mixed state after task completes | — |
| Operational | 7 | Deterministic completion time (unlike compaction-driven) | — |
| Fit | 5 | Parallel system alongside compaction; operational overhead | — |
| **Total** | **37/60** | | |

### C. Schema-Version Gate (Reject Unencrypted Reads)
On schema update, mark all existing SSTables as "pending migration." Reject reads from pending SSTables until they are re-encrypted.

| Dimension | Score | Rationale | KB Source |
|-----------|-------|-----------|-----------|
| Scale | 4 | Data unavailable during migration — effectively a stop-the-world | — |
| Resources | 5 | Same as B during migration | — |
| Complexity | 5 | Simple but violates availability | — |
| Accuracy | 9 | No mixed state — data is either migrated or inaccessible | — |
| Operational | 2 | Data unavailable until migration completes — unacceptable | — |
| Fit | 3 | Contradicts LSM-tree design principle of always-readable data | — |
| **Total** | **28/60** | | |

## Recommendation
**Candidate A — Compaction-Driven Migration**. Reuses the same mechanism designed for key rotation. Zero additional I/O, online migration, mixed unencrypted/encrypted reads during the transition window.

## Falsification Check
- **What if migration must complete by a deadline?** Priority compaction can be triggered for SSTables that are still unencrypted. The same operational lever exists for key rotation convergence.
- **What about index consistency during migration?** DET/OPE indexes must be rebuilt after migration. During migration, queries on newly-encrypted fields may return incomplete results. This is the same tradeoff as key rotation — document it.
- **What about the reverse (encrypted-to-unencrypted)?** Removing encryption from a field follows the same compaction-driven path — compaction reads encrypted, writes unencrypted. The mechanism is bidirectional.
