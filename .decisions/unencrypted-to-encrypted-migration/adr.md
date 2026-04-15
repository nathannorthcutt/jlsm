---
problem: "unencrypted-to-encrypted-migration"
date: "2026-04-14"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-core/src/main/java/jlsm/compaction/internal/CompactionTask.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/TrieSSTableWriter.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/FieldEncryptionDispatch.java"
---

# ADR — Unencrypted-to-Encrypted Schema Migration

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Encryption Key Rotation Patterns | Compaction-driven re-encryption mechanism | [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |
| Encryption Key Rotation (ADR) | Same compaction-driven mechanism being reused | [`.decisions/encryption-key-rotation/adr.md`](../encryption-key-rotation/adr.md) |

---

## Files Constrained by This Decision

- `CompactionTask.java` — detects unencrypted records (no version tag) and encrypts them with current DEK
- `TrieSSTableWriter.java` — SSTable metadata carries schema version indicating encryption state
- `FieldEncryptionDispatch.java` — handles mixed encrypted/unencrypted reads during migration

## Problem
The parent ADR (`field-encryption-api-design`) identified migration from unencrypted to encrypted fields as an out-of-scope concern "requiring data rewrite." How can this migration happen online without downtime or bulk rewrite?

## Constraints That Drove This Decision
- **Online migration**: no stop-the-world; reads and writes continue during migration
- **No bulk rewrite**: leverage existing compaction I/O
- **Bidirectional**: must also support removing encryption from a field

## Decision
**Chosen approach: Compaction-Driven Migration** — same mechanism as key rotation

On schema update (a field gains `EncryptionSpec`), new writes are immediately encrypted. Compaction reads unencrypted SSTables and writes encrypted output with the current DEK. SSTables carry a schema version tag indicating their encryption state. The reader checks the tag and either decrypts (encrypted data) or passes through (unencrypted data). Convergence time equals a full compaction cycle.

## Rationale

### Why Compaction-Driven
- **Same mechanism as key rotation**: the infrastructure built for `encryption-key-rotation` handles this case with minimal extension. Compaction already knows how to read with one encryption state and write with another.
- **Zero additional I/O**: no dedicated migration pass. Data is encrypted as it flows through the normal compaction pipeline.
- **Online**: reads work throughout. The reader detects encryption state from the SSTable schema version tag.

### Why not Background Migration Task
Dedicated I/O pass doubles write amplification and requires 2x storage during migration. Adds a parallel migration subsystem alongside compaction — operational overhead.

### Why not Schema-Version Gate
Rejecting reads from unencrypted SSTables makes data unavailable during migration. Unacceptable.

## Implementation Guidance

### Schema version tag
Each SSTable's footer metadata carries the schema version at write time. The schema version increments when encryption configuration changes. The reader uses this to determine whether decryption is needed.

### Compaction encryption detection

```java
// In CompactionTask.merge():
for (var record : mergedInput) {
    if (record.isEncrypted()) {
        // Already encrypted — decrypt with tagged DEK, re-encrypt with current DEK
        // (same as key rotation path)
    } else {
        // Unencrypted — encrypt with current DEK
        byte[] encrypted = encryptNewly(record.payload(), currentDek, fieldDispatch);
        output.write(encrypted);
    }
}
```

### Write path
Immediately after schema update, all new writes use the updated encryption configuration. New MemTable flushes produce encrypted SSTables. Only old, pre-existing SSTables remain unencrypted until compaction reaches them.

### Index rebuild
Same consideration as key rotation: DET/OPE fields that gain encryption invalidate any existing index entries (which were built from plaintext). After migration converges, affected indices must be rebuilt.

### Reverse migration (encrypted → unencrypted)
Removing `EncryptionSpec` from a field follows the same path: new writes are unencrypted, compaction reads encrypted SSTables and writes unencrypted output. The mechanism is symmetric.

## What This Decision Does NOT Solve
- Priority compaction for deadline-driven migration — operational concern
- Cross-table migration coordination — each table migrates independently
- Partial field migration (some fields encrypted, others pending) — fields migrate independently via per-field encryption dispatch

## Conditions for Revision
This ADR should be re-evaluated if:
- Migration must complete within a bounded time window — would need priority compaction scheduling
- A schema migration framework is introduced that subsumes this mechanism
- Cross-table migration coordination is required

---
*Confirmed by: architect agent (WD-09 batch, pre-accepted) | Date: 2026-04-14*
*Full scoring: [evaluation.md](evaluation.md)*
