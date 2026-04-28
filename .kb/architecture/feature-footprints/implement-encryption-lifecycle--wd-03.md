---
title: "Feature footprint — DEK lifecycle + KEK rotation + revocation + rekey + polling (implement-encryption-lifecycle WD-03)"
aliases:
  - "encryption WD-03 feature footprint"
  - "implement-encryption-lifecycle WD-03 footprint"
  - "encryption-lifecycle wd-03 retrospective"
type: feature-footprint
tags: [feature-footprint, encryption, dek-lifecycle, kek-rotation, revocation, rekey, three-state-machine, kms-failure, liveness-witness, polling, multi-tenant, cross-module-spi, retrospective, wd-03]
feature_slug: implement-encryption-lifecycle--wd-03
work_group: implement-encryption-lifecycle
shipped: 2026-04-27
domains:
  - encryption
  - engine
  - sstable
  - wal
  - dek-lifecycle
  - kek-rotation
  - revocation-loud-fail
  - kms-failure-classification
  - convergence-detection
constructs:
  - "DekVersionRegistry"
  - "ShardLockRegistry"
  - "RotationMetadata"
  - "DekPruner"
  - "CompactionInputRegistry"
  - "WalLivenessSource"
  - "TenantKekRotation"
  - "DomainKekRotation"
  - "RetiredReferences"
  - "TenantState"
  - "TenantStateMachine"
  - "TenantStateProgress"
  - "KmsErrorClassifier"
  - "UnclassifiedErrorEscalator"
  - "KmsObserver"
  - "EventCategory"
  - "RetryDisposition"
  - "KekRevokedException"
  - "TenantKekRevokedException"
  - "DomainKekRevokedException"
  - "RegistryStateException"
  - "R83ExceptionFactory"
  - "KekRefDescriptor"
  - "DomainKekTombstone"
  - "RevokedDekCache"
  - "PermanentlyRevokedDeksSet"
  - "RegistryShredConfirmation"
  - "WrapRoundtripVerifier"
  - "RekeyProgress"
  - "RekeyCompleteMarker"
  - "LivenessWitness"
  - "ContinuationToken"
  - "RekeySentinelVerifier"
  - "RekeySentinel"
  - "ConvergenceState"
  - "ConvergenceTracker"
  - "ConvergenceRegistration"
  - "ManifestCommitNotifier"
  - "KmsPoller"
  - "PollingScheduler"
  - "DeployerInstanceId"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/encryption/**"
  - "modules/jlsm-core/src/main/java/jlsm/encryption/internal/**"
  - "modules/jlsm-core/src/main/java/jlsm/encryption/spi/**"
  - "modules/jlsm-core/src/main/java/jlsm/encryption/local/LocalKmsClient.java"
  - "modules/jlsm-core/src/main/java/module-info.java"
related:
  - ".kb/architecture/feature-footprints/implement-encryption-lifecycle--wd-01.md"
  - ".kb/architecture/feature-footprints/implement-encryption-lifecycle--wd-02.md"
  - ".kb/processes/parallel-tdd/parallel-coordinator-check-discipline.md"
  - ".kb/systems/security/encryption-key-rotation-patterns.md"
  - ".kb/systems/security/dek-revocation-vs-rotation.md"
  - ".kb/systems/security/dek-caching-policies-multi-tenant.md"
  - ".kb/systems/security/three-level-key-hierarchy.md"
decision_refs:
  - "three-tier-key-hierarchy"
  - "encryption-key-rotation"
  - "dek-scoping-granularity"
  - "aad-canonical-encoding"
  - "tenant-key-revocation-and-external-rotation"
  - "kms-integration-model"
  - "index-access-pattern-leakage"
spec_refs:
  - "encryption.primitives-lifecycle"
  - "encryption.ciphertext-envelope"
  - "sstable.footer-encryption-scope"
  - "encryption.primitives-key-holder"
  - "encryption.primitives-dispatch"
research_status: stable
last_researched: "2026-04-27"
sources:
  - url: ".spec/domains/encryption/primitives-lifecycle.md"
    title: "encryption.primitives-lifecycle v13 DRAFT — DEK lifecycle + KEK rotation + revocation"
    accessed: "2026-04-27"
    type: "spec"
  - url: ".feature/implement-encryption-lifecycle--wd-03/work-plan.md"
    title: "WD-03 work plan — 8 WUs, 43 constructs, balanced execution"
    accessed: "2026-04-27"
    type: "docs"
  - url: ".feature/implement-encryption-lifecycle--wd-03/cycle-log.md"
    title: "WD-03 cycle log — coordinator integration-fix + Batch 4 OOM lesson"
    accessed: "2026-04-27"
    type: "docs"
---

# DEK lifecycle + KEK rotation + revocation + rekey + polling

## Shipped outcome

WD-03 of the F41 encryption lifecycle, layered onto WD-01 (three-tier key
hierarchy) and WD-02 (ciphertext envelope + footer scope). Implements DEK
lifecycle (R29-R31), KEK rotation cascade (R32-R34a), the three-state
KMS-failure machine (R76-R77), the rekey API + on-disk liveness witness
(R78), opt-in polling (R79), and DEK revocation read-path semantics
(R83). Discharges open obligation `implement-f41-lifecycle` on
`encryption.primitives-lifecycle` (v10 → v13 DRAFT, 90 findings applied
across Pass 2/3/4 during `/work-plan`). Unblocks WD-04 (compaction-driven
migration) and WD-05 (runtime cache concerns).

## Key constructs (43 new + 6 extensions, 8 WUs)

Exported in `jlsm.encryption` and the new `jlsm.encryption.spi`; internal
in `jlsm.encryption.internal`.

- **WU-1 Foundation (3):** `DekVersionRegistry` (wait-free CoW per R64),
  `ShardLockRegistry` (`StampedLock` shared/exclusive deadline-bounded —
  R32c/R34a), `RotationMetadata` (pinned at rotation start per R37b-1).
- **WU-2 DEK Lifecycle (4):** `DekPruner` (R30-R30c snapshot under shared
  read lock, R31 zeroize), `CompactionInputRegistry` SPI,
  `WalLivenessSource` SPI, `EncryptionKeyHolder.generateDek` extension
  (roundtrip-verify outside shard lock per P4-22).
- **WU-3 KEK Rotation (4):** `TenantKekRotation` (R32a streaming +
  R32c 250 ms max-hold), `DomainKekRotation` (R32b/R32b-1 tier-2
  exclusive), `RetiredReferences` (R33/R33a grace-gated retention),
  `ShardStorage` v1→v2 wire format (`KeyRegistryShard` 5→8 components).
- **WU-4 Three-State Failure Machine (7):** `TenantState` enum, atomic-CAS
  `TenantStateMachine` (R83c-1 transitioning-read), durable
  `TenantStateProgress` (R76b-1a), `KmsErrorClassifier` (R76a-1),
  `UnclassifiedErrorEscalator` (R76a-2), `KmsObserver` SPI +
  `EventCategory` (R76b-1).
- **WU-5 Read-Path Revocation (12):** sealed exception subtree
  (`RetryDisposition.NonRetryable`, sealed `KekRevokedException`,
  `RegistryStateException` with `JLSM-ENC-83B2-*`/`83I2-*` codes),
  `R83ExceptionFactory` (R83h dual-instance — enforced by classfile walk,
  see Test surface), `KekRefDescriptor` (R83i-1 opaque),
  `DomainKekTombstone` (R83b-1a epoch reclamation, **48h hard max**
  P4-1), `RevokedDekCache` (R83g + R83a dedup),
  `PermanentlyRevokedDeksSet` (R83g, 10K bound, P4-28),
  `RegistryShredConfirmation` (single-use 16-byte nonce + replay bloom),
  `WrapRoundtripVerifier` (R83e/R83e-1 outside shard lock).
- **WU-6 Rekey API + Liveness Witness (6):**
  `EncryptionKeyHolder.rekey(...)` extension (R78, paginated, resumable),
  `RekeyProgress` (durable `rekey-progress.bin`, >24h stale detection),
  `RekeyCompleteMarker` (R78f), `LivenessWitness` (per-tenant durable
  monotonic counter — R78e), `ContinuationToken`,
  `RekeySentinelVerifier` (R78a 5 min freshness).
- **WU-7 Convergence Detection (4):** `ConvergenceState` (sealed enum,
  REVOKED terminal), `ConvergenceTracker` (R37b WeakRef + R83d
  revocation-suppresses), `ConvergenceRegistration` (R37b-3 idempotent
  close), `ManifestCommitNotifier` SPI.
- **WU-8 Opt-In Polling (3):** `KmsPoller` (per-tenant virtual thread,
  default-on for flavor-3 R79d), `PollingScheduler` (HMAC-SHA256 jitter
  R79c-1, 100 polls/sec aggregate cap R79c), `DeployerInstanceId`
  (≥256-bit entropy P4-12).
- **Existing extensions (6):** `KmsException extends IOException` (was
  `Exception`); `KmsPermanentException sealed permits KekRevokedException`
  (was `final`); `DekNotFoundException` and `TenantKekUnavailableException`
  → `RetryDisposition.NonRetryable`; `LocalKmsClient` adds R71b-1
  simulation methods (`StackWalker`-gated); `module-info.java` exports
  `jlsm.encryption.spi`.

## Cross-module SPI seams introduced

Three SPIs in `jlsm.encryption.spi` ship with in-memory fakes today;
production wiring is deferred:

- `ManifestCommitNotifier` — convergence post-commit hook (manifest
  module wires this when it lands).
- `CompactionInputRegistry` — in-flight compaction input set tracking
  (WD-04 wires this).
- `WalLivenessSource` — per-tenant retired-`KekRef` artifact dependency
  count (WAL retention work wires this).

Deliberate seam strategy: ship contract + fake now, let downstream
modules adopt without re-opening WD-03's spec surface.

## Caller-visible guarantees

- DEK revocation is **loud-fail**: `KekRevokedException`
  (`NonRetryable`) named by stable error code; no silent success.
- KEK rotation is non-blocking for payloads — DEKs are re-wrapped, payload
  rewrap is deferred to compaction (WD-04). `RetiredReferences` retention
  is gated by `LivenessWitness` reaching zero plus configured grace.
- `forceShredRegistry` requires a single-use 16-byte
  `RegistryShredConfirmation` (replay-protected via in-process bloom).
- Rekey is resumable across crashes via durable `rekey-progress.bin`;
  >24h gap between advances is detected as stale.
- `R83ExceptionFactory` is the **only** legitimate construction surface
  for revocation exceptions — the architectural enforcement test fails
  the build if production code constructs them directly.

## Cross-references

**Governing ADRs (all confirmed pre-WD-03):** `three-tier-key-hierarchy`,
`encryption-key-rotation`, `dek-scoping-granularity`,
`aad-canonical-encoding`, `tenant-key-revocation-and-external-rotation`,
`kms-integration-model`, `index-access-pattern-leakage`.

**Spec amended:** `encryption.primitives-lifecycle` v10 → v13 DRAFT;
obligation `implement-f41-lifecycle` discharged.

**Sibling features:**
- [WD-01 — three-tier key hierarchy](implement-encryption-lifecycle--wd-01.md)
- [WD-02 — ciphertext envelope + footer scope](implement-encryption-lifecycle--wd-02.md)
- WD-04 `encryption.compaction-migration` — DRAFT, now unblocked. Will
  consume `ConvergenceTracker` + `RetiredReferences` for payload
  re-encryption.
- WD-05 `encryption.runtime-concerns` — DRAFT, now unblocked. Will absorb
  DEK cache eviction + leakage profile + encrypt-once invariant runtime.

## Test surface

**456 new tests** across 8 WUs (50 / 40 / 57 / 81 / 59 / 71 / 51 / 47).
Notable:

- **`R83ExceptionFactoryEnforcementTest`** — walks the production
  classpath via `java.lang.classfile` to assert no class outside
  `R83ExceptionFactory` directly constructs `TenantKekRevokedException`
  / `DomainKekRevokedException` (R83h dual-instance discipline).
  **Pattern worth lifting** — architectural enforcement of "construction
  must go through factory X" via classfile bytecode walking, applicable
  to any sealed-construction-discipline case where private constructors
  aren't structurally reachable.
- `ShardStorageV2WireFormatTest` — v1 → v2 migration on load + cross-version
  roundtrip.
- Concurrency stress: `DekVersionRegistry` 50K-iteration CoW publish
  race; `ShardLockRegistry` deadline-bounded exclusive contention;
  `TenantStateMachine` transitioning-read CAS race.
- `WrapRoundtripVerifier` asserts roundtrip + verify executes outside
  shard lock and that plaintext copy is zeroised after verification.
- `DomainKekTombstone` quiescence barrier upper bounds (24h ceiling, 48h
  forced-zero).
- `:tests:jlsm-remote-integration:test` (S3Mock) green — no remote-backend
  regressions.

## Adversarial findings + retro lessons

- **[`parallel-coordinator-check-discipline`](../../processes/parallel-tdd/parallel-coordinator-check-discipline.md)**
  — post-Batch-2 `./gradlew :modules:jlsm-core:check` revealed 4
  integration breaks not caught by per-WU `:test`: planner-subagent
  `non-sealed` relaxation of `KmsPermanentException` looser than R83-1
  P4-30 mandated; pre-existing test asserting old `final` shape;
  anonymous-subclass test illegal under sealing; unused imports failing
  checkstyle. Per-WU `:test` is fast feedback; coordinator's `:check` is
  the truth.
- **Concurrent-OOM during 3-way parallel `:check`** — running 3 TDD
  subagents + their full test suites simultaneously OOM-killed
  `SharedStateAdversarialTest` 50K-thread races. Coordinator's
  single-threaded `:check --rerun` was green; OOMs were environmental,
  not regressions. Captured in
  `parallel-coordinator-check-discipline`: cap concurrent units to ≤ 2
  in heavy projects, or have subagents run `:test` only and let the
  coordinator do post-batch single-threaded `:check`.

## Pitfalls

- `R83ExceptionFactory` is enforced by classfile walk, **not by sealing
  alone** — sealing prevents subclasses, not direct construction.
- `KmsPermanentException` is `sealed permits KekRevokedException`, not
  `non-sealed`; new permanent faults must extend `KekRevokedException`.
- The three `jlsm.encryption.spi` interfaces are **stub-wired only**
  today — do not assume they are observable in production pipelines yet.
- `DomainKekTombstone` 48h forced-zero ceiling is asserted; relaxing
  requires spec re-amendment.

## Follow-up

- Spec promotion: `encryption.primitives-lifecycle` v13 DRAFT pending
  `/spec-verify` promotion to APPROVED.
- Production wiring of the three SPIs (`ManifestCommitNotifier`,
  `CompactionInputRegistry`, `WalLivenessSource`) by their respective
  downstream modules.
- Coordinator-skill update to require `:check` (not `:test`) before
  COMPLETE return — see linked process pattern.
