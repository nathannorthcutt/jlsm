---
problem: "cross-sst-dictionary-sharing"
date: "2026-04-14"
version: 2
status: "deferred"
depends_on: ["codec-dictionary-support"]
---

# Cross-SST Dictionary Sharing — Re-Deferred

## Problem
Dictionary sharing across SSTable files — each file currently trains its own
dictionary. Sharing could reduce storage overhead and improve cache efficiency.

## Why Deferred
codec-dictionary-support (confirmed 2026-04-12) defines per-SST dictionaries.
That feature has not been implemented yet, let alone benchmarked. Cross-file
sharing adds lifecycle complexity (dictionary store, ID-based routing) that is
only justified if per-SST dictionaries prove to have excessive overhead.

## Resume When
When codec-dictionary-support is implemented and benchmarks show per-SST
dictionary overhead (storage or cache) is a measurable problem.

## What Is Known So Far
- KB: `.kb/algorithms/compression/zstd-dictionary-compression.md`
- RocksDB uses per-SST dictionaries — no evidence cross-file sharing is needed
- codec-dictionary-support ADR: writer-orchestrated, per-SST lifecycle

## Next Step
Run `/architect "cross-sst-dictionary-sharing"` when benchmarks demonstrate the need.
