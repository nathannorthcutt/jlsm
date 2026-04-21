---
{
  "id": "partitioning.rebalancing-policy",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "partitioning"
  ],
  "requires": [
    "partitioning.table-partitioning",
    "partitioning.rebalancing-safety"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "partition-to-node-ownership"
  ],
  "kb_refs": [
    "distributed-systems/data-partitioning/partition-rebalancing-protocols",
    "distributed-systems/data-partitioning/partitioning-strategies"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F28"
  ]
}
---
# partitioning.rebalancing-policy — Rebalancing Policy

## Requirements

### Rebalancing trigger policy

R1. The system must define a `RebalancingTrigger` interface with a single method that accepts the current partition assignment map and cluster membership view, and returns a (possibly empty) set of `RebalancingAction` records describing which partitions should move and to which target node.

R2. The `RebalancingTrigger` evaluation method must be a pure function: given structurally equivalent inputs (same partition IDs, same node IDs, same membership epoch, same per-partition metadata values), it must return the same set of actions regardless of invocation time or call count.

R3. The system must provide a `MembershipChangeTrigger` implementation that fires whenever the current membership view epoch differs from the epoch recorded in the partition assignment map's most recent rebalancing epoch field. This trigger must recompute HRW scores for all partitions against the new membership and emit move actions for any partition whose highest-scoring node has changed.

R4. The `MembershipChangeTrigger` must not emit a move action for a partition that is already in the DRAINING or CATCHING_UP state (as defined in F27 R18–R23).

R5. Only partitions in the SERVING or UNAVAILABLE state (as defined in F27 R18–R23) are eligible for rebalancing moves.

R6. The system must provide a `SizeThresholdTrigger` implementation that fires when a partition's estimated byte size exceeds a configurable upper threshold. The trigger must emit a split action (not a move action) identifying the partition to be split.

R7. The `SizeThresholdTrigger` must obtain partition size estimates from a `Function<PartitionId, OptionalLong>` supplier provided at construction. When the supplier returns an empty `OptionalLong` for a partition, the trigger must skip that partition (no action emitted).

R8. The `SizeThresholdTrigger` must reject a null size supplier at construction with a `NullPointerException`.

R9. The `SizeThresholdTrigger` upper threshold must be configurable via builder, with a default value of 256 MiB. The builder must reject values less than or equal to zero with an `IllegalArgumentException`.

R10. The system must provide a `ScheduledTrigger` wrapper that evaluates a delegate `RebalancingTrigger` at a configurable periodic interval and caches the most recent result.

R11. The `ScheduledTrigger` interval must default to 60 seconds. The builder must reject values less than 1 second with an `IllegalArgumentException`.

R12. The `ScheduledTrigger` is an exception to R2's purity requirement: it holds a cached result and a last-evaluation timestamp as internal mutable state. However, the delegate trigger it wraps must still satisfy R2.

R13. The system must provide a `CompositeTrigger` that combines one or more `RebalancingTrigger` instances. The `CompositeTrigger` must reject a null or empty delegate list at construction with an `IllegalArgumentException`.

R14. When evaluated, the `CompositeTrigger` must collect actions from all delegates and merge them.

R15. If two delegates in a `CompositeTrigger` emit conflicting actions for the same partition (e.g., one says move, one says split), the split action must take precedence over the move action.

### RebalancingAction types

R16. `RebalancingAction` must be a sealed interface with permitted subtypes `MoveAction` and `SplitAction`.

R17. `MoveAction` must be a record with components `partitionId` (long), `sourceNodeId` (String), `targetNodeId` (String), and `epoch` (long).

R18. `SplitAction` must be a record with components `partitionId` (long), `splitPoint` (MemorySegment), and `epoch` (long).

R19. `MoveAction` must reject null `sourceNodeId` or `targetNodeId` with a `NullPointerException`.

R20. `SplitAction` must reject a null `splitPoint` with a `NullPointerException`.

R21. `MoveAction.sourceNodeId` must equal the partition's current owner as recorded in the assignment map at the time the action is computed. A trigger must not emit a `MoveAction` with a `sourceNodeId` that differs from the partition's current owner.

R22. All `RebalancingAction` records must carry the membership view epoch at which the action was computed.

R23. The rebalancing executor must reject any action whose epoch does not match the current membership view epoch at execution time.

### Weighted node capacity

R24. The HRW scoring function must accept an optional per-node capacity weight via a `Function<NodeId, OptionalDouble>` supplier. When the supplier returns a present value for a node, the HRW score for a (partition, node) pair must be computed as `hash(partitionId, nodeId) * normalizedWeight`.

R25. When the capacity weight supplier returns an empty `OptionalDouble` for a node, or when no capacity weight supplier is configured (the default), the node must be treated as having weight 1.0, producing behavior identical to unweighted HRW.

R26. Capacity weights must be positive `double` values strictly greater than zero. A weight of 1.0 represents the baseline capacity. The HRW scoring function must reject a weight less than or equal to zero with an `IllegalArgumentException`.

R27. Capacity weight normalization must divide each node's raw weight by the maximum raw weight among all nodes in the current membership view. The node with the highest raw weight must have a normalized weight of 1.0. When all nodes have equal raw weight, all normalized weights must be 1.0.

R28. When a node's capacity weight changes (e.g., due to updated hardware metadata in the membership view), the HRW recomputation must produce the same result as if the node had always had the new weight. No special-case migration logic is required — the standard membership-change trigger (R3) handles any resulting reassignment.

R29. The weighted HRW score computation must use `double` arithmetic for the multiplication of hash output and weight to avoid long overflow.

### Partition affinity

R30. The system must define a `PlacementConstraint` interface that accepts a partition ID and a candidate node ID, and returns whether the candidate is eligible to own the partition.

R31. When placement constraints are configured, the HRW scoring loop must skip any node for which the constraint returns ineligible.

R32. If all nodes are ineligible for a partition after constraint evaluation, the partition must be assigned to the node with the highest HRW score computed with capacity weights (if configured) but without constraint filtering. A warning must be logged identifying the partition and the number of violated constraints.

R33. The system must provide a `ZoneAffinityConstraint` implementation that groups nodes by a zone label (string). The constraint must mark as eligible any node in the same zone as the partition's current owner according to the assignment map.

R34. When a partition has no current owner in the assignment map (initial placement), the `ZoneAffinityConstraint` must treat all nodes as eligible (no zone preference applies).

R35. If no node in the current owner's zone is present in the membership view, the `ZoneAffinityConstraint` must treat all nodes as eligible (fallback to unconstrained placement).

R36. The `ZoneAffinityConstraint` must obtain zone labels from a `Function<NodeId, String>` supplier provided at construction. The supplier must not be null (reject with `NullPointerException`).

R37. The system must provide a `RackSpreadConstraint` implementation that prevents two partitions of the same table from being assigned to nodes in the same failure domain (rack). The constraint must accept a `Function<NodeId, String>` that maps nodes to rack identifiers.

R38. The `RackSpreadConstraint` must accept a `Function<PartitionId, TableId>` at construction to determine which table a partition belongs to. This supplier must not be null (reject with `NullPointerException`).

R39. The `RackSpreadConstraint` must only consider partitions that belong to the same table as the partition being placed. Cross-table placement is unconstrained by rack spread.

R40. When multiple `PlacementConstraint` instances are configured, they must be evaluated in order. A node is eligible only if all constraints return eligible. A single ineligible result from any constraint disqualifies the node (logical AND).

### Integration with ownership epoch model (F27)

R41. Every rebalancing action emitted by a trigger must reference the ownership epoch at which it was computed. The partition manager must discard any action whose epoch is stale relative to the current epoch at execution time.

R42. A move action must initiate the F27 state transition (F27 R23) on the source node (SERVING -> DRAINING) and on the target node (UNAVAILABLE -> CATCHING_UP). The rebalancing policy must not modify partition state directly — it emits actions that the partition manager executes through the F27 state machine (F27 R18–R23).

R43. A split action must not change the ownership epoch of the existing partition.

R44. The two partitions resulting from a split must each receive a new partition ID and an initial epoch equal to the current membership view epoch.

R45. During a split, both resulting partitions must be assigned to the same node that owned the original partition. Rebalancing of the split partitions to other nodes occurs on the next trigger evaluation, not as part of the split itself.

### Concurrency and thread safety

R46. The `RebalancingTrigger` evaluation must be safe to invoke from any thread. Implementations that satisfy R2 (pure function) must not hold mutable state between evaluations. The `ScheduledTrigger` (R10–R12) must use thread-safe internal state (e.g., volatile fields or atomic variables) for its cached result and timestamp.

R47. The weighted HRW scoring function must be safe to invoke concurrently from multiple threads. It must not modify any shared state.

R48. Placement constraint evaluation must be safe to invoke concurrently from multiple threads.

R49. The `CompositeTrigger`'s merge operation (R14–R15) must be safe to invoke concurrently from multiple threads.

### Configuration

R50. The rebalancing policy must be assembled via a builder that accepts: a list of `RebalancingTrigger` instances, an optional capacity weight supplier (`Function<NodeId, OptionalDouble>`), and an optional list of `PlacementConstraint` instances.

R51. The builder must reject a null or empty trigger list with an `IllegalArgumentException`. At least one trigger is required.

R52. The builder must reject a null capacity weight supplier with a `NullPointerException`. To opt out of weighted scoring, omit the supplier (do not pass null).

R53. The builder must reject a null placement constraint list with a `NullPointerException`. To opt out of constraints, omit the list (do not pass null). An empty constraint list is permitted and is equivalent to no constraints.

---

## Design Narrative

### Intent

Define when rebalancing happens, how heterogeneous node capacity affects placement, and how locality/rack/zone preferences constrain partition assignment. This spec resolves three deferred decisions from the partition-to-node-ownership ADR: rebalancing-trigger-policy, weighted-node-capacity, and partition-affinity. The approach extends the existing HRW scoring model with multiplicative capacity weights and a constraint filtering layer, without breaking the fundamental property that assignment is a deterministic pure function of (partition set, membership view, configuration).

### Why a trigger-based architecture

The parent ADR defers "the decision of when to trigger rebalancing" to the membership protocol's grace period. In practice, membership changes are only one of several reasons to rebalance. The KB documents four trigger categories: size-based, load-based, membership-based, and scheduled (partition-rebalancing-protocols.md, trigger-policies). A pluggable trigger interface lets the system compose these orthogonally.

The `CompositeTrigger` (R13–R15) with split-over-move precedence prevents oscillation: if a partition is both oversized and misplaced, splitting it first creates two smaller partitions that may hash to different nodes naturally, reducing unnecessary moves.

Most triggers are pure functions (R2) rather than stateful monitors. This means they can be evaluated on any node and produce identical results, which is essential for the determinism guarantee inherited from the HRW model. The `ScheduledTrigger` (R10–R12) is an explicit exception — it wraps a pure delegate but caches results with a timestamp. Stateful triggers (e.g., load-based with decayed histograms) are excluded from this spec because they require distributed state aggregation that conflicts with the "zero coordination" principle. Load-based rebalancing can be added later as a higher-level orchestration concern that emits actions through the same `RebalancingAction` interface.

### Why multiplicative capacity weighting

The parent ADR notes "simple extension: multiply hash by weight" for heterogeneous nodes. This is the standard approach in weighted rendezvous hashing (Thaler & Ravishankar, 1998). Multiplicative weighting preserves the minimal-movement property: when a node's weight changes, only partitions whose highest-scoring node changes will move — the same O(K/N) bound as unweighted HRW.

Normalization against the maximum weight (R27) ensures that the highest-capacity node always has weight 1.0, which preserves the full hash space for scoring. Without normalization, very small weights would compress the score range and increase collision probability.

Using `double` arithmetic (R29) avoids long overflow when multiplying a 64-bit hash by a weight. The loss of precision in the low bits of the hash is acceptable because HRW scoring only requires a total order, not exact values.

### Why constraint filtering over affinity scoring

Two approaches exist for placement preferences: (1) add affinity terms to the HRW score, or (2) filter candidates before scoring. Score-based affinity is harder to reason about — a sufficiently strong affinity term can override capacity weighting and produce unbalanced placement. Constraint filtering (R30–R40) is simpler: either a node is eligible or it is not. The HRW score determines placement only among eligible nodes, which preserves the scoring model's balance properties within the eligible set.

The fallback to unconstrained HRW when no node satisfies constraints (R32) prevents a misconfigured constraint from making a partition permanently unavailable. The warning log ensures operators can detect and fix the misconfiguration.

Zone affinity (R33–R35) uses a "prefer same zone, fall back to any" model rather than a hard constraint. This avoids unavailability when a zone has no remaining healthy nodes. Rack spread (R37) is a hard constraint because failure domain isolation is a correctness property, not a performance preference — but even it falls back via R32 if no valid placement exists.

### What was ruled out

- **Load-based triggers:** Require distributed telemetry aggregation (decayed request histograms per partition, aggregated across nodes). This conflicts with the zero-coordination determinism of HRW. Deferred to a future spec that introduces a telemetry aggregation layer.
- **Additive capacity weighting:** `hash(id, node) + weight` distorts the hash distribution and can create systematic bias toward high-weight nodes beyond their proportional share.
- **Soft affinity via score boosting:** Multiplying the HRW score by an affinity bonus (e.g., 1.1x for same-zone). Hard to tune, interacts unpredictably with capacity weights, and violates the separation between "who is eligible" and "who scores highest."
- **Automatic split-point selection:** This spec defines that a split action is emitted, but the algorithm for choosing the split point (byte midpoint, key midpoint, load-based) is a separate concern. The `SplitAction` record carries the split point, but how it is computed is not specified here.
