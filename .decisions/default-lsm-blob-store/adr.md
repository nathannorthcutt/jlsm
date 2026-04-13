---
problem: "default-lsm-blob-store"
date: "2026-04-13"
version: 1
status: "deferred"
depends_on: ["binary-field-type"]
---

# Default LSM-Backed BlobStore — Deferred

## Problem
Provide a default BlobStore implementation using jlsm's existing LSM primitives — chunked storage with content-addressed keys, periodic GC scan, streaming read/write.

## Why Deferred
Scoped out during `binary-field-type` decision. The BlobStore SPI defines the contract; the default implementation is a separate feature.

## Resume When
When `binary-field-type` implementation is stable and users need a ready-to-use BlobStore without providing their own.

## What Is Known So Far
KB research at `.kb/systems/database-engines/blob-store-patterns.md` covers the full design: LSM-backed blob store with content-addressed keys, 1 MiB fixed-size chunking, periodic GC scan, dual-write atomicity. Object storage backends may prefer direct S3/GCS upload — this implementation is for local/embedded deployments.

## Next Step
Run `/architect "default-lsm-blob-store"` when ready to evaluate.
