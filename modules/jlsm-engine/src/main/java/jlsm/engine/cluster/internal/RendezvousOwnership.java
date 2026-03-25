package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.Member;
import jlsm.engine.cluster.MemberState;
import jlsm.engine.cluster.MembershipView;
import jlsm.engine.cluster.NodeAddress;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic partition-to-node ownership via Rendezvous (Highest Random Weight) hashing.
 *
 * <p>
 * Contract: Pure function of (identifier, membership view). Given the same view, all nodes compute
 * the same ownership assignment. For each table or partition ID, computes hash(id, node_id) for all
 * live nodes in the view and assigns to the node with the highest weight. Results are cached keyed
 * on view epoch to avoid redundant computation.
 *
 * <p>
 * Thread-safe: uses concurrent cache and pure computations.
 *
 * <p>
 * Governed by: {@code .decisions/partition-to-node-ownership/adr.md}
 */
public final class RendezvousOwnership {

    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, NodeAddress>> cache = new ConcurrentHashMap<>();

    /**
     * Assigns a single owner for the given identifier within the current membership view.
     *
     * @param id the table or partition identifier; must not be null or empty
     * @param view the current membership view; must not be null
     * @return the owning node's address; never null
     * @throws IllegalStateException if the view contains no live members
     */
    public NodeAddress assignOwner(String id, MembershipView view) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(view, "view must not be null");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("id must not be empty");
        }

        final ConcurrentHashMap<String, NodeAddress> epochCache = cache
                .computeIfAbsent(view.epoch(), _ -> new ConcurrentHashMap<>());

        final NodeAddress cached = epochCache.get(id);
        if (cached != null) {
            return cached;
        }

        final List<NodeAddress> ranked = computeRankedOwners(id, view);
        assert !ranked.isEmpty() : "ranked list must not be empty after live-member check";

        final NodeAddress owner = ranked.getFirst();
        epochCache.put(id, owner);
        return owner;
    }

    /**
     * Assigns multiple owners (for future replication) ranked by weight.
     *
     * @param id the table or partition identifier; must not be null or empty
     * @param view the current membership view; must not be null
     * @param replicas the number of owners to assign; must be >= 1
     * @return a list of node addresses ranked by weight, up to {@code replicas} or the number of
     *         live members, whichever is smaller; never null or empty
     * @throws IllegalStateException if the view contains no live members
     */
    public List<NodeAddress> assignOwners(String id, MembershipView view, int replicas) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(view, "view must not be null");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("id must not be empty");
        }
        if (replicas < 1) {
            throw new IllegalArgumentException("replicas must be >= 1, got: " + replicas);
        }

        final List<NodeAddress> ranked = computeRankedOwners(id, view);
        assert !ranked.isEmpty() : "ranked list must not be empty after live-member check";

        final int count = Math.min(replicas, ranked.size());
        return List.copyOf(ranked.subList(0, count));
    }

    /**
     * Invalidates cached assignments for views older than the given epoch.
     *
     * @param currentEpoch the current epoch; entries for older epochs are evicted
     */
    public void evictBefore(long currentEpoch) {
        cache.keySet().removeIf(epoch -> epoch < currentEpoch);
    }

    /**
     * Computes the full ranking of live members for the given id, sorted by descending HRW weight.
     * Ties are broken by nodeId for determinism.
     */
    private List<NodeAddress> computeRankedOwners(String id, MembershipView view) {
        final List<WeightedNode> weighted = new ArrayList<>();
        final byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);

        for (Member member : view.members()) {
            if (member.state() != MemberState.ALIVE) {
                continue;
            }
            final byte[] nodeBytes = member.address().nodeId().getBytes(StandardCharsets.UTF_8);
            final long weight = hrwHash(idBytes, nodeBytes);
            weighted.add(new WeightedNode(member.address(), weight));
        }

        if (weighted.isEmpty()) {
            throw new IllegalStateException("No live members in the membership view");
        }

        // Sort by weight descending, then by nodeId ascending for deterministic tie-breaking
        weighted.sort(Comparator.comparingLong(WeightedNode::weight).reversed()
                .thenComparing(wn -> wn.address().nodeId()));

        final List<NodeAddress> result = new ArrayList<>(weighted.size());
        for (WeightedNode wn : weighted) {
            result.add(wn.address());
        }
        assert result.size() == weighted.size() : "result size must match weighted size";
        return result;
    }

    /**
     * Computes a hash weight for the (id, nodeId) pair using a SipHash-inspired mixing function.
     * The result is deterministic and uniformly distributed.
     */
    private static long hrwHash(byte[] idBytes, byte[] nodeBytes) {
        // Combine id and nodeId bytes with a simple but effective mixing strategy.
        // Use FNV-1a variant for fast, well-distributed hashing.
        long hash = 0xcbf29ce484222325L; // FNV offset basis
        final long prime = 0x100000001b3L; // FNV prime

        for (byte b : idBytes) {
            hash ^= (b & 0xffL);
            hash *= prime;
        }
        // Separator to prevent collisions between ("ab","cd") and ("abc","d")
        hash ^= 0xffL;
        hash *= prime;

        for (byte b : nodeBytes) {
            hash ^= (b & 0xffL);
            hash *= prime;
        }

        // Finalizer mix (Stafford variant 13)
        hash ^= (hash >>> 30);
        hash *= 0xbf58476d1ce4e5b9L;
        hash ^= (hash >>> 27);
        hash *= 0x94d049bb133111ebL;
        hash ^= (hash >>> 31);

        return hash;
    }

    private record WeightedNode(NodeAddress address, long weight) {
    }
}
