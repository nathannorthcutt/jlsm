---
title: "DEK Caching Policies for Multi-Tenant LSM"
aliases: ["data key cache", "DEK cache", "cryptographic materials cache", "key cache eviction", "multi-tenant key cache"]
topic: "systems"
category: "security"
tags: ["encryption", "dek", "cache", "lru", "ttl", "multi-tenant", "noisy-neighbor", "aws-encryption-sdk", "caching-cmm", "security-thresholds"]
complexity:
  time_build: "O(1) insert, O(1) lookup"
  time_query: "O(1) cache hit; O(1) unwrap on miss"
  space: "O(capacity) bounded by configured entry limit"
research_status: "active"
confidence: "high"
last_researched: "2026-04-23"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/encryption/"
related:
  - "systems/security/jvm-key-handling-patterns.md"
  - "systems/security/three-level-key-hierarchy.md"
  - "systems/security/dek-revocation-vs-rotation.md"
  - "systems/security/encryption-key-rotation-patterns.md"
  - "patterns/resource-management/defensive-copy-accessor-defeats-zeroize-on-close.md"
decision_refs: []
sources:
  - url: "https://docs.aws.amazon.com/encryption-sdk/latest/developer-guide/data-key-caching.html"
    title: "AWS Encryption SDK — Data key caching"
    accessed: "2026-04-23"
    type: "docs"
  - url: "https://docs.aws.amazon.com/encryption-sdk/latest/developer-guide/thresholds.html"
    title: "AWS Encryption SDK — Setting cache security thresholds"
    accessed: "2026-04-23"
    type: "docs"
  - url: "https://docs.aws.amazon.com/encryption-sdk/latest/developer-guide/data-caching-details.html"
    title: "AWS Encryption SDK — Data key caching details"
    accessed: "2026-04-23"
    type: "docs"
  - url: "https://github.com/awslabs/aws-encryption-sdk-specification/blob/master/framework/caching-cmm.md"
    title: "AWS Encryption SDK Specification — Caching CMM"
    accessed: "2026-04-23"
    type: "repo"
  - url: "https://aws.amazon.com/blogs/architecture/simplify-multi-tenant-encryption-with-a-cost-conscious-aws-kms-key-strategy/"
    title: "AWS Architecture Blog — Multi-tenant encryption with KMS"
    accessed: "2026-04-23"
    type: "blog"
  - url: "https://patents.justia.com/patent/10680804"
    title: "Salesforce Patent — Distributed key caching for encrypted keys"
    accessed: "2026-04-23"
    type: "patent"
---

# DEK Caching Policies for Multi-Tenant LSM

## summary

A DEK cache bounds per-request KMS unwrap cost by keeping unwrapped data keys in
memory across operations. In a multi-tenant system, the cache must enforce three
orthogonal bounds: **age** (TTL, to bound revocation lag), **usage** (max messages
or bytes per key, to bound blast radius on compromise), and **capacity**
(per-tenant quota, to prevent noisy-neighbor eviction). AWS Encryption SDK's
**caching CMM** formalises this as required TTL plus optional message/byte
thresholds, with LRU eviction and a **partition name** to isolate tenants. For
jlsm the same pattern applies: cache keys carry `(domain_id, dek_handle,
dek_version)`, per-tenant capacity quotas, and a push-based invalidation channel
tied to revocation events so cached DEKs cannot survive a KMS disable/destroy.

## how-it-works

### three-orthogonal-bounds

| Bound | Purpose | AWS default | Revocation lag impact |
|-------|---------|-------------|------------------------|
| **Max age (TTL)** | Bound staleness; force re-unwrap so revocation propagates | **Required — no default** | TTL = max lag |
| **Max messages encrypted** | Limit the number of ciphertexts under one cached DEK | `2^32` | None (usage-bound, not time-bound) |
| **Max bytes encrypted** | Limit total volume under one cached DEK | `2^63 − 1` | None |
| **Capacity (entries)** | Bound cache memory; per-tenant quota for isolation | Implementation-defined | None |

All three must be satisfied for a cache hit; violation evicts the entry at
detection time (not lazily on next lookup).

### cache-key-identifier

A cache entry is matched only when **all** identifying fields match the request:

- **Algorithm suite** (e.g., AES-GCM-256)
- **Encryption context** (the AAD used during wrap; always compared, even if empty)
- **Partition name** (string that identifies the caching CMM — the multi-tenant
  isolation lever)
- **Encrypted data keys** (decrypt-only — prevents accidental reuse across
  differently-wrapped DEKs)

For jlsm's three-tier hierarchy, the cache identifier becomes:

```
cache_key = (partition = domain_id) :: (algo) :: (ctx = {tenant, table}) :: (dek_handle, dek_version)
```

### eviction-policy

**LRU + threshold-triggered**. LRU bounds capacity; threshold checks run on
every lookup and evict entries that fail any bound. An entry that passes
thresholds is returned and has its "age" and "use count" incremented before
return.

### multi-tenant-isolation

Two models:

1. **Partition-per-tenant, shared capacity** — One cache, one LRU list, entries
   tagged by partition. Simple but susceptible to noisy-neighbor: a high-
   throughput tenant can evict all of a quieter tenant's entries.

2. **Segregated-cache-per-tenant, bounded per-tenant capacity** — Each tenant
   gets its own LRU list with its own entry cap. Prevents noisy-neighbor but
   requires per-tenant sizing and has higher aggregate memory ceiling.

The jlsm-appropriate model is **segregated caches per domain (tenant) with a
global memory budget enforced via the `ArenaBufferPool`**. This mirrors how jlsm
already handles block-cache memory: tenant-aware admission, bounded by a shared
budget, with per-tenant quotas.

### revocation-and-invalidation

TTL alone is insufficient for timely revocation response. Three complementary
channels:

1. **TTL expiry (pull)** — Entry expires → next lookup re-unwraps via KMS → if
   revoked, unwrap fails. Latency bound by TTL setting
2. **Push invalidation (push)** — Admin API or KMS event handler invalidates
   specific entries or all entries under a domain KEK (see [DEK Revocation vs
   Rotation](./dek-revocation-vs-rotation.md) for the full revocation model)
3. **Zeroisation on eviction** — When an entry is evicted (for any reason),
   the DEK bytes are zeroised via `MemorySegment.fill(0)` on the owning Arena
   (see [JVM Key Handling Patterns](./jvm-key-handling-patterns.md))

## tradeoffs

### strengths

- **KMS call amortisation** — A DEK unwrapped once can encrypt/decrypt many
  operations, dropping KMS QPS and latency dramatically (hot path: O(1) cache
  lookup vs O(KMS-roundtrip) unwrap)
- **Explicit security thresholds** — Age, message count, and byte count limits
  formalise the exposure window; easy to reason about compromise blast radius
- **Tenant isolation via partitioning** — A partition-aware cache keys each
  entry by tenant; correctness does not depend on application code avoiding
  cross-tenant use

### weaknesses

- **TTL window = revocation lag** — If a DEK is revoked in KMS, a cached copy
  remains usable until TTL expiry (or push-invalidation). Short TTLs fix this
  but raise unwrap cost; trade-off is workload-dependent
- **Plaintext DEK lives in memory for TTL duration** — Same attack surface as
  any in-process secret (heap dump, cold-boot); mitigated by off-heap Arena
  storage per [JVM Key Handling Patterns](./jvm-key-handling-patterns.md), but
  not eliminated
- **Noisy-neighbor without per-tenant quotas** — A single hot tenant can evict
  all of a quieter tenant's DEKs, forcing that tenant to pay unwrap latency
  on every cache miss
- **Cache correctness depends on identifier completeness** — Missing the
  encryption context in the cache key is a classic wrong-key-returned bug; the
  AWS spec explicitly requires the context be compared even when empty

### compared-to-alternatives

- vs no caching — Every encrypt/decrypt hits KMS. Safe but slow; viable only
  when KMS is local (in-process HSM) or request rates are very low
- vs unbounded cache — Simple but unbounded memory growth; loses all
  revocation-lag guarantees; not production-viable
- vs distributed cache (e.g., shared Redis) — Solves cold-start across servers
  but adds network latency and requires the shared store to be at least as
  trusted as each server; see Salesforce patent for a design variant

## implementation-notes

### recommended-thresholds-for-jlsm

| Parameter | Proposed jlsm default | Rationale |
|-----------|-----------------------|-----------|
| Max age (TTL) | **5 minutes** | Bounds revocation lag; short enough for incident response, long enough to amortise KMS unwrap for sustained ingest |
| Max messages | Not used (LSM does not have discrete "messages") | Replace with "max writes per DEK" bound from key-rotation policy instead |
| Max bytes | **Proportional to compaction cycle** | Aim for DEK to survive one full compaction cycle's worth of writes at the domain level |
| Per-tenant capacity | **32–128 entries per domain** | Enough for `(table × version)` working set in a typical multi-table domain |
| Global memory budget | **Bounded by `ArenaBufferPool` quota** | Consistent with existing memory discipline |

### cache-eviction-sequence

When an entry is evicted (LRU, TTL, threshold, or push-invalidation):

1. Mark entry as unusable (atomic CAS on a "valid" flag) to prevent new uses
2. Wait for outstanding references to complete (short grace; jlsm uses
   reference counting in `ArenaBufferPool`, reuse that pattern)
3. Zeroise DEK bytes via `MemorySegment.fill(0)` on the owning Arena
4. Release Arena back to the pool
5. Drop cache-map reference

Order matters: zeroisation must happen **after** all in-flight users release
the DEK; zeroising in-flight bytes would corrupt in-progress crypto operations.

### edge-cases-and-gotchas

- **Encryption context must include domain-id** — Otherwise a DEK from domain A
  could match a request from domain B (same algorithm, same partition if using
  a shared cache). AWS's partition-name requirement is the formal defence; jlsm
  should bind context to `(domain_id, table_id)` at minimum
- **Clock skew breaks TTL** — Use monotonic clocks (`System.nanoTime()`), not
  wall-clock. Otherwise NTP adjustments can cause "young" entries to appear
  expired or vice versa
- **Cache poisoning on decryption** — A decrypted DEK must only be cached under
  an identifier derived from the authenticated envelope, not from untrusted
  input; otherwise an attacker-provided envelope could pin arbitrary wrapped
  bytes into the cache
- **Concurrent revocation during use** — An in-flight operation that is
  holding a DEK reference when revocation fires must complete (short window)
  before the DEK is zeroised; use reference counting, not fire-and-forget

## practical-usage

### when-to-use

- Sustained ingest or read rates where KMS round-trips dominate latency
- Multi-tenant systems with isolation-preserving cache partitions
- Any system where revocation lag can be bounded by a short TTL (5 min typical)

### when-not-to-use

- Extremely low-volume workloads where per-request KMS calls are already cheap
- Environments where revocation must be instant (<5 s) — use synchronous KMS
  calls instead, or a push-invalidation channel with <1 s propagation

## sources

1. [AWS Encryption SDK — Data key caching](https://docs.aws.amazon.com/encryption-sdk/latest/developer-guide/data-key-caching.html) — overall model, caching-CMM architecture
2. [AWS Encryption SDK — Setting cache security thresholds](https://docs.aws.amazon.com/encryption-sdk/latest/developer-guide/thresholds.html) — required max-age, optional message/byte bounds, defaults
3. [AWS Encryption SDK — Caching details](https://docs.aws.amazon.com/encryption-sdk/latest/developer-guide/data-caching-details.html) — cache-key fields (algorithm, encryption context, partition name, encrypted data keys)
4. [AWS Encryption SDK Specification — Caching CMM](https://github.com/awslabs/aws-encryption-sdk-specification/blob/master/framework/caching-cmm.md) — formal spec of the caching CMM
5. [AWS Blog — Multi-tenant encryption with KMS](https://aws.amazon.com/blogs/architecture/simplify-multi-tenant-encryption-with-a-cost-conscious-aws-kms-key-strategy/) — cost and isolation tradeoffs at tenant scale
6. [Salesforce Patent 10,680,804 — Distributed key caching](https://patents.justia.com/patent/10680804) — distributed DEK cache pattern with LRU per server

---
*Researched: 2026-04-23 | Next review: 2026-10-20*
