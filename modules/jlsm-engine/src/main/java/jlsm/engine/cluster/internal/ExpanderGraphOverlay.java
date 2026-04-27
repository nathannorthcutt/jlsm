package jlsm.engine.cluster.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import jlsm.cluster.NodeAddress;

/**
 * K-regular expander-graph monitoring overlay over ALIVE cluster members.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Deterministic construction: the graph is a pure function of
 * {@code (aliveMembers, degree, seed)}; every node builds the same graph independently given
 * identical inputs (seed is derived from the view epoch).</li>
 * <li>Clamping: if {@code degree >= aliveMembers.size() - 1}, every node observes every other ALIVE
 * node.</li>
 * <li>Atomic rebuild: replacing the graph is atomic — queries see either the old or the new graph,
 * never a torn view.</li>
 * <li>Thread-safety: implemented via {@link ReentrantReadWriteLock} so multiple queries run
 * concurrently and a rebuild briefly blocks queries.</li>
 * <li>Validation: null arguments throw {@link NullPointerException}; a negative {@code degree}
 * throws {@link IllegalArgumentException}.</li>
 * </ul>
 *
 * <p>
 * Construction approach: member set is sorted deterministically (by {@code nodeId}, then
 * {@code host}, then {@code port}) so iteration order of the input {@link Set} does not affect the
 * output. A {@link Random} seeded with {@code seed} shuffles the sorted list into a ring. Each
 * node's K monitors are drawn from the K successors on the ring — a ring-plus-rotation construction
 * (K-regular by construction, deterministic, O(N*K) to build). Strict expander properties (true
 * random bipartite matching) are not required by callers; connectivity and degree regularity are
 * sufficient.
 *
 * <p>
 * Query cost: {@code monitorsOf} and {@code observersOf} are O(1) hash-map lookups. Rebuild cost:
 * O(N log N) sort plus O(N*K) edge construction.
 *
 * <p>
 * Governed by: F04.R35 and {@code .decisions/cluster-membership-protocol/adr.md} (see also
 * {@code .kb/distributed-systems/cluster-membership/rapid-consensus.md}).
 */
public final class ExpanderGraphOverlay {

    private static final Comparator<NodeAddress> DETERMINISTIC_ORDER = Comparator
            .comparing(NodeAddress::nodeId).thenComparing(NodeAddress::host)
            .thenComparingInt(NodeAddress::port);

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** Immutable snapshot of the current overlay. Replaced atomically under the write lock. */
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /** Constructs an empty overlay. Call {@link #rebuild} before querying. */
    public ExpanderGraphOverlay() {
        // snapshot starts empty via field initializer
    }

    /**
     * Rebuilds the overlay from the given ALIVE member set. Deterministic given
     * {@code (aliveMembers, degree, seed)} — callers on different nodes will construct identical
     * graphs if they pass identical inputs (seed typically derives from the view epoch).
     *
     * <p>
     * Atomic: concurrent readers see either the old or the new graph, never a mix. A rebuild blocks
     * readers only for the duration of the internal map swap (O(1)); the new graph is computed
     * outside the write lock.
     *
     * @throws NullPointerException if {@code aliveMembers} is null
     * @throws IllegalArgumentException if {@code degree} is negative
     */
    public void rebuild(final Set<NodeAddress> aliveMembers, final int degree, final long seed) {
        Objects.requireNonNull(aliveMembers, "aliveMembers must not be null");
        if (degree < 0) {
            throw new IllegalArgumentException("degree must be non-negative, got: " + degree);
        }

        final Snapshot next = buildSnapshot(aliveMembers, degree, seed);
        assert next != null : "buildSnapshot must never return null";

        lock.writeLock().lock();
        try {
            this.snapshot = next;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the set of addresses that {@code self} monitors (outgoing edges). Returns
     * {@link Set#of()} if {@code self} is not part of the current overlay. The returned set is
     * immutable.
     *
     * @throws NullPointerException if {@code self} is null
     */
    public Set<NodeAddress> monitorsOf(final NodeAddress self) {
        Objects.requireNonNull(self, "self must not be null");
        lock.readLock().lock();
        try {
            return snapshot.monitors().getOrDefault(self, Set.of());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the set of addresses that observe {@code subject} (incoming edges). Returns
     * {@link Set#of()} if {@code subject} is not part of the current overlay. The returned set is
     * immutable.
     *
     * @throws NullPointerException if {@code subject} is null
     */
    public Set<NodeAddress> observersOf(final NodeAddress subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        lock.readLock().lock();
        try {
            return snapshot.observers().getOrDefault(subject, Set.of());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Returns the number of distinct members in the current overlay. */
    public int memberCount() {
        lock.readLock().lock();
        try {
            return snapshot.memberCount();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------
    // Internal — snapshot construction (no lock held during computation)
    // ---------------------------------------------------------------

    private static Snapshot buildSnapshot(final Set<NodeAddress> aliveMembers, final int degree,
            final long seed) {
        assert aliveMembers != null : "aliveMembers should have been validated by caller";
        assert degree >= 0 : "degree should have been validated by caller";

        final int n = aliveMembers.size();
        if (n == 0) {
            return Snapshot.EMPTY;
        }

        // Deterministic ordering of the input set so iteration order does not
        // perturb the seeded shuffle.
        final List<NodeAddress> sorted = new ArrayList<>(aliveMembers);
        sorted.sort(DETERMINISTIC_ORDER);

        // Shuffle into a ring using the seeded PRNG. A single shuffle is
        // enough — the ring layout plus the sorted order is the full source of
        // determinism.
        final List<NodeAddress> ring = new ArrayList<>(sorted);
        Collections.shuffle(ring, new Random(seed));

        // Clamp degree at (n - 1). Singleton is always degree 0.
        final int effectiveDegree = Math.min(degree, Math.max(0, n - 1));

        final Map<NodeAddress, LinkedHashSet<NodeAddress>> monitors = new HashMap<>(n * 2);
        final Map<NodeAddress, LinkedHashSet<NodeAddress>> observers = new HashMap<>(n * 2);
        for (NodeAddress m : ring) {
            monitors.put(m, new LinkedHashSet<>(effectiveDegree));
            observers.put(m, new LinkedHashSet<>(effectiveDegree));
        }

        if (effectiveDegree > 0) {
            for (int i = 0; i < n; i++) {
                final NodeAddress source = ring.get(i);
                final LinkedHashSet<NodeAddress> outgoing = monitors.get(source);
                // K successors on the ring: offsets 1..K. Successors are distinct modulo n
                // whenever K < n; when K == n - 1 (full mesh) they cover every other node
                // exactly once. No self-loop because offset 0 is skipped.
                for (int k = 1; k <= effectiveDegree; k++) {
                    final NodeAddress target = ring.get((i + k) % n);
                    outgoing.add(target);
                    observers.get(target).add(source);
                }
            }
        }

        // Freeze inner sets and outer maps in place — no second copy needed.
        final Map<NodeAddress, Set<NodeAddress>> frozenMonitors = new HashMap<>(n * 2);
        final Map<NodeAddress, Set<NodeAddress>> frozenObservers = new HashMap<>(n * 2);
        for (Map.Entry<NodeAddress, LinkedHashSet<NodeAddress>> e : monitors.entrySet()) {
            assert e.getValue().size() == effectiveDegree
                    : "every node must have exactly effectiveDegree outgoing edges";
            assert !e.getValue().contains(e.getKey()) : "no self-loops allowed in monitors";
            frozenMonitors.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
        }
        for (Map.Entry<NodeAddress, LinkedHashSet<NodeAddress>> e : observers.entrySet()) {
            assert !e.getValue().contains(e.getKey()) : "no self-loops allowed in observers";
            frozenObservers.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
        }

        return new Snapshot(Collections.unmodifiableMap(frozenMonitors),
                Collections.unmodifiableMap(frozenObservers), n);
    }

    /** Immutable snapshot of the overlay graph. Safely publishable across threads. */
    private record Snapshot(Map<NodeAddress, Set<NodeAddress>> monitors,
            Map<NodeAddress, Set<NodeAddress>> observers, int memberCount) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), 0);
    }
}
