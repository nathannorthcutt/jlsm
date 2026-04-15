---
problem: "inline-small-blob-optimization"
date: "2026-04-13"
version: 1
status: "deferred"
depends_on: ["binary-field-type"]
---

# Inline Small Blob Optimization — Deferred

## Problem
A BlobStore decorator that stores values below a configurable threshold inline in the document rather than externally. Avoids the indirection cost for small blobs (hashes, encryption artifacts, small icons).

## Why Deferred
Scoped out during `binary-field-type` decision. Current design always uses BlobRef. Inline optimization is a performance improvement for small values, not required for correctness.

## Resume When
When `binary-field-type` is implemented and profiling shows BlobStore round-trip latency is significant for small blob use cases.

## What Is Known So Far
The BlobStore SPI supports this as a decorator pattern — wrap any BlobStore, intercept store/retrieve for values below threshold, store inline. No changes to FieldType or DocumentSerializer needed.

## Next Step
Run `/architect "inline-small-blob-optimization"` when ready to evaluate.
