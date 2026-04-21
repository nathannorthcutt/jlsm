---
problem: "tenant-key-revocation-and-external-rotation"
date: "2026-04-21"
version: 1
status: "confirmed"
supersedes: null
depends_on:
  - "three-tier-key-hierarchy"
files:
  - "modules/jlsm-core/src/main/java/jlsm/core/io/EncryptionKeyHolder.java"
  - ".spec/domains/encryption/primitives-lifecycle.md"
---

# ADR — Tenant Key Revocation and External Rotation

## Document Links

| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |
| Prerequisite ADR | [`../three-tier-key-hierarchy/adr.md`](../three-tier-key-hierarchy/adr.md) |

## KB Sources Used in This Decision

| Subject | Role in decision | Link |
|---------|-----------------|------|
| Three-Level Key Hierarchy | Prerequisite envelope design | [`.kb/systems/security/three-level-key-hierarchy.md`](../../.kb/systems/security/three-level-key-hierarchy.md) |
| Encryption Key Rotation Patterns | Rotation state machine, KMS outage handling | [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |

---

## Files Constrained by This Decision

- `modules/jlsm-core/src/main/java/jlsm/core/io/EncryptionKeyHolder.java` — adds rekey and three-state lifecycle management per tenant
- `.spec/domains/encryption/primitives-lifecycle.md` — extend with rekey API and revocation-state requirements

## Problem

Under `three-tier-key-hierarchy`, tenants operating in flavor 3 (BYO-KMS)
own their KEK and may rotate or revoke it externally without coordinating
with jlsm. Decide how jlsm detects this, what API the tenant uses to
signal an intended rotation, what happens when a KEK becomes unusable
without such a signal, and how migration between old and new KEKs is
managed.

## Constraints That Drove This Decision

- **Unbounded tenants × unbounded domains per tenant** (inherited from
  ADR A): rekey cannot be a single blocking synchronous call.
- **Rekey coordination posture: API primary + opt-in polling**
  (user-confirmed).
- **Failure semantics: hard fail with bounded grace** (three-state,
  user-confirmed).
- **No silent data corruption; no false-positive failure transitions.**

## Decision

A **three-part design**:

### 1. Rekey coordination — API primary, opt-in polling

**Primary:** `rekey(tenantId, oldKekRef, newKekRef, continuationToken?)`
API. Authenticated via **proof-of-control**: caller supplies a
nonce-bound sentinel blob that is unwrapped under `oldKekRef` and
re-wrapped under `newKekRef`. jlsm verifies both operations before
accepting the rekey request. Returns `{continuationToken | null,
shardsProcessed, shardsRemaining}`; caller iterates until
`continuationToken = null`.

**Secondary (opt-in):** per-tenant configurable polling cadence (default
15 min). Polls by unwrapping a sentinel blob; a classifier distinguishes
**transient** (throttling, timeout — retry without counting) from
**permanent** (AccessDenied, KeyDisabled, NotFound — count toward the
failure threshold) errors.

### 2. Rekey execution — streaming, resumable, per-shard atomic

- Iterates the tenant's sharded domain-KEK registry (from ADR A) one
  bounded batch at a time (default 100 domains per call).
- Per-shard: unwrap with `oldKekRef`, rewrap with `newKekRef`, atomic
  commit (temp+fsync+rename) — per the sharded-registry pattern from
  ADR A.
- A per-tenant `rekey-progress` file captures
  `{oldKekRef, newKekRef, nextShardIndex, startedAt}`. Crash-resumable.
- Per-tenant isolation: one tenant's rekey cannot starve other tenants'
  KMS traffic.

**Dual-reference during migration.** Each shard entry is a
`(kekRef, wrappedBlob)` tuple; in-progress migration *adds* a
`(newKekRef, newWrappedBlob)` alongside rather than replacing.
- **Reads** prefer `newKekRef`; fall back to `oldKekRef` if unwrap fails
  (indicating the shard hasn't migrated yet).
- **Writes** always use `newKekRef` once rekey has started for the
  tenant. No write lands under old during migration.
- **Completion:** when every shard carries `newKekRef`-only, `oldKekRef`
  entries are GC'd. Tenant operator may then disable/delete the old CMK
  in their KMS.

### 3. Three-state failure machine per tenant

```
   healthy  ──(N=5 permanent failures, jittered backoff)──►  grace-read-only
      ▲                                                          │
      │                                                          │  (grace window exhausts:
      │  (rekey API success, OR polling                          │   default 1h
      │   detects KEK usable again)                              │   OR all cached domain KEKs
      │                                                          │   TTL-expire per ADR C)
      │                                                          ▼
      └───────────────────────────────────────────────── failed
```

- **`healthy`**: all operations normal.
- **`grace-read-only`**: writes rejected with
  `TenantKekUnavailableException` identifying the tenant; reads continue
  using cached domain KEKs until their individual TTLs (ADR C) expire or
  the grace window exhausts.
- **`failed`**: reads and writes both rejected. Explicit rekey with a
  usable new KEK is required to return to `healthy`.
- **State transitions are observable** via metrics (`tenant_state` gauge
  per tenant) and structured log events (`tenantKekStateTransition`).

### 4. Explicit decommission — deferred

"Tenant has left; drop their data" is a broader **tenant lifecycle**
decision (data erasure, audit log retention, catalog cleanup). This ADR
does not address it. Deferred to a future `tenant-lifecycle` ADR; a
deferred stub is recorded for compliance teams to discover.

## Rationale

### Why API + opt-in polling

The API is the normative path: tenant operators control their KMS and
must coordinate rotations with jlsm. Polling is a defence-in-depth for
deployers who cannot rely on operator discipline (shared-ops
environments) — it surfaces the failure state before cache TTLs
expire, enabling alerts with less manual runbook burden.

### Why streaming paginated rekey

Unbounded scale rules out atomic single-call rekey. Streaming with
per-shard atomic commit lets jlsm make progress under memory bounds,
resume across crashes, and not block unrelated tenants' KMS traffic.

### Why dual-reference

Resumable rekey requires that partially-migrated state be valid — reads
must succeed whether a shard has migrated or not. Dual-reference makes
this invariant trivially true: `newKekRef` then `oldKekRef` fallback.
Writes use `newKekRef` only to prevent regress.

### Why hard fail with bounded grace

- Pure hard-fail (reject immediately on first unwrap failure) is too
  intolerant of transient errors — a 30-second KMS hiccup would cascade
  to tenant outage.
- Pure soft-fail (indefinitely serve cached state) masks real revocation
  and accumulates data under a dead KEK.
- Hard fail with bounded grace gives alert systems time to notify
  operators (grace window), while forcing a clear failure signal within
  a bounded SLA.

### Why N=5 and 1h defaults

Pragmatic defaults:
- **N=5 consecutive permanent-class failures** with jittered backoff —
  small enough to detect real revocation within ~5min, large enough to
  ride out transient network/throttling issues.
- **1h grace window** — long enough for human operator to be paged and
  respond, short enough that cached domain KEKs don't linger long after
  they're known-untrustworthy.

Both are configurable per deployment.

## Implementation Guidance

```java
// Primary API (flavor 3 only)
public interface EncryptionKeyHolder {
    RekeyResult rekey(
        TenantId tenantId,
        KekRef oldKekRef,
        KekRef newKekRef,
        RekeySentinel proofOfControl,
        ContinuationToken token  // null on first call
    ) throws TenantKekUnavailableException, IOException;

    // Opt-in polling (deployer-configured; runs in background)
    TenantState currentState(TenantId tenantId);  // healthy / graceReadOnly / failed

    // Cache inspection (for tests and monitoring)
    Optional<Instant> graceWindowExhaustsAt(TenantId tenantId);
}

record RekeyResult(
    ContinuationToken next,   // null when rekey complete
    int shardsProcessed,
    int shardsRemaining,
    Instant startedAt
) {}

record RekeySentinel(
    byte[] nonceBoundPlaintext,   // unwrapped under oldKekRef
    byte[] rewrappedUnderNew,     // re-wrapped under newKekRef
    Instant timestamp             // freshness check; must be within 5 min
) {}
```

- Progress files live alongside the sharded registry, named per-tenant.
- Grace window tracking is in-process state; a restart resets the grace
  timer only if the tenant was transitioning, healthy state never.
- State transition events emit via a `KeyStateObserver` interface
  (deployer plugs in metrics/logging).

## What This Decision Does NOT Solve

- **Tenant decommission semantics** — deferred to `tenant-lifecycle` ADR
  (stub created).
- **DEK rotation epoch semantics** — WD-03 / separate rotation concern.
- **KMS client retry/backoff defaults, cache TTL values** — ADR C
  (`kms-integration-model`).
- **Flavor 2 (`local` KMS) rotation** — self-consistent; no external
  rotator exists; rotation handled by the internal reference impl.
- **Cross-tenant rekey coordination** — each tenant is independent; no
  cross-tenant consistency required.

## Conditions for Revision

- **N=5 or 1h defaults prove wrong in production**: production telemetry
  shows classifier false-positives or unresolved grace-exhaustion
  incidents. Tuning would be parameter-only unless a structural change
  is needed.
- **Proof-of-control sentinel proves spoofable**: if replay attacks
  surface despite nonce+timestamp, move to a KMS-side attestation
  (AWS KMS `GenerateDataKeyPairWithoutPlaintext` + signature).
- **Explicit decommission becomes a compliance blocker**: pull it into
  scope, potentially re-splitting this ADR.
- **Sharded-registry layout chosen at spec-authoring is not atomic per-
  shard**: the per-shard-atomic commit assumption needs verification at
  implementation time.

---
*Confirmed by: user deliberation | Date: 2026-04-21*
*Full design: [evaluation.md](evaluation.md)*
