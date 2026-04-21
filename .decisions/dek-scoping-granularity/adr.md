---
problem: "dek-scoping-granularity"
date: "2026-04-21"
version: 1
status: "confirmed"
supersedes: null
depends_on:
  - "three-tier-key-hierarchy"
files:
  - "modules/jlsm-core/src/main/java/jlsm/core/io/EncryptionKeyHolder.java"
  - ".spec/domains/encryption/primitives-lifecycle.md"
---

# ADR — DEK Scoping Granularity

## Document Links

| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |
| Prerequisite ADR | [`../three-tier-key-hierarchy/adr.md`](../three-tier-key-hierarchy/adr.md) |

## KB Sources Used in This Decision

| Subject | Role in decision | Link |
|---------|-----------------|------|
| Three-Level Key Hierarchy | Structural prerequisite | [`.kb/systems/security/three-level-key-hierarchy.md`](../../.kb/systems/security/three-level-key-hierarchy.md) |
| Encryption Key Rotation Patterns | Version rotation + compaction re-encryption | [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |

---

## Files Constrained by This Decision

- `modules/jlsm-core/src/main/java/jlsm/core/io/EncryptionKeyHolder.java` — `resolveDek` signature extends to `(tenantId, domainId, tableId, version)`
- `.spec/domains/encryption/primitives-lifecycle.md` — extend registry schema (R19) to key DEKs by the four-tuple

## Problem

Within the three-tier hierarchy, decide the identity shape of a DEK.
Options considered: per-SSTable (rejected), per-object (rejected),
per-table with domain collapse (rejected), per-(tenant, domain, table)
with version (**accepted**).

## Constraints That Drove This Decision

- **Encrypt-once at ingress** (ADR A): per-field ciphertext produced
  once and reused through WAL → MemTable → SSTable. Rules out
  per-SSTable DEKs (which would force decrypt-then-re-encrypt at flush).
- **Three-tier hierarchy remains meaningful** (ADR A): domain tier must
  be able to contain multiple tables; rules out domain=table collapse.
- **Unbounded scale with tractable registry**: rules out per-object.

## Decision

**DEK identity: `(tenantId, domainId, tableId, dekVersion)`**

- A domain may contain one or more tables. Each table has its own DEK
  wrapped under the domain KEK.
- `dekVersion` is a monotonically increasing integer per table; new
  writes target the current (highest) version; reads accept any version
  present in the registry.
- The domain KEK is shared across the tables in its domain, providing
  sub-tenant blast-radius isolation at the domain boundary.
- Registry key: `(tenantId, domainId, tableId, dekVersion) →
  wrappedDekBlob`.

Per-field encryption keys derive from the table DEK via HKDF:

```
fieldKey = HKDF-SHA256(
  ikm = tableDek,
  info = lengthPrefixed(tenantId) ||
         lengthPrefixed(domainId) ||
         lengthPrefixed(tableName) ||
         lengthPrefixed(fieldName) ||
         bigEndian32(dekVersion)
)
```

Length-prefixed `info` fields prevent canonicalization collisions
(inherited from ADR A).

## Rationale

### Why per-(tenant, domain, table)

- **Encrypt-once invariant holds**: the DEK used at ingress is the same
  DEK used at flush and scan. Ciphertext flows unchanged through WAL,
  MemTable, and SSTable.
- **Domain tier earns its keep**: the domain KEK wraps multiple tables'
  DEKs, providing the sub-tenant blast-radius containment that
  justifies the three-tier hierarchy.
- **Tractable registry**: O(tables × versions × tenants × domains),
  pruneable by compaction.
- **Compatible with existing spec surface**: `primitives-lifecycle` R9's
  `deriveFieldKey(tableName, fieldName)` extends cleanly to carry
  `tenantId` and `domainId` without structural rework.

### Why not per-SSTable

Breaks encrypt-once: MemTable ciphertext would require decrypt +
re-encrypt on flush to match the output SSTable's DEK. Forces plaintext
back into the MemTable-to-flush window — violating ADR A's "plaintext
bounded to ingress" posture.

### Why not per-object

Registry size scales with data volume, not schema size. Infeasible at
unbounded scale.

### Why not domain=table (collapse)

Renders the domain tier redundant; effectively two-tier. Contradicts
ADR A's three-tier commitment.

## Rotation

DEK version rotation is independent of DEK scope:

- **New writes** target the current (highest) `dekVersion` for the
  table.
- **Reads** accept any DEK version present in the registry (lookup by
  the 4-byte wire-tag per existing `primitives-lifecycle` R22).
- **Compaction re-encrypts** records under older versions to the
  current one (existing R25).
- **Version pruning**: once no SSTable references a DEK version (the
  last referencing SSTable has been compacted), that version's registry
  entry may be GC'd.

Time-based vs event-based vs manual rotation epoch is WD-03 territory,
not this ADR.

## Implementation Guidance

```java
public interface EncryptionKeyHolder {
    DekHandle resolveDek(
        TenantId tenantId,
        DomainId domainId,
        TableId tableId,
        int dekVersion
    ) throws DekNotFoundException, TenantKekUnavailableException;

    DekHandle currentDek(
        TenantId tenantId,
        DomainId domainId,
        TableId tableId
    );  // returns the current (highest version) DEK for writes

    MemorySegment deriveFieldKey(
        DekHandle dek,
        String tableName,   // same value as used in info construction
        String fieldName
    );
}

record DekIdentity(
    TenantId tenantId,
    DomainId domainId,
    TableId tableId,
    int version
) {}
```

- `DekHandle` is Arena-backed (Panama FFM `MemorySegment` for
  unwrapped DEK bytes).
- `currentDek` is the write-path primitive; `resolveDek` is the
  read-path primitive.
- Field key derivation preserves existing R9 semantics; the Info
  construction is extended to include tenant + domain.

## What This Decision Does NOT Solve

- **DEK rotation epoch semantics** (time-based / event-based / manual):
  WD-03.
- **Registry file layout for the four-tuple key**: spec-authoring in
  WD-01 (the sharded-registry concrete format from ADR A's
  deferred items).
- **Version GC policy** (how aggressively to prune retired versions):
  WD-04 (compaction migration).
- **Cross-table joins over encrypted data**: out of scope; handled by
  `encrypted-cross-field-joins` (deferred ADR).

## Conditions for Revision

- **Per-table registry size becomes problematic in practice** (e.g.,
  workloads with millions of tables per domain). Would trigger
  consideration of table-group DEKs or per-domain DEKs with field-level
  derivation carrying table identity only in `info`.
- **New primitive variant requires SSTable-scoped randomness** (e.g., a
  future crypto primitive that benefits from per-SSTable nonce
  domains). Would add a scope parameter to the variant without
  changing the DEK identity shape.
- **DEK rotation WD discovers that version-pruning requires per-SSTable
  tracking** to determine last-reference. Would add a per-SSTable
  metadata field but not change DEK identity.

---
*Confirmed by: user deliberation | Date: 2026-04-21*
*Full design: [evaluation.md](evaluation.md)*
