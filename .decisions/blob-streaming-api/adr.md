---
problem: "blob-streaming-api"
date: "2026-04-13"
version: 1
status: "deferred"
depends_on: ["binary-field-type"]
---

# Blob Streaming API Design — Deferred

## Problem
Define the chunked upload/download interface contract for binary field blobs — progress callbacks, resumable uploads, chunked streaming through ReadableByteChannel/WritableByteChannel.

## Why Deferred
Scoped out during `binary-field-type` decision. The BlobStore SPI defines basic store/retrieve; the streaming API design (progress, resumability, chunking strategy) is a separate concern.

## Resume When
When `binary-field-type` implementation is stable and streaming upload/download is needed.

## What Is Known So Far
BlobStore SPI uses ReadableByteChannel/WritableByteChannel for streaming. KB research recommends 1 MiB fixed-size chunks for the 1-50 MiB target range.

## Next Step
Run `/architect "blob-streaming-api"` when ready to evaluate.
