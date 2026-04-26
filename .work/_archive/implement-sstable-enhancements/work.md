---
group: implement-sstable-enhancements
goal: Implement three sstable.* DRAFT specs that layer enhancement capabilities onto the existing sstable writer — byte-budget-driven block caching, pool-aware block sizing, end-to-end integrity validation.
status: active
created: 2026-04-21
---

## Goal

Implement three sstable.* DRAFT specs that layer enhancement capabilities onto the existing sstable writer — byte-budget-driven block caching, pool-aware block sizing, end-to-end integrity validation.

## Scope

### In scope
- sstable.byte-budget-block-cache — fixed-byte-budget LRU block cache
- sstable.pool-aware-block-size — block size selection driven by arena/pool characteristics
- sstable.end-to-end-integrity — per-block + per-file integrity validation on read
- Promotion of all 3 specs DRAFT → APPROVED

### Out of scope
- sstable format version bump (a separate spec sstable.v3-format-upgrade exists for that)
- Compression algorithm changes (compression domain, not sstable)
- Encryption-at-rest for sstable content (WG5)

## Ordering Constraints

All three WDs are independent and can run in parallel. Each lands a self-contained behaviour against the existing sstable writer.

## Shared Interfaces

None cross-WD. Each WD owns its own extension point on the sstable writer.
