---
{
  "id": "query.scan-lease-gc-watermark",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "query"
  ],
  "requires": [
    "query.distributed-pagination",
    "transport.scatter-gather-flow-control"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "scan-lease-gc-watermark",
    "scatter-backpressure"
  ],
  "kb_refs": [
    "distributed-systems/query-execution/distributed-scan-cursors"
  ],
  "open_obligations": [
    "OB-F44-01: Lease message types (LeaseRequest, LeaseRenewRequest, LeaseReleaseRequest) must be registered in the F19 transport framing protocol message type taxonomy. Deferred pending transport layer extension mechanism."
  ],
  "_migrated_from": [
    "F44"
  ]
}
---
# query.scan-lease-gc-watermark — Scan Lease GC Watermark

## Requirements

### Lease registry

R1. The partition must maintain a lease registry that tracks active scan leases. Each lease entry must record the scan's snapshot sequence number and the lease expiry deadline. The expiry deadline must be computed using a monotonic clock source (not wall-clock time) to prevent premature expiry or indefinite hold due to NTP adjustments or clock skew.

R2. The lease registry must be partition-local. Each partition maintains its own registry independently. No cross-partition lease coordination is required.

R3. The lease registry must support concurrent access from multiple query coordinator threads. Insert, remove, and query operations on the registry must be thread-safe without requiring callers to hold external locks.

### Lease acquisition

R4. When a coordinator issues the first-page request for a pagination query (F39 R4-R5), the coordinator must dispatch `LeaseRequest` messages to all non-pruned partitions concurrently with the first-page `PageRequest` messages. Lease acquisition is best-effort — the coordinator must not wait for lease responses before dispatching page requests. If a lease response arrives after the first page has been served, the lease is still valid and protects subsequent pages. If a lease request fails, the coordinator proceeds without a lease for that partition (R42). The lease must be acquired for the query's snapshot sequence number (F39 R23).

R4a. The coordinator must track all pending (dispatched but not yet responded) lease requests. On query completion (R14) or cancellation (R15), the coordinator must cancel all pending lease request transport futures. If a `LeaseResponse` arrives after the query has completed or been cancelled, the coordinator must immediately send a `LeaseReleaseRequest` for the received lease identifier. This prevents orphaned leases when the query completes before all lease responses arrive.

R5. A scan lease must be acquired via a `LeaseRequest` message sent to the partition. The partition must register the lease in its registry and return a `LeaseResponse` containing a lease identifier (opaque, unique within the partition) and the granted expiry time.

R6. The lease identifier must be a 128-bit value unique within the partition's lease registry at the time of generation. The identifier does not require cryptographic unpredictability — a counter-based or pseudo-random source is sufficient. The identifier must not be used for authentication or authorization. Collision with an existing active lease must cause regeneration (retry), not silent overwrite.

R7. If the partition is in DRAINING, CATCHING_UP, or UNAVAILABLE state (F27 R18-R22), the partition must reject the lease request. The rejection must include the partition state and the current ownership epoch.

R8. A scan lease must not pin individual SSTable files or hold open iterators. The lease records only the snapshot sequence number that must remain accessible. The compaction subsystem consults the lease registry to determine whether the GC watermark may advance.

### Lease duration and renewal

R9. Each scan lease must have a bounded duration. The default lease duration must be 300 seconds (5 minutes). The lease duration must be configurable with a minimum of 60 seconds and a maximum of 3600 seconds (1 hour).

R10. The coordinator must renew the scan lease before it expires if the pagination query is still active. The renewal must be sent as a `LeaseRenewRequest` carrying the lease identifier and the partition identifier. The partition must extend the lease expiry by one lease duration from the time the renewal is processed. If the lease identifier is not found in the registry (already expired or explicitly released), the partition must return a `LEASE_NOT_FOUND` error. The coordinator must treat `LEASE_NOT_FOUND` the same as `LEASE_EXPIRED` (R13) — set `snapshotDegraded` for that partition.

R11. The coordinator must schedule lease renewal at half the lease duration (default: 150 seconds). This provides a full half-duration window for retry if the first renewal attempt fails due to transient network issues.

R12. If a lease renewal fails (network timeout, partition unavailable), the coordinator must retry once after a configurable backoff (default: 5 seconds). If the retry also fails, the coordinator must not retry further for that partition. The scan continues — if the lease expires, the partition falls back to F39 R25 (degraded snapshot) or R26 (STALE_TOKEN).

R10a. When the coordinator releases a lease for any reason (R14 normal completion, R15 cancel/error, R16 best-effort release, R26 idle release, R26a partition timeout), the coordinator must cancel any pending renewal timer for that lease. Renewal timers must not fire for leases that have been released.

R13. A lease renewal request for an expired lease must be rejected by the partition with a `LEASE_EXPIRED` error. The coordinator must treat this as a degraded scan (equivalent to F39 R25 snapshot degradation) and propagate `snapshotDegraded` via `PartialResultMetadata`.

### Lease release

R14. When a pagination query completes normally (F39 R32 — all partitions return null continuation tokens), the coordinator must release all scan leases by sending `LeaseReleaseRequest` messages to all partitions that hold active leases for the query.

R15. When a pagination query is cancelled or errors (F39 R49-R50), the coordinator must release all scan leases as part of the cleanup in the finally block. Lease release must occur after cancelling outstanding transport futures but before returning the query slot.

R16. Lease release is best-effort. If the release message fails (network error, partition unavailable), the coordinator must not retry or block. The lease will expire naturally at its deadline.

R17. The partition must remove the lease entry from the registry upon receiving a valid `LeaseReleaseRequest`. If the lease identifier is not found in the registry (already expired or already released), the release must be a no-op — the partition must not return an error.

### GC watermark interaction

R18. The GC watermark for a partition must not advance past the minimum snapshot sequence number across all active (non-expired) leases in the partition's lease registry.

R19. When the compaction subsystem computes the GC watermark for a partition, it must query the lease registry for the minimum leased sequence number. If the registry is non-empty, the effective GC watermark must be the lesser of: (a) the compaction-determined watermark, and (b) the minimum leased sequence number minus one.

R20. When the lease registry is empty (no active leases), the compaction subsystem must use its own GC watermark without modification. The lease mechanism must impose zero overhead on the compaction path when no scans are active.

R21. Expired leases must not constrain the GC watermark. The registry must either eagerly remove expired entries or exclude them from the minimum-sequence-number computation. The choice is an implementation detail, but the behavioral contract is: an expired lease has no effect on GC watermark advancement.

### Lease expiry and compaction starvation prevention

R22. The lease registry must enforce a maximum number of concurrent leases per partition. The default maximum must be 256. The maximum must be configurable with a minimum of 1. When the maximum is reached, new lease requests must be rejected with a `LEASE_CAPACITY_EXHAUSTED` error.

R23. If a coordinator fails to acquire a lease due to capacity exhaustion (R22), the query must proceed without a lease for the affected partition. The coordinator must log a structured warning and set the query-level `snapshotDegraded` flag in `PartialResultMetadata`. The scan operates in degraded mode for that partition — the GC watermark may advance freely.

R24. The maximum lease duration (R9, upper bound of 3600 seconds) bounds the maximum time a single scan can hold the GC watermark. Even if a coordinator fails to release a lease (crash, network partition), the watermark is held for at most one lease duration beyond the last successful renewal.

R25. The compaction subsystem must not block or wait for leases to expire. If the GC watermark is held by active leases, compaction must proceed with the constrained watermark — it may merge SSTables but must not discard versions at or above the minimum leased sequence number.

### Integration with F21 credit-based flow control

R26. A stalled scan must not hold leases indefinitely. The coordinator must monitor query-level page request activity — if no page request has been dispatched to any incomplete partition of the query within a configurable idle threshold (default: 60 seconds), the coordinator must release leases for all completed partitions (those that returned null continuation tokens). Leases for incomplete partitions must be preserved as long as the query is active. A query is not idle if the coordinator has outstanding page requests in flight or is blocked waiting for credits — the idle threshold applies only when the coordinator has no pending work and is not dispatching demand.

R27. When a coordinator releases a lease due to scan idleness (R26), the coordinator must set the `snapshotDegraded` flag for that partition. If the scan later resumes page requests to that partition, the partition serves pages at the latest available snapshot (F39 R25) rather than the original scan snapshot.

R26a. When a partition is marked as timed out per F21 R22 (per-partition timeout, subscription cancelled, credits released), the coordinator must release the lease for that partition. The lease release follows the same best-effort semantics as R16.

R28. The idle threshold (R26) must be independent of the lease duration (R9). The idle threshold governs how long a stalled scan holds the watermark before voluntarily releasing. The lease duration governs the maximum hold time if the coordinator crashes. The idle threshold must be less than or equal to the lease duration.

### Partition lifecycle interaction

R29. When a partition transitions to DRAINING state (F27 R5), the departing owner must continue normal lease expiry processing (R21). Leases that reach their monotonic deadline during the drain phase expire normally and cease to constrain the GC watermark. The departing owner must not administratively purge or bulk-remove non-expired leases during the drain. Lease renewal requests received during the drain must be processed normally (R10) — extending leases for active scans that are still making progress.

R30. When a partition transitions from DRAINING to UNAVAILABLE (after the drain phase completes or times out), the node must discard the lease registry for that partition. Leases do not transfer across ownership changes — they are local to the node that granted them.

R31. When a new owner completes catch-up (F27 R16) and transitions to SERVING, it must start with an empty lease registry. Scans that held leases on the previous owner must re-acquire leases on the new owner or degrade gracefully (F39 R25).

R32. The lease registry must be empty when a partition is in UNAVAILABLE state. A node must not accept lease requests for partitions it does not own.

### Input validation

R33. The partition must reject a `LeaseRequest` with a null scan sequence number with a `NullPointerException`.

R34. The partition must reject a `LeaseRequest` with a sequence number of zero (the pre-write sentinel) with an `IllegalArgumentException` whose message states that the sequence number must reference a valid snapshot.

R35. The partition must reject a `LeaseRequest` with a sequence number that exceeds the partition's current latest sequence number with an `IllegalArgumentException` whose message states the requested and current latest sequence numbers.

R36. The partition must reject a `LeaseRenewRequest` with a null lease identifier with a `NullPointerException`.

R37. The partition must reject a `LeaseReleaseRequest` with a null lease identifier with a `NullPointerException`.

### Concurrency

R38. The lease registry must be safe for concurrent read and write access. Multiple coordinators may acquire, renew, and release leases on the same partition concurrently.

R39. The minimum-sequence-number query (R19) must not block or serialize the compaction subsystem behind concurrent lease acquisition, renewal, or release operations. Compaction and lease management must proceed independently.

R40. Lease expiry checks (R21) must not require stop-the-world scanning of the registry. Expired leases must be removable incrementally (e.g., lazy removal during access or periodic background sweep).

### Error handling

R41. If the lease registry's internal state becomes inconsistent (e.g., a lease identifier maps to a null entry), the partition must log a structured error and treat the registry as empty for GC watermark purposes. The partition must not crash or throw an unhandled exception from the compaction path.

R42. Network errors during lease acquisition must not prevent the pagination query from proceeding. The coordinator must treat a failed lease acquisition the same as capacity exhaustion (R23) — proceed without a lease, set `snapshotDegraded`.

### Observability

R43. The lease registry must expose queryable metrics: (a) active lease count per partition, (b) minimum leased sequence number (or absent if no leases), (c) lease acquisition count, (d) lease renewal count, (e) lease expiry count (leases that expired without explicit release), (f) lease rejection count (capacity exhausted + invalid requests), (g) GC watermark hold delta (difference between unconstrained watermark and effective watermark due to leases).

### Resource lifecycle

R44. The lease registry must implement a closeable lifecycle. When the partition discards the registry (R30), it must call close on the registry, releasing all resources held by active lease entries. The registry's close operation must be idempotent — calling close on an already-closed registry must be a no-op.

R45. After the registry is closed, any subsequent lease mutation (acquire, renew, release) must throw an `IllegalStateException` whose message indicates the registry is closed. The minimum-sequence-number query (R19) must not throw on a closed registry — it must return empty (no leases), allowing compaction to proceed with an unconstrained watermark. This prevents R45 from contradicting R41 (compaction path must not throw).

## Cross-References

- ADR: .decisions/scan-lease-gc-watermark/adr.md
- ADR: .decisions/scatter-backpressure/adr.md
- KB: .kb/distributed-systems/query-execution/distributed-scan-cursors.md
- Spec: F39 R23-R27 (snapshot binding, degraded fallback, STALE_TOKEN)
- Spec: F21 R1-R5 (credit model, flow control)
- Spec: F27 R5-R22 (partition lifecycle states, drain/catch-up)

---

## Design Narrative

### Intent

Define a lease-based mechanism that prevents the GC watermark from advancing past snapshots needed by active distributed scans. Without this, long-running paginated queries (F39) hit degraded snapshot fallback or STALE_TOKEN errors when compaction reclaims version history. The lease acts as a lightweight coordination primitive: it tells the compaction subsystem "this sequence number is still needed" without pinning SSTables or holding open iterators.

### Why leases instead of SSTable pinning

SSTable pinning (the stateful cursor model) holds file references that prevent compaction from deleting merged files. With 100 partitions and 10 concurrent scans, 1,000 pinned SSTable sets cause severe space amplification. The KB article on distributed scan cursors documents this tradeoff extensively.

Leases operate at the logical level — they constrain the GC watermark (a sequence number threshold) rather than pinning physical files. Compaction can merge SSTables freely; it just cannot discard versions at or above the leased sequence number. This preserves compaction throughput while protecting scan snapshots.

### Why bounded duration

Unbounded leases would allow a crashed coordinator to hold the GC watermark indefinitely, causing ever-growing space amplification. The bounded duration (default 5 minutes, max 1 hour) guarantees that even in the worst case (coordinator crash with no cleanup), the watermark is released within one lease period.

The renewal-at-half-duration pattern provides a full half-duration window for transient failures. A 5-minute lease renewed at 2.5 minutes means the renewal can fail, wait 5 seconds, and retry — all within the remaining 2.5-minute window.

### Why idle threshold integration with F21

F21's credit-based flow control can stall a scan when credits are exhausted or when slow partitions dominate the merge. A stalled scan that holds leases prevents GC watermark advancement without making forward progress. The idle threshold (default 60 seconds) detects this condition and releases the lease, allowing compaction to proceed. The scan degrades gracefully — when it resumes, it reads at the latest available snapshot.

### Why leases do not transfer across ownership changes

During partition rebalancing (F27), the new owner starts with a fresh state (empty MemTable, replayed WAL). Transferring leases would require either: (a) replicating the lease registry to the new owner (adding complexity to the catch-up protocol), or (b) forwarding lease operations from the old owner to the new owner during drain. Both add significant complexity for a transient condition. Instead, scans that held leases on the old owner simply degrade (F39 R25) when they hit the new owner. This is the same behavior they would experience if the lease expired naturally.

### GC watermark computation race (TOCTOU)

There is a narrow race between compaction reading the minimum leased sequence number and applying it as the GC watermark. If a lease expires between the read and the apply, compaction may discard versions that were momentarily protected. This race does not create a new failure mode — the consequence is exactly what would happen without leases (degraded snapshot or STALE_TOKEN per F39 R25-R26). The lease provides a best-effort hold, not a hard guarantee. Adding a lock between compaction and lease expiry would serialize compaction behind lease operations, violating R39. The pragmatic resolution: accept the race, rely on F39's fallback path.

### Drain timeout and lease interaction

If a partition's drain phase times out (F27 R8), the departing owner transitions to UNAVAILABLE and discards the lease registry (R30). Active leases for scans targeting that partition are lost. The new owner starts with an empty registry (R31). Scans degrade gracefully via F39 R25. This is inherent to drain timeout — the lease cannot prevent data loss from an incomplete drain.

### What was ruled out

- **SSTable pinning:** Space amplification at scale. Documented in KB.
- **Unbounded leases:** Compaction starvation on coordinator crash.
- **Lease transfer during rebalancing:** Complexity outweighs benefit for a transient condition.
- **Server-side cursor state:** Contradicts F39's stateless partition model (R28).
- **GC watermark freeze (global):** A global watermark freeze would affect all partitions, not just those being scanned. Per-partition leases are more targeted.

### Adversarial falsification (Pass 2)

16 findings from structured adversarial review (all mandatory probes). 10 promoted:
Critical: lease acquisition timing race (R4 amended to concurrent dispatch), renewal
after release (R10a added, R10 amended with LEASE_NOT_FOUND). High: idle threshold
scope (R26 amended to query-level), F21 timeout integration (R26a added), monotonic
clock (R1 amended). Medium: lease ID over-specified (R6 relaxed), transport message
types (OB-F44-01 deferred), R39 over-specified mechanism (amended to behavioral),
registry close lifecycle (R44-R45 added). Low: TOCTOU race (acknowledged in narrative).
6 non-findings: off-by-one in R19, capacity exhaustion UX (already covered by F39 R27),
lease ID equality, lease acquisition + page dispatch atomicity, drain timeout + lease
(acknowledged in narrative), trust boundary for lease ID (consolidated with Finding 3).

### Adversarial falsification (Pass 3 — depth pass)

6 findings from depth pass targeting consequences of Pass 2 fixes. 4 promoted:
Critical: pending lease tracking on early query completion (R4a added — concurrent
dispatch creates orphan risk). High: idle threshold over-release of incomplete partitions
(R26 amended to preserve leases for incomplete partitions), R29/R21 drain expiry
contradiction (R29 amended to allow normal expiry during drain), R45/R41 closed registry
on compaction path (R45 amended to return empty for minimum-sequence query).
2 non-findings: renewal timer cancellation race (benign — no data corruption),
over-protective lease after snapshot degradation (conservative behavior, not a gap).
