---
problem: "table-handle-scope-exposure"
date: "2026-04-24"
version: 2
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/Table.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/TableMetadata.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/Engine.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/internal/CatalogTable.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/internal/TableCatalog.java"
  - "modules/jlsm-core/src/main/java/jlsm/encryption/TableScope.java"
---

# ADR — Table-Handle Scope Exposure

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision

No direct KB coverage — this decision is dominated by fit against
jlsm's existing `Engine`/`Table`/`TableCatalog` API surface and JPMS
module boundaries, not by general-industry research.

Related ADRs surveyed:
- [`.decisions/engine-api-surface-design/adr.md`](../engine-api-surface-design/adr.md) — Engine/Table handle model
- [`.decisions/table-catalog-persistence/adr.md`](../table-catalog-persistence/adr.md) — `TableMetadata` record ownership and persistence
- [`.decisions/three-tier-key-hierarchy/adr.md`](../three-tier-key-hierarchy/adr.md) — WD-01 identity records (TenantId, DomainId, TableId)
- [`.decisions/sstable-footer-scope-format/adr.md`](../sstable-footer-scope-format/adr.md) — consumer of the scope this ADR exposes

---

## Problem

The encryption read path inside `jlsm-core` (invoked during SSTable
decryption) must obtain the `(tenantId, domainId, tableId)` scope
associated with a `Table` handle (public API in `jlsm-engine`). The
scope is needed for (a) fast-fail comparison against an SSTable footer's
declared scope (per `sstable-footer-scope-format` and
`encryption.primitives-lifecycle` R22b), and (b) HKDF-based DEK
derivation (primitives-lifecycle R11).

## Constraints That Drove This Decision

- **Module graph**: `jlsm-engine` depends on `jlsm-core`; the reverse
  is forbidden. Any scope-exposure mechanism that requires `jlsm-core`
  to call back into `jlsm-engine` is architecturally incoherent.
- **Reuse existing accessors**: `Table.metadata()` already exists and
  returns a public `TableMetadata` record. Extending this accessor
  avoids adding surface area to the `Table` interface.
- **Backward compatibility**: pre-encryption tables have no scope.
  Any scope representation must accommodate the unencrypted case
  without forcing changes on existing callers.
- **Lifecycle support**: encryption must be enableable on existing
  unencrypted tables (post-creation), and this transition is a metadata
  update persisted by the catalog. **Encryption is a one-way operation
  — disable is not supported in-place.**

## Revision 2 — 2026-04-24 (evening)

During Pass 2 falsification of the dependent spec
`sstable.footer-encryption-scope`, the adversary (F1, "reader trust
boundary") demonstrated that the v1 recommendation's runtime check on
`metadata().encryption().orElseThrow(...)` does not defend against a
caller passing a non-catalog-mediated `Table` implementation whose
`metadata()` returns attacker-controlled scope. The v1 evaluation
scored candidate F (sealed `Table` with a permitted internal subtype)
as 45 and rejected it on refactor cost. The falsification finding
showed that score under-weighted the **trust boundary invariant**:
public extensibility of `Table` is the attack surface. User direction
at v2 time: "I want the sealed type, its more secure."

**Revision**: adopt candidate F's sealed-subtype pattern in addition
to the v1 sub-record composition. `Table` is sealed; only one permitted
implementation (`CatalogTable` in `jlsm.engine.internal`) exists, and
that subtype is constructed exclusively by `Engine`. External callers
cannot implement `Table`; the encryption read path's trust in
`table.metadata().encryption()` is justified because only
catalog-mediated handles can reach it.

This is additive to the v1 decision, not a replacement — the
`Optional<EncryptionMetadata>` sub-record shape still stands. What
changes:

- `Table` (public, `jlsm.engine`) becomes `public sealed interface Table`
  with `permits jlsm.engine.internal.CatalogTable`.
- `CatalogTable` in `jlsm.engine.internal` is the sole permitted
  subtype. It is package-private or internal-only at the JPMS level —
  `jlsm-engine` does not export `jlsm.engine.internal`.
- `Engine.createTable`, `Engine.getTable`, `Engine.createEncryptedTable`
  all return `Table` (the sealed public type); internally, they
  construct `CatalogTable` instances.
- External consumers of `jlsm-engine` cannot implement `Table`
  (compiler error on any non-permitted implementation).
- Test code requiring a mock Table must either (a) use a package-private
  test helper within `jlsm-engine`, or (b) extend `CatalogTable` via
  module-opens in test configurations. This is an implementation
  detail, not a spec concern.

The v1 cost estimate for candidate F (refactor cost) was overscored
given:
- `jlsm-engine` already ships `LocalTable` in `jlsm.engine.internal` as
  the sole `Table` implementation. The refactor is mechanical: add
  `sealed` + `permits` to the interface; rename `LocalTable` →
  `CatalogTable` (or keep the name and have it be the `permits` target).
- No `Engine.getTable()` signature change — it still returns `Table`.
- No caller-side code change for non-testing consumers.

## Decision (v2, combined)

**Chosen approach: extend `TableMetadata` with an optional
`EncryptionMetadata` sub-record, AND seal `Table` with a single
permitted internal subtype.**

```java
// jlsm.engine (modified)
public record TableMetadata(
    String name,
    JlsmSchema schema,
    Instant createdAt,
    TableState state,
    Optional<EncryptionMetadata> encryption) { ... }

// jlsm.engine (new)
public record EncryptionMetadata(TableScope scope) { ... }

// jlsm.encryption (new, in jlsm-core)
public record TableScope(TenantId tenantId, DomainId domainId, TableId tableId) { ... }
```

- `TableScope` composes the identity records already in `jlsm.encryption`
  from WD-01; it lives in `jlsm-core` so `jlsm-engine` can import it
  (jlsm-engine depends on jlsm-core).
- `EncryptionMetadata` lives in `jlsm.engine` as the public vessel
  for encryption-related per-table facts; future fields (e.g., KEK
  reference, cipher suite) compose without further `TableMetadata`
  churn.
- Encryption read path retrieves scope via
  `table.metadata().encryption().orElseThrow(...)`.
- `Optional.empty()` means "encryption is not configured for this
  table" — attempting to decrypt in this state is a programmer error
  and must fail fast with a descriptive runtime message.

### Engine API surface

Three-method surface cleanly separates creation and post-creation
enablement:

```java
public interface Engine extends Closeable {
    // Existing — unchanged behaviour
    Table createTable(String name, JlsmSchema schema) throws IOException;

    // New — create encrypted from day one
    Table createEncryptedTable(String name, JlsmSchema schema, TableScope scope)
        throws IOException;

    // New — enable encryption on an existing unencrypted table (one-way)
    void enableEncryption(String name, TableScope scope) throws IOException;

    // ... (existing listTables, tableMetadata, etc. unchanged)
}
```

- `createTable(...)` → `TableMetadata.encryption = Optional.empty()`
- `createEncryptedTable(...)` → `TableMetadata.encryption = Optional.of(EncryptionMetadata(scope))`
- `enableEncryption(...)` → atomic catalog rewrite of `table.meta`
  with the encryption populated. Future writes encrypt with the current
  DEK for the scope. Pre-existing v5 (unencrypted) SSTables remain
  readable via format-version dispatch from `sstable-footer-scope-format`;
  compaction migrates them to v6 + encrypted over time (WD-04 territory).

### Encryption is one-way

Once a table is encrypted (via `createEncryptedTable` or
`enableEncryption`), it cannot be returned to the unencrypted state
in place. This matches production practice (CockroachDB, TiKV, MySQL
InnoDB TDE, MongoDB CSFLE) where "disable" — when supported at all —
is implemented as a full table copy + drop, not as an in-place
transition.

Rationale: in-place disable would require (a) a DRAINING state in
`EncryptionMetadata`, (b) compaction integration that rewrites v6 →
v5, (c) extended DEK lifecycle keeping keys alive during drain,
(d) a window where both ciphertext and plaintext exist for the same
logical data — a compliance risk for deployments encrypting for
PCI/HIPAA/data-residency reasons. The state-machine cost is
unjustified without a concrete user requirement. Callers who need
to return a table to unencrypted form can `copyTable` to a fresh
unencrypted table and drop the source. Disable-as-in-place-operation
is captured as a deferred decision
(`encryption-disable-policy`).

## Rationale

### Why extend `TableMetadata` with a sub-record

- **Zero new methods on `Table`** — the existing `Table.metadata()`
  accessor is the entry point
- **Sub-record separation** — `EncryptionMetadata` is its own record,
  so descriptive metadata (name, schema, createdAt, state) stays
  decoupled from security identity; future encryption facts compose
  into EncryptionMetadata without polluting TableMetadata
- **Natural backward-compat** — `Optional<EncryptionMetadata>` expresses
  "not encrypted" cleanly; pre-encryption tables load with
  `encryption=empty`
- **Lifecycle fit** — catalog already atomically replaces `table.meta`
  on metadata changes; `enableEncryption` reuses the same mechanism
- **Module graph compatible** — `TableScope` lives in `jlsm-core` next
  to `TenantId/DomainId/TableId`; `jlsm-engine` imports it naturally
  without any new JPMS directives

### Why not `Table.scope()` direct accessor (Candidate A)

Adds a new method to the `Table` public interface and a duplicate
public type (`TableScope` would end up in `jlsm-engine` alongside its
other composition records). The existing `metadata()` accessor covers
this cleanly with less surface area.

### Why not `EncryptionContext` passed at creation only (Candidate D)

`EncryptionContext` (WD-01) is designed as the HKDF-AAD helper for
cryptographic operations, not a handle-identity lifecycle vessel —
conflating the two invites scope confusion. More importantly, D's
"scope is fixed at creation" premise fights the lifecycle requirement:
enabling encryption on an existing table would require a parallel
`enableEncryption` API that contradicts D's invariant. D loses once
post-creation enable is a requirement.

### Why not sealed `EncryptedTable extends Table` (Candidate F, v1 evaluation)

*v2 note:* v1 rejected this approach but v2 adopts a narrower version
of it — sealed `Table` with a single permitted internal subtype
(`CatalogTable`), **not** a public `EncryptedTable` vs
`UnencryptedTable` split. The v1 rejection reasoning (refactor cost,
downcast ergonomics, `enableEncryption` handle-identity flip) only
applies to the public-split variant. The single-permitted-subtype
variant has none of those problems:
- `Engine.getTable()` still returns `Table`
- Callers never downcast — they see `Table` and use `metadata().encryption()`
  to determine state
- `enableEncryption` transitions state on the existing `CatalogTable`
  instance via its `metadata()` accessor — no handle flip

The sealing is a pure type-system defence against external `Table`
implementations bypassing the trust boundary, not a runtime behaviour
change.

### Why not package-private SPI or catalog-mediated lookup (Candidates C, E)

Both violate the module graph — `jlsm-core` cannot call back into
`jlsm-engine` without a circular module dependency. Architecturally
incoherent given jlsm's existing JPMS layout.

## Implementation Guidance

- **Seal `Table`** (jlsm-engine, public):
  ```java
  public sealed interface Table extends AutoCloseable
      permits jlsm.engine.internal.CatalogTable {
      // ... existing methods unchanged
  }
  ```
  External consumers receive `Table` references from `Engine` methods
  and cannot implement `Table` themselves. Module graph unchanged.
- **`CatalogTable` in `jlsm.engine.internal`**: sole permitted Table
  implementation. Engine factory methods construct it. Package is NOT
  exported in `module-info.java`.
- **New record `TableScope` in `jlsm.encryption`** (jlsm-core):
  `(TenantId tenantId, DomainId domainId, TableId tableId)` — null-checked
  in canonical constructor.
- **New record `EncryptionMetadata` in `jlsm.engine`** (jlsm-engine):
  `(TableScope scope)` — null-checked; expandable with future fields
  (KEK reference, cipher suite, etc.) via additive record components.
- **Extend `TableMetadata` in `jlsm.engine`**: add
  `Optional<EncryptionMetadata> encryption` component. Convenience
  constructor preserving the old 4-arg signature via
  `Optional.empty()` default keeps existing callers source-compatible.
- **Catalog persistence**: `table.meta` file format extended to
  serialize the optional encryption field. A format version byte at
  the head of `table.meta` distinguishes pre/post-encryption layouts
  — missing field on reads of pre-encryption files yields
  `encryption=empty`. The `table-catalog-persistence` ADR will need
  an amendment documenting this extension.
- **`Engine.createEncryptedTable(name, schema, TableScope scope)`**:
  new method; internally constructs `TableMetadata` with
  `encryption=Optional.of(EncryptionMetadata(scope))`, persists via
  catalog, returns the Table handle.
- **`Engine.enableEncryption(name, TableScope scope)`**: acquires the
  catalog lock for the table, reads the current `TableMetadata`, constructs
  a new instance with `encryption=Optional.of(EncryptionMetadata(scope))`,
  persists atomically, invalidates cached handle metadata. Throws
  `IllegalStateException` if the table is already encrypted.
- **Encryption read path** (jlsm-core SSTable reader): calls
  `table.metadata().encryption().orElseThrow(() -> new IllegalStateException("..."))`
  with a descriptive message identifying the attempted-to-decrypt
  Table. No DEK lookup is attempted when encryption is empty — the
  error fires before any key material is touched.

## What This Decision Does NOT Solve

- **Disable encryption in place** — deferred as
  `encryption-disable-policy`. Current expectation: callers who need
  this do a table copy + drop.
- **Catalog file format versioning** — `table-catalog-persistence`
  needs an amendment to cover the new optional `encryption` field in
  `table.meta`. That amendment is part of WD-02's implementation work;
  not a separate ADR.
- **DEK version state tracking in EncryptionMetadata** — e.g., which
  DEK version is currently active for writes. That's WD-03's concern
  (DEK lifecycle + KEK rotation); EncryptionMetadata will grow at
  that time.
- **Cipher suite / algorithm selection per table** — deferred.
  `EncryptionMetadata` currently carries only scope; future ADRs may
  extend it.
- **Handle invalidation on `enableEncryption`** — implementation
  detail. Likely reuses the existing handle-eviction mechanism from
  `engine-api-surface-design`; tracked in WD-02's implementation.

## Conditions for Revision

- A concrete user demand arises for in-place disable — revisit in
  concert with `encryption-disable-policy`
- A typed `EncryptedTable` subtype becomes necessary (e.g., if the
  encryption read path needs a type-level invariant rather than a
  runtime check) — revisit to evaluate sealed-subtype refactor
- `TableMetadata` becomes a hotspot for cross-cutting concerns
  (audit context, retention policy, etc.) — revisit the sub-record
  pattern generalisation

---
*Confirmed by: user deliberation | Date: 2026-04-24*
*Full scoring: [evaluation.md](evaluation.md)*
