---
problem: "partition-takeover-priority"
date: "2026-04-15"
version: 2
status: "accepted"
decision_refs: ["rebalancing-grace-period-strategy"]
spec_refs: ["F29"]
---

# Partition Takeover Priority Ordering

## Problem

Priority ordering of partition takeover — which partitions replay first on the
new owner when a node acquires multiple partitions simultaneously.

## Decision

**Pluggable TakeoverPrioritizer with smallest-first default** — a
`TakeoverPrioritizer` interface accepts partition descriptors (ID, byte size,
entry count) and returns an ordered replay list. Two implementations provided:
`SmallestFirstPrioritizer` (default) and `LargestFirstPrioritizer`.

Key design choices resolved by F29 R1-R9:
- Smallest-first default minimizes time-to-first-SERVING (R5, R7)
- Partition descriptors carry ID, estimated byte size, estimated entry count (R3)
- Prioritizer must be deterministic and thread-safe (R8, R9)
- Tie-breaking by partition ID ascending for both implementations (R5, R6)
- Custom strategies supported via the `TakeoverPrioritizer` interface (R2)

## Context

Originally deferred during `rebalancing-grace-period-strategy` decision
(2026-03-30) as not needed for initial implementation. Resolved by F29
(Rebalancing Operations) which specified the full TakeoverPrioritizer
interface and two implementations. The specification was adversarially
hardened and is in APPROVED state.

## Alternatives Considered

- **Largest-first ordering**: Prioritizes the most impactful partition.
  Provided as an alternative implementation (R6) for workloads where a
  single large partition dominates traffic.
- **Access-frequency-weighted**: Requires telemetry infrastructure that the
  library does not assume. Achievable via custom `TakeoverPrioritizer`.
- **Priority preemption**: Pausing lower-priority replays when higher-priority
  partitions arrive. Ruled out — adds suspend/resume complexity for marginal
  benefit (F29 design narrative).

## Consequences

- Consumers can implement custom prioritizers for workload-specific policies
- Default smallest-first ordering trades individual large-partition latency
  for faster aggregate availability across partitions
