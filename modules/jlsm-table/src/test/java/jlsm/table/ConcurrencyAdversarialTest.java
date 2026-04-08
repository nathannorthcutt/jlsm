package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jlsm.encryption.EncryptionKeyHolder;
import jlsm.table.internal.FieldIndex;
import jlsm.table.internal.IndexRegistry;
import jlsm.table.internal.SseEncryptedIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Adversarial concurrency tests for table-indices-and-queries audit.
 */
class ConcurrencyAdversarialTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    private static MemorySegment stringKey(String key) {
        byte[] bytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MemorySegment seg = Arena.ofAuto().allocate(bytes.length);
        seg.copyFrom(MemorySegment.ofArray(bytes));
        return seg;
    }

    // ── F-R1.conc.2.2: FieldIndex.closed flag checked only via assert ──

    // Finding: F-R1.conc.2.2
    // Bug: FieldIndex.closed flag is checked only via assert — with assertions
    // disabled (production JVM), onInsert/onUpdate/onDelete/lookup proceed
    // on a closed index, silently re-populating cleared entries
    // Correct behavior: operations on a closed FieldIndex must throw
    // IllegalStateException unconditionally (runtime check, not assert)
    // Fix location: FieldIndex.java lines 80, 100, 110, 120 — replace assert
    // with runtime if/throw
    // Regression watch: ensure the check fires even with -da (assertions disabled)
    @Test
    @Timeout(10)
    void test_FieldIndex_closed_flag_assert_only_allows_operations_after_close() throws Exception {
        FieldIndex index = new FieldIndex(new IndexDefinition("name", IndexType.EQUALITY),
                FieldType.Primitive.STRING);

        // Insert a value, then close
        index.onInsert(stringKey("pk-1"), "Alice");
        index.close();

        // onInsert after close must throw IllegalStateException (not silently succeed)
        assertThrows(IllegalStateException.class, () -> index.onInsert(stringKey("pk-2"), "Bob"),
                "onInsert() on a closed FieldIndex must throw IllegalStateException");

        // onUpdate after close must also throw
        assertThrows(IllegalStateException.class,
                () -> index.onUpdate(stringKey("pk-1"), "Alice", "Carol"),
                "onUpdate() on a closed FieldIndex must throw IllegalStateException");

        // onDelete after close must also throw
        assertThrows(IllegalStateException.class, () -> index.onDelete(stringKey("pk-1"), "Alice"),
                "onDelete() on a closed FieldIndex must throw IllegalStateException");

        // lookup after close must also throw
        assertThrows(IllegalStateException.class,
                () -> index.lookup(new Predicate.Eq("name", "Alice")),
                "lookup() on a closed FieldIndex must throw IllegalStateException");
    }

    // ── F-R1.conc.2.3: FieldIndex.close() then concurrent lookup ─────────

    // Finding: F-R1.conc.2.3
    // Bug: lookupGt() obtains a tailMap view, then close() clears the backing
    // map; flattenValues() iterates the view whose backing map was cleared,
    // causing ConcurrentModificationException or partial/corrupt results.
    // The ConcurrentSkipListMap views are weakly consistent (no CME), but
    // the ArrayList values inside are not thread-safe — concurrent iteration
    // via lookupNe (which calls entry.getValue() and addAll) racing with
    // onInsert's .add() on the same ArrayList can corrupt internal state.
    // Correct behavior: concurrent close()/onInsert() during lookup must not
    // throw exceptions — the iterator should return a consistent snapshot
    // Fix location: FieldIndex.java — flattenValues snapshot of value lists,
    // or use CopyOnWriteArrayList for entries values
    // Regression watch: ensure range lookups remain safe under concurrent mutation
    @Test
    @Timeout(30)
    void test_FieldIndex_close_during_range_lookup_no_CME() throws Exception {
        // Strategy: Multiple reader threads call lookupNe which iterates
        // entries.entrySet() and calls addAll on each ArrayList value.
        // Multiple writer threads call onInsert on the same field values,
        // growing those ArrayLists. lookupNe's iteration over the entrySet
        // is safe (ConcurrentSkipListMap), but addAll on the ArrayList value
        // races with add from onInsert — ArrayList.addAll internally calls
        // toArray() which can see stale size vs resized elementData.
        int iterations = 2000;
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < iterations && failure.get() == null; i++) {
            FieldIndex index = new FieldIndex(new IndexDefinition("age", IndexType.RANGE),
                    FieldType.Primitive.INT32);

            // Pre-populate with multiple field values
            for (int j = 0; j < 20; j++) {
                index.onInsert(stringKey("pk-pre-" + j), j % 5);
            }

            CyclicBarrier barrier = new CyclicBarrier(5);

            // 2 reader threads: lookupNe iterates entrySet and calls addAll on each value
            Thread[] readers = new Thread[2];
            for (int r = 0; r < readers.length; r++) {
                readers[r] = Thread.ofVirtual().start(() -> {
                    try {
                        barrier.await();
                        for (int k = 0; k < 50; k++) {
                            try {
                                var it = index.lookup(new Predicate.Ne("age", -1));
                                while (it.hasNext()) {
                                    it.next();
                                }
                            } catch (IllegalStateException _) {
                                break;
                            }
                        }
                    } catch ( java.util.ConcurrentModificationException
                            | ArrayIndexOutOfBoundsException | NullPointerException e) {
                        failure.compareAndSet(null, e);
                    } catch (Exception _) {
                        // not the race we're testing
                    }
                });
            }

            // 2 writer threads: insert same field values, growing the ArrayLists
            Thread[] writers = new Thread[2];
            for (int w = 0; w < writers.length; w++) {
                final int wIdx = w;
                writers[w] = Thread.ofVirtual().start(() -> {
                    try {
                        barrier.await();
                        for (int j = 0; j < 100; j++) {
                            try {
                                index.onInsert(stringKey("pk-w" + wIdx + "-" + j), j % 5);
                            } catch (IllegalStateException _) {
                                break;
                            }
                        }
                    } catch ( java.util.ConcurrentModificationException
                            | ArrayIndexOutOfBoundsException e) {
                        failure.compareAndSet(null, e);
                    } catch (Exception _) {
                        // not the race we're testing
                    }
                });
            }

            // 1 closer thread
            Thread closer = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    index.close();
                } catch (Exception _) {
                    // not the race we're testing
                }
            });

            for ( Thread r : readers)
                r.join(5000);
            for (Thread w : writers)
                w.join(5000);
            closer.join(5000);
        }

        assertNull(failure.get(),
                "Concurrent close()/onInsert() during range lookup must not throw "
                        + "exceptions from unsynchronized ArrayList access: " + failure.get());
    }

    // ── F-R1.conc.2.1: FieldIndex.close() races with mutation ───────────

    // Finding: F-R1.conc.2.1
    // Bug: TreeMap entries is not thread-safe; concurrent close() (entries.clear())
    // and onInsert() (entries.computeIfAbsent().add()) corrupt TreeMap state
    // or throw ConcurrentModificationException
    // Correct behavior: close() and onInsert() must not corrupt internal state;
    // either close() completes cleanly or onInsert() completes cleanly,
    // with no ConcurrentModificationException or corrupted map state
    // Fix location: FieldIndex.java — entries field type (line 30, 44)
    // Regression watch: ensure NavigableMap contract (sorted iteration, subMap views) preserved
    @Test
    @Timeout(30)
    void test_FieldIndex_close_races_with_onInsert() throws Exception {
        // Strategy: use lookupNe which iterates entries.entrySet() — this creates a
        // fail-fast iterator on TreeMap. Concurrent mutation during iteration reliably
        // triggers ConcurrentModificationException.
        int iterations = 500;
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < iterations && failure.get() == null; i++) {
            FieldIndex index = new FieldIndex(new IndexDefinition("age", IndexType.RANGE),
                    FieldType.Primitive.INT32);

            // Pre-populate to make iteration take non-trivial time
            for (int j = 0; j < 100; j++) {
                index.onInsert(stringKey("pk-pre-" + j), j);
            }

            CyclicBarrier barrier = new CyclicBarrier(3);

            // Thread A: continuously inserts (mutates the TreeMap)
            Thread inserter = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    for (int j = 100; j < 300; j++) {
                        index.onInsert(stringKey("pk-" + j), j);
                    }
                } catch (java.util.ConcurrentModificationException e) {
                    failure.compareAndSet(null, e);
                } catch (Exception _) {
                    // Not the race we're testing
                }
            });

            // Thread B: calls close() which calls entries.clear()
            Thread closer = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    index.close();
                } catch (java.util.ConcurrentModificationException e) {
                    failure.compareAndSet(null, e);
                } catch (Exception _) {
                    // Not the race we're testing
                }
            });

            // Thread C: lookupNe iterates entrySet() — fail-fast on concurrent modification
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    // lookupNe iterates all entries via entrySet(), creating a fail-fast iterator
                    var it = index.lookup(new Predicate.Ne("age", -999));
                    while (it.hasNext()) {
                        it.next();
                    }
                } catch (java.util.ConcurrentModificationException e) {
                    failure.compareAndSet(null, e);
                } catch (Exception _) {
                    // Not the race we're testing
                }
            });

            inserter.join(5000);
            closer.join(5000);
            reader.join(5000);
        }

        // With an unsynchronized TreeMap, concurrent mutation during iteration will
        // trigger ConcurrentModificationException from the fail-fast iterator.
        // After the fix (ConcurrentSkipListMap), no CME should occur.
        assertNull(failure.get(), "Concurrent close()/onInsert()/lookup() must not throw "
                + "ConcurrentModificationException: " + failure.get());
    }

    // ── F-R1.conc.2.5: IndexRegistry.closed flag checked only via assert ──

    // Finding: F-R1.conc.2.5
    // Bug: IndexRegistry.closed flag is checked only via assert — with assertions
    // disabled (production JVM), onInsert/onUpdate/onDelete proceed on a closed
    // registry, re-populating cleared indices and documentStore
    // Correct behavior: operations on a closed IndexRegistry must throw
    // IllegalStateException unconditionally (runtime check, not assert)
    // Fix location: IndexRegistry.java lines 52, 77, 110 — replace assert
    // with runtime if/throw
    // Regression watch: ensure the check fires even with -da (assertions disabled)
    @Test
    @Timeout(10)
    void test_IndexRegistry_closed_flag_assert_only_allows_operations_after_close()
            throws Exception {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("age", FieldType.int32()).build();
        List<IndexDefinition> defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
        IndexRegistry registry = new IndexRegistry(schema, defs);

        // Insert a document, then close
        JlsmDocument doc1 = JlsmDocument.of(schema, "name", "Alice", "age", 30);
        registry.onInsert(stringKey("pk-1"), doc1);
        registry.close();

        // onInsert after close must throw IllegalStateException (not AssertionError, not silently
        // succeed)
        JlsmDocument doc2 = JlsmDocument.of(schema, "name", "Bob", "age", 25);
        assertThrows(IllegalStateException.class, () -> registry.onInsert(stringKey("pk-2"), doc2),
                "onInsert() on a closed IndexRegistry must throw IllegalStateException");

        // onUpdate after close must also throw
        JlsmDocument doc3 = JlsmDocument.of(schema, "name", "Carol", "age", 35);
        assertThrows(IllegalStateException.class,
                () -> registry.onUpdate(stringKey("pk-1"), doc1, doc3),
                "onUpdate() on a closed IndexRegistry must throw IllegalStateException");

        // onDelete after close must also throw
        assertThrows(IllegalStateException.class, () -> registry.onDelete(stringKey("pk-1"), doc1),
                "onDelete() on a closed IndexRegistry must throw IllegalStateException");
    }

    // ── F-R1.conc.2.4: IndexRegistry.documentStore unsynchronized ───────

    // Finding: F-R1.conc.2.4
    // Bug: IndexRegistry.documentStore is a LinkedHashMap which is not
    // thread-safe; concurrent onInsert() calls corrupt internal state
    // (lost entries, infinite loops in linked list traversal, or
    // ConcurrentModificationException)
    // Correct behavior: concurrent onInsert() calls must not corrupt the
    // document store — all inserted entries must be retrievable
    // Fix location: IndexRegistry.java line 41 — replace LinkedHashMap
    // with a concurrent map implementation
    // Regression watch: allEntries() iteration order may change if
    // LinkedHashMap is replaced with ConcurrentHashMap
    @Test
    @Timeout(30)
    void test_IndexRegistry_documentStore_concurrent_onInsert_corruption() throws Exception {
        int iterations = 500;
        int threadsPerIteration = 4;
        int insertsPerThread = 50;
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < iterations && failure.get() == null; i++) {
            JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                    .field("age", FieldType.int32()).build();
            List<IndexDefinition> defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
            IndexRegistry registry = new IndexRegistry(schema, defs);
            CyclicBarrier barrier = new CyclicBarrier(threadsPerIteration);
            AtomicInteger successCount = new AtomicInteger(0);

            Thread[] threads = new Thread[threadsPerIteration];
            for (int t = 0; t < threadsPerIteration; t++) {
                final int threadIdx = t;
                threads[t] = Thread.ofVirtual().start(() -> {
                    try {
                        barrier.await();
                        for (int j = 0; j < insertsPerThread; j++) {
                            String pk = "pk-t" + threadIdx + "-" + j;
                            JlsmDocument doc = JlsmDocument.of(schema, "name",
                                    "name-" + threadIdx + "-" + j, "age", j);
                            registry.onInsert(stringKey(pk), doc);
                            successCount.incrementAndGet();
                        }
                    } catch (java.util.ConcurrentModificationException
                            | ArrayIndexOutOfBoundsException | NullPointerException e) {
                        failure.compareAndSet(null, e);
                    } catch (Exception _) {
                        // not the race we're testing
                    }
                });
            }

            for ( Thread t : threads)
                t.join(10_000);

            if (failure.get() != null)
                break;

            // Even if no exception, check for lost entries — a corrupted
            // LinkedHashMap can silently lose puts
            int inserted = successCount.get();
            int stored = 0;
            var it = registry.allEntries();
            while (it.hasNext()) {
                it.next();
                stored++;
            }
            if (stored != inserted) {
                failure.set(new AssertionError(
                        "Lost entries: inserted " + inserted + " but documentStore has " + stored));
            }

            registry.close();
        }

        assertNull(failure.get(), "Concurrent onInsert() on IndexRegistry must not corrupt "
                + "documentStore (LinkedHashMap is not thread-safe): " + failure.get());
    }

    // ── F-R1.concurrency.2.1: TOCTOU race in EncryptionKeyHolder.getKeyBytes ──

    // Finding: F-R1.concurrency.2.1
    // Bug: TOCTOU race between getKeyBytes() ensureOpen() check and keySegment
    // read — Thread A passes ensureOpen(), Thread B close() zeroes keySegment
    // and closes arena, Thread A reads all-zero bytes (silent data corruption)
    // or gets an uncontrolled IllegalStateException from arena scope
    // Correct behavior: getKeyBytes() must either return valid key bytes or throw
    // IllegalStateException("EncryptionKeyHolder has been closed") — never
    // return zeroed bytes and never throw an arena scope exception
    // Fix location: EncryptionKeyHolder.java lines 74-79 (getKeyBytes) and 103-111 (close)
    // Regression watch: fix must not deadlock under concurrent getKeyBytes() calls
    @Test
    @Timeout(30)
    void test_EncryptionKeyHolder_getKeyBytes_TOCTOU_returns_zeroed_key() throws Exception {
        // Strategy: race getKeyBytes() against close() in a tight loop.
        // If the TOCTOU window exists, eventually a reader will pass ensureOpen()
        // then read zeroed memory (all-zero byte array) — silent data corruption.
        // We also catch the case where an uncontrolled arena scope exception leaks.
        final int iterations = 5000;
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < iterations && failure.get() == null; i++) {
            final byte[] keyMaterial = new byte[32];
            for (int k = 0; k < 32; k++)
                keyMaterial[k] = (byte) (k + 0xA0);
            final byte[] expectedKey = keyMaterial.clone();
            final EncryptionKeyHolder holder = EncryptionKeyHolder.of(keyMaterial);

            CyclicBarrier barrier = new CyclicBarrier(3);

            // Two reader threads call getKeyBytes() concurrently
            Thread[] readers = new Thread[2];
            for (int r = 0; r < 2; r++) {
                readers[r] = Thread.ofVirtual().start(() -> {
                    try {
                        barrier.await();
                        for (int j = 0; j < 20; j++) {
                            try {
                                byte[] key = holder.getKeyBytes();
                                // If we got a result, it must be the actual key — not zeroes
                                boolean allZero = true;
                                for (byte b : key) {
                                    if (b != 0) {
                                        allZero = false;
                                        break;
                                    }
                                }
                                if (allZero) {
                                    failure.compareAndSet(null, new AssertionError(
                                            "getKeyBytes() returned all-zero bytes — "
                                                    + "TOCTOU race allowed reading zeroed key material"));
                                    return;
                                }
                            } catch (IllegalStateException e) {
                                // Expected: holder was closed. But verify it's the holder's
                                // message.
                                if (!e.getMessage()
                                        .contains("EncryptionKeyHolder has been closed")) {
                                    failure.compareAndSet(null, new AssertionError(
                                            "getKeyBytes() threw unexpected IllegalStateException: "
                                                    + e.getMessage(),
                                            e));
                                    return;
                                }
                                break; // closed, stop reading
                            }
                        }
                    } catch (Exception e) {
                        failure.compareAndSet(null, e);
                    }
                });
            }

            // Closer thread
            Thread closer = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    holder.close();
                } catch (Exception e) {
                    failure.compareAndSet(null, e);
                }
            });

            for (Thread r : readers)
                r.join(5000);
            closer.join(5000);
        }

        assertNull(failure.get(),
                "getKeyBytes() must never return zeroed key bytes during concurrent close(): "
                        + failure.get());
    }

    // ── F-R1.conc.2.6: IndexRegistry.onInsert phase-2 partial failure ──

    // Finding: F-R1.conc.2.6
    // Bug: IndexRegistry.onInsert phase-2 partial failure leaves inconsistent
    // index state — if index N throws during phase-2 insert, indices 0..N-1
    // already contain the entry but documentStore does not. Phantom index
    // entries block future unique inserts and resolve to null.
    // Correct behavior: if phase-2 insert fails partway through, all indices
    // that received the insert must be rolled back (onDelete) so no
    // phantom entries remain
    // Fix location: IndexRegistry.java lines 66-72 — add rollback on failure
    // Regression watch: rollback must not mask the original exception
    @Test
    @Timeout(30)
    void test_IndexRegistry_onInsert_phase2_partial_failure_rollback() throws Exception {
        // Setup: schema with 3 fields, 2 non-unique indices + 1 unique index.
        // The unique index is last so that phase-2 inserts into non-unique
        // indices 0 and 1 before reaching the unique index 2.
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("city", FieldType.string())
                .field("age", FieldType.int32()).field("email", FieldType.string()).build();
        List<IndexDefinition> defs = List.of(new IndexDefinition("city", IndexType.EQUALITY), // index
                                                                                              // 0:
                                                                                              // non-unique
                new IndexDefinition("age", IndexType.RANGE), // index 1: non-unique
                new IndexDefinition("email", IndexType.UNIQUE)); // index 2: unique

        // Race two threads inserting the same unique email. One will succeed,
        // the other will fail in phase 2 at the unique index. Without rollback,
        // the loser's entries remain in indices 0 and 1 as phantoms.
        int iterations = 2000;
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < iterations && failure.get() == null; i++) {
            IndexRegistry registry = new IndexRegistry(schema, defs);

            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicInteger succeeded = new AtomicInteger(0);
            AtomicInteger duplicateErrors = new AtomicInteger(0);

            Thread t1 = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    JlsmDocument doc = JlsmDocument.of(schema, "city", "NYC", "age", 30, "email",
                            "dup@test.com");
                    registry.onInsert(stringKey("pk-A"), doc);
                    succeeded.incrementAndGet();
                } catch (DuplicateKeyException _) {
                    duplicateErrors.incrementAndGet();
                } catch (Exception e) {
                    failure.compareAndSet(null, e);
                }
            });

            Thread t2 = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    JlsmDocument doc = JlsmDocument.of(schema, "city", "NYC", "age", 30, "email",
                            "dup@test.com");
                    registry.onInsert(stringKey("pk-B"), doc);
                    succeeded.incrementAndGet();
                } catch (DuplicateKeyException _) {
                    duplicateErrors.incrementAndGet();
                } catch (Exception e) {
                    failure.compareAndSet(null, e);
                }
            });

            t1.join(5000);
            t2.join(5000);

            if (failure.get() != null)
                break;

            // At least one must succeed; the other may or may not get a duplicate error
            // (depends on timing — both may pass phase 1 before either enters phase 2,
            // or one may fully complete before the other starts)
            if (succeeded.get() == 0) {
                failure.set(new AssertionError("Neither insert succeeded"));
                break;
            }

            // KEY ASSERTION: the "city" index (non-unique, index 0) must contain
            // entries ONLY for primary keys that are in the documentStore. If rollback
            // is missing, the loser's pk is in the city index but not in documentStore.
            var cityPredicate = new Predicate.Eq("city", "NYC");
            var cityIndex = registry.findIndex(cityPredicate);
            assertNotNull(cityIndex, "city index must exist");

            Iterator<MemorySegment> cityResults = cityIndex.lookup(cityPredicate);
            int cityCount = 0;
            boolean hasPhantom = false;
            while (cityResults.hasNext()) {
                MemorySegment pk = cityResults.next();
                cityCount++;
                if (registry.resolveEntry(pk) == null) {
                    hasPhantom = true;
                }
            }

            if (hasPhantom) {
                failure.set(new AssertionError(
                        "Phantom index entry found: city index contains a primary key "
                                + "with no corresponding documentStore entry. " + "succeeded="
                                + succeeded.get() + ", duplicateErrors=" + duplicateErrors.get()
                                + ", cityIndexEntries=" + cityCount));
            }

            registry.close();
        }

        assertNull(failure.get(),
                "Phase-2 partial failure must roll back index entries: " + failure.get());
    }

    // ── F-R1.conc.1.2: delete() non-atomic read-check-write allows concurrent delete to miss ──

    // Finding: F-R1.conc.1.2
    // Bug: delete() non-atomic read-check-write allows concurrent delete to miss
    // Correct behavior: concurrent deletes of the same document must both succeed;
    // the entry must be deleted regardless of interleaving
    // Fix location: SseEncryptedIndex.java delete() lines 143-154
    // Regression watch: double-delete must remain idempotent
    // RESULT: IMPOSSIBLE — see approach notes below
    //
    // Approach 1: concurrent double-delete — both threads write same deleted marker
    // (idempotent, test passes, no bug)
    // Approach 2: concurrent delete + add of same doc — add gets a new counter
    // position, delete operates on old position, no interference (test passes)
    // Approach 3: concurrent delete + search — search correctly skips DELETED_MARKER
    // entries and breaks on null; delete only modifies existing positions (test passes)
    //
    // The non-atomic read-check-write in delete() is harmless:
    // - store is ConcurrentHashMap (volatile reads/writes)
    // - delete only writes DELETED_MARKER to the position where it found the doc
    // - add() never reuses positions (monotonic counter with getAndIncrement)
    // - concurrent deletes of the same doc produce identical DELETED_MARKER writes
    // - the double-write is idempotent — the final state is always correct

    // ── F-R1.conc.1.3: AtomicInteger counter overflow under sustained concurrent adds ──

    // Finding: F-R1.conc.1.3
    // Bug: AtomicInteger counter in termCounters overflows from Integer.MAX_VALUE
    // to Integer.MIN_VALUE with no guard. Negative counter values produce
    // colliding storage addresses, silently overwriting existing entries.
    // Correct behavior: add() must reject the operation when the per-term counter
    // would overflow Integer.MAX_VALUE, throwing IllegalStateException
    // Fix location: SseEncryptedIndex.java add() line 116 — guard getAndIncrement
    // Regression watch: normal adds must still work; only overflow triggers rejection
    @Test
    @Timeout(10)
    void test_SseEncryptedIndex_add_counter_overflow_rejected() throws Exception {
        final byte[] keyMaterial = new byte[32];
        for (int k = 0; k < 32; k++)
            keyMaterial[k] = (byte) (k + 0xA0);
        final EncryptionKeyHolder kh = EncryptionKeyHolder.of(keyMaterial);
        final SseEncryptedIndex idx = new SseEncryptedIndex(kh);

        // Add one entry to create the per-term counter
        idx.add("overflowTerm", "doc-0".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Use reflection to set the counter to Integer.MAX_VALUE, simulating
        // 2^31 - 1 prior adds. The next add() must detect the overflow.
        var termCountersField = SseEncryptedIndex.class.getDeclaredField("termCounters");
        termCountersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var termCounters = (java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>) termCountersField
                .get(idx);
        termCounters.get("overflowTerm").set(Integer.MAX_VALUE);

        // The next add() must throw — the counter would overflow to negative
        assertThrows(IllegalStateException.class,
                () -> idx.add("overflowTerm",
                        "doc-overflow".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "add() must reject when per-term counter would overflow Integer.MAX_VALUE");

        kh.close();
    }

    // ── F-R1.concurrency.1.1: findIndex() races with close() — no lock protection ──

    // Finding: F-R1.concurrency.1.1
    // Bug: findIndex() checks closed.get() but does not acquire the read lock,
    // so close() can complete teardown (closing all indices) between
    // findIndex() returning and the caller using the result. The caller
    // receives a closed SecondaryIndex and gets ISE "Index is closed"
    // when calling lookup().
    // Correct behavior: findAndLookup() must atomically find an index and execute
    // lookup under the read lock, preventing close() from tearing down indices
    // between the find and the lookup.
    // Fix location: IndexRegistry.java — add findAndLookup() with read lock around
    // both find and lookup; update QueryExecutor to use findAndLookup()
    // Regression watch: must not deadlock with close() write lock
    @Test
    @Timeout(30)
    void test_IndexRegistry_findIndex_races_with_close_returns_closed_index() throws Exception {
        // Strategy: race findAndLookup() against close() in a tight loop.
        // Without the atomic operation, the two-step pattern (findIndex then lookup)
        // allows close() to tear down indices between the calls.
        // findAndLookup() holds the read lock for both steps, so close() blocks
        // until the entire find+lookup completes.
        final int iterations = 5000;
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < iterations && failure.get() == null; i++) {
            JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                    .field("age", FieldType.int32()).build();
            List<IndexDefinition> defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
            IndexRegistry registry = new IndexRegistry(schema, defs);

            // Pre-populate
            JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);
            registry.onInsert(stringKey("pk-1"), doc);

            CyclicBarrier barrier = new CyclicBarrier(2);
            final Predicate pred = new Predicate.Eq("name", "Alice");

            // Thread A: findAndLookup atomically
            Thread finder = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    for (int j = 0; j < 100; j++) {
                        try {
                            // findAndLookup() holds the read lock for both the find
                            // and the lookup. close() cannot tear down indices while
                            // this call is in progress.
                            var results = registry.findAndLookup(pred);
                            // results is null if no index supports the predicate
                            // (should not happen here), or a snapshot list.
                        } catch (IllegalStateException e) {
                            if (e.getMessage() != null
                                    && e.getMessage().contains("Index is closed")) {
                                // BUG: lookup was called on a closed index — the
                                // find and lookup were not atomic.
                                failure.compareAndSet(null, new AssertionError(
                                        "findAndLookup() called lookup() on a closed index — "
                                                + "find and lookup were not atomic",
                                        e));
                                return;
                            }
                            // "Registry is closed" — acceptable
                            break;
                        }
                    }
                } catch (Exception _) {
                    // barrier/interrupt — not the race we're testing
                }
            });

            // Thread B: close the registry
            Thread closer = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    registry.close();
                } catch (Exception _) {
                    // not the race we're testing
                }
            });

            finder.join(5000);
            closer.join(5000);
        }

        assertNull(failure.get(),
                "findAndLookup() must not call lookup() on a closed index: " + failure.get());
    }

    // ── F-R1.concurrency.1.2: isEmpty() races with close() — no lock protection ──

    // Finding: F-R1.concurrency.1.2
    // Bug: isEmpty() does not acquire the read lock before checking closed and
    // reading indices.isEmpty(). Unlike findIndex(), onInsert(), onUpdate(),
    // and onDelete() which all acquire the read lock, isEmpty() only checks
    // closed.get() without holding the lock. This means close() can set
    // closed=true and fully tear down between isEmpty()'s closed check and
    // its return — isEmpty() succeeds on a closed registry.
    // Correct behavior: isEmpty() must acquire the read lock so it participates
    // in the same lock protocol as all other read operations.
    // Fix location: IndexRegistry.java isEmpty() — add rwLock.readLock()
    // Regression watch: must not deadlock with close() write lock
    @Test
    @Timeout(30)
    void test_IndexRegistry_isEmpty_races_with_close_no_lock_protection() throws Exception {
        // Strategy: acquire the write lock externally to simulate a close() in
        // progress, then call isEmpty(). With the read lock, isEmpty() will block.
        // Without the read lock (the bug), isEmpty() bypasses the lock entirely
        // and returns a result while the registry is being torn down.
        //
        // We use reflection to access the rwLock and hold the write lock while
        // isEmpty() is called from another thread. If isEmpty() completes while
        // the write lock is held, it proves isEmpty() doesn't acquire the read lock.
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("age", FieldType.int32()).build();
        List<IndexDefinition> defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
        IndexRegistry registry = new IndexRegistry(schema, defs);

        // Pre-populate
        JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);
        registry.onInsert(stringKey("pk-1"), doc);

        // Access the rwLock via reflection
        var lockField = IndexRegistry.class.getDeclaredField("rwLock");
        lockField.setAccessible(true);
        var rwLock = (java.util.concurrent.locks.ReentrantReadWriteLock) lockField.get(registry);

        // Hold the write lock from the main thread — simulates close() in progress
        rwLock.writeLock().lock();
        try {
            AtomicBoolean isEmptyCompleted = new AtomicBoolean(false);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            // Thread A: call isEmpty() — should block on read lock if properly synchronized
            Thread checker = Thread.ofVirtual().start(() -> {
                try {
                    registry.isEmpty();
                    isEmptyCompleted.set(true);
                } catch (IllegalStateException _) {
                    isEmptyCompleted.set(true);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    isEmptyCompleted.set(true);
                }
            });

            // Give the thread time to either block on the lock or complete
            Thread.sleep(200);

            // If isEmpty() completed while write lock is held, it bypassed
            // the read lock — the bug exists. With the fix, isEmpty() acquires
            // the read lock and blocks until the write lock is released.
            assertFalse(isEmptyCompleted.get(),
                    "isEmpty() completed while write lock was held — proves it does "
                            + "not acquire the read lock, violating the lock protocol "
                            + "that all other read operations follow");

            checker.join(1000);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // ── F-R1.concurrency.1.3: resolveEntry() races with close() — returns MemorySegment from
    // closed arena ──

    // Finding: F-R1.concurrency.1.3
    // Bug: resolveEntry() checks closed.get() but does not acquire the read lock,
    // so close() can complete teardown (closing segmentArena) between
    // resolveEntry()'s closed check and return. The caller receives a
    // StoredEntry whose primaryKey MemorySegment is backed by the now-closed
    // arena — use-after-free.
    // Correct behavior: resolveEntry() must acquire the read lock so it
    // participates in the same lock protocol as all other read operations.
    // Fix location: IndexRegistry.java resolveEntry() — add rwLock.readLock()
    // Regression watch: must not deadlock with close() write lock
    @Test
    @Timeout(10)
    void test_IndexRegistry_resolveEntry_races_with_close_no_lock_protection() throws Exception {
        // Strategy: acquire the write lock externally to simulate a close() in
        // progress, then call resolveEntry(). With the read lock, resolveEntry()
        // will block. Without the read lock (the bug), resolveEntry() bypasses
        // the lock entirely and returns while the registry is being torn down.
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("age", FieldType.int32()).build();
        List<IndexDefinition> defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
        IndexRegistry registry = new IndexRegistry(schema, defs);

        // Pre-populate
        MemorySegment pk = stringKey("pk-1");
        JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);
        registry.onInsert(pk, doc);

        // Access the rwLock via reflection
        var lockField = IndexRegistry.class.getDeclaredField("rwLock");
        lockField.setAccessible(true);
        var rwLock = (java.util.concurrent.locks.ReentrantReadWriteLock) lockField.get(registry);

        // Hold the write lock from the main thread — simulates close() in progress
        rwLock.writeLock().lock();
        try {
            AtomicBoolean resolveCompleted = new AtomicBoolean(false);

            // Thread A: call resolveEntry() — should block on read lock if properly synchronized
            Thread resolver = Thread.ofVirtual().start(() -> {
                try {
                    registry.resolveEntry(pk);
                    resolveCompleted.set(true);
                } catch (IllegalStateException _) {
                    resolveCompleted.set(true);
                } catch (Throwable _) {
                    resolveCompleted.set(true);
                }
            });

            // Give the thread time to either block on the lock or complete
            Thread.sleep(200);

            // If resolveEntry() completed while write lock is held, it bypassed
            // the read lock — the bug exists.
            assertFalse(resolveCompleted.get(),
                    "resolveEntry() completed while write lock was held — proves it does "
                            + "not acquire the read lock, violating the lock protocol "
                            + "that all other read operations follow");

            resolver.join(1000);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // ── F-R1.conc.1.1: add() creates counter gaps visible to concurrent search() ──

    // Finding: F-R1.conc.1.1
    // Bug: add() increments the per-term AtomicInteger counter before putting
    // the entry into the store. Between getAndIncrement (line 110) and
    // store.put (line 118), a concurrent search() sees no entry at that
    // counter position (store.get returns null at line 179), breaks out
    // of its scan loop, and never reaches entries at higher positions.
    // Entries committed at higher counter positions become invisible.
    // Correct behavior: search() must return all committed entries for a term,
    // even when concurrent add() operations are in progress
    // Fix location: SseEncryptedIndex.java add() — serialize counter increment
    // and store.put to prevent gaps
    // Regression watch: fix must not break forward privacy or single-threaded correctness
    @Test
    @Timeout(30)
    void test_SseEncryptedIndex_add_creates_counter_gaps_visible_to_search() throws Exception {
        // Strategy: N threads concurrently add entries for the same term,
        // then after all adds complete, verify search returns all entries.
        // Without the fix, concurrent adds create counter gaps: thread A
        // claims position N via getAndIncrement, thread B claims N+1 and
        // completes store.put first. Position N is temporarily empty.
        // If the search scan runs during this window it breaks at the gap.
        //
        // With the synchronized fix, at most one add is in-flight at a time
        // per term, so no gap can exist between committed positions.
        // After all adds complete, search must find every entry.
        //
        // We also interleave search during adds. A search that starts AFTER
        // add K completes should always find at least K entries (the
        // committed prefix 0..K-1 has no gaps). We verify this by tracking
        // a committed count that each add atomically increments AFTER its
        // store.put completes, and checking that search >= committed at the
        // time the search started.
        final int iterations = 2000;
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int iter = 0; iter < iterations && failure.get() == null; iter++) {
            final byte[] keyMaterial = new byte[32];
            for (int k = 0; k < 32; k++)
                keyMaterial[k] = (byte) (k + 0xA0);
            final EncryptionKeyHolder kh = EncryptionKeyHolder.of(keyMaterial);
            final SseEncryptedIndex idx = new SseEncryptedIndex(kh);
            final byte[] token = idx.deriveToken("testTerm");

            // Pre-seed 3 entries
            for (int j = 0; j < 3; j++) {
                idx.add("testTerm",
                        ("seed-" + j).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            // Track how many adds have fully committed (store.put returned)
            final AtomicInteger committed = new AtomicInteger(3);
            final int adderCount = 8;
            final CyclicBarrier barrier = new CyclicBarrier(adderCount + 2);
            final AtomicBoolean addsDone = new AtomicBoolean(false);

            // Searcher: snapshot committed count BEFORE searching, then verify
            // that search returns at least that many results. A gap would cause
            // search to break early and return fewer than committed.
            Thread[] searchers = new Thread[2];
            for (int s = 0; s < 2; s++) {
                searchers[s] = Thread.startVirtualThread(() -> {
                    try {
                        barrier.await();
                        while (!addsDone.get()) {
                            int baseline = committed.get();
                            List<byte[]> results = idx.search(token);
                            int count = results.size();
                            if (count < baseline) {
                                failure.compareAndSet(null,
                                        new AssertionError("search() returned " + count + " but "
                                                + baseline
                                                + " entries were committed before the search"
                                                + " — gap-termination skipped committed entries"));
                                return;
                            }
                        }
                    } catch (Exception _) {
                    }
                });
            }

            Thread[] adders = new Thread[adderCount];
            for (int t = 0; t < adderCount; t++) {
                final int tIdx = t;
                adders[t] = new Thread(() -> {
                    try {
                        barrier.await();
                        for (int j = 0; j < 5; j++) {
                            idx.add("testTerm", ("t" + tIdx + "-" + j)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            committed.incrementAndGet();
                            Thread.yield();
                        }
                    } catch (Exception e) {
                        failure.compareAndSet(null, e);
                    }
                });
                adders[t].start();
            }

            for (Thread t : adders)
                t.join(10_000);
            addsDone.set(true);
            for (Thread s : searchers)
                s.join(10_000);
            kh.close();
        }

        assertNull(failure.get(),
                "Concurrent add() must not create counter gaps that cause search() "
                        + "to miss committed entries: " + failure.get());
    }

    // ── F-R1.concurrency.1.5: schema() races with close() — no lock protection ──

    // Finding: F-R1.concurrency.1.5
    // Bug: schema() checks closed.get() but does not acquire the read lock,
    // so close() can complete teardown between schema()'s closed check
    // and its return. The caller receives a schema reference while the
    // registry is being torn down — violates the lock protocol that all
    // other read operations follow.
    // Correct behavior: schema() must acquire the read lock so it participates
    // in the same lock protocol as all other read operations.
    // Fix location: IndexRegistry.java schema() — add rwLock.readLock()
    // Regression watch: must not deadlock with close() write lock
    @Test
    @Timeout(10)
    void test_IndexRegistry_schema_races_with_close_no_lock_protection() throws Exception {
        // Strategy: acquire the write lock externally to simulate a close() in
        // progress, then call schema(). With the read lock, schema() will block.
        // Without the read lock (the bug), schema() bypasses the lock entirely
        // and returns a result while the registry is being torn down.
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("age", FieldType.int32()).build();
        List<IndexDefinition> defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
        IndexRegistry registry = new IndexRegistry(schema, defs);

        // Access the rwLock via reflection
        var lockField = IndexRegistry.class.getDeclaredField("rwLock");
        lockField.setAccessible(true);
        var rwLock = (java.util.concurrent.locks.ReentrantReadWriteLock) lockField.get(registry);

        // Hold the write lock from the main thread — simulates close() in progress
        rwLock.writeLock().lock();
        try {
            AtomicBoolean schemaCompleted = new AtomicBoolean(false);

            // Thread A: call schema() — should block on read lock if properly synchronized
            Thread caller = Thread.ofVirtual().start(() -> {
                try {
                    registry.schema();
                    schemaCompleted.set(true);
                } catch (IllegalStateException _) {
                    schemaCompleted.set(true);
                } catch (Throwable _) {
                    schemaCompleted.set(true);
                }
            });

            // Give the thread time to either block on the lock or complete
            Thread.sleep(200);

            // If schema() completed while write lock is held, it bypassed
            // the read lock — the bug exists.
            assertFalse(schemaCompleted.get(),
                    "schema() completed while write lock was held — proves it does "
                            + "not acquire the read lock, violating the lock protocol "
                            + "that all other read operations follow");

            caller.join(1000);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // ── F-R1.concurrency.1.4: allEntries() races with close() — iterator over arena-backed
    // segments from closed arena ──

    // Finding: F-R1.concurrency.1.4
    // Bug: allEntries() checks closed.get() but does not acquire the read lock,
    // so close() can complete teardown (closing segmentArena) between
    // allEntries()'s closed check and the caller iterating the snapshot.
    // Each StoredEntry.primaryKey() is a MemorySegment backed by the
    // now-closed arena — use-after-free.
    // Correct behavior: allEntries() must acquire the read lock so it
    // participates in the same lock protocol as all other read operations.
    // Fix location: IndexRegistry.java allEntries() — add rwLock.readLock()
    // Regression watch: must not deadlock with close() write lock
    @Test
    @Timeout(10)
    void test_IndexRegistry_allEntries_races_with_close_no_lock_protection() throws Exception {
        // Strategy: acquire the write lock externally to simulate a close() in
        // progress, then call allEntries(). With the read lock, allEntries()
        // will block. Without the read lock (the bug), allEntries() bypasses
        // the lock entirely and returns while the registry is being torn down.
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("age", FieldType.int32()).build();
        List<IndexDefinition> defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
        IndexRegistry registry = new IndexRegistry(schema, defs);

        // Pre-populate
        JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);
        registry.onInsert(stringKey("pk-1"), doc);

        // Access the rwLock via reflection
        var lockField = IndexRegistry.class.getDeclaredField("rwLock");
        lockField.setAccessible(true);
        var rwLock = (java.util.concurrent.locks.ReentrantReadWriteLock) lockField.get(registry);

        // Hold the write lock from the main thread — simulates close() in progress
        rwLock.writeLock().lock();
        try {
            AtomicBoolean allEntriesCompleted = new AtomicBoolean(false);

            // Thread A: call allEntries() — should block on read lock if properly synchronized
            Thread caller = Thread.ofVirtual().start(() -> {
                try {
                    registry.allEntries();
                    allEntriesCompleted.set(true);
                } catch (IllegalStateException _) {
                    allEntriesCompleted.set(true);
                } catch (Throwable _) {
                    allEntriesCompleted.set(true);
                }
            });

            // Give the thread time to either block on the lock or complete
            Thread.sleep(200);

            // If allEntries() completed while write lock is held, it bypassed
            // the read lock — the bug exists.
            assertFalse(allEntriesCompleted.get(),
                    "allEntries() completed while write lock was held — proves it does "
                            + "not acquire the read lock, violating the lock protocol "
                            + "that all other read operations follow");

            caller.join(1000);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

}
