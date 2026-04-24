---
title: "Per-Tenant Encryption Key Hierarchy"
slug: per-tenant-encryption-key-hierarchy
domain: security
status: planned
type: core
tags: ["encryption", "security", "key-management", "multi-tenant", "envelope-encryption", "kms", "hkdf", "aes-kwp", "aes-gcm"]
features:
  - slug: implement-encryption-lifecycle--wd-01
    role: core
    description: "Per-tenant three-tier envelope key hierarchy (Tenant KEK -> Domain KEK -> DEK) with wait-free sharded registry, HKDF-SHA256 per-field derivation, AES-KWP + AES-GCM context-bound wrapping, Arena-backed zeroizable key material, and sealed KmsException hierarchy over a KmsClient SPI."
composes: []
spec_refs: ["encryption.primitives-lifecycle"]
decision_refs:
  - three-tier-key-hierarchy
  - dek-scoping-granularity
  - kms-integration-model
  - tenant-key-revocation-and-external-rotation
kb_refs: ["algorithms/encryption", "systems/security"]
depends_on: []
enables:
  - security/field-encryption
---

# Per-Tenant Encryption Key Hierarchy

A three-tier envelope key hierarchy (Tenant KEK -> Data-Domain KEK -> DEK)
that scopes all encryption material per tenant. Provides the cryptographic
substrate that downstream ciphertext format, DEK lifecycle, compaction
migration, and runtime concerns build on — no plaintext key material ever
leaves an `Arena`, every wrap is context-bound, and every lookup is
wait-free on the read path.

## What it does

Every encryption operation in jlsm resolves through a per-tenant key
registry. A root Tenant KEK (held by an external KMS via the `KmsClient`
SPI) wraps one or more Data-Domain KEKs, which in turn wrap per-scope DEKs.
Readers of the registry are wait-free via per-tenant sharding; writers
coordinate only within a single tenant's shard so rotation, revocation, and
new-tenant onboarding do not stall other tenants.

Tier-1 and tier-2 wrapping uses AES-KWP with context-bound AAD binding the
wrap to tenant identity, domain identity, and key generation. Tier-2 and
tier-3 wrapping also supports AES-GCM with the same AAD discipline for
deterministic-length ciphertext. Per-field DEK material is derived via
HKDF-SHA256 from the scoped DEK using a salt that encodes the field path
and purpose, so two fields under the same DEK never share a derived key.

All live key material is allocated in an `Arena` (Panama FFM) and zeroed
on close. Failures surface as a sealed `KmsException` hierarchy that
distinguishes retryable transport failures from non-retryable policy or
integrity failures, so callers can apply a correct retry policy without
leaking ciphertext into an ambiguous state.

The module exports `jlsm.encryption` (public SPI: `KmsClient`,
`KmsException` sealed hierarchy, tenant/domain/DEK handles) and
`jlsm.encryption.local` (the reference `LocalKmsClient` — suitable for
tests and self-hosted deployments; production deployments BYO external
`KmsClient` implementation). Internal derivation and wrapping primitives
live in `jlsm.encryption.internal` and are not exported.

## Features

**Core:**
- **implement-encryption-lifecycle--wd-01** — per-tenant three-tier
  hierarchy, wait-free sharded registry, HKDF-SHA256 derivation, AES-KWP
  and AES-GCM wrapping with AAD, Arena-backed zeroizable key material,
  sealed `KmsException` hierarchy, `KmsClient` SPI, `LocalKmsClient`
  reference implementation. WD-01 of the `implement-encryption-lifecycle`
  work group; downstream WDs (WD-02..WD-05) build on this substrate.

## Key behaviors

- Three tiers, per tenant: Tenant KEK (in KMS) -> Data-Domain KEK (wrapped
  by Tenant KEK) -> DEK (wrapped by Domain KEK). No tenant can decrypt
  another tenant's data, even with a compromised Domain KEK
- Reader path is wait-free via a per-tenant sharded registry; writer
  coordination is scoped to a single tenant's shard
- Tier-1 and tier-2 wraps use AES-KWP (RFC 5649) with AAD binding
  `{tenant_id, domain_id, generation}`
- Tier-2 and tier-3 wraps also support AES-GCM with the same AAD
  discipline for deterministic-length ciphertext
- Per-field DEK material is derived via HKDF-SHA256; salt encodes field
  path and purpose to guarantee cross-field key independence
- All live key material is allocated in `Arena` off-heap segments and
  zeroed on close; no plaintext key bytes live on the Java heap
- Failures surface as a sealed `KmsException` hierarchy distinguishing
  retryable transport errors from non-retryable policy/integrity errors
- `KmsClient` is the pluggable SPI; `LocalKmsClient` is the reference
  in-process implementation; production callers BYO external `KmsClient`
- Security-critical invariants are pinned by an audit pass (run-001,
  52 bugs fixed, 0 FIX_IMPOSSIBLE) and are referenced from the
  `encryption.primitives-lifecycle` spec (v9 DRAFT pending Pass 6)

## Related

- **Specs:** encryption.primitives-lifecycle (v9 DRAFT — Pass 6 pending)
- **Decisions:** three-tier-key-hierarchy (tier structure and scoping),
  dek-scoping-granularity (per-field derivation via HKDF),
  kms-integration-model (`KmsClient` SPI and failure taxonomy),
  tenant-key-revocation-and-external-rotation (rotation and revocation
  protocol)
- **KB:** algorithms/encryption (AES-KWP, AES-GCM, HKDF), systems/security
  (multi-tenant key management patterns)
- **Depends on:** (none — substrate capability)
- **Enables:** security/field-encryption (downstream key material source),
  and within the current work group: WD-02 (ciphertext format),
  WD-03 (DEK lifecycle + rekey), WD-04 (compaction migration),
  WD-05 (runtime concerns)
- **Modules:** jlsm-core (exports `jlsm.encryption`, `jlsm.encryption.local`;
  internal `jlsm.encryption.internal`)
