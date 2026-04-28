---
{
  "id": "encryption.primitives-lifecycle.rotation-lifecycle",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [
    "encryption"
  ],
  "requires": [],
  "invalidates": [],
  "decision_refs": [],
  "kb_refs": [],
  "parent_spec": "encryption.primitives-lifecycle",
  "_split_from": "encryption.primitives-lifecycle"
}
---

# encryption.primitives-lifecycle.rotation-lifecycle — DEK/KEK Rotation, Revocation, Rekey, and Polling

This spec was carved from `encryption.primitives-lifecycle` during a domain subdivision. The
parent retains cross-cutting requirements that span multiple sub-domains;
the requirements below are specific to this concern.

R29. A new DEK must be generated for a `(tenantId, domainId, tableId)` scope by: producing 32 bytes from a SecureRandom instance, wrapping the DEK with the domain KEK using AES-GCM with the tenant+domain encryption context as AAD (R87), assigning the next sequential version number within the scope (R18), and persisting the wrapped entry to the per-tenant registry shard (R19).

R30. A DEK version may be pruned from the registry only when no live SSTable in the manifest references that DEK version within its scope. The pruning check must scan the manifest's SSTable list filtering by `(tenantId, domainId, tableId)`. Premature pruning must be prevented: if any SSTable still contains ciphertext tagged with that DEK version in the same scope, the DEK must remain.

R30a. DEK pruning must not remove a DEK version while any in-flight compaction task references an SSTable containing that version. The pruning check must consider both the live manifest and the set of SSTables currently being compacted (the compaction input set).

R30b. The pruning scan must operate on a consistent snapshot of the manifest. If the manifest uses an atomic-swap update model, the scan must read a single snapshot and not interleave with concurrent manifest mutations.

R30c. The pruning operation must read the manifest snapshot and the in-flight compaction input set as a single atomic operation. The implementation must hold a read lock that prevents new compaction tasks from registering inputs between the manifest snapshot read and the compaction input set read. Without this atomicity guarantee, a new compaction could register an SSTable referencing a DEK version that the pruning scan has already determined is unreferenced.

R31. When a DEK entry is pruned from the registry, the unwrapped key material (if held in memory) must be zeroed before release. The wrapped key material must be removed from the persisted registry shard in a subsequent atomic shard update.

### KEK rotation (cascading lazy rewrap)

R32. KEK rotation at any tier must follow the cascading lazy rewrap model per `three-tier-key-hierarchy` ADR: a rotation at tier N produces a new tier-N reference; wrapped entries at tier N+1 are rewrapped opportunistically on next access (not synchronously). No rotation imposes a global barrier or synchronous O(domains) or O(DEKs) rewrap.

R32a. **Tenant KEK rotation** (tier 1, flavor 3: tenant-driven via `rekey` API from `tenant-key-revocation-and-external-rotation` ADR): on invocation, re-wraps the tenant's domain KEKs under the new Tenant KEK in streaming per-shard batches (R78b). Does not re-wrap DEKs (tier 3) or touch data on disk. Domain KEK blobs remain valid under both old and new Tenant KEK during the migration window (dual-reference per R78d).

R32b. **Domain KEK rotation** (tier 2): on invocation, unwraps the domain KEK from the Tenant KEK, generates a fresh domain KEK, re-wraps every DEK within the rotating domain under the new domain KEK, and persists the updated shard. DEK cipher material on disk is not touched. This is O(DEKs-in-domain), bounded by the domain's size.

R32b-1. Domain KEK rotation must hold an exclusive lock scoped to the specific `(tenantId, domainId)` being rotated. The lock must cover **every DEK entry in the rotating domain** — concurrent DEK creation (R29) for the rotating `(tenantId, domainId)` must be rejected or queued for the rotation's duration. DEK creation in other `(tenantId, domainId)` scopes (same tenant, different domain; different tenant entirely) must not be blocked by this lock.

R32c. Streaming rekey per R32a / R78b must release the exclusive shard lock between batches. Each batch must complete within a configurable max-hold-time budget (default 250 ms). If a batch does not complete within the budget, the implementation must split the batch, commit the processed prefix, release the lock, and reacquire before continuing. This prevents rotation from starving DEK creation (R34a shared lock) for unbounded time.

R33. After any tier rotation, the retired reference must be added to a retired-references set in the registry. The retired reference must not be deleted from the KMS or zeroized in memory until the rewrap cascade has completed for at least one access cycle — old wrapped entries require the old reference to unwrap during the migration window.

R33a. For Tenant KEK rotation (flavor 3), the retired-reference retention window must exceed WAL retention per the grace-period invariant from `three-tier-key-hierarchy` ADR. Otherwise, unreplayed WAL segments that were encrypted under the old domain KEK (which was wrapped under the old Tenant KEK) become undecryptable.

R34. Rotation at any tier must be atomic with respect to the per-tenant registry shard it writes to: either all wrapped entries at that shard are updated, or none. A crash during rotation must leave the shard in a consistent state with the previous state still usable.

R34a. Rotation must hold an exclusive lock on the specific registry shard being modified for the duration of the shard-scoped update. Concurrent DEK creation within the same shard must acquire a shared lock; rotation must acquire an exclusive lock. Rotations in different tenants' shards must not lock each other (per-tenant isolation).

### Dual-read during rotation window

R35. During a key rotation window (when SSTables contain ciphertext under both old and new DEK versions, or wrapped entries exist under both old and new KEK references), every read must be able to decrypt using any DEK version and unwrap via either retired or current KEK reference present in the registry. The reader must not assume all ciphertext uses the current DEK.

R36. DET-encrypted indexed fields must be marked as "rotation-pending" when a new DEK version is activated and the previous version is still referenced by live SSTables. During the rotation-pending window, queries on DET/OPE-indexed encrypted fields may return incomplete results. This limitation must be documented on the rotation API.

R37. After rotation converges (no live SSTables reference old DEK versions within the scope), DET/OPE indices affected by the rotation must be rebuilt. The library must provide a mechanism to detect convergence and trigger index rebuild.

R37a. Convergence detection (R37) must trigger within a bounded wall-clock delay after the last live SSTable referencing the rotated-away DEK version is removed from the manifest. The bound must be (a) configurable in the range `[100ms, 24h]`, (b) defaulted to the configured manifest-update cycle plus a 5-minute slack window to absorb tail latency in remote-backend manifest commits, and (c) observable: implementations must emit a `R37aBoundApproaching` structured event at the configured bound minus 10% (carrying the elapsed wall-clock time) and a `R37aBoundExhausted` event at the bound. After the bound, registered consumers (R37b) must receive an explicit timeout signal — silent hang is forbidden. The 24h ceiling matches R91's cache TTL ceiling so that no encryption-system delay exceeds a known operational bound.

R37b. Consumers awaiting convergence (e.g., DET/OPE index rebuild) must be able to register interest before convergence occurs and must be notified when convergence happens. Registration must return an `AutoCloseable` handle whose `close()` removes the registration. Implementations must hold consumer references via `WeakReference` (or equivalent) so a consumer leaked by its owner does not pin the library; weakly-held registrations cleared by the GC must be silently dropped from the notification set. Registrations are in-process state and do not survive `EncryptionKeyHolder.close()` (R66) or process restart — `close()` must drop all pending registrations atomically. Internal polling, if used as the implementation strategy, must be bounded with cadence in `[100ms, 60s]`; default cadence is `min(60s, manifest-update-cycle / 10)` when the manifest publishes its update-cycle metric, otherwise 5 seconds. Implementations that subscribe to R37c's manifest commit-completion hook must not poll — the hook is the primary signal; polling is a fallback only when the hook surface is unavailable. External consumers must not be required to poll the manifest themselves.

R37b-1. Implementations must additionally expose a synchronous query — `ConvergenceState convergenceStateFor(scope, oldDekVersion)` — returning the current convergence state for a given `(tenantId, domainId, tableId, oldDekVersion)` tuple. `ConvergenceState` is a sealed enum with values `{PENDING, CONVERGED, TIMED_OUT, REVOKED}`:

- `PENDING` — at least one live SSTable in the manifest still references the version, AND R37a's bound has not been exceeded since rotation start.
- `CONVERGED` — no live SSTable in the manifest references the version (the convergence trigger fired or would fire on next manifest scan).
- `TIMED_OUT` — at least one live SSTable still references the version, AND the wall-clock time elapsed since rotation start exceeds **the R37a bound in effect at rotation start** (NOT the currently-configured bound). Per P4-4 fix, the implementation must record the R37a bound alongside the rotation-start timestamp in the registry's rotation metadata. A subsequent dynamic config change to R37a's bound must not retroactively change `TIMED_OUT` classifications for in-flight rotations — rotations started after the new configuration takes effect use the new bound. This state must be derivable from durable state (manifest + rotation-start timestamp + rotation-start bound, all recorded in registry) without process-lifetime memory.
- `REVOKED` — the rotated-from DEK has been observed as permanently revoked per R83g; the convergence event was suppressed per R83d.

**Monotonicity invariant.** `ConvergenceState` is monotonic per `(scope, oldDekVersion)` only in one direction: once the state becomes `REVOKED`, it cannot transition back to any other state. Transitions follow the partial order `PENDING → CONVERGED → REVOKED` and `PENDING → TIMED_OUT → REVOKED`. Consumers must treat `REVOKED` as terminal — observing `CONVERGED` and then `REVOKED` for the same scope means the convergence event was suppressed retroactively per R83d's "revocation is authoritative" rule, and any index-rebuild work begun on the prior `CONVERGED` observation must be aborted (the rebuild target is no longer recoverable).

Consumers registering after convergence has already fired must observe that state via this query rather than via the registration callback. The convergence event for an already-converged tuple must not be replayed via callback, since the library has no per-consumer durable record of which events were already delivered. Consumers responsible for crash recovery must call `convergenceStateFor` on startup before re-registering for the `PENDING` set; consumers observing `TIMED_OUT` must escalate operationally (rotation has stalled and requires investigation).

A deprecated alias `boolean isConvergedFor(scope, oldDekVersion)` returning `state == CONVERGED` is permitted for one minor version to ease migration; new code must use `convergenceStateFor`.

R37b-2. When a weakly-held registration (R37b) is reaped by the GC before its convergence fires, implementations must emit a `convergenceRegistrationDropped` event via `KmsObserver` (per R76b-1's `polling` category — in-process durability). The event payload must include the registered `(tenantId, domainId, tableId, oldDekVersion)` so operators can detect cases where index rebuild was silently abandoned. Consumers that need guaranteed delivery must hold the registration handle in a strongly-referenced field for the registration's full lifetime, or alternatively call `convergenceStateFor` (R37b-1) on a recovery cycle to detect missed convergences.

**Event-payload tenant-visibility discipline (P4-17).** This event — and all `KmsObserver` events that include scope information — must follow the tenant-vs-deployer split paralleling R83h's exception discipline. Each event carries a deployer-only flag `isDeployerInternal: true|false`; if the observability pipeline routes events to a tenant-visible surface, the event payload must redact `domainId`, `tableId`, and `oldDekVersion`, retaining only `tenantId` plus an opaque correlation id. Implementations must construct the redacted variant at event emission time, not rely on downstream filtering — reflective serializers (Jackson, Logback) walk all event fields and could leak the unredacted form. The redacted-variant construction follows the same centralised-factory pattern as R83h's exception construction.

R37b-3. Idempotent silent-no-op `handle.close()` must apply to **all post-registration-end states**, not just the holder-close-driven drop. The handle's `close()` must be a pure no-op once the registration is no longer pending in the holder's set, regardless of which path removed the registration. The exhaustive list of registration-end paths:

- (a) Explicit `handle.close()` — first invocation removes the registration; subsequent invocations no-op.
- (b) `EncryptionKeyHolder.close()` (R66) drops the registration during holder shutdown.
- (c) Convergence fires and the per-event delivery completes the registration's lifecycle (the registration's callback invocation also discards the registration).
- (d) GC reaping of the weakly-held consumer (R37b's WeakReference clearing) — the registration is dropped from the notification set; the handle is now stale.
- Any combination of the above (e.g., GC-reaping followed by explicit `handle.close()`).

In every case, the handle's `close()` is a pure no-op once the registration is non-pending. The handle must not throw `IllegalStateException` because `EncryptionKeyHolder.close()` is the canonical lifecycle endpoint and consumers must not be required to coordinate close ordering with the holder. Implementations must use a per-handle volatile boolean (or AtomicReference to a sealed-state enum) to guard against re-entry; the boolean must observe state transitions atomically with the registration removal so concurrent close-and-deliver paths cannot leak a fired-but-not-removed registration.

R37c. Convergence detection must be wired through the manifest's commit-completion notification surface so the trigger fires synchronously with the manifest commit that removes the last referencing SSTable. The manifest must publish a narrowly-scoped post-commit hook for encryption-layer convergence detectors to subscribe to; convergence detection must not require the manifest to scan SSTable contents for DEK version references. The post-commit subscription is the only sanctioned coupling point between the manifest and the encryption layer for convergence purposes.

### Compaction-driven encryption migration

R76. Per `tenant-key-revocation-and-external-rotation` ADR, each tenant under flavor 3 must have one of three operational states, tracked per-tenant in the `EncryptionKeyHolder`:

- **`healthy`** — Tenant KEK unwrap operations succeed; all reads and writes proceed normally.
- **`grace-read-only`** — N consecutive permanent-class unwrap failures (default N=5, configurable) with jittered exponential backoff (per `kms-integration-model` ADR). Writes are rejected with `TenantKekUnavailableException`; reads continue using cached domain KEKs for their remaining TTL (R91).
- **`failed`** — Grace window exhausts (default 1 hour, configurable), OR all cached domain KEKs have TTL-expired. Reads and writes both rejected until the tenant rekeys to a usable Tenant KEK.

R76a. Permanent-class KMS errors (AccessDenied, KeyDisabled, KeyNotFound, KeyDestroyed) count toward N. Transient-class errors (throttling, timeout, 5xx) do not count toward N; they are retried per the backoff policy of `kms-integration-model` ADR and only escalate to a permanent-class outcome if the retry budget is exhausted.

R76a-1. `KmsClient` plugins must implement the following provider-state-to-class mapping. Plugin authors are responsible for translating provider-specific error types and codes into the canonical class:

| Provider state | Class |
|---|---|
| AWS `AccessDeniedException`, GCP `PERMISSION_DENIED`, Vault 403 | Permanent |
| AWS `DisabledException`, AWS `KMSInvalidStateException(state=Disabled)`, GCP `FAILED_PRECONDITION(state=DISABLED)`, Vault `disabled` | Permanent |
| AWS `NotFoundException`, GCP `NOT_FOUND`, Vault 404 | Permanent |
| AWS `KMSInvalidStateException(state=PendingDeletion)`, GCP `FAILED_PRECONDITION(state=DESTROY_SCHEDULED)` | Permanent (despite reversibility — the read attempt itself cannot succeed; if the operator cancels deletion, opt-in polling per R79 restores the tenant) |
| AWS `KMSInvalidStateException(state=PendingImport)`, AWS `KeyUnavailableException` | Transient |
| AWS `IncorrectKeyException`, AWS `InvalidCiphertextException` | Permanent (ciphertext-key mismatch — never recoverable for that ciphertext) |
| AWS `ThrottlingException`, GCP `RESOURCE_EXHAUSTED`, AWS 5xx, Vault 5xx | Transient |
| Unknown / unclassified `RuntimeException` from plugin | Transient (with degraded confidence — `KmsObserver` must emit a `kmsUnclassifiedError` event at WARN level; jlsm must not count toward N until the error is classified or surfaced via observability so the deployer can update the plugin) — but see R76a-2 for escalation discipline |

`[UNVERIFIED: provider error-code names as of 2026-04 — plugin authors must verify against the SDK version in use; jlsm tightens this table via amendment if upstream renames occur]`

R76a-2. When `kmsUnclassifiedError` events for a single `(tenantId, plugin-class)` exceed a configurable threshold (default 100 events within 60 seconds) without an intervening successful unwrap, the implementation must escalate the failure to permanent-class for that tenant: emit `tenantKekStateTransition(grace-read-only, reason=unclassified-error-storm)` and increment the R76 counter. This bounds the silent-retry duration that an unclassified-RuntimeException-throwing plugin would otherwise produce. The threshold and observation window are configurable per `kms-integration-model` ADR. Implementations must additionally rate-limit `kmsUnclassifiedError` log emissions to at most 1 per second per tenant (deduplicating subsequent occurrences within the second) so a high-frequency plugin bug cannot DDOS the deployer's log pipeline.

R76b. State transitions must be observable: emit `tenantKekStateTransition` structured log events and `tenantKekState` metric (gauge per tenant) via the `KmsObserver` interface (`kms-integration-model` R93).

R76b-1. All `KmsObserver` events must carry an `eventSeq` field unique within the event's `(tenantId, eventCategory)` scope. `eventCategory` is one of:

- `state-transition` — R76b transitions; **durable** (must persist across restart so consumer dedup works post-recovery).
- `rekey` — R78g events; **durable** via the rekey-progress file's `lastEmittedEventSeq` (R78g).
- `polling` — convergence registrations (R37b-2), polling diagnostics; **in-process** (resets on restart).
- `unclassified-error` — R76a-1 unknown-class events; **in-process** (resets on restart).

Each category uses a separate counter. Categories whose events are durably consequential to operator behaviour (`state-transition`, `rekey`) must persist the counter alongside related state (the rekey-progress file for rekey; a dedicated `tenant-state-progress` file for state-transition — see R76b-1a below). Categories that are advisory (`polling`, `unclassified-error`) may use in-process counters. Every event payload must declare its volatility via field `seqDurability ∈ {durable, in-process}` so consumers can apply the appropriate dedup strategy.

**Cross-category ordering (P4-24).** `eventSeq` is monotonic *within* a `(tenantId, eventCategory)` pair but NOT across categories. Consumers cannot order a `state-transition` event relative to a `rekey` event by `eventSeq` alone — the two counters are independent. When ordering across categories matters (e.g., "did the rekey complete before or after the tenant entered grace-read-only?"), consumers must use the wall-clock `timestamp` field that all `KmsObserver` events must include. The timestamp is monotonic within a single process per the JVM clock, but consumers reading durable events post-restart must accept that durable categories restart from `lastEmittedEventSeq + 1` while in-process categories restart from 0 — cross-category ordering of the first post-restart events is not preserved.

R76b-1a. The `tenant-state-progress` file required by R76b-1 must conform to the same shard-discipline invariants as the per-tenant registry shards:

- **Path:** deterministically derivable from `tenantId` per R82b's function family — specifically `<registry-root>/<tenant-shard-dir>/state-progress.bin` for per-domain-file layouts, or as a designated record-type within the tenant log for log-structured layouts. Operational tools (backup, compliance scans) must be able to enumerate all of a tenant's state-progress files from `tenantId` alone without a separate index.
- **Permissions:** owner-only (0600 on POSIX, equivalent on other platforms) per R70a's discipline. Permissions must be set before any state-transition record is written.
- **Atomic commit:** per R20's atomic-commit primitive (write-temp-then-rename, or per-layout equivalent). A crash mid-write must leave the file in a recoverable prior state.
- **Integrity:** CRC-32C trailer per R19a's discipline. On read, a checksum mismatch must throw `IOException` with a stable error code identifying the file. The in-process state machine, when initialising from a CRC-mismatched record per R76b-2, must initialise affected tenants to a conservative state (`failed`) rather than `healthy`, surfacing the integrity failure to operators rather than silently masking it.

Reuse of R20's atomic-commit primitive is intentional — if R20's implementation evolves (e.g., to a log-structured backend), the state-progress file must follow the same evolution. This coupling ensures consistency across all tenant-scoped persistent artifacts.

R76b-2. On `EncryptionKeyHolder` construction, the in-process state machine must be initialised by reading the most recent `state-transition` record from the durable `tenant-state-progress` file (R76b-1a) for each tenant. If the durable record indicates `grace-read-only` or `failed`, the in-process state machine must adopt that state and its associated grace-window timestamp. The initial in-process `eventSeq` for the `state-transition` category must be initialised to the durable `lastEmittedEventSeq + 1`. This ensures durable state-transition records and in-process state machine observations agree at every observation point.

R83a's failure counter — documented as in-process per R83a — does **not** transitively become durable. It remains zero on restart. This separation is intentional: the *current state* is durable (so reads against `failed` tenants continue to fail across restart), but the *count of failures-toward-N* that drove transitions to that state is dedup-bookkeeping that need not survive restart. A restarted process inherits the state but starts a fresh detection-epoch counter.

R76c. One tenant entering `grace-read-only` or `failed` must not affect other tenants' operations (per-tenant isolation from `three-tier-key-hierarchy`).

R77. Recovery transitions:

- **From `grace-read-only` to `healthy`:** automatic when polling (R79) detects `KmsClient.isUsable(tenantKekRef) == true`, OR when a successful `rekey` API call (R78) installs a usable new Tenant KEK.
- **From `failed` to `healthy`:** automatic only when the deployer-configured **per-tenant** flag `auto-recover-from-failed = true` (default `false`) AND polling (R79) detects `KmsClient.isUsable(tenantKekRef) == true`. With the default `false`, recovery from `failed` requires an explicit successful `rekey` call (R78) — even if the Tenant KEK becomes healthy on its own. The conservative default reflects that `failed` may indicate undelivered cache invalidations or operator action that requires explicit confirmation; a regional KMS outage that auto-resolves should not silently re-enable a tenant whose data may have been operationally written off.

The `auto-recover-from-failed` flag is **per-tenant** in scope, configurable on `EncryptionKeyHolder` construction via `flavorConfig.tenant(tenantId).autoRecoverFromFailed(boolean)`. Multi-process deployments serving the same tenant on multiple nodes must coordinate flag values via shared configuration (e.g., a config service); inconsistent flag values across nodes serving the same tenant must be detected at construction and rejected with `IllegalStateException` if the implementation has cross-node visibility, or surfaced as a WARN log to `jlsm.encryption.config` when only the local value is known. Holder-instance scope is forbidden — it would create inconsistent recovery semantics across multi-tenant deployments where some tenants are operationally allowed to auto-recover and others are not.

This reconciles R76's "`failed` requires rekey" framing with R79d's polling-default-on posture: polling drives `grace-read-only → healthy` automatically (the common transient-outage case), while `failed → healthy` is operator-gated per-tenant. The two-clause OR in earlier drafts of R77 is replaced by the explicit per-state recovery rules above.

### DEK revocation read-path (explicit loud-fail semantics)

R78. `EncryptionKeyHolder` must expose a `rekey(TenantId, KekRef oldKekRef, KekRef newKekRef, RekeySentinel proofOfControl, ContinuationToken token)` API per `tenant-key-revocation-and-external-rotation` ADR.

R78a. `proofOfControl` must contain: a nonce-bound plaintext wrapped under `oldKekRef` (the "old sentinel"), the same nonce wrapped under `newKekRef` (the "new sentinel"), and a timestamp. jlsm must verify both operations by **independently invoking `KmsClient.unwrapKek(oldSentinel, oldKekRef, ctx)` AND `KmsClient.unwrapKek(newSentinel, newKekRef, ctx)` — comparing the unwrapped nonce bytes for byte-for-byte equality**. A structural inspection of the provided wrapped bytes without an actual KMS unwrap call is insufficient; both unwraps are required. Freshness window is 5 minutes maximum (timestamp must be within `now - 5min` and `now`).

R78b. The rekey operation must be streaming and paginated: a single invocation processes a bounded batch of domains (default 100, configurable) and returns a `ContinuationToken`. Callers iterate until the token is null.

R78c. Rekey execution must be resumable across crashes. A per-tenant rekey-progress file records `{oldKekRef, newKekRef, nextShardIndex, startedAt}` and is updated after each shard commit. A crashed rekey resumes at the next uncompleted shard. Stale progress files (>24h) must emit an observable event.

R78c-1. The rekey-progress file and any temporary files created during its atomic updates must be created with owner-only permissions (0600 on POSIX systems, equivalent on other platforms). Although `oldKekRef` and `newKekRef` are references (ARNs / paths / IDs) rather than key material, they identify KMS resources that a reader could probe to infer tenant-specific key-management topology; permission discipline matches R70a for registry shards.

R78d. During an in-progress rekey, each affected registry shard carries dual-reference entries `(kekRef, wrappedBlob)`: the existing entry under `oldKekRef` plus a new entry under `newKekRef`. Reads prefer `newKekRef`, falling back to `oldKekRef` if unwrap fails (shard not yet migrated). Writes always use `newKekRef` once rekey has started for the tenant.

R78e. Rekey completes when **BOTH** of the following are true:

1. **Registry migration**: every registry shard for the tenant carries `newKekRef` entries only (no `oldKekRef` entries remain); AND
2. **On-disk liveness witness**: no live SSTable in the manifest and no unreplayed WAL segment for the tenant references a DEK whose wrapping chain transitively depends on `oldKekRef`. "Transitively depends" means: the DEK's wrapping entry in the registry at the time that SSTable or WAL was written recorded a domain KEK that was itself wrapped under `oldKekRef`.

The implementation must track the on-disk liveness witness by maintaining, for each tenant, a monotonic counter of SSTables and WAL segments whose wrapping chain depends on `oldKekRef`; the counter decrements on SSTable compaction completion and WAL segment retirement. R78e is satisfied only when the counter reaches zero.

Before R78e is satisfied, the `rekey` API must not return a null `continuationToken`. Instead it must return a `continuationToken` with an explicit "awaiting on-disk witness" marker so the caller polls to completion. Once R78e is satisfied, the API returns null, and only then may the `oldKekRef` entries be garbage-collected from the registry shard and the tenant operator may disable/delete the old KEK in the tenant's KMS.

R78f. Rekey completion must be recorded atomically via a `rekey-complete` marker written to the tenant's shard (via the same atomic commit mechanism per R20). The marker records `{completedKekRef, timestamp}`. On crash recovery, the presence of the marker means rekey is complete for that KekRef; its absence (even when all shards appear migrated) means the on-disk witness was never confirmed and rekey remains incomplete. The marker makes the "rekey complete" transition idempotent and crash-safe; retries of R78e after recovery must be O(1) registry reads, not a full rescan.

R78g. Rekey progress must be observable via the `KmsObserver` interface (per `kms-integration-model` ADR R93) — `KmsObserver` is the canonical observability plane and is mandatory. Implementations may *additionally* emit structured log events as a deployer convenience, but `KmsObserver` is required so deployers wiring observability against the canonical plane receive all R78g events from any compliant implementation. The events emitted are:

- `rekeyStarted` — emitted on initial rekey invocation (the first call with a non-null `oldKekRef`/`newKekRef` for that tenant when no `rekey-complete` marker exists for the pair).
- `rekeyResumed` — emitted on crash recovery resumption (R78c). Distinct from `rekeyStarted` so consumers can distinguish a fresh rekey from a resumed one. Re-emitting `rekeyStarted` on resume is forbidden.
- `rekeyShardCommitted` — emitted on each per-shard commit completion. Carries `shardIndex` and `totalShards`. For shards already completed in a prior run (per R78c's progress file), this event must not be re-emitted on resume.
- `rekeyWitnessProgress` — emitted three times during on-disk witness counter draining (R78e): at start (`witnessCount=initialCount`), at midpoint (`witnessCount = floor(initialCount/2)` where `initialCount` is the value at rekey start; subsequent counter increments do not retrigger the midpoint event), and at zero. The `initialCount` field is the snapshot taken at rekey start.
- `rekeyCompleted` — emitted when both R78e clauses are satisfied and the `rekey-complete` marker (R78f) is written. Carries the final `tenantId`, `oldKekRef`, `newKekRef`.

Each event must include `tenantId`, `oldKekRef` (as `KekRefDescriptor` per R83i-1), `newKekRef` (same), a per-tenant monotonic `rekeyEpoch` (incremented at every fresh rekey start; stable across resume so consumers can group resumed events with their initiating epoch), and a `(tenantId, rekeyEpoch)`-scoped `eventSeq` that is strictly monotonic and gap-free.

`eventSeq` starts at 0 on `rekeyStarted`. `rekeyResumed` does NOT reset `eventSeq` — it resumes from `lastEmittedEventSeq + 1` (where `lastEmittedEventSeq` is read from the rekey-progress file per R78c). Subsequent shard-commit and witness-progress events continue monotonically. Consumers must treat `rekeyResumed` as a continuation marker, not a reset. The progress file's `lastEmittedEventSeq` must only advance after the corresponding event has been delivered to the `KmsObserver` (assume a synchronous observer for spec purposes); on resume, only events with `seq ≤ lastEmittedEventSeq` are suppressed and emission resumes from `lastEmittedEventSeq + 1`.

Partial-shard commits (where a shard's bytes were partially written before crash but the atomic rename did not complete) are NOT considered "completed" for R78g event-suppression purposes; the next attempt's `rekeyShardCommitted(shardIndex=N)` event uses a fresh `eventSeq` (gap-free continuation from `lastEmittedEventSeq + 1`).

R78g-1. A consumer observing a `rekeyCompleted` event for a `(tenantId, rekeyEpoch)` pair must treat it as authoritative-and-final only if it has also observed an `eventSeq < final` event of type `rekeyWitnessProgress` with `witnessCount=0` for the same `rekeyEpoch`. An out-of-order `rekeyCompleted` arrival must be rejected by the consumer. Implementations are not responsible for enforcing consumer behaviour, but the spec contract on event ordering enables correct consumer-side validation.

R78g-2. At most one rekey may be in-flight per tenant at any time. The in-flight rekey is identified by `(tenantId, oldKekRef, newKekRef)` and the persistent progress file (R78c). The library must distinguish two call shapes:

- **Start new rekey** — `rekey(tenantId, oldKekRef, newKekRef, sentinel, continuationToken=null)`. Must throw `IllegalStateException(rekeyEpoch=N)` if a progress file already exists for the same tenant whose `(oldKekRef, newKekRef)` pair differs OR whose `(oldKekRef, newKekRef)` pair matches but is incomplete (no `rekey-complete` marker per R78f). Otherwise emits `rekeyStarted` with a freshly-incremented `rekeyEpoch`.
- **Resume in-flight rekey** — `rekey(tenantId, oldKekRef, newKekRef, sentinel, continuationToken=non-null)`. Must succeed only if (a) a progress file exists for the same `(tenantId, oldKekRef, newKekRef)`, (b) the continuation token's recorded `rekeyEpoch` matches the progress file's recorded `rekeyEpoch`, AND (c) no `rekey-complete` marker exists for that pair. Mismatched continuation tokens (epoch drift, refs mismatch, completion-marker present) must throw `IllegalArgumentException` identifying the specific mismatch. Successful resume emits `rekeyResumed` (NOT `rekeyStarted`).

This eliminates concurrent-epoch event interleaving while permitting crash-resume per R78c.

R78g-3. **(Subsumed by R83i-1.)** `KekRef` values included in any R78g event payload must use the `KekRefDescriptor` form per R83i-1; raw `KekRef.toString()` is forbidden in observability surfaces.

### Opt-in polling (flavor 3)

R79. Per-tenant opt-in polling may be enabled by the deployer; when enabled, the polling loop invokes `KmsClient.isUsable(tenantKekRef)` on a configurable cadence (default 15 minutes) and updates the tenant's state machine (R76) based on the result.

R79a. Polling failure classification must follow the same permanent-vs-transient rules as R76a. Transient failures do not count toward N; permanent failures do.

R79b. Polling must be per-tenant — one tenant's polling load must not affect other tenants' KMS quota or jlsm's thread pool capacity.

R79c. Polling cadence must enforce the following bounds:

- **Per-tenant minimum interval:** 30 seconds default; configurable by the deployer; **hard floor 10 seconds**. Configurations below the floor must be rejected at `EncryptionKeyHolder` construction with `IllegalArgumentException` identifying the offending tenant and the requested cadence. The IAE must also be logged at ERROR level to the deployer-named logger `jlsm.encryption.config` so that frameworks that swallow the IAE (e.g., generic Spring exception handling) do not silently mask the misconfiguration. The 10-second floor is a hard ceiling — implementations must not provide system-property, environment-variable, or other escape hatches that allow per-tenant cadence below 10s; changing the floor itself requires a spec amendment.
- **Per-tenant phase jitter:** the first poll for each tenant must occur at a wall-clock offset within `[0, cadence)`. Subsequent polls then proceed at the configured cadence. This prevents thundering-herd alignment across tenants on the same KMS endpoint.
- **Per-instance aggregate rate limit:** total polls per second across all tenants on a single `EncryptionKeyHolder` instance must not exceed a configurable limit (default 100 polls/sec). When the limit would be exceeded, polls must be deferred (not dropped) — deferred polls do not violate the per-tenant minimum because the floor is a *minimum interval*, not a *maximum staleness* guarantee. The aggregate rate limit also covers eager Tenant-KEK probes per R83c-2 so reactive-probe bursts do not amplify around it.

Rationale: per-tenant minimum alone does not prevent burst storms when many tenants align cadence; jitter + aggregate rate limit together bound KMS quota consumption to the deployer's allocation.

R79c-1. The jitter offset for each tenant must be **deterministic** given the tenant identity and the deployer instance: `offset = HMAC-SHA256(deployerInstanceId, tenantId)[0..3] mod cadenceMillis`. `deployerInstanceId` is a deployer-configured value that ensures different deployer instances draw different offsets but the same instance draws the same offset for a given tenant across `EncryptionKeyHolder` recreations. Naive uniform-random draws (re-rolled on every holder construction) produce fresh alignment opportunities under high recreation rate (Spring `@RefreshScope`, hot-reload deployments) and are forbidden — the jitter must be stable across the deployer's lifetime.

`deployerInstanceId` discipline (from P4-12 — defends against tenant-correlation side-channel attacks):

- **Entropy:** at least 256 bits. Short identifiers (UUIDs at 122 bits, Kubernetes pod UIDs at 122 bits) must be combined with a 256-bit secret salt before use as the HMAC key (e.g., `HMAC-SHA256(secret-salt, podUid)` materialises the effective `deployerInstanceId`). Using a raw UUID is forbidden — an attacker who learns the UUID via observability/log correlation could predict every tenant's jitter offset, defeating the thundering-herd defence and enabling timing-correlation between observed traffic and tenants.
- **Storage context:** the `deployerInstanceId` must be stored in the same security context as the `KekRefDescriptor` HMAC secret (R83i-2) — both are deployer secrets whose compromise weakens privacy properties. Co-rotation is recommended; rotating one without the other is permitted but must be documented as an explicit choice (rotating the jitter key alone changes scheduling timing globally; rotating the descriptor key alone breaks descriptor stability per R83i-2).
- **Persistence:** durable across process restart (e.g., written to a deployer-managed secret at provisioning) so jitter remains stable across the deployer's lifetime per the requirement above.

R79d. Opt-in polling **must be enabled by default for flavor-3 tenants** (per F-32 arbitration: the `dek-revocation-vs-rotation` KB position that pull-based revocation detection alone is insufficient unless polling is on the read-path-revocation-detection critical path). Deployers may opt out by explicit configuration; opt-out must emit a WARN-level log to `jlsm.encryption.config` with the rationale that revocation latency is then bounded only by cache TTL (R91, up to 24h) — there is no other revocation-detection path. Webhook-based KMS push notification is a future ADR (`tenant-key-revocation-and-external-rotation` deferred sub-decisions) and is not part of v11.

**Polling cost note.** At the default 15-minute cadence, polling generates `tenantsCount * 96` KMS calls per day per `EncryptionKeyHolder` instance. AWS KMS, GCP Cloud KMS, and Vault Enterprise charge per-API-call; a 100K-tenant deployment generates ~10M polls/day. Deployers must size KMS quotas accordingly. The R79d-default-on choice is a security-over-cost tradeoff (per F-32 arbitration). Cost-sensitive deployers may opt out via the documented configuration flag with the understanding that revocation latency becomes cache-TTL bounded.

### KmsClient SPI contract (normative)

R79e. **Aggregate polling cost.** The total polling cost for an `EncryptionKeyHolder` instance is the sum of all polling sources, all subject to R79c's per-instance rate limit (default 100 polls/sec, 8.6M/day):

- **Flavor-3 KMS polls (R79d)** — `tenantsCount * 96` calls/day at default 15min cadence.
- **Flavor-2 filesystem polls (R83b-1b)** — `tenantsCount * 96` filesystem `stat` calls/day at default 15min cadence (negligible cost vs network polls).
- **Eager Tenant-KEK probes (R83c-2)** — bursty; coalesced per tenant per detection epoch; bounded by R79c's aggregate rate limit.
- **R37b convergence-detection internal polls** — bounded `[100ms, 60s]`; default `min(60s, manifest-cycle/10)`; skipped when R37c's manifest commit-completion hook is wired.

Implementations must expose an aggregate-polling-budget metric per `EncryptionKeyHolder` instance (e.g., `pollsScheduledPerSec`, `pollsDeferredPerSec`) so deployers can size KMS quotas accurately. At the default settings on a 100K-tenant deployment, polling pressure approaches AWS KMS's 5500 ops/sec per-region default quota; deployers operating near this scale must either request a quota increase, increase polling cadence, opt out per R79d, or shard tenants across multiple holder instances.

R83. The library must distinguish three classes of read-path KEK unwrap outcomes:

- **Healthy** — unwrap succeeds; data is readable normally.
- **Transient failure** — KMS returned a transient error class per R76a / R76a-1; the implementation retries per `kms-integration-model` backoff; reads continue against cached unwrapped material per R91c.
- **Permanent revocation** — KMS returned a permanent error class per R76a / R76a-1 (AccessDenied, KeyDisabled, KeyNotFound, KeyDestroyed, IncorrectKeyException, InvalidCiphertextException, KMSInvalidStateException(PendingDeletion), and equivalents per the R76a-1 table). The implementation must not silently retry the unwrap, return null, or return wrong-key garbage. The read must fail with a public `KekRevokedException` (defined in package `jlsm.encryption`) extending `KmsPermanentException`. Both `KmsPermanentException` and `KekRevokedException` must extend `IOException` (i.e. they are **checked exceptions**), aligning with `RegistryStateException` (R83b-2) and `DekNotFoundException` (R57); read API surfaces in encryption boundaries already declare `IOException`, so the checked discipline is already paid. Subclasses for tenant-tier and domain-tier revocation must extend `KekRevokedException` and inherit its `KmsPermanentException` lineage. Implementations must not introduce parallel revocation hierarchies under unrelated parent types.

R83-1. To support uniform operator alarm wiring across the diverse parent types in the read-path failure type matrix, the spec defines a sealed marker interface `RetryDisposition.NonRetryable` in package `jlsm.encryption` whose only legitimate implementations are the four matrix exception families: `KmsPermanentException` (and its `KekRevokedException` subtree), `RegistryStateException`, `DekNotFoundException`, and `TenantKekUnavailableException`. The marker carries no methods; it exists solely so operator code can write `catch (RetryDisposition.NonRetryable e) { alert(); }` and pick up every non-retryable read-path failure regardless of its parent type. The matrix's "Parent type" column (see Read-path failure type matrix below) records each exception's declared parent so callers can construct correct `catch` chains when finer discrimination is needed.

**Sealing discipline (P4-6, P4-30).** The seal is a contract that adding a new alarm-class exception requires an explicit spec amendment — this is intentional to prevent silent alarm-class drift across `KmsClient` plugins and future jlsm versions. Plugin authors who want plugin-specific permanent exceptions to participate in `NonRetryable` alarms must:

- (a) **Wrap their exception** in `KmsPermanentException` (or `KekRevokedException` for revocation-class faults) at the plugin's boundary — the wrapper inherits `NonRetryable` via existing seal members. Plugin authors must NOT introduce parallel exception hierarchies that bypass the seal.
- (b) Use the per-plugin diagnostic field on `KmsPermanentException` (e.g., a `pluginErrorCode` accessor) to surface plugin-specific detail without adding new sealed members.

**JPMS module scope (P4-30).** The seal is pinned to module `jlsm.core` — all permitted subclasses must reside in `jlsm.core`. Java sealed types require all permitted subclasses in the same module unless the sealed type explicitly opts out via `non-sealed` intermediates (which would defeat the alarm-class invariant). Adding a new permitted type therefore requires (i) a `jlsm.core` source change to extend the `permits` clause, AND (ii) a spec amendment per the prior paragraph. Third-party modules (KmsClient plugins distributed as separate JPMS modules) cannot extend the seal; they must wrap into existing types.

R83a. The `KekRevokedException` thrown by R83 must register a permanent-class failure against the tenant's three-state machine (R76, R76a) at most **once per detection epoch**, where a detection epoch is bounded by the polling cadence (R79) for the tenant or 60 seconds, whichever is shorter. Within an epoch, additional reads against revoked DEKs in the same tenant must be coalesced into a single counter increment regardless of how many distinct `(domainId, tableId, dekVersion)` tuples are affected. Rationale: domain-KEK destruction is a single fault in the tenant's KMS but produces fan-out at jlsm's read path; R76's `N` threshold counts faults, not affected DEK cardinality. The dedup contract holds within a single `EncryptionKeyHolder` lifetime — JVM restart resets the failure counter and dedup set per the in-process state model of `tenant-key-revocation-and-external-rotation` ADR; the first revocation observation in a fresh process counts as one regardless of pre-restart history. Implementations must not persist the counter or dedup set. The counter must be implemented as `long` and saturated at `Long.MAX_VALUE`; overflow into negative values is forbidden.

R83a-1. When the saturating R83a counter reaches `Long.MAX_VALUE`, the implementation must emit a `revocationCounterSaturated` event via `KmsObserver` once per tenant per process lifetime (idempotent on subsequent emission attempts within the same process). The event signals to the operator that the counter has lost fidelity and any further N-threshold-derived state changes should be cross-checked against the underlying KMS state. Saturation is not expected under normal operation; its occurrence indicates either an attack or a deployment where polling cadence × N-increment-rate exceeds `Long.MAX_VALUE`. The event uses the `polling` category per R76b-1 (in-process durability).

R83b. Domain-KEK destruction (the multi-tenant crypto-shredding primitive per `dek-revocation-vs-rotation` KB and `three-tier-key-hierarchy` ADR) is observed at the read path through R83's permanent revocation outcome. When a domain KEK is destroyed by the operator, every DEK in that domain becomes permanently unwrappable; reads under those DEKs must produce R83's `DomainKekRevokedException` (subclass of `KekRevokedException`). The library does not provide a separate "explicit per-DEK disable" surface — domain-KEK lifecycle is the canonical revocation surface.

R83b-1. Domain-KEK destruction observation latency is bounded by `cache TTL (R91) + polling cadence (R79c)`. The unwrapped domain-KEK plaintext is cached per R91 and continues to function for in-flight reads/writes until cache TTL expiry regardless of upstream KMS revocation status. Implementations must (a) document this lag in the public rotation API, (b) eagerly invalidate the cached domain-KEK entry for a `(tenantId, domainId)` scope when polling (R79) detects `isUsable=false` for the wrapping KEK, and (c) emit a WARN-level log event when polling is *not* enabled on a flavor-3 tenant since revocation latency is then bounded only by cache TTL (up to 24h per R91). New writes under a cache-still-valid-but-upstream-revoked domain KEK are an explicitly accepted operational consequence of the lazy/cached model and must be surfaced in the deployer-facing API documentation.

R83b-1a. Eager cache invalidation per R83b-1 must use an **epoch-based reclamation** pattern to prevent in-flight readers from receiving zeroed-segment reads:

- The polling thread inserts a **tombstone entry** into the cache marking the `(tenantId, domainId)` as revoked. Insertion is atomic (CoW or volatile-reference swap per R64).
- The cached `MemorySegment` is **NOT zeroed** at the moment of tombstone insertion. Zeroisation per R69 is deferred until a quiescence barrier — e.g., an epoch counter advance that confirms no reader registered before the tombstone insertion is still in-flight.
- In-flight readers that obtained a `MemorySegment` reference *before* the tombstone insertion must, after their decryption attempt, check the tombstone for the corresponding `(tenantId, domainId)`. If the tombstone is present, they must NOT return decrypted plaintext to the caller — they must throw `DomainKekRevokedException` per R83b. This enforces R83's loud-fail discipline at the read boundary even when the cache layer raced ahead.
- Once the quiescence barrier confirms no pre-tombstone reader holds the segment, the implementation zeroes the segment (R69) and removes the tombstone (or replaces it with a permanent-revocation marker for R83g's caching).

The quiescence barrier must enforce a **configurable upper bound** (default = `cache TTL` from R91, ceiling 24h, hard maximum 48h) after tombstone insertion (P4-1). If the barrier has not advanced within the bound, the implementation must:

- (a) Emit a `quiescenceBarrierTimeout` event via `KmsObserver` (`state-transition` category, durable per R76b-1) carrying the count of pre-tombstone readers still in-flight and the time elapsed since tombstone insertion.
- (b) **Forcibly zero** the segment regardless of in-flight reader presence.
- (c) Cause any subsequent decryption attempt by the still-in-flight readers to throw `DomainKekRevokedException` instead of returning data — the post-decrypt tombstone check (clause 3 above) already enforces this; readers must additionally null-check the segment contents (or detect the forced-zero via a sentinel-bit) before returning to the caller, since the decryption may have already produced output buffers from zeroed key material.

The forced-zero path must **NOT** wait for reader cooperation; key plaintext residency time after upstream destruction is bounded by `cache TTL + polling cadence + quiescence-timeout` and must not exceed 48h under any configuration. This bounds the security exposure of an unbounded long-running scan that holds a cached domain KEK after the upstream KEK has been destroyed.

**Relationship to R83g cached-revocation (P4-15).** The per-`(tenantId, domainId)` tombstone of R83b-1a and the per-`(tenantId, domainId, tableId, dekVersion)` cached-revocation entries of R83g are **distinct data structures with a lazy-population relationship**:

- The tombstone is the per-domain short-circuit. Once inserted, all DEK lookups in the affected domain bypass the KMS regardless of dekVersion. The tombstone exists for the full lifetime of the domain-KEK revocation (cleared only by R83g's clearing conditions: operator-driven invalidation or successful rekey).
- R83g's per-DEK entries are populated **on demand** — each unique `(tableId, dekVersion)` whose unwrap attempt is short-circuited by the tombstone produces a per-DEK entry recording the specific tuple. This per-DEK entry is what R83a's epoch counter dedups against, what R83g's "must not re-contact KMS" enforces, and what R83b-2's manifest-witness scan can consult.

A reader observing the tombstone throws `DomainKekRevokedException` immediately; the implementation MAY (but is not required to) populate the corresponding R83g per-DEK entry at that moment. The tombstone alone is sufficient for short-circuiting; per-DEK entries provide finer-grained observability for tooling that wants to enumerate revoked DEKs.

This pattern preserves R69's zero-before-release at the operational level while eliminating the in-flight-reader race that would otherwise expose plaintext-derived-from-zeroed-key garbage.

R83b-1b. Flavor-2 (`local`) deployments must implement an equivalent revocation-detection mechanism: poll the filesystem `mtime` of the Tenant KEK file at the same default cadence (15 minutes) and treat absence-of-file or permission-denied as a permanent-class signal triggering R83b-1's eager invalidation path. Flavor-2 polling inherits R79c's bounds (10s floor, jitter, aggregate rate limit) so deployers' test rigs do not stampede the filesystem. Flavor-1 (`none`) has no KEK and therefore no revocation path; this is intentional and not a gap. R83b-1's mandate to invalidate cached entries on revocation detection applies to both flavor-2 (filesystem-polling-driven) and flavor-3 (KMS-polling-driven) — the cache invariant is flavor-independent.

R83b-2. Registry-side destruction (loss of the `WrappedDomainKek` entry while the upstream KMS-side KEK remains alive) is operationally distinct from KMS-side destruction. On `openDomain` for a `(tenantId, domainId)` where any DEK exists in the same shard but the wrapping `WrappedDomainKek` is absent, the operation must throw `RegistryStateException` (subclass of `IOException`, marker `RetryDisposition.NonRetryable` per R83-1) identifying the inconsistency.

Auto-provisioning a fresh domain KEK for `(tenantId, domainId)` is permitted only when **all three** of the following hold:

1. No DEK entry exists in any registry shard for that `(tenantId, domainId)`, AND
2. No live SSTable in the manifest records `(tenantId, domainId)` as its scope (per `sstable.footer-encryption-scope` R3a-c — the footer's scope tuple is the witness), AND
3. No unreplayed WAL segment for the tenant references a DEK in that domain (per the on-disk liveness counter from R75c).

The check must consult the **manifest and WAL**, not just the registry. If clause 1 is satisfied but clauses 2 or 3 are not, the registry has been destroyed while live data still depends on the destroyed wrapping KEK; auto-provisioning a fresh KEK in that case would silently shred the existing data and is forbidden. Throw `RegistryStateException` instead with the message identifying which clause was violated (e.g., `RegistryStateException: registry shard absent but manifest contains 47 SSTables under (tenantId=t, domainId=d) — recovery required`).

**Caching, scoping, and coalescing (P4-2).** The manifest+WAL scan is `O(SSTables) + O(WAL segments)` and on remote backends (S3, GCS) translates to `O(N)` LIST/HEAD/GET requests. Per-`openDomain` invocation of the unscoped scan is operationally infeasible. Implementations must:

- **Scope the WAL scan** to segments whose maximum-timestamp post-dates the most recent registry commit timestamp. Segments older than the registry's last commit are guaranteed not to reference DEKs in a now-empty registry. This prunes the typical N=50,000 segment scan to the recent slice.
- **Cache the negative result** as `auto-provision-permitted` per `(tenantId, domainId)` until invalidated. The cache is invalidated by the manifest's commit-completion hook (R37c) when a commit affects `(tenantId, domainId)`. The cache survives within a single `EncryptionKeyHolder` lifetime; restart re-runs the scan once.
- **Coalesce concurrent calls** for the same `(tenantId, domainId)` to a single in-flight scan via a per-`(tenantId, domainId)` future/promise; the second caller waits on the first's result. Implementations must not pay the scan cost N times for N concurrent callers.
- **Surface unbounded scan cost.** Implementations on backends with no segment-timestamp index (and therefore no way to scope the WAL scan) must either degrade gracefully via a configurable scan timeout (default 30s) — beyond which `RegistryStateException(JLSM-ENC-83B2-SCAN-TIMEOUT)` is thrown — or refuse `auto-provision` entirely on those backends, requiring operator-driven recovery via R83b-2a's `forceShredRegistry` API. Hanging on the scan is forbidden.

**Manifest-rebuild guard (P4-27).** Auto-provisioning is forbidden during any manifest-rebuild window. The implementation must detect manifest-rebuild state via a global flag (or equivalent durable marker — e.g., a `manifest-rebuild-in-progress` lock-file in the manifest directory) and refuse auto-provisioning while the flag is set, throwing `RegistryStateException(JLSM-ENC-83B2-MANIFEST-REBUILD)`. During manifest rebuild, the manifest's view of "live SSTables" is incomplete; clause 2 of R83b-2's auto-provision predicate could be trivially satisfied for a scope whose live SSTables haven't been re-registered yet. Auto-provisioning during this window would re-introduce the silent-crypto-shred attack the rest of R83b-2 prevents.

R83b-2a. The `RegistryStateException` thrown by R83b-2 must include a stable error code (e.g., `JLSM-ENC-83B2-REGISTRY-DESTROYED`, `JLSM-ENC-83B2-MANIFEST-REBUILD`, `JLSM-ENC-83B2-SCAN-TIMEOUT`) suitable for operator runbook lookups, in addition to its descriptive message. The implementation must additionally expose a `forceShredRegistry(TenantId, DomainId, RegistryShredConfirmation)` operator-only API that explicitly destroys all SSTables, WAL segments, and registry entries in the affected scope.

**`RegistryShredConfirmation` token discipline (P4-8, P4-23):**

The token must contain:

- The affected `(tenantId, domainId)` scope tuple — required, validated byte-for-byte against the API call.
- A timestamp within **5 minutes ± 30 seconds** of the API invocation — the 30-second grace handles modest clock skew across multi-process clusters but is not a relay window. The clock used for validation is the **single canonical clock of the process performing the destructive action** (NOT the token-generation process); multi-process token relay is unsupported. If the deployer's process generated a token on host A and the destructive action happens on host B, B's clock validation may reject the token if the inter-host skew exceeds 30 seconds — this is intentional defense against clock-relay attacks.
- A **16-byte cryptographically-random nonce** — required, validated as not-previously-consumed.

**Single-use enforcement.** The library must maintain a per-process bloom filter (or equivalent presence map) of recently-consumed token nonces. Each generated token has a unique nonce. On `forceShredRegistry` invocation, the nonce is checked against the consumed-set; if present, the call must throw `IllegalStateException(JLSM-ENC-83B2-TOKEN-REPLAY)`. The consumed-set must persist for at least the token TTL (5min30s) plus a safety margin (1h) so a replay attempt within the realistic clock-skew window is rejected. The consumed-set may be in-process (resets on restart) — restart resets the protection window to zero, which is acceptable because the token TTL is short (a stale token after restart is rejected by clock validation).

**Audit observability.** The API must emit a `forceRegistryShred` audit event via `KmsObserver` (`state-transition` category, durable per R76b-1) recording the `(tenantId, domainId)`, the operator identity (if available via the calling thread's security context), the elapsed-since-token-generation timestamp, and a confirmation of the shred's completion. Token replay attempts must additionally emit a `forceShredRegistryTokenReplay` event at WARN level for security observability.

Without this surface, deployers facing the registry-destroyed-while-data-live state would have no sanctioned recovery path; with this surface, the recovery path is explicitly auditable and replay-resistant.

R83c. Tenant-tier revocation (Tenant KEK destruction in flavor 3) must be detectable distinctly from domain-tier revocation. When the implementation observes a permanent unwrap failure for a domain KEK, it must additionally probe Tenant KEK usability via `KmsClient.isUsable(tenantKekRef)` before classifying the failure as domain-tier. If the Tenant KEK probe also fails permanently, the revocation is tenant-tier and the read path must throw `TenantKekRevokedException` (subclass of `KekRevokedException`) — even if other domain KEKs in the tenant are still cache-valid. This eager-probe upgrade prevents the same root-cause fault (Tenant KEK destruction) from being misclassified as a churn of domain-tier events as cached domain KEKs expire one by one. The probe result is itself cached for an interval equal to `min(polling cadence (R79) / 4, 5 minutes)` — defaulting to 3.75 minutes when polling cadence is 15 minutes, capping at 5 minutes for any longer cadence. The 5-minute cap bounds probe-result staleness independently of cadence, so long-cadence deployments do not lose Tenant-KEK-revocation detection latency. `TenantKekRevokedException` identifies the tenant scope but not specific `(domainId, tableId, dekVersion)` (the failure is upstream of any specific DEK).

R83c-1. The relationship between `TenantKekRevokedException` (R83c) and `TenantKekUnavailableException` (R76) is sequential: the **transitioning read** (the read whose failure causes the tenant to enter `failed` state) throws `TenantKekRevokedException`. **Subsequent reads** in the same tenant, while the tenant remains in `failed` state, throw `TenantKekUnavailableException` (R76's process-level state-machine signal). This ordering is observable to callers — a single root-cause fault produces one revocation-class exception followed by N unavailability-class exceptions, not a churn of revocations.

When multiple reads concurrently observe the N-th and (N+k)-th permanent failures during the transition to `failed`, exactly one read — atomically determined via the state machine's compare-and-set state-field operation with proper memory-fence semantics — is the "transitioning read" and throws `TenantKekRevokedException`; all others observe the post-transition state and throw `TenantKekUnavailableException`. The state-machine implementation must use `VarHandle` or `AtomicReference` semantics so the transition is observable as a single happens-before edge to all concurrent readers.

R83c-2. Eager Tenant-KEK probes per R83c must share the per-instance aggregate rate limit (R79c default 100 polls/sec) with scheduled polls. When the aggregate budget is exhausted, eager probes must be **coalesced per tenant**: multiple domain-tier failures observed within one detection epoch (R83a) trigger at most one Tenant-KEK probe per tenant. Probes deferred by the rate limit must not block the originating read — the read must propagate `KekRevokedException` (domain-tier subclass) using the most recent classification cached for that tenant (initially "domain-tier" until the first probe completes); the probe completes asynchronously and updates the classification cache for subsequent reads. This prevents a multi-tenant outage from amplifying through the eager-probe path into a self-inflicted KMS DDoS that would flap healthy tenants to `failed`.

**Probe-cache TTL vs detection-epoch orthogonality (P4-25).** R83c's probe-cache TTL bounds *cache freshness* — how long a cached classification is trusted before a fresh probe is needed. The detection epoch (R83a + R83c-2) bounds *probe coalescence* — how many probes per tenant are issued per fault batch. The two are orthogonal: cache TTL governs reads against the classification cache, epoch governs probe issuance. A cached classification observed within its TTL serves reads regardless of epoch boundaries; a probe issued within an epoch coalesces additional probe requests in the same epoch even if the cache TTL would otherwise have expired. Implementations must not conflate the two — for example, treating a cache-miss as an automatic probe-issue would defeat coalescing.

R83d. Convergence detection (R37) and revocation detection (R83) must not be conflated. When the implementation observes both convergence-eligibility (no live SSTable references the rotated-away version) and revocation (the wrapping KEK is permanently unusable) for the same `(scope, oldDekVersion)`, the revocation event is authoritative and the convergence event must be **suppressed**. The implementation must check the revocation status before emitting a convergence event for any `(scope, oldDekVersion)`; if revocation is concurrently or previously detected, emit only the revocation event with an additional flag `convergedAtRevocation=true` so consumers can distinguish "rotation completed normally" from "rotation completed because the old KEK was destroyed".

R83e. Implementations must not assume `KmsClient` plugins return correct plaintext on `UnwrapResult` success. The library must validate KMS-returned plaintext at the SPI boundary: (a) the returned `MemorySegment` must have the expected byte length for the wrapped key type (32 bytes for DEK, configured length for domain KEK); (b) any further integrity available via the wrap envelope (e.g., AES-KW round-trip self-check, AAD AEAD verification on a wrap-format that uses `EncryptionContext` as AAD per R80a) must be performed before the unwrapped material is cached or used. A misbehaving plugin that returns success-with-wrong-bytes must surface either as a mapped `KmsPermanentException` (for length / AAD validation failures) or via the AEAD failure path (R60); silent acceptance is forbidden. The validation logic resides in the library wrapper around `KmsClient`, not in the plugin itself, so all plugins inherit the protection.

R83e-1. Implementations must validate `wrapKek` outputs symmetrically with R83e's unwrap validation: after every `wrapKek(plaintext, ref, ctx)` call, the library must immediately call `unwrapKek(wrappedBytes, ref, ctx)` and verify byte-for-byte equality with the original plaintext **before persisting** the `wrappedBytes` to the registry. Mismatch must surface as `KmsPermanentException` with an explicit message identifying the plugin class and `wrap-roundtrip-failure` mode, and must NOT result in a registry write. The wrap-roundtrip cost is one extra KMS call per DEK creation — acceptable because DEK creation is rare relative to reads, and this is the only on-write defense against a malicious or buggy plugin that returns garbage `wrappedBytes` and corrupts the persistent registry. The plaintext copy used for the roundtrip comparison must be zeroed in a `finally` block immediately after the comparison completes (R16 zeroisation discipline applied at the SPI boundary).

**Atomicity vs `close()` (P4-5).** The wrap-roundtrip pair (`wrapKek` + `unwrapKek` + byte-equality verify + plaintext-copy zeroisation) and the subsequent registry persistence must be atomic with respect to `EncryptionKeyHolder.close()`, with the same discipline as R62a for `deriveFieldKey`: if `close()` is invoked while the roundtrip is executing, the roundtrip must either complete and persist (using the original plaintext copy and zeroing it in `finally`) or throw `IllegalStateException` *before* invoking the KMS — never produce a partial state where `wrapKek` has been called but the plaintext copy cannot be zeroed because the Arena is already released. R66 must wait for in-flight DEK-creation roundtrips to drain before zeroing the internal Arena, paralleling R62a's discipline for in-flight `deriveFieldKey` calls. This protects R66's "zero-before-release" invariant against a JVM-allocator-leak surface that would otherwise let plaintext key material persist on freed memory.

**Lock discipline (P4-22).** The wrap-roundtrip pair and equality verify must execute **outside** any registry shard lock (R34a). The persistence step — registering the verified `wrappedBytes` in the registry shard — is the only step that requires the shared lock. Implementations must structure DEK creation as: (1) acquire plaintext, (2) wrap+unwrap+verify+zero outside any lock, (3) acquire the shared shard lock per R34a, (4) persist the verified `wrappedBytes`, (5) release the lock. Holding the shard lock during the (potentially-network-bound) roundtrip would block concurrent DEK creation and KEK rotation in the same shard for the duration of two KMS round-trips, defeating R34a's concurrency model.

R83f. Throwing R83's exception (or any subclass) must leave no pooled or arena-backed resources unreleased. Read-path code that traverses the SSTable footer, acquires `ArenaBufferPool` buffers, or allocates from a per-read internal `Arena` must release/close those resources in a `finally` block before R83 propagates. The caller's externally-supplied `Arena` (per R9) is the caller's responsibility to close — the library does not close caller arenas — but no internal pool resource may survive the throw. Implementations must include a regression test that asserts pool occupancy is unchanged after N consecutive R83 throws (where N is at least the configured pool capacity).

R83g. Once a permanent revocation has been observed for a `(tenantId, domainId, tableId, dekVersion)` four-tuple within a process lifetime, all subsequent unwrap attempts for the same tuple must short-circuit-fail with R83 **without re-contacting the KMS**. The cached-revocation entry must be cleared only by (a) explicit operator-driven cache invalidation, or (b) successful `rekey` API completion that retires the affected scope. This prevents misclassified-transient errors from being recovered by caller-side retry loops, and ensures R83 is terminal at the *outcome* level, not just the *single-event* level. The cached-revocation entry must not persist across process restart (consistent with R83a's in-process scope).

R83g-1. Crash-resumed operations (R78c rekey resume) must follow R83's loud-fail discipline on every encountered DEK. Because R83g's revocation cache is in-process, a resumed rekey starts with an empty cache and would otherwise hit O(DEKs) wasted KMS calls re-detecting the same revocation across a damaged scope. To bound this:

- A resumed rekey that observes a permanent revocation on `oldKekRef` itself (the rekey's source KEK) must abort the resume with `IllegalStateException` rather than continuing per-shard. The rekey is operationally meaningless if the source KEK is destroyed; the operator must either restore the source KEK or invoke `forceShredRegistry` (R83b-2a) to discard the affected scope.
- A resumed rekey that observes revocation on a non-source domain KEK (e.g., a domain-tier KEK destroyed independently of the rekey) must surface as `DomainKekRevokedException` and **skip the affected DEK from the rekey**, recording the skip in the progress file's `permanently-skipped` set so subsequent retries do not repeat the wasted KMS call. After resume completes, the implementation must emit a `rekeyPermanentlySkipped` event via `KmsObserver` listing the skipped DEKs so the operator can investigate.
- The per-rekey-pair `permanently-skipped` set is durable (recorded in the progress file alongside `nextShardIndex`) so post-restart resumes inherit it. This set is cleared along with the progress file when the `rekey-complete` marker (R78f) is written.

**Per-tenant durable revoked-deks set (P4-9).** In addition to the per-rekey-pair `permanently-skipped` set, the implementation must maintain a **per-tenant durable `permanently-revoked-deks` set in the registry shard** (alongside the regular DEK entries — e.g., as a parallel list with the same atomic-commit primitive per R20). This set persists across rekey-pair boundaries: a DEK skipped in rekey N because its wrapping KEK was destroyed is also recorded in the tenant-scoped set, so rekey N+1 (using a fresh `oldKekRef → newerKekRef`) does not re-discover the same revocation by attempting to wrap the skipped DEK and observing the destruction again. Without this set, each successive rekey pays O(revoked-DEKs) wasted KMS calls re-discovering the same revocation.

The per-tenant `permanently-revoked-deks` set is cleared only by `forceShredRegistry` (R83b-2a) for the affected scope, or by an operator-driven cache invalidation API (parallel to R83g's clearing conditions for the in-process cache).

**Bounds (P4-28).** Both the per-rekey-pair `permanently-skipped` set and the per-tenant durable `permanently-revoked-deks` set must be **bounded** (default 10,000 entries per tenant). When the bound is exceeded:

- (a) Emit a `permanentlySkippedSetOverflow` event via `KmsObserver` (`state-transition` category, durable per R76b-1) recording the bound, the set size, and the affected tenant.
- (b) Transition the tenant to `failed` state (R76) — a tenant accumulating >10K permanently-revoked DEKs has experienced a structural fault that requires operator investigation.
- (c) Reject further DEK creation for the tenant until the operator invokes `forceShredRegistry` (R83b-2a) to discard the affected scope or otherwise reduces the set size below the bound.

The bound prevents pathological tenants from inflating the durable set unboundedly, which would balloon registry shard size and resume-time scan cost without bound.

R83h. The `KekRevokedException` and its subclasses must enforce confidentiality via **two distinct exception instances** constructed at the read-path failure boundary:

- A **tenant-visible instance** for surfaces that may be exposed to the tenant (e.g., multi-tenant SaaS error responses). This instance has `setCause(null)` (no chained cause), and its `getMessage()` includes only `tenantId` and an opaque correlation id; `dekVersion`, `domainId`, `tableId`, and any provider-specific metadata are redacted. The opaque correlation id maps (deployer-side) to the audit channel where the full diagnostic record lives.
- A **deployer-internal instance** for the named-logger `jlsm.encryption.scope`. This instance retains the full scope tuple `(tenantId, domainId, tableId, dekVersion)` in its `getMessage()` and may chain the underlying KMS plugin exception via `getCause()` for stack-trace and SDK-field diagnostics.

Implementations must construct the two instances at the moment of failure, before either reaches a logger, exception serializer, or RPC boundary. **Emitting one instance to both surfaces and relying on string sanitization is not sufficient defense** against reflective serializers (Jackson, Gson, Logback `ThrowableProxyConverter` with custom converters) that walk all exception fields beyond `getMessage()` — modern Java exception types from KMS SDKs include fields like `getResponseHeaders() : Map<String,String>`, `getRequestId() : String`, `getStatusCode() : int`, `getCause() : Throwable`, etc., and reflective walkers serialize all of them. The split-instance design ensures the tenant-visible instance has no diagnostic payload to leak.

The exception's `getStackTrace()` may retain provider stack frames on the deployer-internal instance only (these do not contain key material in standard JDK / AWS SDK / Vault stacks). The tenant-visible instance must have its stack trace truncated to the public API entry point (e.g., `EncryptionKeyHolder.deriveFieldKey`, `EncryptionKeyHolder.openDomain`, `EncryptionKeyHolder.rekey`, and shallower) — internal frames are stripped via `setStackTrace(...)` before throwing.

**Centralised factory (P4-13).** Implementations must centralise the dual-instance construction in a single factory, e.g., `R83ExceptionFactory.tenantAndDeployer(tenantId, ScopeTuple, KmsCause)` returning a record `(TenantVisibleException tenant, DeployerInternalException deployer)`. R83 throw sites must construct via the factory only — direct `new TenantKekRevokedException(...)` or `new DomainKekRevokedException(...)` call sites must be banned by static analysis (e.g., a build-time check via ArchUnit or an equivalent static rule on the test classpath). This eliminates the maintenance trap where a future code path forgets to construct one of the two instances; the factory enforces dual construction at every throw site by construction.

The factory must additionally handle stack-trace truncation, opaque correlation-id minting, KMS cause sanitisation (per the bullet list above), and `KekRefDescriptor` transformation (R83i-1) — all in one place. Adding a new R83 throw site requires only a factory call; adding a new R83 subclass requires a factory method extension. This centralisation also makes the security-critical confidentiality logic auditable in a single source location.

R83i. Neither the tenant-visible nor the deployer-internal exception instance (R83h) may include any byte-form of `wrappedBytes`, `MemorySegment` plaintext, or HMAC digest of key material in `getMessage()`, `getCause().getMessage()`, or any structured-payload field exposed to consumers. `KekRef` values must use the opaque `KekRefDescriptor` form per R83i-1 below.

R83i-1. `KekRef` instances passed into R78g event payloads, R83 exception payloads (deployer-internal **and** tenant-visible), and any other observable surface must be transformed to a `KekRefDescriptor` value containing only:

- A 16-byte hash prefix of the canonical `KekRef.toString()`, computed as HMAC-SHA256 keyed by a deployer-configured secret to prevent rainbow-table reverse-lookups.
- The KMS provider class name (`AwsKms`, `GcpKms`, `Vault`, `LocalKms`).
- A non-confidential region/zone tag (e.g., `us-east-1`) when the plugin can supply one without leaking account context.

The `KmsClient` SPI must expose `KekRefDescriptor describeKekRef(KekRef ref)` defaulting to the hash-prefix form; plugin authors may override to provide richer descriptors that conform to the no-byte-fingerprint clause of R83i. The library must never log raw `KekRef.toString()` in observability or exception surfaces — `describeKekRef` is the only sanctioned path. This avoids both (a) leaking AWS account context via raw ARNs, and (b) breaking binary compatibility with existing callers that may rely on `KekRef.toString()` for their own logging — `KekRef.toString()` retains its current format for direct caller use; only library-emitted observability and exceptions are constrained to use `KekRefDescriptor`.

R83i-2. **Deployer-secret discipline (P4-7).** The deployer-configured secret used for `KekRefDescriptor` HMAC keying must satisfy:

- **Cluster-wide consistency.** All jlsm processes serving the same tenants must use the same secret so the same `KekRef` produces the same descriptor across processes; otherwise cross-process correlation in the deployer's observability pipeline breaks. Implementations that can detect inconsistency (e.g., via a configuration service that rejects mismatch) must reject construction with `IllegalStateException(JLSM-ENC-83I2-SECRET-MISMATCH)`. Implementations without cross-process visibility must surface a WARN log at construction so deployers configure the secret consistently.
- **Storage context** equivalent to R79c-1's `deployerInstanceId` — both are deployer secrets whose compromise weakens privacy properties; co-location in a deployer-managed secret store is recommended.
- **Rotation discipline.** Rotating the secret breaks descriptor stability — historical descriptors emitted under the old secret cannot be matched against descriptors emitted under the new secret. Rotation is permitted but requires deployer-driven re-emission of historical descriptors via a shared correlation table maintained outside jlsm (the deployer records `(old-descriptor, KekRef, new-descriptor)` triples during the rotation window). The library does not maintain this table — it is operator policy. Rotation events must emit a `kekRefDescriptorSecretRotated` audit event via `KmsObserver` (state-transition category) so deployers can mark the rotation boundary in observability pipelines.
- **No persisted descriptors.** The library must not persist `KekRefDescriptor` values to disk (registry shards, progress files, etc.) under any circumstance. Descriptors are observability ephemera; persisting them would make rotation a multi-stage data migration. All persistent artifacts use raw `KekRef` (untransformed) which is shielded from observability surfaces by the `describeKekRef` discipline above.

#### Read-path failure type matrix

The encryption read path throws four distinct exception families. The matrix below specifies precedence (when multiple apply, surface the higher-precedence one first), the declared parent type (so callers can construct correct `catch` chains), and ownership.

All four families implement the sealed marker interface `RetryDisposition.NonRetryable` (R83-1) so operator alarm code may write `catch (RetryDisposition.NonRetryable e) { alert(); }` and pick up every non-retryable read-path failure regardless of the concrete parent type. Finer discrimination uses the per-family parent.

| Exception | Defining requirement | Parent type | Marker | Meaning | Tenant scope? |
|---|---|---|---|---|---|
| `TenantKekUnavailableException` | R76 (state machine) | `IOException` | `RetryDisposition.NonRetryable` | Tenant in `grace-read-only` or `failed` state — process-level state-machine signal, applies tenant-wide regardless of which DEK is being read | Tenant-wide |
| `KekRevokedException` (and subclasses `TenantKekRevokedException`, `DomainKekRevokedException`) | R83 / R83b / R83c | `KmsPermanentException` (which extends `IOException`) | `RetryDisposition.NonRetryable` | Per-DEK or per-tenant upstream KMS revocation — fault originated at the KMS layer | Per-DEK or per-tenant |
| `RegistryStateException` | R83b-2 / R83b-2a | `IOException` | `RetryDisposition.NonRetryable` | Registry-side inconsistency (wrapped entry missing while DEKs reference it, manifest/WAL still references the destroyed scope) — requires operator intervention via `forceShredRegistry` or backup restore | Per-`(tenant, domain)` |
| `DekNotFoundException` | R57 | `IOException` | `RetryDisposition.NonRetryable` | DEK version not in the registry — caller error or stale client view of the registry | Per-DEK |

All four exceptions extend `IOException` (i.e. checked exceptions) — the encryption read API surface already declares `IOException`, so the checked discipline is paid uniformly.

Precedence (highest first):
1. `TenantKekUnavailableException` — if the tenant is already in `failed` or `grace-read-only` (write path), surface that; do not probe the KMS further.
2. `KekRevokedException` — per-DEK or per-tenant revocation-class fault from the KMS.
3. `RegistryStateException` — inconsistent registry state from a partially-recovered or operator-altered shard.
4. `DekNotFoundException` — registry pruning normal lifecycle, or a caller-side stale view.

Implementations must check higher-precedence conditions before invoking the unwrap path that could throw lower-precedence exceptions.

**Footnote (P4-16) — concurrent-class transitions.** Precedence applies to a single read's *observation point*. When concurrent reads observe the same root-cause fault and the atomic CAS in R83c-1 selects exactly one as the transitioning read, the matrix's precedence is overridden for non-transitioning concurrent reads: those reads observe the post-transition state and throw `TenantKekUnavailableException` (the higher-precedence type) even though the root-cause was a `TenantKekRevokedException` event. This is intentional — only the transitioning read carries the revocation-class signal; subsequent observers see the state-machine signal. The matrix precedence describes the discrimination order at a single point in time, not the ordering across a fault's full propagation.

### Rekey API (flavor 3)



---

## Notes
