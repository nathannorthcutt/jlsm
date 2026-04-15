---
problem: "wal-dictionary-compression"
date: "2026-04-14"
version: 2
status: "deferred"
depends_on: ["codec-dictionary-support", "wal-compression"]
---

# WAL Dictionary Compression — Re-Deferred

## Problem
Dictionary compression for WAL records. Individual records lack batch context
for training — a pre-trained dictionary from historical data would be needed.

## Why Deferred
WAL compression (wal-compression ADR, confirmed 2026-04-12) uses per-record
compression. That feature has not been implemented yet. Dictionary training
for WAL would require representative samples from recent SSTable data, adding
lifecycle complexity. Plain ZSTD/Deflate ratios haven't been measured yet.

## Resume When
When WAL compression is implemented and benchmarks show compression ratios
with plain per-record ZSTD/Deflate are insufficient for the target workload.

## What Is Known So Far
- KB: `.kb/algorithms/compression/zstd-dictionary-compression.md#when-not-to-use`
- WAL uses per-record compression (wal-compression ADR)
- Dictionary could be trained from recent SSTable data
- Adds lifecycle complexity: dictionary versioning, association with WAL segments

## Next Step
Run `/architect "wal-dictionary-compression"` when benchmarks demonstrate the need.
