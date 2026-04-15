---
problem: "blob-object-storage-strategy"
date: "2026-04-13"
version: 1
status: "deferred"
depends_on: ["binary-field-type"]
---

# Blob Storage Strategy for Object Storage Backends — Deferred

## Problem
Whether blobs on S3/GCS should be separate objects, co-located with SSTables, or use a hybrid approach. Different cost model from local storage — co-locating frequently-changing documents with write-once blobs causes expensive compaction rewrites.

## Why Deferred
Scoped out during `binary-field-type` decision. Requires evaluation against S3/GCS PUT/GET cost model and compaction write amplification analysis.

## Resume When
When `binary-field-type` and `default-lsm-blob-store` are implemented and object storage deployment is planned.

## What Is Known So Far
User insight: co-locating things that change a lot with blobs (write-once) could be messy on object storage. KB research covers inline/external threshold patterns but not object-storage-specific cost analysis. The BlobStore SPI allows different implementations per backend.

## Next Step
Run `/architect "blob-object-storage-strategy"` when ready to evaluate.
