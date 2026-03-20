---
problem: "How should tables and partitions be mapped to cluster members?"
slug: "partition-to-node-ownership"
captured: "2026-03-20"
status: "draft"
---

# Constraint Profile — partition-to-node-ownership

## Problem Statement
Choose an algorithm for assigning tables and table partitions to nodes in a jlsm-engine cluster. The assignment must be deterministic from the membership view (so all nodes agree without coordination), support automatic rebalancing on membership changes with minimal data movement, and produce balanced distribution across nodes.

## Constraints

### Scale
Hundreds of nodes, thousands of tables/partitions. The assignment algorithm must handle the cross-product efficiently. Growth is near-linear with cluster size.

### Resources
Not a constraining factor. Assignment computation is lightweight relative to data operations.

### Complexity Budget
High. Team is expert-level in distributed systems. Complex algorithms are acceptable.

### Accuracy / Correctness
- Assignment must be deterministic: given the same membership view and table/partition set, all nodes must compute identical assignments
- This is guaranteed by the Rapid membership protocol (strongly consistent views)
- Minimal data movement on membership changes: ideally O(K/N) items move when one node joins/leaves (K = total items, N = nodes)
- Balanced distribution: no node should own significantly more than its fair share

### Operational Requirements
- Rebalancing is non-disruptive — only affected partitions move, unaffected tables continue serving
- Grace period before reassignment (configurable, handled at the membership layer)
- Assignment computation must be fast (on the query routing hot path)

### Fit
- Pure Java 25, no external dependencies
- Must compose with Rapid's strongly consistent membership views — assignment is a pure function of (membership view, table/partition set)
- Must compose with range partitioning (existing ADR: table-partitioning)
- Must work for both whole tables (non-partitioned) and individual partitions of partitioned tables

## Key Constraints (most narrowing)
1. **Deterministic from membership view** — all nodes must compute identical assignment without coordination; this rules out stateful placement services
2. **Minimal movement on membership change** — O(K/N) ideal; this rules out modulo/round-robin
3. **Balanced distribution** — even spread; this requires virtual nodes or multi-probe techniques

## Unknown / Not Specified
- Whether weighted assignment is needed (nodes with different capacities)
- Whether partition affinity (co-locate related partitions) is a requirement
- Exact acceptable imbalance ratio (e.g., 1.1x vs 1.5x of fair share)
