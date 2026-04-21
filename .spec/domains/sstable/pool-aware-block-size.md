---
{
  "id": "sstable.pool-aware-block-size",
  "version": 2,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [
    "sstable"
  ],
  "requires": [
    "F16"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "automatic-backend-detection",
    "backend-optimal-block-size"
  ],
  "kb_refs": [],
  "open_obligations": [],
  "_migrated_from": [
    "F24"
  ]
}
---
# sstable.pool-aware-block-size — Pool-Aware Block Size Configuration

## Requirements

### Pool accessor

R1. ArenaBufferPool must expose a `public long bufferSize()` method that returns the value passed to `Builder.bufferSize(long)`.

R2. ArenaBufferPool.bufferSize() must return the configured value even after the pool is closed.

### Builder pool method

R3. TrieSSTableWriter.Builder must expose a `pool(ArenaBufferPool)` method that accepts a non-null pool reference.

R4. TrieSSTableWriter.Builder.pool(null) must throw NullPointerException.

R5. TrieSSTableWriter.Builder.pool() must throw IllegalStateException if the provided pool is closed at the time of the call.

### Block size derivation

R6. The builder must track whether blockSize(int) was explicitly called, independently of the blockSize field's value.

R7. When a pool is provided and blockSize(int) was never called, the builder must derive the block size from `(int) pool.bufferSize()` at build() time.

R8. If pool.bufferSize() exceeds Integer.MAX_VALUE, build() must throw IllegalArgumentException.

R9. The pool-derived block size must pass SSTableFormat.validateBlockSize(int) at build() time.

R10. If the pool-derived block size fails SSTableFormat.validateBlockSize, build() must throw IllegalArgumentException — consistent with the behavior of an explicit blockSize(int) call.

### Explicit override

R11. If blockSize(int) was called, the explicitly set value must be used regardless of whether a pool was also provided, and regardless of the order of pool() and blockSize() calls.

R12. When both pool() and blockSize(int) are called, the pool reference must be retained for any non-block-size purpose (e.g., future buffer acquisition) but must not influence the block size.

### Default behavior (no pool)

R13. When no pool is provided and blockSize(int) was not called, the writer must use SSTableFormat.DEFAULT_BLOCK_SIZE (4096).

R14. Adding the pool(ArenaBufferPool) method must not change the behavior of existing build() call sites that do not call pool().

### F16 interaction

R15. A pool-derived non-default block size is subject to F16.R16: if no compression codec is provided, build() must throw IllegalArgumentException.

R16. The effective block size (after pool derivation and validation) must be the value written to the SSTable footer per F16.R15.

### Non-goals

N1. The pool reference is queried for bufferSize() at build() time only. The writer does not store the pool reference for runtime buffer acquisition — that is a separate implementation concern.

N2. StripedBlockCache pool integration is out of scope for F24. Cache sizing uses F25's byteBudget(long) API independently.

---

## Design Narrative

Block size is a deployment-level resource concern — local deployments use 4 KiB
blocks, remote deployments use 8 MiB blocks. The ArenaBufferPool is the resource
management layer that centralizes memory allocation. Coupling block size with pool
buffer size eliminates a misconfiguration vector where the two are set independently
to incompatible values.

The user configures their deployment profile once on the pool, and the writer
inherits it. No auto-detection, no guessing. Pool-derived block sizes are validated
identically to explicit block sizes — invalid values throw, never silently fall back.

See `.decisions/automatic-backend-detection/adr.md` for the full rationale.

## Adversarial Review Notes (v2)

v1 had 8 requirements with 7 failures. Key fixes in v2:
- R4 (silent fallback) replaced by R10 (throw on invalid) — consistent with F16.R11
- R1 split into R3-R5 (method, null check, closed-pool check)
- R2 derivation clarified: cast to int at build() time, overflow check added (R8)
- R5 ordering ambiguity resolved: blockSize(int) wins regardless of call order (R11)
- R6 return type specified as long (R1)
- R8 replaced with concrete backward-compat statement (R14)
- Added F16 interaction requirements (R15, R16)
- Added non-goals (N1, N2) to prevent scope creep
