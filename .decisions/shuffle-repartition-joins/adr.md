---
problem: "shuffle-repartition-joins"
date: "2026-04-14"
version: 1
status: "deferred"
---

# Shuffle / Repartition Joins — Deferred

## Problem
Shuffle/repartition join strategy for large non-co-partitioned tables — hash-redistribute
both sides so matching keys land on the same executor before applying a local join algorithm.

## Why Deferred
Scoped out during the `distributed-join-execution` decision. The two-tier strategy
(co-partitioned + broadcast) covers more than 90% of real workloads. Shuffle joins add
significant network, buffering, and fault-tolerance complexity that is not justified until
large non-co-partitioned joins become a common use case.

## Resume When
Large non-co-partitioned joins become a common use case and the reject-path fallback
(error or degrade to broadcast) is unacceptable in practice.

## What Is Known So Far
See `.decisions/distributed-join-execution/adr.md` for the architectural context and the
two-tier strategy that excluded shuffle joins from initial scope.
