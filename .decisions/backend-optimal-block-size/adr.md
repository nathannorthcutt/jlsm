---
problem: "backend-optimal-block-size"
date: "2026-04-10"
version: 1
status: "accepted"
depends_on: []
---

# Backend-Optimal Block Size Selection

## Problem
SSTable block size is hardcoded at 4096 bytes (`SSTableFormat.DEFAULT_BLOCK_SIZE`).
This is optimal for local SSD page-aligned reads but suboptimal for remote backends
(S3, GCS) where per-request overhead of 5-20ms makes small blocks catastrophic
for random read performance.

## Decision
**Parameterize block size as a builder option on `TrieSSTableWriter`.**

The caller chooses the block size based on their deployment context. The library
provides named constants for common backend profiles but does not auto-detect.

### API change
```java
TrieSSTableWriter.builder()
    .blockSize(SSTableFormat.REMOTE_BLOCK_SIZE)  // or custom int
    // ... other options
    .build();
```

### Named constants on `SSTableFormat`
- `DEFAULT_BLOCK_SIZE = 4096` — local SSD, page-aligned (unchanged)
- `REMOTE_BLOCK_SIZE = 65_536` — S3/GCS, amortizes per-request latency
- `LARGE_BLOCK_SIZE = 262_144` — batch/analytics workloads with sequential access

### Storage
The block size used at write time is stored in the SSTable footer so the reader
can interpret blocks correctly. This is already implicit in v2 (compression map
entries carry `compressedSize`), but for v1 (uncompressed) the block size must
be recorded.

### Validation
- Minimum: 1024 bytes (below this, overhead dominates)
- Maximum: 1 MiB (above this, cache efficiency degrades)
- Must be a power of 2 (simplifies alignment)

## Rationale
- Parameterization over auto-detection because the library doesn't know the
  deployment context — the same binary might write to local disk in tests and
  S3 in production.
- Named constants guide callers without forcing them into a fixed set.
- Power-of-2 constraint simplifies buffer allocation and alignment.

## Key Assumptions
- Callers know their backend type at configuration time.
- 4KB remains the right default for local storage.

## Conditions for Revision
- If auto-detection via `FileSystem` provider metadata becomes reliable and
  zero-cost, it could be added as a convenience default.

## Implementation Guidance
1. Add `int blockSize` field to `TrieSSTableWriter` (default 4096)
2. Add `blockSize(int)` to the writer builder with validation
3. Add `REMOTE_BLOCK_SIZE` and `LARGE_BLOCK_SIZE` constants to `SSTableFormat`
4. Replace all `SSTableFormat.DEFAULT_BLOCK_SIZE` references in the writer with
   the instance field
5. Store block size in footer for v1 compatibility
6. Reader uses stored block size (v2+ already works via compression map sizes)

## What This Decision Does NOT Solve
- Automatic backend detection — caller responsibility
- Block cache sizing relative to block size — separate concern
