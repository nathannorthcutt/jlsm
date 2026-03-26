---
title: "Missing close guard on cache operations"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/cache/**"
research_status: active
last_researched: "2026-03-26"
---

# Missing close guard on cache operations

## What happens
Cache implementations that clear internal state in `close()` but don't set a
"closed" flag allow continued use after close. `put()` silently re-populates
a zombie cache; `get()` returns empty (indistinguishable from a cache miss).
Callers that forget to update references after closing a cache get silent
degradation — wasted memory and missed cache hits — instead of a clear error.

## Why implementations default to this
The `BlockCache` contract says behavior after `close()` is "undefined", which
implementations interpret as "don't need to guard". Adding a `volatile boolean`
flag and check on every operation adds a small overhead, so it's skipped. The
`close()` method focuses on cleanup, not enforcement.

## Test guidance
- After `close()`: call `put()`, `get()`, `evict()`, `getOrLoad()` — all must
  throw `IllegalStateException`
- `size()` may remain accessible for diagnostic purposes (no throw)
- Verify double-close is idempotent (does not throw)

## Found in
- striped-block-cache (audit round 2, 2026-03-26): both `LruBlockCache` and `StripedBlockCache` silently accepted operations after close
