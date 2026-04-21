---
group: implement-encryption-lifecycle
goal: Implement the encryption.primitives-lifecycle DRAFT spec (F41 decomposed by section) — key hierarchy, ciphertext format, DEK lifecycle, KEK rotation, compaction-driven re-encryption, and runtime concerns.
status: active
created: 2026-04-21
---

## Goal

Implement the encryption.primitives-lifecycle DRAFT spec (F41 decomposed by section) — key hierarchy, ciphertext format, DEK lifecycle, KEK rotation, compaction-driven re-encryption, and runtime concerns.

## Scope

### In scope
- encryption.primitives-lifecycle decomposed into five WDs by section (per F41 structure)
- Key hierarchy, ciphertext format + signalling, DEK/KEK lifecycle + rotation, compaction migration, runtime concerns
- Promotion of encryption.primitives-lifecycle DRAFT → APPROVED

### Out of scope
- Encryption primitive selection (already decided — see encryption.primitives-variants)
- Index-layer encryption application (query/ side, lives in query specs with multi-domain tags)
- WAL-level encryption (wal/ domain, separate spec)

## Ordering Constraints

WD-01 (key-hierarchy) is the foundation. WD-02 (ciphertext-format + signalling) builds on it. WD-03 (DEK lifecycle + KEK rotation) depends on both. WD-04 (compaction migration) and WD-05 (runtime concerns) land last and can run in parallel after WD-03.

## Shared Interfaces

Ciphertext wire format — encoded layout produced by WD-02 — is the contract consumed by everyone downstream (wal encryption, serialization, query index encryption). Publishing as an interface-contract spec after WD-02 lets those consumers code against a stable shape.
