# Query Processing — Category Index
*Topic: systems*

Join algorithms, query execution strategies, and cost models for LSM-tree-backed storage
engines. Covers algorithm selection (sort-merge, hash, index nested loop), common
anti-patterns and optimizations, and snapshot consistency for multi-table operations.
Directly relevant to jlsm-sql (query planning) and jlsm-table (execution layer).

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [lsm-join-algorithms.md](lsm-join-algorithms.md) | Join Algorithm Selection & Cost Models | active | Sort-merge: O(N+M) when sorted | Choosing the right join strategy |
| [lsm-join-anti-patterns.md](lsm-join-anti-patterns.md) | Join Anti-Patterns & Optimizations | active | MultiGet: 5-10x over naive INLJ | Avoiding common LSM join pitfalls |
| [lsm-join-snapshot-consistency.md](lsm-join-snapshot-consistency.md) | Snapshot Consistency for Joins | active | Global sequence number for cross-table | Ensuring correct multi-table reads |

## Comparison Summary

The three files form a complete picture: **algorithms** covers *which* join to use,
**anti-patterns** covers *what to avoid* and *how to optimize*, and **snapshot consistency**
covers *how to ensure correctness* across tables.

Key decision flow:
1. Is the join key a prefix of the LSM sort order? → Sort-merge (free sort)
2. Is one side small? → Hash join or INLJ with MultiGet batching
3. Is the predicate a range? → Sort-merge (bloom filters useless for ranges)
4. Do you need cross-table consistency? → Global snapshot with sequence numbers

## Recommended Reading Order
1. Start: [lsm-join-algorithms.md](lsm-join-algorithms.md) — algorithm selection and cost models
2. Then: [lsm-join-anti-patterns.md](lsm-join-anti-patterns.md) — what to avoid, optimization checklist
3. Then: [lsm-join-snapshot-consistency.md](lsm-join-snapshot-consistency.md) — correctness guarantees

## Research Gaps
- Query optimizer / cost-based join selection for LSM (automatic algorithm choice)
- Parallel join execution across LSM tables (intra-query parallelism)
- Adaptive join switching (start with one algorithm, switch mid-query based on runtime stats)
- Semi-join and anti-join optimizations specific to LSM (bloom filter pre-screening)
- Join ordering optimization for multi-way joins over LSM tables
- Predicate pushdown through join operators to SSTable level

## Shared References Used
@../../_refs/complexity-notation.md
