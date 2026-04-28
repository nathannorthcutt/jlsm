---
{
  "id": "encryption.primitives-lifecycle.runtime",
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

# encryption.primitives-lifecycle.runtime — Runtime — Key Cache, WAL Mapping, Response Padding

This spec was carved from `encryption.primitives-lifecycle` during a domain subdivision. The
parent retains cross-cutting requirements that span multiple sub-domains;
the requirements below are specific to this concern.

R51. Query options must support an opt-in `padResults` flag. When enabled, the query executor must pad the result list size to the next power of 2 by appending distinguishable padding entries.

R52. Padding entries must be distinguishable from real results. JlsmDocument must provide an `isPadding()` method (or equivalent marker) that returns `true` for padding entries and `false` for real documents.

R53. When the actual result count is already a power of 2, no padding must be added. When the actual result count is 0, no padding must be added.

R54. The padding size must be computed as: if actual count is 0, padded count is 0; if actual count is 1, padded count is 1; if actual count is a power of 2 (and greater than 1), padded count equals actual count; otherwise padded count is `Integer.highestOneBit(actual) << 1`. The padded count must never exceed Integer.MAX_VALUE.

### Input validation and error handling

R75. For WAL encryption per F42 (`wal.encryption`), each tenant carries a synthetic **`_wal` data domain** per `three-tier-key-hierarchy` ADR. WAL metadata-envelope ciphertext is encrypted under a DEK belonging to the `_wal` domain; field payload bytes embedded in WAL records are the per-field ciphertext already produced at ingress (R74b) and are not encrypted again by the WAL envelope. The `_wal` domain identifier is **reserved** and must be runtime-enforced: the public `DomainId` constructor must reject the string `_wal` with IllegalArgumentException from any application caller. Construction of the `_wal` domain is permitted only via a sanctioned internal factory path — `DomainId.forWal()` — that is either package-private to the jlsm WAL subsystem or gated on caller identity (e.g., via `StackWalker`) so that only internal code can construct it. Violating constructions from application callers must throw IllegalArgumentException. This promotes the reservation from a naming convention / javadoc note into a runtime invariant, preventing registry-collision attacks where an application-authored domain shadows the jlsm-internal WAL domain and aliases WAL DEKs into application-visible storage.

R75a. F42's "KEK" input parameter at WAL builder construction must resolve internally to the tenant's `_wal` domain DEK-resolver. No F42 spec amendment is required; the mapping is an implementation-level resolution documented in this spec and in a Verification Note on `wal.encryption`.

R75b. Retiring a retired Tenant KEK (R33a) must not precede the replay or compaction of WAL segments encrypted under the `_wal` domain's DEKs whose wrapping chain depends on the retired Tenant KEK. This is the grace-period invariant from `three-tier-key-hierarchy` ADR enforced at WAL retention.

R75c. The grace-period invariant in R75b is enforced via the **on-disk liveness witness** mechanism of R78e: the per-tenant counter of SSTables and WAL segments whose wrapping chain depends on `oldKekRef` includes `_wal` domain WAL segments. Rekey completion (R78e, R78f) cannot complete — and therefore the old Tenant KEK cannot be marked eligible for tenant-side deletion — until that counter reaches zero. This gives the invariant a mechanical enforcement path: tenant operators relying on the `rekey` API to signal "safe to delete old KEK" inherit the protection automatically.

R75d. For the polling path (R79) that does NOT invoke a rekey (transient outage detection, not migration), no grace-period enforcement is required — polling does not transition the tenant KEK to retired.

### Three-state failure machine (flavor 3)

R91. Unwrapped domain KEKs and DEKs held in the in-memory cache must expire after a configurable TTL. The default TTL, per-tenant LRU scoping under memory pressure, and observability metrics are specified in the `kms-integration-model` ADR; this spec references that ADR as normative for the concrete defaults and eviction policy. On TTL expiry or LRU eviction, the off-heap MemorySegment holding key material must be zeroised per R69 before the segment is released. Zeroisation must occur even if an exception is thrown during eviction. The configurable TTL must carry a finite, implementation-enforced upper bound not exceeding **24 hours**. The bound must be finite so that TTL-based expiry arithmetic (e.g., `Instant.plus(ttl)`) cannot overflow or produce a past/invalid expiry time for any supported `Instant` within the lifetime of a holder, and so that R69 / R91 zeroisation is guaranteed to occur within a bounded window regardless of deployer configuration. A lower maximum is permitted for deployments that require shorter cache residency; cross-reference `kms-integration-model` ADR for the rationale and default bound. `[UNVERIFIED: the 24h upper bound is the audit-landed constant; a future research step should align this with comparable library defaults (AWS Encryption SDK CMM, Google Tink KeysetHandle) before the ADR pins a normative value — future research gate]`.

R91a. Cache eviction must be per-tenant — one tenant's cache pressure or eviction storm must not evict another tenant's cached entries, consistent with the per-tenant isolation invariant from `three-tier-key-hierarchy` ADR.

R91b. Cache entries are keyed by the four-tuple `(tenantId, domainId, tableId, dekVersion)` for DEKs and `(tenantId, domainId)` for domain KEKs. A cache lookup must match the full tuple; a partial match must not return a cached entry.

R91c. The cache must remain usable while a tenant is in the `grace-read-only` state (R76): reads against cached entries within their remaining TTL must succeed. New unwrap attempts against the KMS are blocked during `grace-read-only` per R76's state semantics, but no additional cache-side guard is required.

### Tenant encryption flavors



---

## Notes
