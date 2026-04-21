---
problem: "kms-integration-model"
date: "2026-04-21"
version: 1
status: "confirmed"
supersedes: null
depends_on:
  - "three-tier-key-hierarchy"
  - "tenant-key-revocation-and-external-rotation"
files:
  - "modules/jlsm-core/src/main/java/jlsm/core/io/EncryptionKeyHolder.java"
  - ".spec/domains/encryption/primitives-lifecycle.md"
---

# ADR — KMS Integration Model

## Document Links

| Document | Path |
|----------|------|
| Decision log | [log.md](log.md) |
| Constraints | [constraints.md](constraints.md) |
| Prerequisites | [`../three-tier-key-hierarchy/adr.md`](../three-tier-key-hierarchy/adr.md), [`../tenant-key-revocation-and-external-rotation/adr.md`](../tenant-key-revocation-and-external-rotation/adr.md) |

## KB Sources Used in This Decision

| Subject | Role | Link |
|---------|------|------|
| Encryption Key Rotation Patterns | KMS integration + outage tolerance | [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |
| JVM Key Handling Patterns | Arena-backed caching, zeroize-on-close | [`.kb/systems/security/jvm-key-handling-patterns.md`](../../.kb/systems/security/jvm-key-handling-patterns.md) |
| Three-Level Key Hierarchy | KmsClient SPI shape | [`.kb/systems/security/three-level-key-hierarchy.md`](../../.kb/systems/security/three-level-key-hierarchy.md) |

---

## Files Constrained by This Decision

- `modules/jlsm-core/src/main/java/jlsm/core/io/EncryptionKeyHolder.java` — composes a `KmsClient`
- `.spec/domains/encryption/primitives-lifecycle.md` — extend with KmsClient SPI contract

## Problem

Define how jlsm interacts with external KMS implementations under flavor 3
(BYO-KMS). Decide: `KmsClient` SPI shape, cache TTL for unwrapped domain
KEKs, retry/backoff policy, timeout defaults, encryption-context semantics,
and observability requirements.

## Constraints That Drove This Decision

Inherited from prior ADRs:
- Per-tenant isolation (ADR A): one tenant's KMS traffic cannot affect others
- Three-flavor KMS model (ADR A): `none` / `local` (reference) / `external` (SPI)
- Three-state failure machine with N=5 permanent-error threshold (ADR D)
- Proof-of-control sentinel pattern for rekey (ADR D)
- Panama FFM Arena-backed key material; zeroize-on-close

## Decision

### `KmsClient` SPI

```java
public interface KmsClient extends AutoCloseable {
    WrapResult wrapKek(
        MemorySegment plaintextKek,
        KekRef kekRef,
        Map<String,String> encryptionContext
    ) throws KmsException;

    UnwrapResult unwrapKek(
        ByteBuffer wrappedBytes,
        KekRef kekRef,
        Map<String,String> encryptionContext
    ) throws KmsException;

    boolean isUsable(KekRef kekRef) throws KmsException;  // polling health check

    @Override void close();
}

// Exception hierarchy
sealed interface KmsException permits KmsTransientException, KmsPermanentException {}
final class KmsTransientException extends Exception implements KmsException { /* throttling, timeout, 5xx */ }
final class KmsPermanentException extends Exception implements KmsException { /* AccessDenied, KeyDisabled, KeyNotFound */ }
final class KmsRateLimitExceededException extends KmsTransientException { /* subclass for observability */ }
```

- `KekRef` is an opaque string identifier (ARN for AWS, resource name for GCP, path for Vault, etc.). jlsm does not parse it.
- `encryptionContext` is the AWS-KMS-style side-channel: jlsm passes
  `tenantId` and `domainId` at minimum for KMS-side audit. Context is
  bound as AAD to the wrap/unwrap operation so KMS can reject
  cross-context replay.
- `isUsable` is the polling primitive; returns true if a sentinel
  unwrap would succeed, false if the KEK is disabled/deleted, throws
  for transient errors.
- Arena-backed `MemorySegment` for plaintext KEK material on the write
  side. `UnwrapResult` returns a `MemorySegment` the caller owns
  (Arena-scoped) that gets zeroized on close.

### Cache TTL: 30 min default (configurable)

- Unwrapped domain KEKs are cached in Arena-backed `MemorySegment`
  for **30 minutes** by default.
- On TTL expiry: segment zeroized, Arena closed, next access
  re-unwraps from KMS.
- Cache is **per-tenant**: eviction under memory pressure uses an LRU
  scoped by tenant. One tenant's eviction does not purge another's hot
  state.

### Retry / backoff

- **Single-call timeout: 10 s** per KMS operation (configurable).
- **Transient errors**: retry up to **3 attempts** with exponential
  backoff (100 ms → 400 ms → 1.6 s) and ±25% jitter. If all retries
  exhausted, propagate as `KmsTransientException` — the caller path
  surfaces this to the application as retryable.
- **Permanent errors**: no retry; escalate to per-tenant failure
  counter (ADR D). The Nth permanent failure transitions the tenant
  to `grace-read-only`.
- **Rate-limit errors** (`KmsRateLimitExceededException`): treated as
  transient; retried per the same policy. No internal queuing — jlsm
  does not buffer KMS operations.

### Encryption context

- jlsm passes these keys in every wrap/unwrap call:
  - `tenantId` — opaque tenant identifier
  - `domainId` — opaque domain identifier
  - `purpose` — one of `domain_kek`, `rekey_sentinel` (ADR D), or other
    reserved values as introduced
- KMS binds these as AAD; a ciphertext produced under context
  `{tenant=A}` cannot be unwrapped under context `{tenant=B}`.
- jlsm does not include the plaintext key material in the context.

### Connection management

- `KmsClient` implementations own their connection pooling. AWS SDK,
  Vault client, and native KMIP clients all pool natively.
- jlsm does **not** manage KMS connection state, retry queues, or
  circuit-breaker logic. Implementations may do so but are not required.

### Observability

Metrics (emitted via a `KmsObserver` interface deployers implement):

| Metric | Type | Tags |
|--------|------|------|
| `kms.operation.count` | counter | `tenantId`, `operation` (`wrap`/`unwrap`/`isUsable`), `outcome` (`success`/`transient`/`permanent`) |
| `kms.operation.latency` | histogram | `tenantId`, `operation` |
| `kms.cache.hit.rate` | gauge | `tenantId` |
| `kms.retry.count` | counter | `tenantId`, `operation`, `attempt` (1-indexed) |

Structured log events emitted on:
- State transitions (per ADR D): `tenantKekStateTransition`
- Rekey lifecycle: `rekeyStarted`, `rekeyBatchComplete`, `rekeyComplete`, `rekeyAborted`

The observer is optional — no-op default. Deployers plumb to their
telemetry stack (Prometheus, OpenTelemetry, etc.).

## Rationale

### Why 30-minute cache TTL

- Shorter: higher KMS load, more latency on every domain open.
- Longer: longer window for unwrapped material to exist in memory
  after revocation.
- 30 min balances — within the typical 1h grace window from ADR D
  but short enough that cache expiry provides natural revocation
  detection without active polling.
- Configurable per deployment; paranoid deployments shorten to
  5–10 min, cost-sensitive ones extend to hours.

### Why 3 retries with exponential backoff

- Industry standard for cloud SDKs (AWS SDK default).
- Total wait in worst case: 100+400+1600 ms ≈ 2 seconds; well within
  the 10 s single-call timeout.
- Permanent errors don't retry because retrying won't help (the KEK
  isn't coming back).

### Why encryption context includes tenantId + domainId

- KMS-side audit: operators can see "which tenant/domain used this
  key at this time" without plaintext access.
- AAD binding: ciphertext cannot be cross-wired between tenants even
  if attacker has write access to the wrapped bytes.
- Avoids re-encoding identifiers in the wrapped payload where KMS
  can't validate them.

### Why `KmsClient` implementations own connection pooling

- Cloud SDKs and Vault clients already implement sophisticated pooling,
  backoff, and circuit-breaker logic. jlsm should not reimplement.
- Plugin implementations are free to choose: lightweight (`HttpClient`
  + per-call connection) or heavy (pooled + retry-queue).

## Implementation Guidance

The reference `LocalKmsClient` (flavor 2) ships with jlsm:

- Stores wrapped-under-master-key blobs in a configurable directory.
- Master key loaded from env var or file at construction.
- Supports rotation by incrementing a master-key-version file (for dev/test only).
- Documented insecure: `@ApiStatus.Experimental` annotation + Javadoc
  warning that flavor 2 is not for production.

Example third-party implementations (not shipped):

- `AwsKmsClient` — wraps AWS KMS SDK; encryption context is passed
  directly to `Encrypt`/`Decrypt` AWS calls.
- `VaultTransitClient` — uses Vault Transit's `encrypt`/`decrypt` with
  `context` param.
- `GcpKmsClient` — Google Cloud KMS.
- `KmipClient` — generic KMIP v1.x.

## What This Decision Does NOT Solve

- **HSM integration specifics** (FIPS 140-2 Level 3 compliance, hardware
  attestation) — depends on the concrete `KmsClient` plugin;
  out of scope for the core SPI.
- **KMS migration between vendors** — covered by ADR D's rekey flow
  (tenant provisions new KekRef in new KMS, calls rekey API).
- **Audit log retention** — deployer-plumbed; jlsm emits events but
  does not persist audit logs.
- **KMS client implementations for specific vendors** — each is its own
  module / jar.

## Conditions for Revision

- **30 min cache TTL proves too long or too short** under production
  telemetry — tunable; structural change only if a completely
  different caching model (e.g., continuous refresh) is needed.
- **Retry policy amplifies outages** under real traffic — consider
  per-`KmsClient` circuit breaker; currently optional, could become
  required.
- **New KMS vendor requires a method not in the SPI** (e.g., key
  derivation offloaded to KMS, per some HSM-native designs) — add
  optional SPI methods without breaking existing implementations.
- **Audit requirements mandate a specific event format** (e.g., CEF,
  LEEF) — add a standard event schema alongside the current
  `KmsObserver`.

---
*Confirmed by: user deliberation | Date: 2026-04-21*
