---
problem: "client-side-encryption-sdk"
date: "2026-04-15"
version: 2
status: "accepted"
decision_refs: ["per-field-pre-encryption", "pre-encrypted-flag-persistence"]
spec_refs: ["F45"]
---

# Client-Side Encryption SDK

## Problem

How should the library expose a client-side encryption API that lets callers
manage their own encryption keys and pre-encrypt document fields before
storage?

## Decision

**Schema-driven auto-encrypt/decrypt with KeyVault SPI** -- the SDK follows
the MongoDB CSFLE / AWS Database Encryption SDK pattern. Callers configure
per-field encryption via `EncryptionConfig` (field name, EncryptionSpec
variant, keyId), provide a `KeyVault` implementation that resolves key IDs
to DEK material, and the SDK transparently encrypts on write and decrypts
on read.

Key design choices resolved by F45:
- `KeyVault` SPI with `resolve(keyId)` returning `MemorySegment` -- no
  KMS coupling (R1-R5)
- `LocalKeyVault` provided for testing and single-deployment (R6-R7)
- Auto-encrypt produces `JlsmDocument.preEncrypted()` per F41 R2 (R12-R13)
- HKDF per-field key derivation reuses F41 R10-R11 (R12)
- Key caching with bounded TTL (default 5 min) and off-heap storage (R22-R25)
- Leakage profile exposure per field (R29-R30)

## Context

Originally deferred during per-field-pre-encryption decision (2026-03-30)
because the pre-encryption bitset, key hierarchy, and per-field key binding
had not yet been specified. All three are now resolved by F41 (Encryption
Lifecycle, APPROVED). The closed ADR `pre-encrypted-flag-persistence`
directed per-field markers to this SDK.

## Alternatives Considered

- **Inline encryption in JlsmDocument factory methods**: Simpler but
  conflates the library's internal encryption dispatch with caller-managed
  keys. The SDK as a separate layer keeps the boundary clean.
- **Per-field KeyVault resolution (keyId per field)**: The chosen design
  supports this via EncryptionConfig's per-field keyId.

## Consequences

- Callers get a high-level API for field-level encryption without knowing
  the library's internal encryption mechanics
- The KeyVault SPI allows plugging in any KMS (AWS KMS, HashiCorp Vault,
  local file-based) without library changes
- Key caching reduces KMS round-trips but introduces a staleness window
  (configurable TTL)
- The SDK does not manage key rotation -- that remains F41's responsibility
