---
problem: "client-side-encryption-sdk"
date: "2026-03-30"
version: 1
status: "deferred"
depends_on: ["per-field-pre-encryption", "encryption-key-rotation", "per-field-key-binding"]
---

# Client-Side Encryption SDK — Deferred

## Problem
A client-side encryption SDK that wraps the encryption primitives into a higher-level API for external consumers — schema-driven auto-encrypt/decrypt, KeyVault abstraction, encrypted blob wrapping.

## Why Deferred
Dependencies are now resolved (per-field-pre-encryption, encryption-key-rotation, per-field-key-binding all confirmed 2026-04-14), but the SDK itself is a substantial API surface requiring implementation of those dependencies first. The SDK needs:
1. Working per-field pre-encryption (bitset flag)
2. Working key rotation (envelope encryption + compaction-driven)
3. Working per-field key binding (HKDF derivation)
4. KeyVault SPI design (caller-provided key resolution)

## Resume When
Core encryption features (per-field-pre-encryption, per-field-key-binding, encryption-key-rotation) are implemented and tested.

## What Is Known So Far
KB research is complete: `.kb/systems/security/client-side-encryption-patterns.md` covers MongoDB CSFLE, AWS DB Encryption SDK, KeyVault abstraction, and per-field type byte detection. The design should follow the schema-driven auto-encrypt pattern:
- Schema declarations drive per-field encryption
- KeyVault interface resolves keyId to DEK material (caller-provided implementation)
- Envelope encryption wraps DEKs with CMK via KMS
- Write path: SDK intercepts `JlsmDocument.of()`, encrypts marked fields, produces `JlsmDocument.preEncrypted()`
- Read path: SDK intercepts query results, decrypts marked fields transparently

Key design decisions already made:
- Per-field pre-encryption: bitset flag (confirmed)
- Per-field keys: HKDF from master key for library-managed; explicit keyId for SDK-managed (future)
- Key rotation: envelope encryption + compaction-driven re-encryption (confirmed)

## Next Step
After core encryption implementation: `/architect "client-side-encryption-sdk"` — focus on KeyVault SPI, auto-encrypt interceptor design, and encrypted blob format.
