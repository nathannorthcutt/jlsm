---
problem: "automatic-backend-detection"
date: "2026-04-11"
version: 1
status: "deferred"
---

# ADR: Automatic Backend Detection

**Status:** deferred
**Source:** out-of-scope from `backend-optimal-block-size`

## Problem

Block size is currently caller-configured via named constants. Automatic
detection of the underlying storage backend (local SSD, S3, GCS) to select
an optimal block size without explicit configuration.

## Why Deferred

Scoped out during `backend-optimal-block-size` decision. Caller responsibility
is the simpler and more predictable approach. Auto-detection via `FileSystem`
provider metadata is not yet reliable or zero-cost across all NIO providers.

## Resume When

When `FileSystem` provider metadata becomes reliable and zero-cost for the
backends jlsm supports, or when a significant number of users misconfigure
block size.

## What Is Known So Far

See `.decisions/backend-optimal-block-size/adr.md` for the current approach.
Named constants (`LOCAL_DEFAULT`, `S3_DEFAULT`, `GCS_DEFAULT`) are provided
as guidance.

## Next Step

Run `/architect "automatic-backend-detection"` when ready to evaluate.
