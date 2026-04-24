---
title: "Three-tier key hierarchy foundation (implement-encryption-lifecycle WD-01)"
type: feature-footprint
tags: [encryption, key-hierarchy, kek, dek, kms, multi-tenant, hkdf, aes-kwp, aes-gcm, adversarial-hardening]
feature_slug: implement-encryption-lifecycle--wd-01
work_group: implement-encryption-lifecycle
shipped: 2026-04-23
domains:
  - encryption
  - key-hierarchy
  - data-domain-boundary
  - dek-scoping
  - kms-integration
  - tenant-isolation
constructs:
  - "TenantId"
  - "DomainId"
  - "TableId"
  - "DekVersion"
  - "KekRef"
  - "DekHandle"
  - "Purpose"
  - "TenantFlavor"
  - "EncryptionContext"
  - "KmsClient"
  - "WrapResult"
  - "UnwrapResult"
  - "KmsException"
  - "KmsTransientException"
  - "KmsPermanentException"
  - "KmsRateLimitExceededException"
  - "WrappedDek"
  - "WrappedDomainKek"
  - "DekNotFoundException"
  - "TenantKekUnavailableException"
  - "EncryptionKeyHolder"
  - "LocalKmsClient"
  - "Hkdf"
  - "AesKeyWrap"
  - "AesGcmContextWrap"
  - "ShardPathResolver"
  - "ShardStorage"
  - "KeyRegistryShard"
  - "TenantShardRegistry"
  - "OffHeapKeyMaterial"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/encryption/**"
  - "modules/jlsm-core/src/main/java/jlsm/encryption/internal/**"
  - "modules/jlsm-core/src/main/java/jlsm/encryption/local/**"
related:
  - ".kb/systems/security/three-level-key-hierarchy.md"
  - ".kb/systems/security/three-level-key-hierarchy-detail.md"
  - ".kb/systems/security/dek-caching-policies-multi-tenant.md"
  - ".kb/systems/security/dek-revocation-vs-rotation.md"
  - ".kb/systems/security/jvm-key-handling-patterns.md"
  - ".kb/systems/security/encryption-key-rotation-patterns.md"
  - ".kb/patterns/concurrency/shared-rwlock-bracketing-facade-close-atomicity.md"
  - ".kb/patterns/resource-management/defensive-copy-accessor-defeats-zeroize-on-close.md"
  - ".kb/patterns/testing/stale-test-after-exception-type-tightening.md"
  - ".kb/patterns/resource-management/atomic-move-vs-fallback-commit-divergence.md"
decision_refs:
  - "three-tier-key-hierarchy"
  - "dek-scoping-granularity"
  - "kms-integration-model"
  - "tenant-key-revocation-and-external-rotation"
  - "aad-canonical-encoding"
spec_refs:
  - "encryption.primitives-lifecycle"
research_status: stable
last_researched: "2026-04-23"
---

# Three-tier key hierarchy foundation

## Shipped outcome

Lands the F41 encryption lifecycle foundation: tenant/domain/DEK identity
records, a pluggable `KmsClient` SPI, a per-tenant sharded on-disk key registry
with atomic commit, HKDF + AES-KWP + AES-GCM primitives with context-bound AAD,
and the tenant-scoped `EncryptionKeyHolder` facade that downstream WD-02
(ciphertext format), WD-03 (DEK lifecycle + rekey), WD-04 (compaction
migration), and WD-05 (runtime concerns) all code against. A full `/audit`
adversarial pass surfaced 95 findings (52 CONFIRMED_AND_FIXED, 40
cascade-impossible, 3 already covered, 1 STILL_VULNERABLE deferred to WD-05)
before ship, with `./gradlew check` green across `jlsm-core` and `jlsm-table`.

Architecturally this replaces the two-tier posture of
`encryption.primitives-lifecycle` R17 (caller KEK → library DEKs) with the
three-tier model mandated by the project's multi-tenant positioning:
**per-tenant root KEK (held by KMS) → per-(tenant, domain) KEK (wrapped by KMS,
cached in the facade) → per-(tenant, domain, table, version) DEK (wrapped by
the domain KEK via AES-GCM with `EncryptionContext` as canonical AAD)**. The
HKDF-hybrid DEK identity (derive bytes from context, store wrapped bytes for
fast open) was chosen over pure-random DEKs to enable registry reconstruction
should a shard be lost — per the `three-tier-key-hierarchy` ADR.

One spec involved:
- `encryption.primitives-lifecycle` v8 APPROVED → v9 DRAFT (R10a, R75, R80a,
  R91 amendments from `/audit`). Promotion via `/spec-verify` pending.

## Key constructs

**New (28) + 1 rename. 22 exported in `jlsm.encryption` / `jlsm.encryption.local`,
6 internal in `jlsm.encryption.internal`.**

### `jlsm.encryption` (exported)

Identity records (6):
- `TenantId` — opaque per-tenant handle; length-masked `toString`
- `DomainId` — data-domain identity within a tenant; public ctor rejects the
  reserved `_wal` sentinel (sanctioned factory `DomainId.forWal()`)
- `TableId` — table identity within a (tenant, domain); length-masked `toString`
- `DekVersion` — monotonically increasing version tag per (tenant, domain, table)
- `KekRef` — opaque reference to a root/domain KEK held by a `KmsClient`
- `DekHandle` — pairs a `WrappedDek` with its resolved `DekVersion`

Enums (2):
- `Purpose` — closed set of AAD purpose codes with pinned persistence codes per
  R80a (1 `DOMAIN_KEK`, 2 `DEK`, 3 `REKEY_SENTINEL`, 4 `HEALTH_CHECK`)
- `TenantFlavor` — `NONE` / `LOCAL` / `EXTERNAL`; selected per-tenant at
  registry creation time

Context factory:
- `EncryptionContext` — purpose-scoped record with factories
  (`forDomainKek`, `forDek`, `forRekeySentinel`, `forHealthCheck`); R80a-1
  required-attrs invariant enforced in the compact constructor; canonical
  wire-form `[4B BE Purpose.code() | 4B BE attr-count | sorted (len|key|len|val) pairs]`
  is the AAD that binds ciphertexts to their scope (AAD canonical encoding ADR)

SPI + results + exceptions (8):
- `KmsClient` — SPI with `wrapKek`, `unwrapKek`, `isUsable`, `close`
- `WrapResult` / `UnwrapResult` — SPI value records
- `KmsException` — abstract `sealed` parent
- `KmsTransientException` — `non-sealed` (callers may extend for provider-specific transients)
- `KmsPermanentException` — `final`
- `KmsRateLimitExceededException` — `final`
- `DekNotFoundException` / `TenantKekUnavailableException` — IOException subclasses

Persistence records (2):
- `WrappedDek` — defensive byte-clone + length-masked `toString` + ≥1-byte
  wrapped-bytes guard; persisted `createdAt` widened to 12 bytes (seconds +
  nanos) to preserve `Instant` precision
- `WrappedDomainKek` — same invariants as `WrappedDek`

Facade (1):
- `EncryptionKeyHolder` (the F41 v6 public facade) + its `Builder` — tenant-
  scoped: `openDomain`, `currentDek`, `resolveDek`, `generateDek`,
  `deriveFieldKey`, `close`

### `jlsm.encryption.local` (exported)

- `LocalKmsClient` — reference `KmsClient` impl. 32B master key from a
  POSIX-0600 file; `ReentrantReadWriteLock` for wrap/unwrap vs close;
  `isProductionReady() → false` per R71b; rejects DEK-purpose contexts
  (tier-2 only); dev/test use only

### `jlsm.encryption.internal` (NOT exported; transitional qualified export to `jlsm.table`)

- `Hkdf` — HKDF-SHA256 extract + multi-counter expand per R16a; length-prefixed
  field-key `info` per R11; DEK ≥16B guard with `System.Logger` WARN below 32B
  per R59/R59a; secret-buffer zeroing in `finally` on both happy and exception paths
- `AesKeyWrap` — RFC 5649 AES-KWP via JCE `AESWrapPad`; 16/24/32-byte KEK
  validation; `SecretKeySpec` null-after-init per R68a
- `AesGcmContextWrap` — AES-GCM with canonical-encoded `EncryptionContext` as
  AAD; tag-vs-infra error classification split
  (`AEADBadTagException → IllegalArgumentException`; other `GeneralSecurityException →
  IllegalStateException`)
- `ShardPathResolver` — deterministic `tenantId` → base32 RFC-4648-URL-safe-
  no-padding filesystem path; temp-file suffix path-traversal guard
- `ShardStorage` — log-structured single file per tenant with `KRSH` magic +
  version + canonical sorted emission + CRC-32C trailer; `CREATE_NEW` temp +
  `force(true)` + parent-dir fsync + `ATOMIC_MOVE`; POSIX 0600 at create-time;
  strict UTF-8 decoding (`REPORT` mode); orphan-temp recovery with CRC +
  version-tiebreaker including `activeTenantKekRef`; HashMap size bounded
  against file remaining-bytes to defeat OOM amplification;
  `int`-overflow in `estimateSize` surfaces as `IOException`
- `KeyRegistryShard` — immutable record with defensive map copy, salt clone,
  and package-private `zeroizeSalt()` that writes the authoritative array
  (not a defensive clone)
- `TenantShardRegistry` — per-tenant `ReentrantLock` for writers +
  `AtomicReference` wait-free reader per R64; cross-tenant isolation per R82a;
  thread-local re-entry guard on `updateShard`; require-open gates close
  `readSnapshot` / `updateShard` races after close; mutator-returned shard
  `tenantId` validated against routing key
- `OffHeapKeyMaterial` — **renamed** from the pre-existing
  `jlsm.encryption.EncryptionKeyHolder` (the old name collided with the new
  F41 v6 facade). 7 main call-sites + 22 test call-sites updated

## API change the caller sees

```java
// New tenant-scoped facade — the API WD-02..WD-05 codes against
try (var holder = EncryptionKeyHolder.builder()
        .tenantId(TenantId.of("tenant-a"))
        .tenantFlavor(TenantFlavor.LOCAL)
        .kmsClient(localKmsClient)
        .shardStoragePath(registryRoot)
        .hkdfSalt(salt32Bytes)      // ≥ 32B enforced; default 32 zero bytes
        .cacheTtl(Duration.ofMinutes(30))  // ≤ 24h enforced
        .clock(clock)
        .build()) {

    // Tier 2: domain KEK (wrapped-by-root, unwrapped-on-demand, cached)
    holder.openDomain(DomainId.of("orders"));

    // Tier 3: DEK generation and reuse
    var handle = holder.generateDek(DomainId.of("orders"), TableId.of("line_items"));
    var current = holder.currentDek(DomainId.of("orders"), TableId.of("line_items"));
    var byVersion = holder.resolveDek(DomainId.of("orders"), TableId.of("line_items"), DekVersion.of(1));

    // Optional field-key derivation via HKDF (length-prefixed info)
    var fieldKey = holder.deriveFieldKey(handle, "email");
}
```

Caller-visible guarantees:
- Per-tenant KMS isolation is **always-on** when encryption is enabled
  (`tenantFlavor != NONE`). There is no single-tenant collapse path — the
  three-tier hierarchy is universal (per `three-tier-key-hierarchy` ADR).
- Three flavors — `none`, `local`, `external` — selectable per-tenant.
- Domain KEK cache TTL is **≤ 24h** (prevents `Instant.plus` overflow); default
  **30 min** per the `kms-integration-model` ADR.
- HKDF salt **≥ 32B** when caller-supplied; default is 32 zero bytes persisted
  in the shard.
- `_wal` `DomainId` is **reserved**: the public ctor rejects it;
  `DomainId.forWal()` is the sanctioned factory (prevents accidental collision
  with the separate F42 WAL encryption domain).
- `EncryptionKeyHolder.close()` takes the write-lock of `deriveGuard`
  (`ReentrantReadWriteLock`); all cache-touching public methods bracket under
  the read-lock, so no operation can observe a partially-closed facade.

## Cross-references

**ADRs consulted / governed by:**
- [`three-tier-key-hierarchy`](../../../.decisions/three-tier-key-hierarchy/adr.md)
  — tier count, data-domain boundary, always-on posture, HKDF-hybrid DEK identity
- [`dek-scoping-granularity`](../../../.decisions/dek-scoping-granularity/adr.md)
  — per-(tenant, domain, table, version) scope
- [`kms-integration-model`](../../../.decisions/kms-integration-model/adr.md)
  — cache TTL default, SPI shape, per-tenant isolation
- [`tenant-key-revocation-and-external-rotation`](../../../.decisions/tenant-key-revocation-and-external-rotation/adr.md)
  — tenant revocation path + `TenantKekUnavailableException` surface
- [`aad-canonical-encoding`](../../../.decisions/aad-canonical-encoding/adr.md)
  — canonical `EncryptionContext` wire-form used as AAD (landed in parallel,
  surfaced during `/audit`)

**KB entries used / created:**
- Used during authoring:
  [`three-level-key-hierarchy`](../../systems/security/three-level-key-hierarchy.md),
  [`three-level-key-hierarchy-detail`](../../systems/security/three-level-key-hierarchy-detail.md),
  [`jvm-key-handling-patterns`](../../systems/security/jvm-key-handling-patterns.md),
  [`encryption-key-rotation-patterns`](../../systems/security/encryption-key-rotation-patterns.md)
- Created during audit (2026-04-23):
  [`dek-caching-policies-multi-tenant`](../../systems/security/dek-caching-policies-multi-tenant.md),
  [`dek-revocation-vs-rotation`](../../systems/security/dek-revocation-vs-rotation.md),
  [`shared-rwlock-bracketing-facade-close-atomicity`](../../patterns/concurrency/shared-rwlock-bracketing-facade-close-atomicity.md),
  [`defensive-copy-accessor-defeats-zeroize-on-close`](../../patterns/resource-management/defensive-copy-accessor-defeats-zeroize-on-close.md),
  [`stale-test-after-exception-type-tightening`](../../patterns/testing/stale-test-after-exception-type-tightening.md)

## Adversarial pipeline summary

| Phase | Findings | Applied |
|-------|----------|---------|
| `/feature-test` (TDD) | 323 tests (120 WU-1 + 80 WU-2 + 67 WU-3 + 41 WU-4 + 15 pre-existing `OffHeapKeyMaterial`) | baseline |
| Adversarial hardening (test-side) | ~260 tests across 6 adversarial test classes | baseline |
| `/audit` run-001 | 95 findings across 6 lenses | 52 CONFIRMED_AND_FIXED, 40 cascade-impossible (29 Phase-0 cascades — one `deriveGuard` fix cascade-solved ~10), 3 already covered, 1 STILL_VULNERABLE |

**581 total tests for WD-01 scope.** No `FIX_IMPOSSIBLE` findings. 5 pre-existing
stale tests updated (exception-type narrowing; temp suffix path-traversal guard;
widened timestamp parser). 6 cross-domain bug chains identified and fused.

Audit lens coverage: concurrency, contract-boundaries, data-transformation,
dispatch-routing, resource-lifecycle, shared-state.

## Noteworthy constraints and pitfalls

- **Three-tier is always-on when encryption is enabled.** There is no
  single-tenant collapse path. Callers that do not want encryption set
  `TenantFlavor.NONE`. `TenantFlavor.LOCAL` and `TenantFlavor.EXTERNAL` both
  require a working `KmsClient` and a domain KEK in the registry before any
  `openDomain` call will succeed. Ops visibility: rotation at tier N never
  forces re-encryption at tier N+1 (root rotation re-wraps O(domains); domain
  rotation re-wraps O(DEKs); only DEK rotation touches data — WD-04).
- **`Purpose` codes are a persistence format.** Codes `1/2/3/4` are pinned and
  appear in canonical-AAD bytes that persist in `WrappedDek.ciphertext`.
  Reordering or renumbering is a breaking format change. New purposes append
  only.
- **`EncryptionContext` canonical AAD encoding.** Length-prefixed sorted
  attribute pairs — `tenant=a,table=bc` vs `tenant=ab,table=c` collide under
  naive concatenation. The spec authored in parallel
  (`aad-canonical-encoding`) pins the wire-form; any caller constructing AAD
  manually MUST use the canonical encoding.
- **`EncryptionKeyHolder` close atomicity.** `deriveGuard`
  (`ReentrantReadWriteLock`) brackets all cache-touching public methods under
  the read-lock; `close()` takes the write-lock and then accumulates any
  deferred-close exceptions. This pattern is captured in the
  [`shared-rwlock-bracketing-facade-close-atomicity`](../../patterns/concurrency/shared-rwlock-bracketing-facade-close-atomicity.md)
  KB entry for reuse in WD-05 and beyond.
- **`KeyRegistryShard.zeroizeSalt()` writes the authoritative salt array, not a
  defensive clone.** A naive implementation that defensively cloned on the
  accessor would silently defeat zeroize-on-close. Captured in
  [`defensive-copy-accessor-defeats-zeroize-on-close`](../../patterns/resource-management/defensive-copy-accessor-defeats-zeroize-on-close.md).
- **`TenantShardRegistry` reader path is wait-free.** Readers `get()` on an
  `AtomicReference<KeyRegistryShard>` and do not acquire the per-tenant writer
  lock. Writers (CAS update) serialize under the per-tenant `ReentrantLock`
  and replace the reference. This is the pattern from
  `dek-caching-policies-multi-tenant`.
- **`ShardStorage` atomic commit has 4 sync points.** `CREATE_NEW` temp file +
  `force(true)` on data + parent-dir fsync + `ATOMIC_MOVE` rename + POSIX 0600
  at create-time (not post-rename — a chmod-after-move window is exploitable).
  Matches the pattern in
  [`atomic-move-vs-fallback-commit-divergence`](../../patterns/resource-management/atomic-move-vs-fallback-commit-divergence.md)
  but with a stricter perm-at-create requirement.
- **`AesGcmContextWrap` error classification is load-bearing.**
  `AEADBadTagException → IllegalArgumentException` (caller-provided ciphertext
  or AAD is wrong); other `GeneralSecurityException → IllegalStateException`
  (infrastructure broken). Collapsing both into one exception type was an
  audit-resolved bug — the caller cannot distinguish "your request is bad"
  from "the JCE provider is broken" without this split.
- **`LocalKmsClient` is dev/test only (`isProductionReady() → false`).**
  Production deployments MUST plug in an external KMS implementation.
  `LocalKmsClient` rejects DEK-purpose contexts (tier-2 only) — domain KEK
  wrap/unwrap is the only operation it services.
- **Transitional qualified export.** `exports jlsm.encryption.internal to
  jlsm.table;` is a temporary bridge for `jlsm-table`'s current field-key
  derivation code. The qualified export must be removed in WD-02+ once
  `jlsm-table` migrates to the public `EncryptionKeyHolder` facade.

## Prior art displaced (from this feature group)

- **`jlsm.encryption.EncryptionKeyHolder` (old)** → renamed to
  `jlsm.encryption.internal.OffHeapKeyMaterial`. The new
  `jlsm.encryption.EncryptionKeyHolder` is the F41 v6 facade. 7 main call-sites
  + 22 test call-sites updated. No `@spec` annotations were invalidated — the
  old class's annotations were against variant-internal primitives lifecycle,
  which now live under the `OffHeapKeyMaterial` name.
- **Two-tier hierarchy (`encryption.primitives-lifecycle` R17)** — amended
  in-place to three-tier (per the `feedback_spec_inplace_amendment` feedback:
  partial displacement, bump version, strike-through invalidated Rs, keep impl
  `@spec` annotations valid). v8 APPROVED → v9 DRAFT pending `/spec-verify`
  promotion.

## Related work definitions (same work group)

- WD-02 `encryption.ciphertext-envelope` — BLOCKED on WD-01 (now unblocked).
  Will consume `WrappedDek`, `DekVersion`, `EncryptionContext`, and the
  `EncryptionKeyHolder` facade to produce on-disk ciphertext with DEK-version
  tag and scope metadata (R22, R23a).
- WD-03 `encryption.dek-lifecycle-and-rotation` — BLOCKED on WD-01, WD-02.
  WD-01 ships `generateDek` (R29); WD-03 will add prune (R30), rotation
  cascade (R32), three-state machine (R76), and rekey API (R78).
- WD-04 `encryption.compaction-migration` — BLOCKED on WD-03. Will consume
  DEK lifecycle to re-encrypt during compaction (R25-R28, R38-R43).
- WD-05 `encryption.runtime-concerns` — BLOCKED on WD-03. Covers leakage
  profile (R44-R54), encrypt-once invariant runtime (R72-R75d), and the LRU
  cache eviction that resolves the sole STILL_VULNERABLE audit finding
  (Arena allocation leak on concurrent domain open — bounded by cache size,
  zeroed on close, scope-deferred here).

## Follow-up

- Spec v9 is DRAFT; `/spec-verify` needed to promote to APPROVED.
- 2 `[UNVERIFIED]` annotations in spec v9: RFC 5869 §3.1 HashLen framing, 24h
  `cacheTtl` bound ADR rationale. Resolve before promotion.
- 1 STILL_VULNERABLE audit finding deferred to WD-05 (Arena allocation leak
  on concurrent domain open — requires LRU cache eviction, which is WD-05's
  territory per `kms-integration-model` ADR).
- Transitional qualified export `jlsm.encryption.internal to jlsm.table` —
  remove in WD-02+ once `jlsm-table` migrates to the public facade.
- Zero third-party dependencies added to the build (pure Java 25 + JCE +
  Panama FFM).
