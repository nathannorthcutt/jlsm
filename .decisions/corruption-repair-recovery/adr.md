---
problem: "corruption-repair-recovery"
date: "2026-04-11"
version: 1
status: "deferred"
---

# ADR: Corruption Repair and Recovery

**Status:** deferred
**Source:** out-of-scope from `per-block-checksums`

## Problem

When per-block CRC32C detects corruption, there is no repair or recovery
mechanism. The current behavior is to throw an exception on checksum mismatch.
A higher-level strategy is needed: skip-and-log, rebuild from replica, partial
read with degraded results, etc.

## Why Deferred

Scoped out during `per-block-checksums` decision. Repair/recovery is a
higher-level concern that depends on replication and cluster topology decisions
not yet made.

## Resume When

When replication is implemented and there is a source of truth to repair from,
or when single-node recovery guarantees are needed.

## What Is Known So Far

See `.decisions/per-block-checksums/adr.md` for the detection mechanism.
Corruption is detected at read time via CRC32C mismatch on individual blocks.

## Next Step

Run `/architect "corruption-repair-recovery"` when ready to evaluate.
