---
problem: "partition-replication-protocol"
date: "2026-03-30"
version: 1
status: "deferred"
depends_on: ["connection-pooling", "message-serialization-format"]
---

# Partition Replication Protocol — Deferred

## Problem
Replication protocol (Raft/Paxos per partition) for durability and availability of partitioned data.

## Why Deferred
Scoped out during `table-partitioning` decision. Separate decision needed.

## Resume When
When `table-partitioning` implementation is stable and this concern becomes blocking.

## What Is Known So Far
See `.decisions/table-partitioning/adr.md` for the architectural context that excluded this concern.

## Next Step
Run `/architect "partition-replication-protocol"` when ready to evaluate.
