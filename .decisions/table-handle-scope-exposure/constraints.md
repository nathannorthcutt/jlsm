---
problem: "table-handle-scope-exposure"
slug: "table-handle-scope-exposure"
captured: "2026-04-24"
status: "draft"
---

# Constraint Profile — table-handle-scope-exposure

## Problem Statement

The encryption read path — invoked from SSTable-reader code inside
`jlsm-core` — must resolve the `(tenantId, domainId, tableId)` scope
associated with a `Table` handle (public API in `jlsm-engine`) in order
to (a) compare against the SSTable footer's declared scope (R22b fast-fail)
and (b) derive DEK material via HKDF (primitives-lifecycle R11).

How does scope travel from the caller (who holds a `Table` handle) into
the encryption code path?

Specific sub-questions:
1. **Where does scope live?** On `Table` directly, on `TableMetadata`,
   in the catalog, or passed through explicitly at construction?
2. **What's the public-API shape?** New public type in `jlsm-engine`,
   or reuse the existing records from `jlsm.encryption`?
3. **Module boundaries?** Do we need new JPMS `exports ... to` or
   `opens` directives, or does the existing jlsm-core → jlsm-engine
   dependency suffice?
4. **Backward compatibility?** Pre-encryption tables have no scope;
   nullable vs. "always-present-with-sentinel" pattern?

## Constraints

### Scale
N/A at this layer. Scope access is per-Table-handle, not per-query.

### Resources
Cheap — few references per access.

### Complexity Budget
Minimise new public abstractions. Prefer extending existing records
over introducing new types. Avoid new JPMS boundary crossings.

### Accuracy / Correctness
- Scope must not be ambiguously derivable at runtime — it must be baked
  in at Table construction / catalog load, not inferred from the SSTable
  footer (which would be tautological per R22b).
- Tenant/domain/table identifiers are UTF-8 strings (records in
  `jlsm.encryption`).

### Operational
- Backward compatibility: pre-encryption tables must continue to work
  through the same API. No required scope means the engine can open
  single-tenant `null`-scope tables silently.
- No online migration; scope is set at create-time.

### Fit
- `Table` interface is in `jlsm.engine` package (`jlsm-engine` module),
  already exposes `Table.metadata()` returning `TableMetadata`.
- `TableMetadata` is currently `(name, schema, createdAt, state)` —
  a public `record` governed by
  `.decisions/table-catalog-persistence/adr.md`.
- `TenantId`, `DomainId`, `TableId` records live in `jlsm.encryption`
  (`jlsm-core` module) from WD-01. `jlsm-engine` depends on `jlsm-core`
  so importing is free.

## Key Constraints (most narrowing)

1. **Reuse `Table.metadata()`** — the accessor already exists; no new
   public method required on Table if scope lives on TableMetadata.
2. **Backward compatibility** — `TableMetadata.scope` must be optional
   (nullable or sentinel) for non-encrypted tables.
3. **No new JPMS boundary crossings** — keep the exposure inside the
   already-established jlsm-core → jlsm-engine public dependency.

## Unknown / Not Specified

None — constraints are well-bounded by the existing Engine/Table/Catalog
interfaces and the WD-01 identity records.
