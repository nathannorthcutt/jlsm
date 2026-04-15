---
problem: "automatic-backend-detection"
date: "2026-04-14"
version: 2
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-core/src/main/java/jlsm/core/io/ArenaBufferPool.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableWriter.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/SSTableFormat.java"
---

# ADR — Automatic Backend Detection

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| (none) | Decision space bounded by NIO API surface — no KB research needed | — |

---

## Files Constrained by This Decision
- `ArenaBufferPool.java` — carries deployment block size configuration
- `TrieSSTableWriter.java` — derives block size from pool when no explicit override
- `SSTableFormat.java` — named constants and validation unchanged

## Problem
The parent ADR (backend-optimal-block-size) parameterized block size on the
writer builder with named constants. It deferred automatic backend detection
as "caller responsibility." The question is whether the library should
auto-detect the storage backend to select optimal block sizes.

## Constraints That Drove This Decision
- **Zero-surprise**: Wrong auto-detection is worse than no auto-detection —
  the most narrowing constraint, ruling out all heuristic-based approaches
- **Zero-cost**: No I/O at writer construction — rules out FileStore.getBlockSize()
  which performs a stat call
- **Architectural fit**: Block size and buffer pool size are the same deployment
  concern and should flow from the same configuration point

## Decision
**Pool-aware block size configuration — derive block size from the resource
management layer instead of detecting the backend.**

Auto-detection is the wrong framing. The user knows their deployment context
(local SSD, S3, GCS). The problem is making it easy to express that context
once, in the right place. The ArenaBufferPool is the resource management layer
that already centralizes memory allocation. Block size is a resource allocation
concern — how much memory each I/O operation consumes. Coupling block size
with pool buffer size eliminates a misconfiguration vector where the two are
set independently to incompatible values.

### Design
When the TrieSSTableWriter builder receives a pool reference and no explicit
`blockSize(int)` override, it derives its block size from the pool's buffer
size. When no pool is provided, `DEFAULT_BLOCK_SIZE` (4096) applies. The
explicit `blockSize(int)` always overrides for edge cases (per-table or
per-level variation).

### Why not auto-detection
- `FileStore.getBlockSize()` returns the storage allocation unit, not the
  optimal I/O transfer size — semantic mismatch causes silent degradation
- `FileSystem.provider().getScheme()` is a closed set requiring maintenance
- Both approaches guess at something the user already knows
- The parent ADR's reasoning holds: "the library doesn't know the deployment
  context" — but it doesn't need to if the user can express it once

## Rationale

### Why pool-aware configuration
- **Zero-surprise**: Explicit configuration, no guessing or heuristics
- **Zero-cost**: No I/O, no detection — configuration at startup
- **Reduced config surface**: One deployment knob instead of two independent
  ones (pool buffer size + writer block size)
- **Architectural alignment**: The buffer pool is already the resource
  management layer; block size is a resource concern

### Why not FileStore-based approaches
- `getBlockSize()` has a semantic mismatch: storage allocation unit ≠ optimal
  I/O transfer size. An S3 provider might return 4096 (internal chunk size)
  while optimal is 8 MiB. Silent performance degradation with no diagnostic.
- `Files.getFileStore(path)` performs I/O (stat call), violating the zero-cost
  constraint. On remote providers, this could block.

### Why not scheme-based lookup
- Closed set of known schemes (`s3`, `gcs`, `file`) needs maintenance when
  new providers appear. The mapping from scheme to optimal block size is a
  heuristic that may not hold for all providers of a given scheme.

### Why not close (status quo)
- The existing parameterization works but leaves block size and buffer size
  as independent knobs. A user who configures the pool for 8 MiB buffers
  but forgets to set the writer's block size gets 4 KiB blocks in 8 MiB
  buffers — wasteful and likely unintended.

## Implementation Guidance
1. The writer builder accepts an `ArenaBufferPool` reference (or the pool
   exposes a `blockSize()` accessor derived from its buffer size)
2. When pool is provided and no explicit `blockSize()` is set: use pool's
   buffer size as block size (subject to `SSTableFormat.validateBlockSize()`)
3. When no pool is provided: default to `DEFAULT_BLOCK_SIZE` (4096)
4. Explicit `blockSize(int)` always overrides pool-derived value
5. Named constants (`REMOTE_BLOCK_SIZE`, `HUGE_PAGE_BLOCK_SIZE`) remain
   available for direct use

## What This Decision Does NOT Solve
- Per-table or per-level block size variation — use explicit `blockSize(int)` override
- Writer integration with pool for block I/O — separate implementation concern

## Conditions for Revision
This ADR should be re-evaluated if:
- Per-table block size variation becomes the common case (pool-wide default insufficient)
- The pool's buffer size semantics diverge from block size semantics (e.g., pool
  manages sub-block buffers for a different purpose)

---
*Confirmed by: user deliberation | Date: 2026-04-14*
*Full scoring: [evaluation.md](evaluation.md)*
