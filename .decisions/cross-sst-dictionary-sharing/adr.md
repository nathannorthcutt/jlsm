---
problem: "cross-sst-dictionary-sharing"
date: "2026-04-12"
version: 1
status: "deferred"
depends_on: ["codec-dictionary-support"]
---

# Cross-SST Dictionary Sharing — Deferred

## Problem
Dictionary sharing across SSTable files — each file currently trains its own dictionary. Sharing dictionaries across files in the same compaction level could reduce storage overhead and improve cache efficiency.

## Why Deferred
Scoped out during `codec-dictionary-support` decision. Per-SST dictionaries are simpler and adapt to each file's data distribution.

## Resume When
When `codec-dictionary-support` implementation is stable and per-SST dictionaries prove to have excessive overhead or poor cache behavior.

## What Is Known So Far
RocksDB uses per-SST dictionaries. Cross-file sharing would require a dictionary store and ID-based routing. See `.decisions/codec-dictionary-support/adr.md` and `.kb/algorithms/compression/zstd-dictionary-compression.md`.

## Next Step
Run `/architect "cross-sst-dictionary-sharing"` when ready to evaluate.
