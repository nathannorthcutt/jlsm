package jlsm.engine.cluster.internal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jlsm.engine.cluster.NodeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * TDD test suite for {@link ExpanderGraphOverlay}.
 *
 * <p>
 * Covers deterministic construction, clamping, empty/singleton overlays, degree regularity, query
 * invariants, and atomic rebuild under concurrency.
 */
@Timeout(5)
final class ExpanderGraphOverlayTest {

    private static NodeAddress node(int i) {
        return new NodeAddress("node-" + i, "127.0.0.1", 9000 + i);
    }

    private static Set<NodeAddress> members(int n) {
        Set<NodeAddress> set = new HashSet<>();
        for (int i = 0; i < n; i++) {
            set.add(node(i));
        }
        return set;
    }

    @Test
    void rebuild_rejectsNullMembers() {
        ExpanderGraphOverlay overlay = new ExpanderGraphOverlay();
        assertThrows(NullPointerException.class, () -> overlay.rebuild(null, 3, 0L));
    }

    @Test
    void rebuild_rejectsNegativeDegree() {
        ExpanderGraphOverlay overlay = new ExpanderGraphOverlay();
        assertThrows(IllegalArgumentException.class, () -> overlay.rebuild(members(5), -1, 0L));
    }

    @Test
    void rebuild_withEmptyMembers_yieldsEmptyOverlay() {
        ExpanderGraphOverlay overlay = new ExpanderGraphOverlay();
        overlay.rebuild(Set.of(), 3, 42L);

        assertEquals(0, overlay.memberCount());
        assertEquals(Set.of(), overlay.monitorsOf(node(0)));
        assertEquals(Set.of(), overlay.observersOf(node(0)));
    }

    @Test
    void rebuild_withSingletonMembers_yieldsNoMonitorsOrObservers() {
        ExpanderGraphOverlay overlay = new ExpanderGraphOverlay();
        NodeAddress solo = node(0);
        overlay.rebuild(Set.of(solo), 3, 42L);

        assertEquals(1, overlay.memberCount());
        assertEquals(Set.of(), overlay.monitorsOf(solo));
        assertEquals(Set.of(), overlay.observersOf(solo));
    }

    @Test
    void rebuild_deterministicFromInputs() {
        Set<NodeAddress> m = members(20);
        int degree = 4;
        long seed = 0xDEADBEEFL;

        ExpanderGraphOverlay a = new ExpanderGraphOverlay();
        a.rebuild(m, degree, seed);

        ExpanderGraphOverlay b = new ExpanderGraphOverlay();
        b.rebuild(m, degree, seed);

        for (NodeAddress n : m) {
            assertEquals(a.monitorsOf(n), b.monitorsOf(n), "monitor set for " + n.nodeId()
                    + " must be identical across independent rebuilds");
            assertEquals(a.observersOf(n), b.observersOf(n), "observer set for " + n.nodeId()
                    + " must be identical across independent rebuilds");
        }
    }

    @Test
    void rebuild_differentSeedsDifferentEdges() {
        Set<NodeAddress> m = members(20);
        int degree = 4;

        ExpanderGraphOverlay a = new ExpanderGraphOverlay();
        a.rebuild(m, degree, 1L);

        ExpanderGraphOverlay b = new ExpanderGraphOverlay();
        b.rebuild(m, degree, 2L);

        boolean anyDifferent = false;
        for (NodeAddress n : m) {
            if (!a.monitorsOf(n).equals(b.monitorsOf(n))) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue(anyDifferent, "different seeds must produce at least one differing monitor set");
    }

    @Test
    void rebuild_degreeRegular() {
        Set<NodeAddress> m = members(20);
        int degree = 4;

        ExpanderGraphOverlay overlay = new ExpanderGraphOverlay();
        overlay.rebuild(m, degree, 42L);

        for (NodeAddress n : m) {
            assertEquals(degree, overlay.monitorsOf(n).size(),
                    "each member must have exactly degree outgoing edges; violated for "
                            + n.nodeId());
        }
    }

    @Test
    void rebuild_clampsWhenDegreeAtOrAboveSize() {
        int size = 5;
        Set<NodeAddress> m = members(size);

        // degree == size-1: already full mesh
        ExpanderGraphOverlay a = new ExpanderGraphOverlay();
        a.rebuild(m, size - 1, 42L);
        for (NodeAddress n : m) {
            Set<NodeAddress> mon = a.monitorsOf(n);
            assertEquals(size - 1, mon.size(), "full mesh must give size-1 monitors");
            assertFalse(mon.contains(n), "self must not appear in own monitors");
        }

        // degree > size-1: still clamped to full mesh
        ExpanderGraphOverlay b = new ExpanderGraphOverlay();
        b.rebuild(m, size + 10, 42L);
        for (NodeAddress n : m) {
            Set<NodeAddress> mon = b.monitorsOf(n);
            assertEquals(size - 1, mon.size(), "degree >= size-1 must clamp to full mesh");
            assertFalse(mon.contains(n), "self must not appear in own monitors under clamp");
        }
    }

    @Test
    void rebuild_noSelfLoops() {
        Set<NodeAddress> m = members(15);

        ExpanderGraphOverlay overlay = new ExpanderGraphOverlay();
        overlay.rebuild(m, 4, 0xC0FFEEL);

        for (NodeAddress n : m) {
            assertFalse(overlay.monitorsOf(n).contains(n),
                    "self-loop present in monitorsOf for " + n.nodeId());
            assertFalse(overlay.observersOf(n).contains(n),
                    "self-loop present in observersOf for " + n.nodeId());
        }
    }

    @Test
    void monitorsOf_rejectsNull() {
        ExpanderGraphOverlay overlay = new ExpanderGraphOverlay();
        overlay.rebuild(members(5), 2, 0L);
        assertThrows(NullPointerException.class, () -> overlay.monitorsOf(null));
    }

    @Test
    void observersOf_rejectsNull() {
        ExpanderGraphOverlay overlay = new ExpanderGraphOverlay();
        overlay.rebuild(members(5), 2, 0L);
        assertThrows(NullPointerException.class, () -> overlay.observersOf(null));
    }

    @Test
    void observersOf_returnsReverseOfMonitors() {
        Set<NodeAddress> m = members(12);
        ExpanderGraphOverlay overlay = new ExpanderGraphOverlay();
        overlay.rebuild(m, 3, 0xAB12L);

        for (NodeAddress a : m) {
            for (NodeAddress b : m) {
                boolean aMonitorsB = overlay.monitorsOf(b).contains(a);
                boolean bObservedByA = overlay.observersOf(a).contains(b);
                assertEquals(aMonitorsB, bObservedByA,
                        "reverse-edge invariant broken: a=" + a.nodeId() + " b=" + b.nodeId());
            }
        }
    }

    @Test
    void queryForUnknownNode_returnsEmptySet() {
        ExpanderGraphOverlay overlay = new ExpanderGraphOverlay();
        overlay.rebuild(members(5), 2, 0L);

        NodeAddress stranger = node(999);
        Set<NodeAddress> monitors = overlay.monitorsOf(stranger);
        Set<NodeAddress> observers = overlay.observersOf(stranger);

        assertNotNull(monitors, "query for unknown node must not return null");
        assertNotNull(observers, "query for unknown node must not return null");
        assertTrue(monitors.isEmpty(), "unknown node must have empty monitor set");
        assertTrue(observers.isEmpty(), "unknown node must have empty observer set");
    }

    @Test
    void concurrent_queries_duringRebuild_areConsistent() throws InterruptedException {
        Set<NodeAddress> m = members(16);
        int degree = 3;
        ExpanderGraphOverlay overlay = new ExpanderGraphOverlay();

        // Precompute the two candidate snapshots (seeds A and B) so we can verify
        // that every concurrent read returns exactly one of them — never a torn mix.
        overlay.rebuild(m, degree, 100L);
        List<NodeAddress> ordered = new ArrayList<>(m);
        List<Set<NodeAddress>> snapshotA = new ArrayList<>();
        for (NodeAddress n : ordered) {
            snapshotA.add(Set.copyOf(overlay.monitorsOf(n)));
        }
        overlay.rebuild(m, degree, 200L);
        List<Set<NodeAddress>> snapshotB = new ArrayList<>();
        for (NodeAddress n : ordered) {
            snapshotB.add(Set.copyOf(overlay.monitorsOf(n)));
        }
        // Sanity: seeds produce distinguishable snapshots for at least one node.
        boolean distinguishable = false;
        for (int i = 0; i < ordered.size(); i++) {
            if (!snapshotA.get(i).equals(snapshotB.get(i))) {
                distinguishable = true;
                break;
            }
        }
        assertTrue(distinguishable,
                "test precondition: seeds must yield distinguishable snapshots");

        // Leave overlay at snapshot B, then flap rebuilds between A and B while readers query.
        final int readerCount = 8;
        final int iterations = 500;
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<AssertionError> failure = new AtomicReference<>();
        CountDownLatch readyLatch = new CountDownLatch(readerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(readerCount + 1);
        try {
            for (int r = 0; r < readerCount; r++) {
                pool.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    while (!stop.get()) {
                        for (int i = 0; i < ordered.size(); i++) {
                            Set<NodeAddress> result = overlay.monitorsOf(ordered.get(i));
                            Set<NodeAddress> copy = Set.copyOf(result);
                            if (!copy.equals(snapshotA.get(i)) && !copy.equals(snapshotB.get(i))) {
                                failure.compareAndSet(null, new AssertionError(
                                        "torn read for " + ordered.get(i).nodeId() + ": " + copy));
                                return;
                            }
                        }
                    }
                });
            }
            pool.submit(() -> {
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < iterations && !stop.get(); i++) {
                    overlay.rebuild(m, degree, (i % 2 == 0) ? 100L : 200L);
                }
                stop.set(true);
            });

            readyLatch.await();
            startLatch.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(4, TimeUnit.SECONDS),
                    "pool did not terminate in time");
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }

        if (failure.get() != null) {
            throw failure.get();
        }
    }

    @Test
    void memberCount_reflectsLastRebuild() {
        ExpanderGraphOverlay overlay = new ExpanderGraphOverlay();

        overlay.rebuild(members(7), 2, 0L);
        assertEquals(7, overlay.memberCount());

        overlay.rebuild(members(3), 2, 0L);
        assertEquals(3, overlay.memberCount());

        overlay.rebuild(members(10), 2, 0L);
        assertEquals(10, overlay.memberCount());

        overlay.rebuild(Set.of(), 2, 0L);
        assertEquals(0, overlay.memberCount());
    }

    @Test
    void returned_sets_areImmutable_or_defensiveCopies() {
        Set<NodeAddress> m = members(10);
        ExpanderGraphOverlay overlay = new ExpanderGraphOverlay();
        overlay.rebuild(m, 3, 0L);

        NodeAddress n0 = node(0);
        Set<NodeAddress> monitorsBefore = Set.copyOf(overlay.monitorsOf(n0));
        Set<NodeAddress> observersBefore = Set.copyOf(overlay.observersOf(n0));

        assertAll("mutation of returned sets must not affect the overlay", () -> {
            Set<NodeAddress> monitors = overlay.monitorsOf(n0);
            try {
                monitors.add(node(123));
            } catch (UnsupportedOperationException expected) {
                // acceptable
            }
            assertEquals(monitorsBefore, Set.copyOf(overlay.monitorsOf(n0)),
                    "mutating returned monitors set leaked into overlay state");
        }, () -> {
            Set<NodeAddress> observers = overlay.observersOf(n0);
            try {
                observers.remove(observers.stream().findFirst().orElse(node(999)));
            } catch (UnsupportedOperationException expected) {
                // acceptable
            } catch (NullPointerException ignored) {
                // Set.of() rejects null remove; empty sets are fine.
            }
            assertEquals(observersBefore, Set.copyOf(overlay.observersOf(n0)),
                    "mutating returned observers set leaked into overlay state");
        });

        // Tangentially verify the precomputed snapshots are themselves safe to read.
        assertEquals(monitorsBefore, Collections.unmodifiableSet(monitorsBefore));
    }
}
