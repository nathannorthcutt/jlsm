---
problem: "dek-scoping-granularity"
evaluated: "2026-04-21"
candidates:
  - path: "n/a"
    name: "Per-(tenant, domain, table) DEK — structurally determined by ADR A"
  - path: "n/a"
    name: "Per-SSTable DEK — rejected (breaks encrypt-once)"
  - path: "n/a"
    name: "Per-object DEK — rejected (unbounded registry, infeasible KMS traffic)"
  - path: "n/a"
    name: "Per-(tenant, table) DEK with domain=table — rejected (collapses three-tier)"
constraint_weights:
  scale: 3
  resources: 2
  complexity: 1
  accuracy: 3
  operational: 2
  fit: 2
---

# Evaluation — dek-scoping-granularity

## Approach

ADR A's constraints pin the answer by construction. The evaluation is a
structural argument, not a candidate-comparison matrix.

## References

- Constraints: [constraints.md](constraints.md)
- Prerequisite: [`../three-tier-key-hierarchy/adr.md`](../three-tier-key-hierarchy/adr.md)

## Why per-(tenant, domain, table) is structurally determined

### Per-SSTable DEK — rejected

At flush time, MemTable ciphertext (encrypted at ingress under the source
DEK) would have to be **decrypted and re-encrypted** to match the output
SSTable's DEK. This breaks ADR A's two core invariants:

1. "Encrypt once at ingress; reuse ciphertext through WAL → MemTable → SSTable"
2. "Plaintext bounded to ingress window"

Per-SSTable would require decrypt-on-flush, which re-materialises
plaintext in MemTable → flush path — the exact thing ADR A precludes.

### Per-object DEK — rejected

DEK count scales with data volume (every record, or every field of every
record). Registry size becomes O(records × fields × versions); KMS
wrap/unwrap operations per write explode. Infeasible under the unbounded
scale constraint.

### Per-(tenant, table) with domain=table — rejected

Collapses the domain tier. If every table is its own domain, the domain
KEK and table KEK become redundant — effectively a two-tier hierarchy
dressed in three-tier clothing. If this were the right answer, ADR A
should have chosen two-tier.

### Per-(tenant, domain, table) — accepted

DEK identity: `(tenantId, domainId, tableId, dekVersion)`.

- Registry size: O(tables × versions × tenants × domains) — tractable.
- Encrypt-once invariant holds: per-field ciphertext derives from the
  table DEK; same table DEK produces same ciphertext across WAL,
  MemTable, and SSTable.
- Domain layer retains meaning: a domain KEK wraps the DEKs of the
  tables it contains, enabling sub-tenant blast-radius isolation.
- Compatible with `primitives-lifecycle` R9 `deriveFieldKey(tableName,
  fieldName)` — the API surface doesn't need to carry SSTable identity.

## Rotation interaction

DEK *version* rotation is independent of DEK *scope*. Rotation is:
1. New writes target the current (highest) `dekVersion` for the table.
2. Reads accept any DEK version present in the registry.
3. Compaction re-encrypts records under older versions to the current
   one (inherited from `primitives-lifecycle` R25).

Rotation detail (time-based, event-based, manual) is WD-03 territory,
not this ADR.

## Falsification (inline)

### Challenged invariants

**Determinism across DEK versions**
- Risk: same plaintext under `dekVersion = N` and `dekVersion = N+1`
  produces different ciphertexts (expected for AES-GCM per nonce
  uniqueness; also for AES-SIV since the key differs). For
  deterministic variants (DET, OPE), this means rotation breaks
  cross-version equality searches until compaction homogenises the
  store.
- Holds: the existing spec's `primitives-variants` already documents
  this limit via its leakage profile. Compaction-driven re-encryption
  (R25) homogenises the ciphertext within bounded time.

**Per-table registry size**
- Risk: `O(tables × versions × tenants × domains)` grows as tenants
  accumulate rotations over years.
- Mitigation: retired DEK versions can be pruned from the registry
  once no SSTable references them (compaction removes the last
  reference, then the registry entry can be GC'd). This is a WD-03
  concern but is structurally enabled by this ADR.

### Strongest counter-argument

The only serious counter-argument is per-SSTable scope plus deferred
encryption (encrypt at flush, not at ingress). This would make
encrypt-once moot by encrypting only once at flush. Rejected: it
violates the "plaintext bounded to ingress" posture confirmed in ADR
A. Keeping plaintext live from ingress to flush (seconds to minutes
depending on MemTable lifetime) is exactly what ADR A is designed to
prevent.

### Most dangerous assumption

**That compaction-driven version cleanup actually runs.** If compaction
lags or is disabled, old DEK versions accumulate in the registry
indefinitely. Eventually becomes an operational issue at very long
lifetimes or with pathological write patterns. WD-03 / WD-04 must
ensure compaction progress is observable.

## Confidence

**High.** The decision is structurally determined by ADR A's
encrypt-once invariant and three-tier commitment. The only
design-space freedom was whether to collapse domain=table (Option B),
which the user rejected.
