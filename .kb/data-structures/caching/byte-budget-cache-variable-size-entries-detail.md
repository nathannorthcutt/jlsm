# Byte-budget cache — implementation detail

Companion to [byte-budget-cache-variable-size-entries.md](byte-budget-cache-variable-size-entries.md).
Contains the full code skeleton, extended edge cases, and research directions
that exceeded the main article's 200-line budget.

## code-skeleton

```java
public final class ByteBudgetCache<K, V> {
    private final long maxBytes;
    private final long maxSingleEntry;        // e.g. maxBytes / 4
    private final boolean strictCapacityLimit;
    private final Weigher<K, V> weigher;
    private final ConcurrentHashMap<K, Entry<K, V>> map;
    private final LruList lru;                // mutex-guarded
    private final AtomicLong bytesInUse = new AtomicLong();

    public Handle<V> put(K key, V value) {
        long w = weigher.weigh(key, value);
        if (w > maxSingleEntry) return null;          // oversized: reject

        // Reserve optimistically
        long after = bytesInUse.addAndGet(w);
        if (after > maxBytes) {
            evictToFit(w);                            // drain unpinned LRU
            if (bytesInUse.get() > maxBytes && strictCapacityLimit) {
                bytesInUse.addAndGet(-w);             // back out
                return null;
            }
        }
        Entry<K, V> e = new Entry<>(key, value, w);
        map.put(key, e);
        lru.addToHead(e);
        return e.newHandle();                         // refcount starts at 1
    }

    public Handle<V> get(K key) {
        Entry<K, V> e = map.get(key);
        if (e == null) return null;
        Handle<V> h = e.tryAcquire();                 // CAS refcount 0→1 or n→n+1
        if (h == null) return null;                   // raced with eviction
        lru.touch(e);                                 // O(1) enqueued reorder
        return h;
    }

    // Called when Handle.close() drops refcount to 0
    void onRelease(Entry<K, V> e) {
        if (!e.inCache()) {                           // evicted-while-pinned
            bytesInUse.addAndGet(-e.weight);
            e.freeValue();
        }
    }
}
```

## extended-edge-cases

- **Weight drift across serialized forms** — a compressed SSTable block
  decompresses to a different size than its on-disk form. Weigh the
  in-memory representation actually held, not the disk size.
- **Zero-weight entries** — if the weigher returns 0 for a null/tombstone
  value, guard with `max(w, 1)` so admission logic cannot starve.
- **Wrap-around on `AtomicLong`** — a 64-bit counter cannot overflow at
  realistic cache sizes, but unbalanced `add`/`release` pairs (a missing
  release) will drift the counter silently. Add an assertion in test that
  `bytesInUse == sum(entry.weight)` on quiescence.
- **Startup warm-up** — admission against an empty budget always accepts;
  ensure the first N inserts do not all evict each other in a thrash loop
  because `bytesInUse` races ahead of installs completing.

## active-research-detail

- **Weight-aware admission** — TinyLFU variants that amortize weight into
  the frequency sketch so a 256 KiB block needs N× the hits of a 4 KiB
  block to displace it. Open question in the literature as of 2026.
- **Adaptive `singleEntryFraction`** — measure P99 block size at runtime
  and set the cap to e.g. 4× P99, so the large-block path is exercised
  but bounded.
- **PI eviction controllers** — a background thread that runs a PI loop
  against a soft watermark (e.g., 90 %) so the foreground inserter rarely
  triggers synchronous eviction. Used in some storage engines to smooth
  latency; adds a thread and complicates pin accounting.
