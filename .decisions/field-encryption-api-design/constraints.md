---
problem: "How should field-level encryption be expressed in JlsmSchema, how should keys bind to fields, and how should DocumentSerializer integrate with encryption/decryption?"
slug: "field-encryption-api-design"
captured: "2026-03-18"
status: "draft"
---

# Constraint Profile — field-encryption-api-design

## Problem Statement
Design the API surface for opt-in field-level encryption in jlsm-table: how encryption configuration is expressed in the schema, how caller-provided keys bind to fields, and how DocumentSerializer integrates encryption/decryption into its serialize/deserialize path. Must support type-aware encryption (DET for scalars, DCPE for vectors, AES-GCM for opaque fields) without changing the existing unencrypted API contract.

## Constraints

### Scale
Billions of records, millions of QPS distributed across partitioned tables. Encryption overhead must not become a bottleneck at partition level — per-field encrypt/decrypt must be O(field-size) with no allocation amplification.

### Resources
CPU + RAM only. No GPU or specialized crypto hardware. Standard cloud/datacenter commodity servers. Must work within existing ArenaBufferPool memory budgets.

### Complexity Budget
Very high. AI development team is deeply familiar with all concepts. Can create new tooling, complex APIs, and diagnostic utilities as needed. No need to simplify at the expense of capability.

### Accuracy / Correctness
As high as possible. Vector and full-text search are inherently approximate — encryption may further degrade recall, which is acceptable if documented. Wrong-key decryption must be detected, never silent corruption.

### Operational Requirements
Minimize latency — encryption should add minimal overhead to hot paths. Deterministic operation caps — no unbounded encryption operations. Graceful fallback on failure (no timeouts or crashes). Background rebuild for re-encryption (key rotation) — not frequent but must complete in bounded time.

### Fit
Pure Java 25 stack (javax.crypto, java.security). Can create new interfaces, types, and modules. Must compose with existing JlsmSchema, DocumentSerializer, FieldType hierarchy, JlsmTable builder, and secondary index infrastructure.

## Key Constraints (most narrowing)
1. **Fit with existing type system** — must compose cleanly with FieldType hierarchy, JlsmSchema builder, DocumentSerializer, and secondary indices without breaking the existing unencrypted API
2. **Type-aware encryption** — different FieldTypes need different encryption schemes (DET for equality-searchable, DCPE for vectors, OPE for ranges, AES-GCM for opaque) — the API must express this per-field
3. **Scale + latency** — encryption on every serialize/deserialize at millions of QPS means the API design must avoid unnecessary object allocation, key lookup overhead, and dispatch indirection in the hot path

## Unknown / Not Specified
None — full profile captured.
