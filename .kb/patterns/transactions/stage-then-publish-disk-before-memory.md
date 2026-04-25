---
title: "Stage-then-Publish — Disk-State Ordering Before In-Memory Publication"
aliases:
  - "disk-before-memory ordering"
  - "staged placeholder publication"
  - "snapshot-then-promote"
  - "two-layer publish ordering"
type: adversarial-finding
topic: "patterns"
category: "transactions"
tags:
  - "publication-ordering"
  - "TOCTOU"
  - "rollback"
  - "atomic-rename"
  - "catalog"
  - "two-layer-state"
  - "concurrent-register"
research_status: "active"
confidence: "high"
last_researched: "2026-04-25"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/TableCatalog.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/CatalogIndex.java"
related:
  - "patterns/transactions/multi-index-partial-failure-rollback.md"
  - "patterns/concurrency/torn-volatile-publish-multi-field.md"
  - "patterns/resource-management/mutation-outside-rollback-scope.md"
  - "patterns/resource-management/atomic-move-vs-fallback-commit-divergence.md"
  - "patterns/resource-management/partial-init-no-rollback.md"
  - "data-structures/caching/getorload-non-atomic.md"
decision_refs: []
source_audit: "implement-encryption-lifecycle--wd-02"
sources:
  - url: "https://cwe.mitre.org/data/definitions/367.html"
    title: "CWE-367 — Time-of-check Time-of-use (TOCTOU) Race Condition"
    accessed: "2026-04-25"
    type: "docs"
  - url: "https://man7.org/linux/man-pages/man2/rename.2.html"
    title: "rename(2) — atomic-replace semantics for the durable-publish step"
    accessed: "2026-04-25"
    type: "docs"
  - title: "F-R1.shared_state.1.1 — TableCatalog.updateEncryption paired-mutation rollback"
    accessed: "2026-04-25"
    type: "audit-finding"
  - title: "F-R1.shared_state.1.2 — TableCatalog.registerInternal staged placeholder before I/O"
    accessed: "2026-04-25"
    type: "audit-finding"
  - title: "F-R1.shared_state.1.3 — CatalogIndex.setHighwater snapshot, encode, atomic rename, then promote"
    accessed: "2026-04-25"
    type: "audit-finding"
  - title: "F-R1.shared_state.1.4 — TableCatalog.open deferred rescan after iteration"
    accessed: "2026-04-25"
    type: "audit-finding"
---

# Stage-then-Publish — Disk-State Ordering Before In-Memory Publication

## summary

A mutation that updates both an in-memory live reference (a
`ConcurrentHashMap` entry, a `volatile` field, a registry table) and an
on-disk artifact (a metadata file, a catalog index, a segment file) must
order the disk write **before** the in-memory publication so any reader who
observes the in-memory state is guaranteed to also observe the durable disk
state. The naive ordering — populate map first, then persist — produces a
window in which a concurrent reader sees the in-memory entry and either
(a) tries to load the disk artifact, sees "file not found", and crashes or
falls back; or (b) trusts the in-memory state and proceeds despite no
disk-side commitment, allowing recovery to lose state on restart.

The fix has two structural shapes; choose by whether the producer is the
unique writer of the name or whether multiple producers may race to claim
it.

## problem

Two-layer state — memory plus disk — appears in catalogs, registries, and
caches whenever durability is required and lookup must be fast. Authors
naturally write:

```java
public void register(String name, Table t) throws IOException {
    map.put(name, t);                  // in-memory FIRST
    writeMetadata(name, t.metadata()); // disk SECOND — may throw
}
```

Three failure modes follow:

1. **Reader-during-publish race.** Thread A runs `map.put`, then begins the
   I/O. Thread B observes the entry in the map, attempts to read the
   metadata file, and finds it absent (TOCTOU). The reader either crashes
   or, worse, silently treats the absence as "no metadata yet" and reuses
   stale defaults.
2. **Crash window.** A crash between `map.put` and `writeMetadata` (in this
   process or elsewhere) produces no observable effect on disk — but
   in-process callers already proceeded as though the registration was
   durable. Reads succeed, writes accumulate, and recovery on restart loses
   the registration entirely with no signal.
3. **Compensating-rollback after partial success.** When `writeMetadata`
   throws, a naive rollback removes the in-memory entry — but if a
   concurrent register raced to put a *different* entry under the same key
   in the meantime, the rollback removes the racing entry's READY state
   along with the failed entry's STAGING state. (This is the
   compensating-rollback variant of `mutation-outside-rollback-scope`.)

For multi-mutation operations (update both the metadata file and a
catalog-index high-water mark), the same problem multiplies: each pair of
disk writes plus its in-memory effect needs an ordering, and a
mid-sequence failure can leave one durable and one not.

## symptoms

- An adversarial test injects an I/O failure between an in-memory put and
  the corresponding disk write, then asserts that observable state on both
  sides is consistent. Naive code fails because the in-memory state shows
  the entry but the disk does not.
- A two-thread race where T1 calls `register("alpha", a)` and T2 calls
  `register("alpha", b)` produces an observable interleaving where T1's
  failed-and-rolled-back call removes T2's successful entry.
- On restart after a crash, an entry that the application "knows" was
  registered (because it had observed it via the in-memory map before the
  crash) is missing from the recovered catalog.

## root-cause

Two simultaneous misconceptions:

- **In-memory state is "free" so it should go first.** Authors order the
  cheap operation (map insertion) before the expensive operation (I/O) on
  intuition. But in-memory publication is the *commit* of an
  observability-side-effect: every concurrent reader can see it. Disk I/O
  is the *commitment* to the durability side-effect. Reversing the natural
  order — disk first, then memory — costs nothing on the happy path but
  defends against all three failure modes simultaneously.
- **Compensating rollback removes "the entry I added".** When the rollback
  is `map.remove(name)` it actually removes "whatever entry currently
  exists under name" — including a racing peer's READY entry. The remove
  must be conditional on the value the failed thread put.

## fix

Two structural shapes — choose by the concurrency model.

### Shape (a): staged placeholder

When multiple producers may race to claim the same name, populate the
in-memory map with a STAGING/LOADING sentinel before I/O begins. The
sentinel claims the name (so concurrent registers see "in flight" and can
fail or wait), the I/O runs, and a final transition promotes STAGING →
READY. Rollback is conditional on observing the STAGING sentinel — never
unconditional remove.

```java
public Table register(String name, TableData data) throws IOException {
    Entry staged = new Entry(name, STAGING);
    Entry prior = map.putIfAbsent(name, staged);
    if (prior != null) {
        throw new IOException("name already registered or in flight");
    }
    try {
        writeMetadata(name, data);                       // disk FIRST
        Entry ready = new Entry(name, READY, data);
        map.replace(name, staged, ready);                // promote in-memory
        return ready.table;
    } catch (IOException e) {
        map.remove(name, staged);   // conditional — only remove the staging entry
        throw e;
    }
}
```

The `putIfAbsent` claims the name before any I/O begins — a reader that
observes the entry can be told "wait, this is staging" or treat staging as
"not yet visible" depending on the contract. The conditional `remove` on
rollback is critical: an unconditional `remove(name)` could discard a
peer's READY entry that won the race and completed cleanly while this
thread's I/O was failing.

### Shape (b): snapshot-then-promote

When the producer is the unique writer (single-thread compaction commits,
exclusive-lock-protected updates), build the proposed in-memory state in a
local snapshot, encode bytes, persist via atomic-rename, *only then*
promote the snapshot to the live volatile reference. No staged placeholder
is needed because no peer can observe the in-memory state until the
promote.

```java
public void setHighwater(long newValue) throws IOException {
    long current = highwater.get();
    if (newValue <= current) return;
    Index updated = current().withHighwater(newValue);   // local snapshot
    byte[] encoded = encode(updated);                    // local bytes
    atomicWrite(indexPath, encoded);                     // disk FIRST
    this.live = updated;                                 // memory PROMOTE last
    highwater.set(newValue);                             // companion field
}
```

`atomicWrite` is the standard temp-file-plus-rename idiom. After it
returns successfully, the disk state is the new state; before it returns,
the disk state is the old state. There is no observable intermediate.

### Shape (c): paired-mutation rollback

When two disk artifacts must both update (e.g., metadata file plus
catalog-index highwater), and the second can fail after the first
succeeds, accumulate a rollback action for the first as soon as the second
begins:

```java
public void updateEncryption(String name, EncryptionState s) throws IOException {
    EncryptionState prior = readMetadata(name).encryption();
    writeMetadata(name, withEncryption(name, s));
    try {
        catalogIndex.setHighwater(name, s.highwater());
    } catch (IOException second) {
        try {
            writeMetadata(name, withEncryption(name, prior));   // compensate
        } catch (IOException compensate) {
            second.addSuppressed(compensate);
        }
        throw second;
    }
}
```

This is the multi-resource generalization of shapes (a) and (b): the
first write is durable on entry to the second's `try`, and the catch
compensates by re-running the first write with the prior state.

### Shape (d): deferred rescan to absorb peer publishes

When the registration is cooperatively published by peer JVMs (a
multi-process catalog), a process opening the catalog must scan the
on-disk view, then wait for any peer-publish that landed during the scan,
then rescan the residue. Without the rescan, a peer who published mid-scan
appears to be missing in this process's in-memory map; the deferred
rescan picks them up.

## relationship to other patterns

- `multi-index-partial-failure-rollback`: addresses N-way fan-out where
  each step is a single-resource mutation. This pattern addresses 2-way
  fan-out where each step is a *layer* (memory vs disk), not an
  independent peer.
- `torn-volatile-publish-multi-field`: addresses within-layer ordering
  (two volatile fields published non-atomically). This pattern addresses
  cross-layer ordering at coarser granularity.
- `mutation-outside-rollback-scope`: the conditional-remove rule in shape
  (a) is exactly that pattern's discipline — every mutation must be
  reversible by the rollback path. Unconditional remove violates it
  whenever a concurrent producer can interleave.
- `atomic-move-vs-fallback-commit-divergence`: shape (b) relies on
  atomic-rename semantics; if the platform falls back to non-atomic
  copy-then-rename, the disk-first guarantee weakens.
- `getorload-non-atomic`: a sibling of shape (a) at finer granularity —
  cache-loader concurrency where two threads race to populate a cache key.

## verification

For shape (a):

1. Two-thread race test — both threads call `register("k", v1/v2)` with a
   `CountDownLatch` interleaving the I/O. Inject a failure on T1's I/O.
   Assert T2's READY state survives T1's rollback.
2. Reader-during-publish test — T1 begins `register`; T2 reads the map
   while T1's I/O is in progress (controlled via mock); assert T2 sees
   STAGING (or the chosen "not yet" semantics) and never observes
   "in-memory entry plus missing disk file".

For shape (b):

3. Crash-injection test — kill the process between the atomic rename and
   the volatile assignment; on restart, assert the disk state is the new
   state and an in-process reader does not see a stale `live` reference
   that survived (it cannot — the JVM is gone). Then assert the recovery
   path repopulates `live` from the durable disk state.

For shape (c):

4. Inject failure on the second write; assert the compensating rewrite of
   the first occurred and the on-disk state matches `prior`.

For shape (d):

5. Two-JVM test (via a shared file system mock) — JVM A scans, JVM B
   publishes mid-scan, JVM A rescans; assert JVM A's final in-memory map
   includes JVM B's publish.

## tradeoffs

**Strengths.** Eliminates the entire class of "in-memory state without
durable backing" bugs. Provides a clean rollback story: failed I/O leaves
no in-memory residue (shape a) or no observable in-memory effect at all
(shape b). Cross-layer ordering is documented at the structural level so
maintainers cannot accidentally re-introduce the bug by reordering.

**Weaknesses.** Disk-first ordering can dominate latency for the producer
on the happy path — the in-memory observability is delayed until I/O
completes. For high-frequency mutations, this latency is the dominant cost
and may motivate batching. Shape (a)'s STAGING sentinel adds a state
machine to entries that did not previously have one — readers must handle
the sentinel explicitly. Shape (b) requires atomic-rename support; on a
remote backend without atomic-rename, a fallback discipline must be added
(see `atomic-move-vs-fallback-commit-divergence`).

## when-to-apply

Any registry, catalog, or cache where:

- An in-memory live reference is paired with on-disk durable state, and
- Concurrent readers can observe the in-memory state, and
- The disk state is part of the contract (recovery, peer-process
  visibility, audit trail).

Specifically:

- Catalog `register` / `unregister` paired with metadata-file writes.
- Highwater / sequence-number publishers backed by an index file.
- Encryption-state updates paired with key-version files.
- Compaction commits where a new SSTable's manifest entry must be durable
  before the in-memory levels update.

**When not to apply.** Pure in-memory caches with no durability contract
(eviction is the only consistency requirement). Single-actor sequential
code where no concurrent reader exists and no crash window matters
(rare — usually some recovery path counts as a concurrent reader).

## reference-implementation

`modules/jlsm-table/src/main/java/jlsm/table/internal/TableCatalog.java` —
`registerInternal` uses shape (a) with STAGING sentinel + conditional
rollback; `updateEncryption` uses shape (c) with paired-mutation
compensation; `open` uses shape (d) with deferred rescan.

`modules/jlsm-table/src/main/java/jlsm/table/internal/CatalogIndex.java` —
`setHighwater` uses shape (b): snapshot, encode, atomic rename, then
promote.

Audit findings that surfaced the pattern (4 findings on the same theme,
across 3 distinct shapes):

- `F-R1.shared_state.1.1` — `TableCatalog.updateEncryption`
  paired-mutation rollback (shape c).
- `F-R1.shared_state.1.2` — `TableCatalog.registerInternal` staged
  placeholder before I/O (shape a).
- `F-R1.shared_state.1.3` — `CatalogIndex.setHighwater` snapshot, encode,
  atomic rename, then promote (shape b).
- `F-R1.shared_state.1.4` — `TableCatalog.open` deferred rescan to
  absorb peer-JVM publishes (shape d).

WD-02 ciphertext-format audit, 2026-04-25.

## sources

1. [CWE-367 — Time-of-check Time-of-use (TOCTOU) Race Condition](https://cwe.mitre.org/data/definitions/367.html) — the reader-during-publish race in shape (a) is a TOCTOU window.
2. [rename(2) — atomic-replace semantics](https://man7.org/linux/man-pages/man2/rename.2.html) — POSIX guarantee that `rename` is atomic with respect to concurrent observers when both source and destination are on the same filesystem; the foundation of shape (b).

---
*Researched: 2026-04-25 | Next review: 2026-07-25*
