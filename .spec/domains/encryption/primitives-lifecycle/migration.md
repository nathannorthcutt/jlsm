---
{
  "id": "encryption.primitives-lifecycle.migration",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [
    "encryption"
  ],
  "requires": [],
  "invalidates": [],
  "decision_refs": [],
  "kb_refs": [],
  "parent_spec": "encryption.primitives-lifecycle",
  "_split_from": "encryption.primitives-lifecycle"
}
---

# encryption.primitives-lifecycle.migration — Compaction-Driven Re-Encryption and Schema Migration

This spec was carved from `encryption.primitives-lifecycle` during a domain subdivision. The
parent retains cross-cutting requirements that span multiple sub-domains;
the requirements below are specific to this concern.

R25. During compaction, for every encrypted field in a record, the compaction task must: (a) read the 4-byte DEK version tag, (b) look up the corresponding DEK in the registry scoped by the input SSTable's `(tenantId, domainId, tableId)`, (c) decrypt with that DEK, (d) re-encrypt with the current (active) DEK for the scope, and (e) write the re-encrypted ciphertext with the current DEK's version tag.

R25a. The compaction re-encryption path must not rely on the pre-encrypted bitset. The compactor must pass re-encrypted field data to the SSTable writer through a compaction-specific write path that accepts raw ciphertext bytes per field. This path must be distinct from the JlsmDocument-based write path (which uses the pre-encrypted bitset) and must accept an array or map of field-index-to-ciphertext entries. The SSTable writer must write these bytes verbatim without consulting the field's EncryptionSpec. This path must support schemas of any size (no 64-field limit). The SSTable writer must still record the schema version tag (R40), DEK version set (R23a), and scope identifier (R23a) in the footer metadata regardless of which write path is used.

R25b. The compaction task must capture the current DEK version (for the scope being compacted) at task start and use it for all re-encryption within that task. The capture must read the current version from the volatile-reference immutable map (R64), not from the locked registry. This ensures compaction startup is never blocked by KEK rotation (R34a) or cascading rewrap (R82). The output SSTable records this single DEK version in its footer metadata (R23a). If the DEK rotates during compaction, the output SSTable will use the pre-rotation version, and a subsequent compaction pass will re-encrypt to the new version. Convergence detection (R37) must not treat output SSTables from recent compaction as converged — they may reference the pre-rotation DEK by design.

R26. If a record's encrypted field already uses the current DEK version for its scope, compaction must not decrypt and re-encrypt that field. It must copy the ciphertext unchanged. This avoids unnecessary cryptographic operations when no rotation has occurred.

R27. Re-encryption during compaction must not block reads or writes to the table. Compaction operates on immutable SSTable inputs and produces new SSTable outputs; no lock on the active write path is required. Compaction from one tenant must not block compaction for another tenant (per-tenant isolation invariant from `three-tier-key-hierarchy`).

R28. After a compaction run completes and the output SSTable replaces the input SSTables in the manifest, the compaction task must not delete old DEK entries from the registry. DEK pruning is a separate operation (R30).

### DEK lifecycle

R38. When a schema update adds EncryptionSpec to a previously unencrypted field, all new writes must immediately encrypt that field with the current DEK for the table's scope. Existing SSTables with unencrypted data for that field must be encrypted during compaction.

R39. When a schema update removes EncryptionSpec from a previously encrypted field, all new writes must write that field in plaintext. Existing SSTables with encrypted data for that field must be decrypted during compaction.

R40. Each SSTable must carry a schema version tag in its footer metadata that identifies the encryption configuration at write time. The reader must use this tag to determine whether a field requires decryption for a given SSTable.

R40a. When concurrent schema updates and DEK rotations can produce records with different encryption states within a single SSTable, the SSTable footer must record both the minimum and maximum schema versions present. During reads, the reader must check the per-field ciphertext to determine encryption state when min and max schema versions differ. The presence or absence of the 4-byte DEK version tag (R22) combined with the field's current EncryptionSpec is sufficient to disambiguate encrypted from plaintext data for each record.

R41. The reader must handle mixed SSTables during migration: some SSTables may have a field encrypted and others may have the same field unencrypted. The schema version tag per SSTable determines the correct interpretation.

R42. Migration must not block reads or writes. The table must remain fully available during the entire migration window. Convergence time is bounded by a full compaction cycle.

R43. Migration must be bidirectional: adding encryption and removing encryption must use the same compaction-driven mechanism. The direction is determined by comparing the SSTable's schema version tag against the current schema's encryption configuration.

### Leakage profile documentation



---

## Notes
