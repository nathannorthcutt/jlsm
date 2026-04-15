---
problem: "table-ownership-discovery"
date: "2026-04-13"
version: 1
status: "closed"
---

# Table Ownership Discovery — Closed (Won't Pursue)

## Problem
Discovery of which tables a node owns — how does a node determine its partition assignments?

## Decision
**Will not pursue.** This topic is explicitly ruled out and should not be raised again.

## Reason
Already resolved by two accepted ADRs:
- `partition-to-node-ownership` — Rendezvous Hashing (HRW) provides deterministic
  partition→node mapping computed purely from the membership view
- `table-catalog-persistence` — Per-table metadata directories provide the table catalog

A newly joining node: (1) receives the membership view via Rapid, (2) recovers the
table catalog from its local metadata directory, and (3) computes its partition
assignments via HRW. No separate discovery mechanism needed.

## Context
The discovery SPI (`discovery-spi-design`) is for bootstrap — finding the first peer.
Ownership is computed, not discovered. The membership protocol + HRW give each node
deterministic knowledge of which partitions it owns.

## Conditions for Reopening
If ownership assignment becomes non-deterministic (e.g., weighted capacity, affinity
rules that require external state), this may need a dedicated lookup mechanism. See
deferred decisions `weighted-node-capacity` and `partition-affinity`.
