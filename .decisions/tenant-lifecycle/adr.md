---
problem: "tenant-lifecycle"
date: "2026-04-21"
version: 1
status: "deferred"
---

# tenant-lifecycle — Deferred

## Problem

Tenant lifecycle operations beyond KEK revocation: decommissioning a tenant,
erasing tenant data (GDPR right-to-erasure), audit log retention, catalog
cleanup when a tenant leaves, archival policies, and tenant-scoped
observability.

## Why Deferred

Scoped out during `tenant-key-revocation-and-external-rotation` decision
(ADR D). That ADR focused on handling external KEK rotation/revocation
under flavor 3 BYO-KMS. Decommission is a broader data-lifecycle concern
involving data erasure, audit retention, catalog mutations, and
cross-module coordination (engine, storage, audit).

## Resume When

- A compliance requirement surfaces (GDPR right-to-erasure request,
  data residency mandate, contractual deletion clause)
- Production deployment begins to onboard departing tenants and needs
  explicit decommission semantics
- A downstream feature (e.g., per-tenant billing, quota enforcement)
  requires tenant lifecycle primitives

## What Is Known So Far

- Three-tier key hierarchy (ADR A) provides per-tenant isolation primitives
  that would make cryptographic erasure possible (destroy the tenant
  KEK and its wrapping — derived DEKs become unrecoverable)
- Sharded registry design localises per-tenant state; cleanup can
  operate shard-at-a-time
- Tenant state machine (healthy/grace-read-only/failed) from ADR D
  gives a natural "decommissioned" terminal state

## Next Step

Run `/architect "tenant-lifecycle"` when ready to evaluate.
