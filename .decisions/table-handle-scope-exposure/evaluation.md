---
problem: "table-handle-scope-exposure"
evaluated: "2026-04-24"
candidates:
  - name: "A — Table.scope() direct accessor"
  - name: "B — TableMetadata extended with optional TableScope"
  - name: "C — Package-private SPI inside jlsm-engine"
  - name: "D — EncryptionContext passed at Table construction"
  - name: "E — Catalog-mediated TableCatalog.scopeOf(name)"
constraint_weights:
  scale: 1
  resources: 1
  complexity: 3
  accuracy: 3
  operational: 2
  fit: 3
---

# Evaluation — table-handle-scope-exposure

## References
- Constraints: [constraints.md](constraints.md)
- Prior ADR: [`.decisions/table-catalog-persistence/adr.md`](../table-catalog-persistence/adr.md)
- Related: [`.decisions/engine-api-surface-design/adr.md`](../engine-api-surface-design/adr.md)

## KB coverage

No direct KB coverage for "multi-tenant handle scope exposure in Java
database engines" — this is an API-shape decision specific to jlsm's
module layout. General patterns apply (accessor vs. context-passing vs.
SPI), but the decision is dominated by **fit** against the existing
Engine/Table/Catalog surface, not by general-industry research.

## Constraint Summary

A Table handle must surface `(tenantId, domainId, tableId)` to the
encryption read path. The decision is dominated by API ergonomics,
module boundaries, and backward compatibility with pre-encryption
callers — not by scalability or runtime cost.

## Weighted Constraint Priorities

| Constraint | Weight | Why |
|---|---|---|
| Complexity | 3 | Minimise new public types; extend existing records where possible |
| Accuracy | 3 | Scope must be baked at Table construction; no runtime derivation from ambiguous sources |
| Fit | 3 | Existing Table/TableMetadata API shape dominates |
| Operational | 2 | Backward compatibility matters; non-encrypted tables must work unchanged |
| Scale | 1 | Per-handle, not per-query |
| Resources | 1 | Trivial |

---

## Candidate A — `Table.scope()` direct accessor

Adds a new method to the `Table` interface returning `TableScope(TenantId, DomainId, TableId)` (a new public record, likely in `jlsm.engine`).

| Constraint | Weight | Score | Weighted | Evidence |
|---|---|---|---|---|
| Complexity | 3 | 3 | 9 | New public type (`TableScope`) + new interface method |
| Accuracy | 3 | 5 | 15 | Scope surfaced directly, no indirection |
| Fit | 3 | 3 | 9 | Adds method to public interface — binary-compatible addition is possible with `default` method but adds surface area |
| Operational | 2 | 4 | 8 | Default `Table.scope()` returns null or Optional.empty() for pre-encryption tables — requires Optional<TableScope> return type |
| Scale | 1 | 5 | 5 | |
| Resources | 1 | 5 | 5 | |
| **Total** | | | **51** | |

**Weaknesses:** introduces a new public type (`TableScope`) in
`jlsm.engine` that duplicates the conceptual shape of a triple
`(TenantId, DomainId, TableId)` already implicit in WD-01.

---

## Candidate B — `TableMetadata` extended with optional `TableScope`

Extend the existing public `TableMetadata` record to `(name, schema, createdAt, state, scope)` where `scope` is nullable (or `Optional<TableScope>`). Encryption read path retrieves via `table.metadata().scope()`. Amends `table-catalog-persistence` ADR.

| Constraint | Weight | Score | Weighted | Evidence |
|---|---|---|---|---|
| Complexity | 3 | 5 | 15 | Zero new methods on `Table`; one new field on an existing public record; `TableScope` reuses `TenantId/DomainId/TableId` from jlsm.encryption |
| Accuracy | 3 | 5 | 15 | Scope loaded with metadata at catalog open → baked in at construction as required |
| Fit | 3 | 5 | 15 | `Table.metadata()` accessor already exists; `TableMetadata` is already the canonical public "about this table" record |
| Operational | 2 | 5 | 10 | Nullable/Optional field = pre-encryption tables get scope=null or Optional.empty(); existing callers unaffected |
| Scale | 1 | 5 | 5 | |
| Resources | 1 | 5 | 5 | |
| **Total** | | | **65** | |

**Strengths:**
- Reuses existing `metadata()` accessor — zero new methods on Table
- `TableMetadata` is already the "facts about this table" vessel — scope is a natural addition
- Nullable field cleanly expresses "not-encrypted" without a sentinel
- Record amendment only requires bumping record shape + updating the catalog serialization format (already persisted to `table.meta`)

**Weaknesses:**
- Requires amendment to `table-catalog-persistence` ADR — the catalog's
  `table.meta` file format needs a backward-compatible extension
- TableScope still needs to be defined somewhere (jlsm.engine or jlsm.encryption)

---

## Candidate C — Package-private SPI inside jlsm-engine

Expose scope via a package-private interface `TableScopeAccessor` in `jlsm.engine.internal`. Encryption layer (in `jlsm-core`) calls this SPI via a JPMS `exports jlsm.engine.internal to jlsm.core` directive.

| Constraint | Weight | Score | Weighted | Evidence |
|---|---|---|---|---|
| Complexity | 3 | 2 | 6 | New SPI + JPMS directive + opens/exports policy decisions |
| Accuracy | 3 | 4 | 12 | Scope accessible but via indirection that obscures where it came from |
| Fit | 3 | 2 | 6 | Inverts the module dependency direction — jlsm-core consuming an API exposed by jlsm-engine (which depends on jlsm-core) = circular |
| Operational | 2 | 3 | 6 | SPI is hidden so callers don't interact with it directly; backward-compat OK |
| Scale | 1 | 4 | 4 | |
| Resources | 1 | 4 | 4 | |
| **Total** | | | **38** | |

**Hard disqualifier:** the module dependency graph forbids this —
jlsm-engine depends on jlsm-core. jlsm-core cannot call back into
jlsm-engine without creating a circular module dependency. This
candidate is architecturally incoherent given the current graph.

---

## Candidate D — `EncryptionContext` passed at Table construction

Caller constructs an `EncryptionContext` (from WD-01) and passes it to `Engine.createTable(name, schema, encryptionContext)`. Engine stores it and the encryption read path retrieves it from the handle.

| Constraint | Weight | Score | Weighted | Evidence |
|---|---|---|---|---|
| Complexity | 3 | 3 | 9 | New Engine overload; existing `EncryptionContext` from WD-01 is reusable but was scoped for HKDF AAD not lifecycle |
| Accuracy | 3 | 5 | 15 | Explicit = unambiguous |
| Fit | 3 | 4 | 12 | EncryptionContext is a WD-01 type — already in jlsm.encryption; engine just stores it on TableMetadata or on an internal per-Table struct |
| Operational | 2 | 3 | 6 | Caller wiring is more intrusive — every `Engine.createTable` call site needs an EncryptionContext for encrypted tables; non-encrypted callers need a default/null overload |
| Scale | 1 | 5 | 5 | |
| Resources | 1 | 5 | 5 | |
| **Total** | | | **52** | |

**Weaknesses:** pushes wiring cost onto every caller; EncryptionContext
is designed as an HKDF-AAD helper (carries Purpose + ordered
attributes), not a long-lived handle-identity type — conflating the
two could lead to scoping confusion.

---

## Candidate E — Catalog-mediated `TableCatalog.scopeOf(name)`

Scope lives in the `TableCatalog` (already present, in `jlsm.engine.internal`). Encryption read path queries the catalog by table name. Table interface unchanged.

| Constraint | Weight | Score | Weighted | Evidence |
|---|---|---|---|---|
| Complexity | 3 | 3 | 9 | New catalog method (but internal) |
| Accuracy | 3 | 4 | 12 | Lookup by name — name-based lookups have historically been a source of bugs (rename, drop-recreate ambiguity) |
| Fit | 3 | 3 | 9 | Same circular-module problem as C if encryption in jlsm-core has to call back to jlsm-engine's catalog |
| Operational | 2 | 3 | 6 | Lookup overhead per encryption op (bounded by cache) |
| Scale | 1 | 4 | 4 | |
| Resources | 1 | 4 | 4 | |
| **Total** | | | **44** | |

**Hard disqualifier:** same as C — jlsm-core cannot call back into
jlsm-engine without a circular module dependency.

---

## Comparison Matrix

| Candidate | Complexity | Accuracy | Fit | Operational | Scale | Resources | Weighted Total |
|---|---|---|---|---|---|---|---|
| A — Table.scope() | 3 | 5 | 3 | 4 | 5 | 5 | **51** |
| B — TableMetadata.scope | 5 | 5 | 5 | 5 | 5 | 5 | **65** |
| C — Package-private SPI | 2 | 4 | 2 | 3 | 4 | 4 | **38** (disqualified) |
| D — EncryptionContext arg | 3 | 5 | 4 | 3 | 5 | 5 | **52** |
| E — Catalog-mediated | 3 | 4 | 3 | 3 | 4 | 4 | **44** (disqualified) |

## Preliminary Recommendation

**B — TableMetadata extended with optional TableScope.** Reuses the
existing `Table.metadata()` accessor (zero new methods on the Table
interface), extends a record that's already the public "about this
table" vessel, and expresses "not-encrypted" cleanly as
`Optional.empty()` / nullable. The catalog's persisted metadata format
needs a backward-compatible extension, which is the natural home for
scope anyway.

**Scope of catalog amendment**: `table-catalog-persistence` ADR needs a
companion amendment to describe scope persistence in `table.meta`.
Out-of-scope for this ADR — followup work item.

**TableScope placement**: put the record in `jlsm.encryption` alongside
`TenantId/DomainId/TableId` from WD-01. jlsm-engine already depends on
jlsm-core, so the import is free and the composition is natural.

## Risks and Open Questions

- **Catalog file format bump**: `table.meta` needs to serialize the new
  optional scope field without breaking existing `.meta` files on disk.
  Versioning the .meta file is a small separate concern that lands
  within this ADR's implementation but is governed by the catalog ADR.
- **EncryptionContext vs TableScope separation**: EncryptionContext
  (WD-01) remains the HKDF-AAD helper; TableScope is the durable
  handle-identity type. These are different purposes — keep them
  distinct types even though the underlying data overlaps.
