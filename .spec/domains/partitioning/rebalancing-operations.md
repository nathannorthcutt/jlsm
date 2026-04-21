---
{
  "id": "partitioning.rebalancing-operations",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "partitioning"
  ],
  "requires": [
    "F27",
    "F28"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "rebalancing-grace-period-strategy"
  ],
  "kb_refs": [
    "distributed-systems/data-partitioning/partition-rebalancing-protocols"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F29"
  ]
}
---
# partitioning.rebalancing-operations — Rebalancing Operations

## Requirements

### Partition takeover priority

R1. When a node acquires multiple partitions simultaneously (e.g., another node departs and HRW assigns several partitions to this node), the node must replay WAL and transition partitions from CATCHING_UP to SERVING (F27 R18–R23) in the order determined by the configured `TakeoverPrioritizer` (R7).

R2. The system must define a `TakeoverPrioritizer` interface with a single method that accepts a collection of partition descriptors and returns an ordered list of partition IDs representing the replay order.

R3. A partition descriptor must carry the partition ID (`long`), estimated byte size (`long`, bytes), and estimated entry count (`long`). The partition manager must obtain these values from the partition's metadata on object storage (SSTable footer summaries and WAL sequence bounds) before invoking the prioritizer.

R4. When the `TakeoverPrioritizer` receives an empty collection, it must return an empty list.

R5. The system must provide a `SmallestFirstPrioritizer` implementation that orders partitions by estimated byte size ascending. When two partitions have the same estimated byte size, the tie must be broken by partition ID in ascending natural order.

R6. The system must provide a `LargestFirstPrioritizer` implementation that orders partitions by estimated byte size descending. When two partitions have the same estimated byte size, the tie must be broken by partition ID in ascending natural order.

R7. The `SmallestFirstPrioritizer` must be the default when no prioritizer is explicitly configured.

R8. The `TakeoverPrioritizer` must be deterministic: given the same set of partition descriptors, it must return the same ordering regardless of invocation time or call count.

R9. The `TakeoverPrioritizer` must be safe to invoke concurrently from multiple threads without external synchronization.

### Concurrent WAL replay limits

R10. The partition manager must enforce a configurable maximum number of concurrent WAL replays (partitions in CATCHING_UP state actively replaying). When the number of active replays equals the concurrency limit, additional partitions that need catch-up must wait in a pending queue ordered by the configured `TakeoverPrioritizer`.

R11. The default maximum concurrent WAL replay count must be 2. The builder must reject values less than 1 with an `IllegalArgumentException`.

R12. When a WAL replay completes (partition transitions from CATCHING_UP to SERVING per F27 R23), the partition manager must dequeue the next pending partition (if any) and begin its WAL replay without requiring a new trigger evaluation or external signal.

R13. The partition manager must expose the number of partitions currently replaying and the number waiting in the pending queue. Both values must be readable without blocking.

R14. If a new membership view arrives while partitions are queued for replay, the partition manager must remove from the pending queue any partition that is no longer assigned to this node in the new view, and transition each removed partition to UNAVAILABLE (F27 R22).

R15. If a new membership view arrives while partitions are queued for replay and the partition descriptors for queued partitions have changed (e.g., updated size estimates), the partition manager must re-sort the pending queue using the configured `TakeoverPrioritizer`.

### I/O throttling during WAL replay

R16. The partition manager must enforce a configurable maximum WAL replay read throughput in bytes per second, shared across all concurrent WAL replays on the node. This limit bounds the aggregate I/O bandwidth consumed by catch-up operations.

R17. The default maximum WAL replay throughput must be 64 MiB/s (67,108,864 bytes per second). The builder must reject values less than or equal to zero with an `IllegalArgumentException`.

R18. Each WAL replay must acquire a byte allowance from the shared throughput limiter before reading the next segment. If insufficient allowance is available, the replay must block until allowance is replenished rather than exceeding the configured rate.

R19. The throughput limiter must permit a burst of up to one WAL segment size (as configured in the WAL, defaulting to 64 MiB) without delay. The burst allowance must be configurable via builder independently of the WAL segment size.

R20. When multiple concurrent replays share the throughput budget, each replay must acquire allowance from the same shared limiter. No per-replay sub-allocation is required.

### Memory budgeting during catch-up

R21. The partition manager must enforce a configurable maximum memory budget (in bytes) for all concurrent WAL replays combined. This budget covers the MemTable memory consumed by replayed entries across all catching-up partitions.

R22. The default maximum catch-up memory budget must be 256 MiB (268,435,456 bytes). The builder must reject values less than or equal to zero with an `IllegalArgumentException`.

R23. Before beginning WAL replay for a queued partition, the partition manager must estimate the MemTable memory required for that partition using the partition descriptor's estimated byte size (R3) as the initial estimate.

R24. If the estimated memory for a queued partition plus the memory already consumed by active replays exceeds the memory budget, the partition must remain queued until active replays complete and free sufficient memory.

R25. If a single partition's estimated memory requirement exceeds the total memory budget, the partition must not be permanently blocked. The partition manager must proceed with the replay as the sole active replay (no other replays may run concurrently with an over-budget partition).

R26. When a single partition's estimated memory exceeds the total memory budget (R25), the partition manager must log a warning identifying the partition ID, its estimated memory requirement, and the configured budget before beginning replay.

R27. The memory accounting for a catching-up partition must be updated during replay to reflect actual MemTable consumption rather than the initial estimate.

R28. If actual memory consumption for a catching-up partition diverges from the initial estimate by more than 50% (higher or lower), the partition manager must log a warning identifying the partition ID, the initial estimate, and the actual consumption.

### Backpressure from compaction

R29. If the new owner's compaction backlog (number of pending compaction tasks) exceeds a configurable threshold during catch-up, the partition manager must pause WAL replay for all catching-up partitions until the backlog drops below the threshold.

R30. The compaction backpressure threshold must be configurable via builder. The default must be 8 pending compaction tasks. The builder must reject values less than 1 with an `IllegalArgumentException`.

R31. When WAL replay is paused due to compaction backpressure, the partition manager must log a warning identifying the number of paused replays and the current compaction backlog size. The warning must be emitted at most once per pause-resume cycle (from the moment replay pauses until it resumes).

### Error handling during WAL replay

R32. If WAL replay for a partition fails due to an unrecoverable error (e.g., all WAL segments missing from object storage), the partition manager must transition the partition to UNAVAILABLE (F27 R22), emit a structured error event containing the partition ID, the ownership epoch, and the error detail, and proceed with replaying the next queued partition.

R33. A WAL replay failure for one partition must not prevent or delay replay of other queued partitions.

### Shutdown

R34. When the partition manager is closed, it must cancel all pending replay queue entries and interrupt active WAL replays. Active replays must terminate within the drain timeout configured in F27 R42.

R35. Partitions whose replay was interrupted by shutdown must remain in CATCHING_UP state. On restart, a new partition manager must re-evaluate ownership and re-queue catch-up from scratch per F27 R31.

### Observability

R36. The partition manager must emit a structured event when a partition begins WAL replay, containing: partition ID, ownership epoch, estimated byte size, estimated entry count, replay queue position at the time of start, and concurrent replay count at the time of start.

R37. The partition manager must emit a structured event when a partition completes WAL replay, containing: partition ID, ownership epoch, actual bytes replayed, actual entries replayed, replay duration in milliseconds, and the number of partitions remaining in the pending queue.

R38. The partition manager must emit a structured event when WAL replay is throttled, containing: the throttle reason (one of: I/O limit, memory budget, or compaction backpressure), the current limit value, and the number of affected partitions.

R39. The partition manager must emit a structured event when a throttle condition resolves (replay resumes), containing: the throttle reason that was resolved and the duration of the throttle in milliseconds.

### Configuration

R40. All configurable parameters defined in this spec must be settable via the partition manager builder. The builder must enforce the validation constraints specified in each parameter's requirement.

R41. The builder must accept an optional `TakeoverPrioritizer` instance. If not provided, `SmallestFirstPrioritizer` must be used (R7).

R42. The builder must accept an optional I/O throughput limit (`long`, bytes per second). If not provided, the default from R17 must be used.

R43. The builder must accept an optional I/O burst allowance (`long`, bytes). If not provided, the default from R19 must be used.

R44. The builder must accept an optional memory budget (`long`, bytes). If not provided, the default from R22 must be used.

R45. The builder must accept an optional compaction backpressure threshold (`int`, pending task count). If not provided, the default from R30 must be used.

---

## Design Narrative

### Intent

Define the operational mechanics of partition takeover when a node acquires multiple partitions simultaneously. This spec resolves two deferred decisions from the rebalancing-grace-period-strategy ADR: partition-takeover-priority (which partitions to replay first) and concurrent-wal-replay-throttling (how to prevent I/O saturation during concurrent catch-up). The approach applies three independent resource constraints — concurrency limit, I/O throughput limit, and memory budget — to bound the impact of catch-up operations on foreground query traffic. This spec also covers error handling during replay, shutdown behavior, and throttle-resolution observability — gaps identified during adversarial review.

### Why smallest-first as the default priority

When a node acquires N partitions simultaneously, smallest-first minimizes the time until the first partition reaches SERVING state. A 1 MiB partition replays in milliseconds; a 1 GiB partition takes seconds to minutes. By clearing small partitions first, the node becomes partially available sooner, reducing the aggregate unavailability window across partitions. The KB notes that "available capacity" is the highest-weight factor in takeover priority (partition-rebalancing-protocols.md, takeover-priority table), but for a composable library, partition size is the only universally available signal — load and access frequency require telemetry infrastructure that this spec does not assume.

Largest-first is provided as an alternative for workloads where a single large partition dominates traffic and its unavailability has disproportionate impact. The pluggable `TakeoverPrioritizer` interface allows consumers to implement custom strategies (e.g., access-frequency-weighted) without modifying the core.

### Why a shared throughput limiter for I/O throttling

CockroachDB uses a per-store rate limit for snapshot rebalancing (default 32 MiB/s). The same principle applies here: WAL replay reads from object storage, and unbounded concurrent reads can saturate network bandwidth and starve foreground reads. A shared limiter is simpler than per-replay sub-allocation because the total node-level I/O budget is what matters — how it is divided among concurrent replays is irrelevant as long as the aggregate stays within the limit. The requirements (R18–R20) specify the behavioral contract (acquire-before-read, block-on-exhaustion, shared budget) without mandating a specific rate-limiting algorithm. A token-bucket is the expected implementation but the spec does not prescribe it.

The burst allowance of one WAL segment (R19) prevents degenerate behavior where a replay acquires allowance for a partial segment, reads it, then waits for refill before reading the next few bytes. WAL segments are the natural read unit, so the burst aligns with the access pattern. The burst is independently configurable (R43) so operators can decouple it from WAL segment size if their access pattern differs.

The default of 64 MiB/s was chosen to leave substantial headroom for foreground I/O on typical cloud instances (which offer 125-500 MiB/s of network throughput to object storage). Operators can tune this higher for dedicated catch-up windows or lower for latency-sensitive workloads.

### Why a memory budget in addition to a concurrency limit

The concurrency limit (R10–R11) bounds the number of simultaneous replays, but does not bound their memory consumption. Two concurrent replays of 500 MiB partitions would attempt to build two 500 MiB MemTables simultaneously, consuming 1 GiB. The memory budget (R21–R28) provides a second gate: even if the concurrency limit allows 2 replays, the second replay will not start if it would push aggregate MemTable consumption past the budget.

This two-gate approach (concurrency AND memory) is necessary because partition sizes vary by orders of magnitude. A concurrency limit of 2 is fine for 10 MiB partitions but dangerous for 1 GiB partitions. The memory budget adapts to the actual partition sizes without requiring the operator to predict them.

### Why compaction backpressure

WAL replay ingests entries at rates far exceeding normal write traffic. Each replayed entry lands in the MemTable, which flushes to L0 SSTables when full. Rapid L0 accumulation triggers compaction storms that can stall foreground writes (the classic "write stall" problem). The KB notes: "Pause transfer if target's compaction backlog grows too large. Prevents write stalls from bulk ingestion write amplification" (partition-rebalancing-protocols.md, concurrent-rebalancing-throttling section).

The threshold of 8 pending compaction tasks (R30) is a conservative default. Production LSM stores typically stall writes at 12-20 pending L0 files. Setting the pause threshold below the stall threshold gives compaction time to catch up before foreground operations are affected.

### What was ruled out

- **Per-replay I/O sub-budgets:** Dividing the throughput limit evenly among concurrent replays (e.g., 32 MiB/s each with limit=2). This underutilizes bandwidth when one replay is between segments — the shared bucket naturally reallocates unused bandwidth to active replays.
- **Adaptive concurrency:** Automatically increasing/decreasing the concurrent replay count based on observed I/O utilization. Adds feedback-loop complexity and oscillation risk. The three static knobs (concurrency, throughput, memory) are simpler to reason about and tune.
- **Priority preemption:** Pausing a lower-priority replay when a higher-priority partition arrives in the queue. This adds implementation complexity (suspend/resume WAL replay state) for marginal benefit — the priority order only matters for the initial queue ordering, and replays are typically short enough that waiting for completion is acceptable.
- **Weighted fair queuing for I/O tokens:** Allocating more tokens to higher-priority replays. The priority already determines start order; once a replay is active, equal treatment is simpler and sufficient.
