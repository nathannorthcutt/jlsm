---
title: "Non-atomic getOrLoad default method"
type: adversarial-finding
domain: "concurrency"
severity: "confirmed"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/core/cache/**"
  - "modules/jlsm-core/src/main/java/jlsm/cache/**"
research_status: active
last_researched: "2026-03-26"
---

# Non-atomic getOrLoad default method

## What happens
The `BlockCache.getOrLoad` default method performs a non-atomic check-then-act
sequence: `get()` acquires lock → miss → releases lock → `loader.get()` runs
outside any lock → `put()` acquires lock → inserts. Under concurrent access,
two threads can both miss for the same key and both invoke the loader. When
the loader performs disk I/O (the primary use case — loading an SSTable block),
this doubles I/O under contention.

## Why implementations default to this
Interface default methods cannot access implementation-specific locks. The
default `getOrLoad` is written in terms of `get()` and `put()`, each of which
is individually thread-safe. The non-atomicity is an emergent property of
composing two atomic operations without a covering lock.

## Test guidance
- Two threads race `getOrLoad` for the same key with an `AtomicInteger`
  counting loader invocations; assert count == 1
- Use `Thread.sleep(50)` in the loader to widen the race window
- Test both single-lock and striped implementations

## Found in
- striped-block-cache (audit round 2, 2026-03-26): `LruBlockCache` and `StripedBlockCache` both inherited the non-atomic default; fixed by overriding `getOrLoad` to hold the lock across get+load+put
