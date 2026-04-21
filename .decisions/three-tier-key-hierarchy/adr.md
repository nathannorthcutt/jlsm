---
problem: "three-tier-key-hierarchy"
date: "2026-04-21"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-core/src/main/java/jlsm/core/io/EncryptionKeyHolder.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/FieldEncryptionDispatch.java"
  - "modules/jlsm-core/src/main/java/jlsm/compaction/internal/CompactionTask.java"
  - ".spec/domains/encryption/primitives-lifecycle.md"
  - ".spec/domains/wal/encryption.md"
amends:
  - "encryption-key-rotation"
  - "per-field-key-binding"
---

# ADR — Three-Tier Key Hierarchy

## Document Links

| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision

| Subject | Role in decision | Link |
|---------|-----------------|------|
| Three-Level Key Hierarchy | Primary research — envelope wrap, HKDF derivation, tier semantics | [`.kb/systems/security/three-level-key-hierarchy.md`](../../.kb/systems/security/three-level-key-hierarchy.md) |
| Three-Level Key Hierarchy (detail) | Reference designs (CockroachDB/AWS/Tink/Vault), code skeleton | [`.kb/systems/security/three-level-key-hierarchy-detail.md`](../../.kb/systems/security/three-level-key-hierarchy-detail.md) |
| Encryption Key Rotation Patterns | Envelope pattern, key registry, compaction re-encryption | [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |
| JVM Key Handling Patterns | Panama FFM Arena, zeroize-on-close, Serializable prohibition | [`.kb/systems/security/jvm-key-handling-patterns.md`](../../.kb/systems/security/jvm-key-handling-patterns.md) |
| Client-Side Encryption Patterns | Multi-tenant CSFLE patterns for flavor 3 comparison | [`.kb/systems/security/client-side-encryption-patterns.md`](../../.kb/systems/security/client-side-encryption-patterns.md) |

---

## Files Constrained by This Decision

- `modules/jlsm-core/src/main/java/jlsm/core/io/EncryptionKeyHolder.java` — refactored to compose a `KmsClient` SPI and expose tier-aware unwrap/derive methods
- `modules/jlsm-table/src/main/java/jlsm/table/internal/FieldEncryptionDispatch.java` — consumes per-field DEK identity derived from the table DEK
- `modules/jlsm-core/src/main/java/jlsm/compaction/internal/CompactionTask.java` — re-encryption during compaction must address per-domain DEK registries
- `.spec/domains/encryption/primitives-lifecycle.md` — amend R17 (two-tier → three-tier), R19–R21 (registry schema), R22 (wire tag format `(domainId, version)`), R34+ (rotation cascade)
- `.spec/domains/wal/encryption.md` — Verification Note: F42's "KEK" parameter resolves internally to the tenant's `_wal`-domain DEK

## Problem

Design the key hierarchy for jlsm encryption: tier count, layer mechanics,
derivation semantics, tenant isolation posture, and compatibility with the
existing `encryption.primitives-lifecycle` DRAFT v4, `wal.encryption`
APPROVED v3, and the `primitives-key-holder` / `primitives-variants`
approved specs.

## Constraints That Drove This Decision

- **Unbounded multi-tenant scale with sub-tenant blast-radius isolation**:
  tenants may hold multiple data domains at different sensitivity levels;
  rotation and storage must scale without design-time ceilings.
- **Per-tenant KMS isolation (possibly customer-managed)**: tenant KEKs may
  live in the tenant's own KMS; jlsm must never materialise tenant KEK
  material persistently.
- **"Plaintext bounded to ingress window" correctness posture**: field
  plaintext lifetime limited to ingress validation + encryption; MemTable
  holds per-field ciphertext; no decrypt-then-re-encrypt on flush.

## Decision

**Three-tier envelope key hierarchy** with per-tenant KMS isolation:

| Tier | Name | Owned by | Wrapped by | Storage |
|------|------|----------|-----------|---------|
| 1 | **Tenant KEK** | Customer (flavor 3) or deployer (flavor 2) | external root of trust | tenant's KMS; never at rest in jlsm |
| 2 | **Data-domain KEK** | jlsm | Tenant KEK | sharded registry; unwrapped in Arena-backed cache |
| 3 | **DEK** | jlsm | Domain KEK | sharded registry; scope pinned by ADR B |

Tenant isolation is **always-on when encryption is enabled** — no shared-KEK
mode between tenants. Three tenant encryption flavors are supported:

- `none` — no encryption; `EncryptionKeyHolder` not constructed
- `local` — jlsm-shipped reference `KmsClient` (dev/test; **documented insecure for production**)
- `external` — BYO-KMS via `KmsClient` SPI (AWS / GCP / Vault / KMIP / custom)

Wrap primitives per tier:
- **L1 → L2** (Tenant KEK wraps Domain KEK): AES-KW or AES-KWP (RFC 3394/5649) — deterministic, no RNG, +8 bytes overhead
- **L2 → L3** (Domain KEK wraps DEK): AES-GCM with HKDF-info-as-AD — context binding blocks cross-scope ciphertext swap
- **L3 → field value** (DEK derives field key via HKDF): HKDF-SHA256 per existing `primitives-lifecycle` R9–R16, with length-prefixed `info` inputs

Deterministic hybrid derivation: per-field DEK identity is deterministically
reproducible from `(tableDEK, tableName, fieldName, dekVersion)`. Per-field
ciphertext produced once at ingress and reused unchanged through
WAL → MemTable → SSTable boundaries.

## Rationale

### Why three-tier

- **Sub-tenant blast-radius containment**: the domain tier partitions a tenant's
  data by sensitivity (e.g., `payments` / `logs` / `analytics`) so DEK compromise
  stays within a domain rather than propagating across a tenant's entire dataset.
- **Per-tenant KMS isolation**: tier 1 lives in the customer's KMS (flavor 3),
  resisting jlsm operator compromise. Two-tier cannot express this cleanly.
- **Unbounded scale**: the domain tier is the horizontal unit for scale-out.
  Sharded registry + per-domain version scope + lazy domain open let the
  registry grow without bound.

### Why not two-tier (KEK → DEK)

- Fails the sub-tenant blast-radius requirement. Any DEK compromise within a
  tenant exposes the whole tenant.
- Hyperscale production (AWS/GCP/Tink/MongoDB) ships two-tier but targets
  single-sensitivity-per-tenant workloads. jlsm's requirement profile differs.

### Why not flat (per-field independent keys, no KEK wrapping)

- Every field write requires a KMS operation — unbounded KMS traffic
  infeasible at the claimed scale.
- Rotation requires re-encrypting every field individually.

## Structural Consequences (pinned)

These are load-bearing for the hierarchy; amendments must preserve them:

1. **Sharded or log-structured registry.** Single-file atomic update
   (`temp+fsync+rename`) is rejected. Concrete layout deferred to
   spec-authoring.
2. **Per-domain-scoped DEK version space.** Wire tag becomes
   `(domainId, version)`. Concrete byte layout deferred to spec-authoring.
3. **Lazy domain open.** No eager enumeration at startup; domains unwrap on
   first use and cache per ADR C's TTL.
4. **Cascading lazy rewrap on tenant KEK rotation.** Any single operation is
   bounded; domain KEKs rewrap opportunistically on next access.
5. **Grace period for retiring old tenant KEK > WAL retention.** Otherwise
   un-replayed WAL segments become undecryptable. Hard invariant inherited
   by WD-03 and ADR D.
6. **Synthetic `_wal` data domain per tenant.** WAL records encrypt under a
   DEK in the `_wal` domain; record envelope covers metadata only; field
   payload bytes in WAL records are the same per-field ciphertext that
   flushes to SSTable.
7. **Plaintext bounded to ingress** for field values only; **primary keys
   remain plaintext** for MemTable sort order. Queries on encrypted
   non-primary fields rely on OPE/DET variants already specified in
   `primitives-variants`.
8. **HKDF hybrid derivation** (deterministic per-field DEK identity)
   required. Pure-random per-field DEKs are ruled out — the "encrypt once
   at ingress, reuse across pipeline" pattern depends on determinism.
9. **HKDF `info` length-prefixed** to block canonicalization attacks
   (`tenant=a,table=bc` vs `tenant=ab,table=c`).
10. **Key material at all tiers**: 32B (AES-256) or 64B (AES-SIV) only, per
    `primitives-key-holder` R2. Panama FFM `MemorySegment` throughout; never
    heap `byte[]` for live keys.

## Implementation Guidance

`EncryptionKeyHolder` is refactored:
- Composes a `KmsClient` (new SPI) rather than owning master key bytes directly.
- `openDomain(tenantId, domainId)` returns a handle over a cached unwrapped domain KEK (Arena-backed, TTL per ADR C).
- `resolveDek(domainHandle, dekVersion)` returns a DEK handle; unwrap happens on first resolve.
- `deriveFieldKey(dekHandle, tableName, fieldName)` preserves the existing contract surface.
- `close()` zeroizes cached domain KEKs and DEKs per `primitives-key-holder` R4.

`KmsClient` SPI (minimal, versioned):
- `wrapKek(plaintextKey, keyIdentifier)` / `unwrapKek(ciphertext, keyIdentifier)`
- `listKekVersions(keyIdentifier)`
- Reference implementation: `LocalKmsClient` (filesystem-backed) — ships with jlsm, documented insecure for production.
- BYO implementations provided as separate modules or third-party jars.

Registry surface:
- Per-domain sharded files, path derived from `tenantId` + `domainId` (layout pinned by spec-authoring).
- Each shard is self-contained: CRC-32C trailer, atomic temp+fsync+rename for that single shard.
- Cross-domain rotation: lazy, driven by observed tenant-KEK version during domain open.

## What This Decision Does NOT Solve

- **DEK scoping granularity** (per-table vs per-SSTable vs per-object) — ADR B.
- **KMS client cache TTL and outage semantics** — ADR C.
- **Tenant-driven KEK revocation + external rotation** — ADR D.
- **Sharded-registry concrete file-layout** (per-domain files vs log-structured) — spec-authoring in WD-01.
- **`(domainId, version)` wire-tag byte layout** (4B+4B vs 8B combined vs varint pair) — spec-authoring in WD-01.
- **`KmsClient` SPI precise method signatures** (timeout/retry parameters, metadata side-channel) — spec-authoring in WD-01.

## Conditions for Revision

This ADR should be re-evaluated if:
- **Deployment evidence** shows jlsm tenants are overwhelmingly single-sensitivity (sub-tenant isolation turns out to be aspirational and unused) — would trigger simplification to two-tier with per-tenant KEK IDs.
- **Primary keys must be encrypted** (e.g., PII primary keys without OPE support) — the "MemTable holds ciphertext, primary keys plaintext" assumption breaks and the posture needs rework.
- **Sharded-registry layout** selected at spec-authoring turns out infeasible (e.g., filesystem inode exhaustion for per-domain-file layout) — could force a log-structured redesign that this ADR's other pins must tolerate.
- **HKDF hybrid derivation across rotation epochs** surfaces a leakage pattern not captured in `primitives-variants`' leakage profiles — would require pure-random DEKs and drop the "ciphertext reuse across pipeline" optimisation.
- **KMS cost** at unbounded tenant count proves uneconomic — would trigger reconsideration of the "domain open on first use + TTL cache" pattern from ADR C.

## Amendments to Prior Decisions

This ADR amends:

- **`encryption-key-rotation`** (confirmed 2026-04-14): the rotation model
  generalises to three tiers. Root rotation re-wraps O(domains) lazily;
  domain rotation re-wraps O(DEKs in domain); DEK rotation delegates to
  compaction.
- **`per-field-key-binding`** (confirmed 2026-04-14): per-field HKDF
  derivation is preserved, but the "per-field" scope is now *within a DEK
  within a domain within a tenant*. `deriveFieldKey` signature may extend
  to include `tenantId` and `domainId` inputs.

---
*Confirmed by: user deliberation | Date: 2026-04-21*
*Full scoring: [evaluation.md](evaluation.md)*
