---
type: adversarial-finding
title: "Shared RWLock Bracketing for Facade Close Atomicity"
aliases:
  - "derive-vs-close RWLock"
  - "writer-drain close pattern"
  - "off-heap facade close fence"
topic: "patterns"
category: "concurrency"
tags:
  - "concurrency"
  - "close"
  - "rwlock"
  - "ReentrantReadWriteLock"
  - "off-heap"
  - "Arena"
  - "MemorySegment"
  - "lifecycle"
  - "use-after-free"
  - "zeroization"
research_status: "stable"
confidence: "high"
last_researched: "2026-04-23"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/encryption/EncryptionKeyHolder.java"
  - "modules/jlsm-core/src/main/java/jlsm/encryption/local/LocalKmsClient.java"
related:
  - "read-method-missing-close-guard"
  - "non-atomic-lifecycle-flags"
  - "check-then-act-across-paired-acquire-release"
  - "../../data-structures/caching/missing-close-guard"
decision_refs:
  - ".decisions/three-tier-key-hierarchy/adr.md"
source_audit: "implement-key-hierarchy / WD-01 encryption-foundation (2026-04-23)"
sources:
  - url: "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantReadWriteLock.html"
    title: "ReentrantReadWriteLock — Javadoc"
    accessed: "2026-04-23"
    type: "docs"
  - url: "https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html"
    title: "JLS Chapter 17 — Threads and Locks"
    accessed: "2026-04-23"
    type: "docs"
---

# Shared RWLock Bracketing for Facade Close Atomicity

## summary

A facade that owns mutable off-heap state (e.g., `Arena` + cached
`MemorySegment`s) and exposes public derive/read methods alongside a
`close()` that zeroizes and releases that state can race: in-flight derives
read or allocate off shared state while `close()` zeroizes and frees it. The
fix is a single `ReentrantReadWriteLock` — every public method that touches
shared state holds the **read lock** across its entire critical region
(allocations, external I/O, publishes); `close()` holds the **write lock**
across zeroize + release + mark-closed. The write lock blocks until
in-flight readers drain; new arrivals see the closed flag and refuse work.
An `AtomicBoolean closed` gate is necessary to elect one closer but is
insufficient on its own — it does not fence in-flight critical regions.

## how-it-works

Two invariants must hold jointly:

1. No in-flight derive observes a zeroed or released `cacheArena`.
2. No derive scheduled after `close()` begins mutates the shared cache.

`AtomicBoolean.compareAndSet(false, true)` elects one closer but has no
happens-before edge to in-flight derives that already passed
`requireOpen()`. Those calls keep reading, allocating, and publishing
while close is zeroizing.

`ReentrantReadWriteLock` supplies the missing synchronization. Read locks
are non-exclusive between readers — concurrent derives do not serialize
against each other. The write lock is exclusive w.r.t. every read lock,
so `close()` blocks until in-flight readers release. After `close()`
releases the write lock, new read-lock acquires still succeed but each
one re-checks `requireOpen()` inside the lock and throws.

## algorithm-steps

1. Declare `AtomicBoolean closed` and `ReentrantReadWriteLock guard`.
2. Every public method touching shared state validates inputs **before**
   `guard.readLock().lock()`, then inside a try/finally calls
   `requireOpen()` first, then allocates / does external I/O / publishes.
3. `close()` starts with `if (!closed.compareAndSet(false, true)) return;`,
   then `guard.writeLock().lock()` in a try/finally: zeroize every cached
   segment, close the arena, close other resources. Accumulate failures
   and rethrow at the end — R66/R69 zeroization failures must not be
   silently swallowed.
4. Never allocate in the shared arena or publish to the shared cache
   outside the read lock.

## implementation-notes

- **No upgrade.** `ReentrantReadWriteLock` cannot upgrade read→write —
  attempting it deadlocks. For fine-grained exclusive sections inside a
  read region, use `ConcurrentHashMap.compute` or a per-entry lock.
- **Input validation goes before the lock.** A `requireNonNull` failure
  should not block concurrent close.
- **Re-check `requireOpen()` inside the read lock.** The pre-lock check
  is best-effort; the post-lock check is the binding gate.
- **try/finally around every lock acquire.** An exception inside the
  critical region must unlock.
- **External I/O inside the read lock is acceptable** if the caller
  charges for that latency. `close()` waits at most as long as the
  slowest in-flight external call; bound unbounded I/O at the I/O layer,
  not by shortening the lock hold.
- **Orphan segments.** A derive that allocates in `cacheArena` and fails
  before publishing leaves an orphan owned by the arena. The arena release
  frees it, but if the orphan holds plaintext key material, zeroize it
  explicitly in the failure path first (see
  `EncryptionKeyHolder.provisionDomainKek`'s compute-then-orphan guard).

## tradeoffs

Strengths — cascade fix (one RWLock retires 10+ ad-hoc guards in WD-01:
F-R1.concurrency.1.1/1.4/1.7/1.8, F-R1.shared_state.1.3/2.6/4.1/4.2,
F-R1.contract_boundaries.1.11); concurrent reads stay cheap; composes
with the `AtomicBoolean` gate; tolerates external I/O in the critical
region. Weaknesses — slowest in-flight reader gates close(); no upgrade;
reader-reentry from the outer boundary can deadlock against a waiting
writer (prefer private helpers assuming the lock is held).

Complexity: `readLock.lock()` is O(1) uncontended (non-fair mode);
`writeLock.lock()` in close is O(R) in currently-held read locks;
memory is one lock per facade (~100 bytes).

### compared-to-alternatives

- vs [non-atomic-lifecycle-flags](non-atomic-lifecycle-flags.md): CAS
  elects one closer but does not fence in-flight readers — layer the
  rwlock on top.
- vs [read-method-missing-close-guard](read-method-missing-close-guard.md):
  that pattern adds a read lock for TOCTOU on a flag check; this pattern
  extends the protection to off-heap allocation, external I/O, and
  publishes — the full derive critical region.
- vs [check-then-act-across-paired-acquire-release](check-then-act-across-paired-acquire-release.md):
  paired-predicate mutex vs lifecycle-vs-work.
- vs volatile flag + `synchronized` on every method: serializes concurrent
  readers — too heavy for the hot path.
- vs `StampedLock` optimistic read: fine for short pure-CPU reads; wrong
  when reads allocate or perform I/O (optimistic retry wastes the
  allocation).

## practical-usage

Use when the facade owns off-heap state or external resources with a
lifecycle, public methods allocate or mutate shared state, `close()` must
zeroize or release that state (silent failure is a correctness bug), and
concurrent reads are part of the contract.

Skip when the facade is single-threaded (AtomicBoolean gate suffices),
reads touch only immutable fields, reads are short/pure-CPU/allocation-free
(StampedLock may fit better), or late reads are semantically harmless.

## reference-implementations

| Class | Lock field | Audit findings resolved |
|-------|-----------|--------------------------|
| `jlsm.encryption.EncryptionKeyHolder` | `deriveGuard` | F-R1.concurrency.1.1, 1.4, 1.7, 1.8; F-R1.shared_state.1.3, 2.6; F-R1.contract_boundaries.1.11 |
| `jlsm.encryption.local.LocalKmsClient` | `rwLock` | F-R1.shared_state.4.1, 4.2 |

## code-skeleton

```java
public final class Facade implements AutoCloseable {

    private final Arena cacheArena = Arena.ofShared();
    private final ConcurrentHashMap<Key, CachedEntry> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ReentrantReadWriteLock guard = new ReentrantReadWriteLock();

    public Result derive(Input input) throws ExternalException {
        Objects.requireNonNull(input, "input must not be null");   // before lock
        guard.readLock().lock();
        try {
            requireOpen();                                         // post-lock re-check
            CachedEntry entry = cache.computeIfAbsent(
                    input.key(),
                    k -> loadAndPublish(k));                       // allocates in cacheArena
            return doWork(entry, input);                           // external I/O OK here
        } finally {
            guard.readLock().unlock();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        guard.writeLock().lock();
        Throwable primary = null;
        try {
            for (CachedEntry e : cache.values()) {
                try { e.segment().fill((byte) 0); }
                catch (RuntimeException ex) { primary = addFailure(primary, ex); }
            }
            cache.clear();
            try { cacheArena.close(); }
            catch (RuntimeException ex) { primary = addFailure(primary, ex); }
        } finally {
            guard.writeLock().unlock();
        }
        if (primary != null) {
            throw new IllegalStateException("close encountered failures", primary);
        }
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("closed");
    }
}
```

## detection

Static: facade classes with `Arena`/`MemorySegment` fields whose `close()`
calls `fill((byte)0)` or `arena.close()`, where public methods allocate in
that arena without surrounding lock acquisition. For every
`AtomicBoolean closed`, verify every public method that reads/mutates
shared `MemorySegment`/`Arena` state holds the read lock and that
`close()` holds the write lock.

Concurrency lens harness: use a `CountDownLatch` to pause a deriver
mid-allocation, call `close()` from a second thread, then release. Without
the rwlock the deriver sees zero-filled bytes or closed-arena
`IllegalStateException`.

## sources

1. [ReentrantReadWriteLock — Javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantReadWriteLock.html) —
   read/write semantics, fairness, reentrancy, lack of upgrade.
2. [JLS Chapter 17 — Threads and Locks](https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html) —
   happens-before formalism. The write-lock release / read-lock acquire
   pair establishes the edge that guarantees post-close derives observe
   `closed=true`.

---
*Researched: 2026-04-23 | Next review: 2026-10-20*
