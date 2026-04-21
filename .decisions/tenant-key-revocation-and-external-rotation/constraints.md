---
problem: "How jlsm handles tenant KEK revocation and external rotation when tenant controls their own KMS"
slug: "tenant-key-revocation-and-external-rotation"
captured: "2026-04-21"
status: "draft"
---

# Constraint Profile — tenant-key-revocation-and-external-rotation

## Problem Statement

In flavor 3 (BYO-KMS, confirmed in `three-tier-key-hierarchy`), the tenant
owns their KEK and may rotate or revoke it externally in their own KMS
without coordinating with jlsm. Decide:

1. How jlsm detects external rotation / revocation
2. The rekey API contract shape (what tenants call when they want to
   intentionally switch to a new CMK)
3. What happens when tenant KEK becomes unusable without an API call
4. Whether dual-reference (old + new KEK) is supported during migration
5. Whether explicit tenant-decommission is a first-class operation

This ADR only applies to flavor 3. The `local` reference flavor is self-
consistent (jlsm rotates its own keys; no external coordinator).

## Constraints

### Scale

**Unbounded tenants × unbounded domains per tenant** (inherited from
`three-tier-key-hierarchy`). A single tenant may have thousands of domains;
rekeying a tenant cannot be a blocking synchronous call that holds the
universe. Rekey must be **streaming / paginated / bounded-per-call** so
any single operation completes in bounded time. Progress must be
resumable across jlsm restarts.

### Resources

- JVM (Java 25), Panama FFM Arena-backed key material at all tiers.
- KMS rate-limited (AWS KMS ~30K ops/sec/region). Rekey operations must
  be pace-controlled so one tenant's rekey cannot starve all other
  tenants' cache-miss traffic.
- Polling (opt-in) must not amplify KMS load — polling cadence is
  configurable per tenant, defaults conservative.

### Complexity Budget

- Not penalised per project feedback.
- Three-state machine (healthy / grace-read-only / failed) is acceptable
  and was explicitly chosen by the user.

### Accuracy / Correctness

- **No silent data corruption.** On unwrap failure, jlsm must never write
  ciphertext that would be later unreadable. Writes fail-fast; reads fall
  back to cached domain KEKs within the grace window.
- **No false-positive failure state.** Transient KMS errors (rate limit,
  network blip) must not prematurely enter grace-read-only. Detection
  requires multiple consecutive failures with jittered backoff per
  standard retry discipline.
- **Idempotent rekey** — a retried rekey call (same `(tenantId, oldKekRef,
  newKekRef)` triple) must not corrupt the registry or double-wrap DEKs.
  Resume-after-crash must produce an identical end state.
- **Authenticated rekey** — only a caller with proof of control over both
  old and new KEKs can initiate a rekey. Typically: the caller unwraps a
  sentinel blob under the old KEK and re-wraps it under the new KEK; jlsm
  verifies both sides before accepting the rekey request.

### Operational Requirements

- **Rekey coordination: API primary + opt-in polling** (confirmed
  2026-04-21). The rekey API is the normative path. Polling is an
  opt-in safety net for deployers who want early detection without
  relying on tenant-operator discipline.
- **Three-state failure machine** (confirmed 2026-04-21):
  - `healthy` — tenant KEK unwraps successfully; all operations normal
  - `grace-read-only` — N consecutive unwrap failures; writes rejected;
    reads continue using cached domain KEKs within their individual
    TTLs (ADR C)
  - `failed` — grace window exhausted or cache TTL expired; reads
    rejected with a specific error surface
- **Grace window duration** must be configurable per deployer. Default
  target: long enough for ops alerting but short enough that cached
  domain KEKs don't stay in memory longer than necessary (e.g., 1 hour
  default).
- **State transitions** must be observable (metrics / events) so
  deployers can alert on a tenant entering grace-read-only.
- **Rekey must not block unrelated tenants.** Each rekey operation is
  scoped to its own tenant.

### Fit

- Integrates with the `KmsClient` SPI from ADR A — detection fires when
  `KmsClient.unwrapKek` returns a `KmsUnwrapException` that classifies
  as permanent (not retryable).
- Sharded registry layout (from ADR A) naturally supports streaming
  rekey: iterate shards, rewrap each domain's KEK, commit each shard
  atomically. A crash mid-rekey resumes at the next un-rewrapped shard.
- Dual-reference (old + new KEK refs) is a registry-level concern:
  during migration, each domain's wrapped-KEK entry may carry two
  wrappers (old + new). Readers prefer new; fall back to old. New
  writes only under new. When all entries carry new-only, migration
  completes.

## Key Constraints (most narrowing)

1. **Streaming / resumable rekey under unbounded scale** — rules out any
   single-atomic-rekey contract; forces per-shard commit discipline.
2. **Three-state failure machine with observable transitions** — forces
   per-tenant operational state tracking; rules out silent degraded
   modes.
3. **Authenticated rekey + idempotent retries** — rules out naive
   "accept new KEK ref and reconfigure" patterns; requires proof-of-
   control on both old and new.

## Unknown / Not Specified

- **Grace window default duration** — left configurable; the ADR should
  propose a default (1h) but not make it a hard pin.
- **Polling cadence default** — similar; proposed default but tunable.
- **Explicit decommission ("forget this tenant")** — whether this is
  first-class in this ADR or deferred. Leaning toward: scope it as a
  follow-on "tenant lifecycle" ADR; this ADR focuses on revocation
  handling.

## Confirmed In-Session (2026-04-21)

- Rekey posture: **both API + opt-in polling**
- Failure semantics: **hard fail with bounded grace** (three-state)
- ADR scope covers flavor 3 only; flavor 2 (`local`) is out of scope.
