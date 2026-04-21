---
problem: "DEK scoping granularity: what identifies a DEK in the three-tier key hierarchy"
slug: "dek-scoping-granularity"
captured: "2026-04-21"
status: "confirmed"
---

# Constraint Profile — dek-scoping-granularity

## Problem Statement

Within the three-tier key hierarchy (tenant KEK → data-domain KEK → DEK),
decide the identity shape of a DEK: what fields identify it uniquely,
what owns its rotation epoch, and how it composes with the domain layer.

## Constraints

### Scale

Unbounded tenants × domains × tables × rotation versions. Registry entry
count must remain tractable (logarithmic in throughput, not linear).

### Accuracy / Correctness

- **Encrypt-once-at-ingress invariant (from ADR A):** per-field ciphertext
  produced once at ingress and reused unchanged through WAL → MemTable
  → SSTable. No decrypt-then-re-encrypt on flush.
- **Deterministic HKDF hybrid derivation (from ADR A):** per-field DEK
  identity deterministically reproducible from `(tableDEK, tableName,
  fieldName, dekVersion)`. Same plaintext → same ciphertext across the
  write pipeline.

### Operational

- Rotation must not impose a synchronous global barrier.
- Compaction re-encrypts records under old DEK versions to the current
  version (inherited from `encryption.primitives-lifecycle` R25).

### Fit

- Composes with `primitives-lifecycle` R9's `deriveFieldKey(tableName,
  fieldName)` surface.
- Composes with the sharded registry design from ADR A.

## Key Constraints (most narrowing)

1. **Encrypt-once-at-ingress forces per-table scope.** Per-SSTable would
   require re-encrypt at flush time (MemTable ciphertext under
   MemTable-DEK would not match the output SSTable's DEK). Per-object
   is infeasible at scale. Per-table is the only remaining candidate.
2. **Three-tier hierarchy requires a domain boundary.** If each table
   becomes its own domain, the middle tier collapses — the structure
   becomes effectively two-tier. To keep three-tier meaningful, a
   domain must be able to contain multiple tables.
3. **Deterministic HKDF derivation from `tableDEK`** (per ADR A) pins
   the DEK identity at the table level.

## Confirmed In-Session (2026-04-21)

- **DEK scope: per-(tenant, domain, table) with version.** The domain
  layer is a grouping boundary; multiple tables can share a domain KEK
  that wraps their individual DEKs.
