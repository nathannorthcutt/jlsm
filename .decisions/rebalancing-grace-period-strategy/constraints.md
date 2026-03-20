---
problem: "How and when should partition ownership be redistributed when nodes join/leave?"
slug: "rebalancing-grace-period-strategy"
captured: "2026-03-20"
status: "draft"
---

# Constraint Profile — rebalancing-grace-period-strategy

## Problem Statement
Define the rebalancing strategy for partition ownership when cluster membership changes. Key sub-problems: (1) where does the grace period live in the architecture — membership layer vs ownership layer, (2) when is rebalancing triggered and how is it coordinated, (3) how is data actually moved for affected partitions. Must compose with Rapid (consistent membership views) and rendezvous hashing (deterministic assignment).

## Constraints

### Scale
Hundreds of nodes, thousands of tables/partitions. Rebalancing should affect only O(K/N) partitions per membership change (guaranteed by HRW).

### Resources
Not constraining. Data movement is bounded by O(K/N) partitions.

### Complexity Budget
High. Team is expert-level. Complex state machines acceptable if they deliver correct behavior.

### Accuracy / Correctness
- All nodes must agree on whether a departed node is in grace period or permanently removed
- During grace period, departed node's partitions are unavailable (not reassigned)
- After grace period, assignment recomputes cleanly via HRW on the updated view
- No split-brain ownership — at no point should two nodes believe they own the same partition

### Operational Requirements
- Must tolerate rolling restarts (Kubernetes) — brief departures should not trigger rebalancing
- Configurable grace period duration
- Rebalancing must not block queries on unaffected tables
- A node returning after grace period is treated as new (brief says so)

### Fit
- Composes with Rapid (strongly consistent views) and HRW (pure function of view)
- Pure Java 25, no external deps

## Key Constraints (most narrowing)
1. **Consistency with Rapid views** — grace period must be deterministic from the view state so all nodes agree
2. **Non-disruptive** — only affected partitions are impacted; everything else serves normally
3. **Rolling restart tolerance** — configurable delay prevents unnecessary data movement

## Unknown / Not Specified
None — full profile captured from feature brief and prior decisions.
