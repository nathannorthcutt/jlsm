---
problem: "wal-dictionary-compression"
date: "2026-04-12"
version: 1
status: "deferred"
depends_on: ["codec-dictionary-support"]
---

# WAL Dictionary Compression — Deferred

## Problem
Dictionary compression for WAL records — individual records arrive one at a time with no batch context for training a dictionary.

## Why Deferred
Scoped out during `codec-dictionary-support` decision. WAL records lack the batch context needed for dictionary training. A pre-trained dictionary from historical data could work but adds lifecycle complexity.

## Resume When
When `codec-dictionary-support` implementation is stable and WAL compression ratios with plain ZSTD/Deflate are insufficient.

## What Is Known So Far
WAL uses per-record compression (see `wal-compression` ADR). Dictionary training requires representative samples — could use a dictionary trained from recent SSTable data. See `.kb/algorithms/compression/zstd-dictionary-compression.md#when-not-to-use`.

## Next Step
Run `/architect "wal-dictionary-compression"` when ready to evaluate.
