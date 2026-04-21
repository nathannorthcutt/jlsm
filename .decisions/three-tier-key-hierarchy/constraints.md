---
problem: "jlsm three-tier key hierarchy structure with per-tenant KMS isolation"
slug: "three-tier-key-hierarchy"
captured: "2026-04-21"
status: "confirmed"
---

# Constraint Profile — three-tier-key-hierarchy

## Problem Statement

Design the key hierarchy for jlsm encryption: tier count, layer mechanics, wrap
primitives per tier, HKDF-based derivation semantics, key registry schema, and
tenant isolation posture. This ADR supersedes the two-tier model implicit in
`encryption.primitives-lifecycle` R17, `encryption-key-rotation` (confirmed
2026-04-14), and `per-field-key-binding` (confirmed 2026-04-14).

**Scope excludes** (separate ADRs):
- DEK scoping granularity (per-table vs per-SSTable vs per-object) — ADR B
- KMS client integration, cache TTL, outage tolerance — ADR C
- Tenant-driven KEK revocation and external rotation — ADR D

## Constraints

### Scale

**Unbounded.** Tenants, data domains, tables, objects, and rotation epochs all
have no design-time ceiling.

Pinned consequences (structural):

1. **Sharded or log-structured registry.** The single-file atomic-update pattern
   (`temp+fsync+rename`) specified in `encryption.primitives-lifecycle` R19–R20
   is rejected — at 1M tenants × 100 tables × 10 rotation epochs it rewrites a
   ~100GB file per key op. The amended design must shard per-domain or use a
   log-structured registry.
2. **Per-domain-scoped DEK version space.** The 4-byte version tag in R22 is a
   hard 32-bit wall at unbounded cumulative rotation. Either widen the tag, or
   scope versioning per-domain so the wire tag is `(domainId, 32-bit version)`.
   The amended spec must pick one.
3. **Lazy domain-open.** Startup must not enumerate all domain KEKs; domain
   unwrap happens on first use and caches per ADR C's TTL.
4. **Cascading lazy rewrap on root rotation.** Rotating a tenant KEK must not
   require synchronous O(domains) rewrap — the amended design must cascade
   rewrap lazily so any single operation is bounded even under unbounded scale.

### Resources

- **JVM (Java 25)** with Panama FFM for all key material (`MemorySegment`
  Arena-backed; `Arena.ofShared` for long-lived caches, `Arena.ofConfined` for
  short-lived unwrap operations). No heap `byte[]` for live key bytes.
- **Container-friendly** — fits bounded memory (`-Xmx`) and disk (no unbounded
  temp scratch).
- **KMS is an external dependency** with per-tenant configurability. Three
  tenant encryption flavors (see Fit below): `none`, `local` (dev/test
  reference impl), `external` (BYO-KMS plugin — AWS/GCP/Vault/KMIP/custom).
- No GPU required.

### Complexity Budget

- Agent-assisted project; complexity is not penalized (per
  `.claude/rules/project-config` / project feedback memory).
- Must stay JPMS-clean: public surface in exported `jlsm.encryption.*` or
  `jlsm.core.io.*` packages, internals in `jlsm.encryption.internal`.
- Must reuse `ArenaBufferPool`, `SecureSecretKeySpec`, and the existing
  `EncryptionKeyHolder` shape where compatible — but `EncryptionKeyHolder` is
  **open for modification** in this WD (key-system hardening pass).
- A stable `KmsClient` SPI becomes a new public API surface; must be minimal
  and versioned.

### Accuracy / Correctness

- **Zero tolerance for key reuse across tenants or domains.** HKDF `info`
  (length-prefixed) enforces domain separation at derivation. AEAD AAD binds
  ciphertext to `(tenantId, domainId, dekVersion)`.
- **MAC-before-primitive** for OPE and DCPE (per existing F03 v1 pattern).
- **Tenant isolation must resist jlsm operator compromise** when external
  KMS (flavor 3) is in use. The tenant KEK is never materialised at rest by
  jlsm. Bounded unwrap/cache window per ADR C.
- **Length-prefixed canonicalization** for all HKDF info inputs (blocks
  `tenant=a,table=bc` vs `tenant=ab,table=c` collisions).
- For `local` KMS flavor: documented as **insecure without a valid KMS
  backing** — supports rotation mechanics but no HSM, no audit trail, no
  hardware-protected keys. Not a production posture.

### Operational Requirements

- Rotation at any tier must not block unrelated tenants' writes.
- Sub-millisecond key resolution on cache hit (hash-map lookup keyed by
  `(domainId, dekVersion)`).
- Cold-tenant first-query pays one KMS round-trip; subsequent queries hit
  cache within ADR C's TTL.
- The read path must tolerate tenant KMS outage for the TTL window (ADR C
  pins the TTL).
- Rekey / re-wrap operations (ADR D) must be bounded per-call; unbounded
  data migration happens via compaction (WD-04).

### Fit

- **MUST use Panama FFM `MemorySegment`** for every live key (tenant KEK
  transient unwrap handle, domain KEK Arena-cached, DEKs Arena-cached).
- **`EncryptionKeyHolder` is open for modification** — this WD is the
  key-system hardening pass. Replace or refactor as needed.
- **Stable `KmsClient` SPI** (new public API) with three built-in flavors:
  - `NoneKmsClient` — not constructed when schema has no encrypted fields
  - `LocalKmsClient` — reference impl (filesystem + OS perms, or in-process
    master key) — supports rotation mechanics for test rigour but documented
    insecure for production
  - `ExternalKmsClient` — SPI implementations for AWS KMS / GCP KMS / Vault
    / KMIP / custom. Third-party jars implement the interface.
- **Tenant isolation is always-on when encryption is enabled** — no shared-
  KEK mode exists between tenants. Every tenant always has a distinct logical
  KEK (different key ID/ARN), regardless of whether the KMS backing is shared
  or per-tenant.
- Compose with `encryption.primitives-variants` (AES-GCM / AES-SIV / OPE /
  DCPE) and the ciphertext format that WD-02 will extend.

## Key Constraints (most narrowing)

1. **Unbounded scale + per-tenant KMS isolation** — this is the binding pair.
   Together they force sharded registry, per-domain version scope, lazy
   open, cascading rewrap, and a pluggable `KmsClient` SPI. A two-tier
   model cannot satisfy both dimensions.
2. **Panama FFM-only key material + modifiable `EncryptionKeyHolder`** —
   narrows implementation to Arena-backed patterns. Rules out heap `byte[]`
   caching and `Arena.ofAuto()` in hot paths per project I/O rules.
3. **Local KMS flavor shipped as reference** — narrows the `KmsClient`
   SPI surface: every contract the local impl must satisfy becomes a
   compile-time check on future external impls. Prevents divergence.

## Unknown / Not Specified

- **Regulatory ceilings** (FIPS 140-2/3, KMIP conformance, HSM binding) —
  deferred to deployer. The design must not *preclude* these but isn't
  scoped to require any specific certification.
- **Audit log sink** — expected to be deployer-plumbed; jlsm emits events
  but doesn't persist audit logs.

## Finalized Invariants (confirmed 2026-04-21 deliberation)

### Plaintext lifecycle (strong posture)

- Plaintext is bounded to the **ingress window**: from client submission through
  per-field encryption completion. Closes/zeroizes immediately after.
- **MemTable holds per-field ciphertext**, not plaintext. Queries decrypt on read.
- No plaintext in heap dumps, core dumps, or OS swap after the ingress window.
- Per-field ciphertext is produced **once** at ingress and reused unchanged
  through WAL → MemTable → SSTable boundaries. No decrypt-then-re-encrypt on
  flush.

### HKDF hybrid derivation (required)

- Per-field DEK derivation must be **deterministic** given
  `(tableKEK, tableName, fieldName, dekVersion)`. Same plaintext → same
  ciphertext across the write pipeline.
- HKDF-SHA256 with length-prefixed `info` is the mechanism.
- This rules out pure-random per-field DEKs.

### WAL mapping (F42 compatibility)

- Each tenant carries a synthetic **`_wal` data domain** with its own domain
  KEK.
- WAL record envelope is encrypted by a DEK under the `_wal` domain and
  covers only metadata (schema ref, opcode, timestamps).
- **Field payload bytes in WAL records are the same per-field ciphertext
  that flushes to SSTable** — no re-encryption at flush.
- F42's "KEK" parameter stays intact; jlsm internally resolves to the `_wal`
  DEK.

### Rotation cascade invariant

- Tenant KEK rotation never imposes a synchronous global barrier.
- Cascading rewrap is lazy and opportunistic (rewrap on next domain open).
- **Grace period for retiring an old tenant KEK must exceed WAL retention** —
  otherwise unreplayed WAL segments become undecryptable. This is a hard
  invariant that WD-03 (rotation) and ADR D (revocation) inherit.

### Primitives-Key-Holder compatibility (APPROVED, load-bearing)

- All keys at all tiers are **32B or 64B** (per `primitives-key-holder` R2 —
  AES-256 / AES-SIV).
- All key-bearing types: idempotent close, atomic-boolean close guard, not
  `Serializable`, no `toString` leakage, no key bytes in exceptions or logs
  (per R4, R5, R6, R7, R9).
- HKDF intermediates (PRK, OKM) zeroized in finally blocks (per R8).

## Tenant Encryption Flavors (confirmed 2026-04-21)

| Flavor | Purpose | KMS backing | KeyHolder | Production-ready? |
|---|---|---|---|---|
| 1. `none` | No encryption at all | — | not constructed | N/A |
| 2. `local` | Dev/test, reference impl | jlsm-shipped local reference | yes, holding local key material | **documented insecure** — supports rotation mechanics only |
| 3. `external` | BYO-KMS production | AWS KMS / GCP KMS / Vault / KMIP / custom plugin | yes, holding KMS client + Arena-cached unwrapped domain KEKs | yes |

## Hierarchy Re-labelling Under Per-Tenant KMS

| Tier | Name | Owned by | Wrapped by | Lives where |
|---|---|---|---|---|
| 1 | **Tenant KEK** | Customer (flavor 3) or deployer (flavors 2) | external root of trust | in tenant's KMS; never at rest in jlsm |
| 2 | **Data-domain KEK** | jlsm | Tenant KEK | jlsm's sharded registry; unwrapped in Arena-backed cache |
| 3 | **DEK** | jlsm | Domain KEK | jlsm's sharded registry; per-table or per-SSTable (ADR B) |

Three conceptual tiers per tenant. The structural framing is consistent across
single-tenant and multi-tenant deployments — single-tenant is the degenerate
case (one tenant row in the registry).
