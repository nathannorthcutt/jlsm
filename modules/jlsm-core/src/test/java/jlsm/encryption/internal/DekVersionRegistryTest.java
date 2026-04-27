package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;

/**
 * Tests for {@link DekVersionRegistry} — wait-free CoW per-tenant DEK version map (R64).
 *
 * @spec encryption.primitives-lifecycle R64
 */
class DekVersionRegistryTest {

    private static final TableScope SCOPE_A = new TableScope(new TenantId("tenantA"),
            new DomainId("domain-1"), new TableId("table-1"));
    private static final TableScope SCOPE_B = new TableScope(new TenantId("tenantB"),
            new DomainId("domain-2"), new TableId("table-2"));
    private static final TableScope SCOPE_C = new TableScope(new TenantId("tenantA"),
            new DomainId("domain-1"), new TableId("table-2"));

    @Test
    void empty_returnsNonNullInstance() {
        assertNotNull(DekVersionRegistry.empty());
    }

    @Test
    void currentVersion_emptyRegistry_returnsEmpty() {
        final DekVersionRegistry registry = DekVersionRegistry.empty();
        assertEquals(Optional.empty(), registry.currentVersion(SCOPE_A));
    }

    @Test
    void knownVersions_emptyRegistry_returnsEmptySet() {
        final DekVersionRegistry registry = DekVersionRegistry.empty();
        assertTrue(registry.knownVersions(SCOPE_A).isEmpty());
    }

    @Test
    void publishUpdate_thenCurrentVersionReturnsPublishedValue() {
        final DekVersionRegistry registry = DekVersionRegistry.empty();
        registry.publishUpdate(SCOPE_A, 1, Set.of(1));
        assertEquals(Optional.of(1), registry.currentVersion(SCOPE_A));
    }

    @Test
    void publishUpdate_thenKnownVersionsReturnsPublishedSet() {
        final DekVersionRegistry registry = DekVersionRegistry.empty();
        registry.publishUpdate(SCOPE_A, 3, Set.of(1, 2, 3));
        assertEquals(Set.of(1, 2, 3), registry.knownVersions(SCOPE_A));
    }

    @Test
    void publishUpdate_overwritesPriorEntryForSameScope() {
        final DekVersionRegistry registry = DekVersionRegistry.empty();
        registry.publishUpdate(SCOPE_A, 1, Set.of(1));
        registry.publishUpdate(SCOPE_A, 2, Set.of(1, 2));
        assertEquals(Optional.of(2), registry.currentVersion(SCOPE_A));
        assertEquals(Set.of(1, 2), registry.knownVersions(SCOPE_A));
    }

    @Test
    void publishUpdate_differentScopesAreIsolated() {
        final DekVersionRegistry registry = DekVersionRegistry.empty();
        registry.publishUpdate(SCOPE_A, 1, Set.of(1));
        registry.publishUpdate(SCOPE_B, 5, Set.of(4, 5));
        assertEquals(Optional.of(1), registry.currentVersion(SCOPE_A));
        assertEquals(Optional.of(5), registry.currentVersion(SCOPE_B));
        assertEquals(Optional.empty(), registry.currentVersion(SCOPE_C));
    }

    @Test
    void currentVersion_nullScope_throwsNpe() {
        final DekVersionRegistry registry = DekVersionRegistry.empty();
        assertThrows(NullPointerException.class, () -> registry.currentVersion(null));
    }

    @Test
    void knownVersions_nullScope_throwsNpe() {
        final DekVersionRegistry registry = DekVersionRegistry.empty();
        assertThrows(NullPointerException.class, () -> registry.knownVersions(null));
    }

    @Test
    void publishUpdate_nullScope_throwsNpe() {
        final DekVersionRegistry registry = DekVersionRegistry.empty();
        assertThrows(NullPointerException.class, () -> registry.publishUpdate(null, 1, Set.of(1)));
    }

    @Test
    void publishUpdate_nullKnownSet_throwsNpe() {
        final DekVersionRegistry registry = DekVersionRegistry.empty();
        assertThrows(NullPointerException.class, () -> registry.publishUpdate(SCOPE_A, 1, null));
    }

    @Test
    void publishUpdate_currentNotInKnown_throwsIae() {
        final DekVersionRegistry registry = DekVersionRegistry.empty();
        assertThrows(IllegalArgumentException.class,
                () -> registry.publishUpdate(SCOPE_A, 5, Set.of(1, 2, 3)));
    }

    @Test
    void publishUpdate_emptyKnownSet_throwsIae() {
        final DekVersionRegistry registry = DekVersionRegistry.empty();
        // newCurrent cannot be a member of an empty set
        assertThrows(IllegalArgumentException.class,
                () -> registry.publishUpdate(SCOPE_A, 1, Set.of()));
    }

    @Test
    void knownVersions_returnsImmutableSnapshot() {
        // R64 — readers must observe a stable snapshot. Mutating the returned set
        // (or the input set after publish) must not corrupt the registry.
        final DekVersionRegistry registry = DekVersionRegistry.empty();
        final Set<Integer> input = new HashSet<>();
        input.add(1);
        input.add(2);
        registry.publishUpdate(SCOPE_A, 2, input);

        // Mutate the input — registry must be unaffected
        input.add(99);
        assertFalse(registry.knownVersions(SCOPE_A).contains(99),
                "registry must defensively snapshot the published set");

        // Mutate the returned snapshot — should either throw (immutable) or not affect registry
        final Set<Integer> snapshot = registry.knownVersions(SCOPE_A);
        try {
            snapshot.add(123);
        } catch (UnsupportedOperationException expected) {
            // immutable view — preferred
        }
        // Re-read: registry state unchanged
        assertEquals(Set.of(1, 2), registry.knownVersions(SCOPE_A));
    }

    @Test
    void currentVersion_missingScope_doesNotThrow() {
        // R64 — constant-time regardless of outcome. Missing-scope lookup must
        // return Optional.empty(), not throw.
        final DekVersionRegistry registry = DekVersionRegistry.empty();
        assertEquals(Optional.empty(), registry.currentVersion(SCOPE_A));
        assertTrue(registry.knownVersions(SCOPE_A).isEmpty());
    }

    @Test
    void publishUpdate_concurrentReadObservesPriorOrPublishedSnapshot() throws Exception {
        // R64 — wait-free CoW: readers observe either the prior snapshot or the
        // newly published one, never a partial state.
        final DekVersionRegistry registry = DekVersionRegistry.empty();
        registry.publishUpdate(SCOPE_A, 1, Set.of(1));

        final int writerThreads = 4;
        final int writesPerThread = 200;
        final int readerThreads = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(writerThreads + readerThreads);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicBoolean inconsistencyDetected = new AtomicBoolean(false);

        try {
            // Writers: republish increasing versions
            for (int w = 0; w < writerThreads; w++) {
                final int wid = w;
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < writesPerThread; i++) {
                        final int version = wid * writesPerThread + i + 2;
                        final Set<Integer> known = new HashSet<>();
                        for (int v = 1; v <= version; v++) {
                            known.add(v);
                        }
                        registry.publishUpdate(SCOPE_A, version, known);
                    }
                });
            }

            // Readers: continuously poll and verify currentVersion is in knownVersions
            for (int r = 0; r < readerThreads; r++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < 5_000; i++) {
                        final Optional<Integer> cur = registry.currentVersion(SCOPE_A);
                        final Set<Integer> known = registry.knownVersions(SCOPE_A);
                        if (cur.isPresent() && !known.contains(cur.get())) {
                            // R64 invariant: current must be a member of known
                            // Note: cur and known are read in two separate calls; the
                            // CoW snapshot guarantees each call returns a consistent
                            // entry, but two calls may straddle a publish. We accept
                            // straddle by requiring known to contain cur OR known to
                            // contain a strictly larger version (newer snapshot).
                            boolean newerVisible = false;
                            for (Integer k : known) {
                                if (k > cur.get()) {
                                    newerVisible = true;
                                    break;
                                }
                            }
                            if (!newerVisible) {
                                inconsistencyDetected.set(true);
                            }
                        }
                    }
                });
            }

            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS),
                    "concurrent test did not terminate");
            assertFalse(inconsistencyDetected.get(),
                    "CoW invariant violated: currentVersion not present in knownVersions");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void publishUpdate_doesNotCorruptOtherTenantsEntries() {
        // R64 + per-tenant isolation: writes to one scope must never observably mutate other
        // scopes.
        final DekVersionRegistry registry = DekVersionRegistry.empty();
        registry.publishUpdate(SCOPE_A, 1, Set.of(1));
        registry.publishUpdate(SCOPE_B, 1, Set.of(1));

        // Concurrent updates to A; B must remain stable
        final ConcurrentHashMap<Integer, Boolean> bSnapshots = new ConcurrentHashMap<>();
        for (int i = 2; i <= 50; i++) {
            final Set<Integer> known = new HashSet<>();
            for (int v = 1; v <= i; v++) {
                known.add(v);
            }
            registry.publishUpdate(SCOPE_A, i, known);
            bSnapshots.put(registry.currentVersion(SCOPE_B).orElseThrow(), true);
        }
        assertEquals(1, bSnapshots.size(),
                "SCOPE_B must remain at version 1 throughout SCOPE_A updates");
        assertTrue(bSnapshots.containsKey(1));
    }
}
