package jlsm.encryption.internal;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import jlsm.encryption.TableScope;

/**
 * Wait-free per-tenant copy-on-write immutable map of {@code (tenantId, domainId, tableId)} →
 * {@code DekVersionSet}. A volatile-reference swap publishes mutations; readers observe a stable,
 * fully-populated snapshot or none at all. Reads are constant-time regardless of outcome (R64).
 *
 * <p>
 * Used by the read path to determine the current DEK version of a scope and the full set of known
 * versions (for replay / rewrap pinning) without acquiring a per-scope lock.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R64); ADR
 * encryption-three-tier-key-hierarchy.
 *
 * @spec encryption.primitives-lifecycle R64
 */
public final class DekVersionRegistry {

    /**
     * Volatile reference to the current immutable snapshot. Reads observe this in a single volatile
     * load (R64); writes CAS-replace the entire map (CoW).
     */
    private final AtomicReference<Map<TableScope, VersionSet>> snapshot;

    private DekVersionRegistry(Map<TableScope, VersionSet> initial) {
        this.snapshot = new AtomicReference<>(initial);
    }

    /**
     * Construct an empty registry. CoW-publication is via {@link #publishUpdate}.
     */
    public static DekVersionRegistry empty() {
        return new DekVersionRegistry(Map.of());
    }

    /**
     * Return the highest DEK version known for {@code scope}, or empty if none has been registered.
     *
     * @throws NullPointerException if {@code scope} is null
     */
    public Optional<Integer> currentVersion(TableScope scope) {
        Objects.requireNonNull(scope, "scope");
        final VersionSet vs = snapshot.get().get(scope);
        return vs == null ? Optional.empty() : Optional.of(vs.current());
    }

    /**
     * Return the full set of DEK versions known for {@code scope}. Empty set means the scope has
     * not been registered.
     *
     * @throws NullPointerException if {@code scope} is null
     */
    public Set<Integer> knownVersions(TableScope scope) {
        Objects.requireNonNull(scope, "scope");
        final VersionSet vs = snapshot.get().get(scope);
        return vs == null ? Set.of() : vs.known();
    }

    /**
     * Atomically publish a mutation to the registry: replace the entry for {@code scope} with
     * {@code (newCurrent, newKnown)}. Implemented as a CAS-loop volatile-reference swap of the
     * entire map (CoW); concurrent readers see either the prior snapshot or this one, never a
     * partial state.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code newCurrent} is not a member of {@code newKnown}
     */
    public void publishUpdate(TableScope scope, int newCurrent, Set<Integer> newKnown) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(newKnown, "newKnown");
        if (!newKnown.contains(newCurrent)) {
            throw new IllegalArgumentException(
                    "newCurrent " + newCurrent + " must be a member of newKnown " + newKnown);
        }
        // Defensive copy of newKnown — caller-mutation must not corrupt the published snapshot.
        final Set<Integer> defensiveKnown = Set.copyOf(newKnown);
        final VersionSet entry = new VersionSet(newCurrent, defensiveKnown);

        // CAS loop: build a new immutable map containing the prior contents with this entry
        // replaced, then volatile-swap. Concurrent readers observe the old or new snapshot
        // atomically — never a partial state.
        //
        // Monotonic-publish discipline. Under racing concurrent writers a naive last-writer-wins
        // CoW can regress: writer A publishes (V_hi, ..) then writer B (started earlier with a
        // smaller version) lands its CAS publishing (V_lo, ..). A reader that previously
        // observed currentVersion()=V_hi would later observe V_lo, and a two-call read
        // straddle could see currentVersion()=V_hi from the older snapshot and
        // knownVersions()={1..V_lo} from the newer — surfacing a "current ∉ known" inconsistency
        // across calls. Per R64 the registry is consulted for the current DEK version of a
        // scope, and DEK lifecycle (R29 generates new versions; R30 prunes old ones once
        // unreferenced) is monotonic on `current`: rotation only mints new versions, never
        // un-mints them. We enforce this here by no-op'ing any publish that would not
        // increase `current` AND would not add to `known` — preserving the cross-read
        // invariant readers depend on.
        Map<TableScope, VersionSet> prior;
        Map<TableScope, VersionSet> next;
        do {
            prior = snapshot.get();
            final VersionSet existing = prior.get(scope);
            if (existing != null && existing.current() >= newCurrent
                    && existing.known().containsAll(defensiveKnown)) {
                // The existing snapshot already supersedes this update on both
                // dimensions — no-op publish.
                return;
            }
            final VersionSet toWrite = (existing == null) ? entry : mergeUp(existing, entry);
            final HashMap<TableScope, VersionSet> rebuilt = new HashMap<>(prior);
            rebuilt.put(scope, toWrite);
            next = Map.copyOf(rebuilt);
        } while (!snapshot.compareAndSet(prior, next));
    }

    /**
     * Merge two version sets monotonically: take the larger {@code current} and the union of the
     * two {@code known} sets. Used by the CAS loop to ensure a publish never regresses on either
     * dimension under racing writers.
     */
    private static VersionSet mergeUp(VersionSet existing, VersionSet incoming) {
        final int mergedCurrent = Math.max(existing.current(), incoming.current());
        final LinkedHashSet<Integer> union = new LinkedHashSet<>(existing.known());
        union.addAll(incoming.known());
        return new VersionSet(mergedCurrent, Set.copyOf(union));
    }

    /**
     * Immutable per-scope value: the current DEK version and the full set of known versions. Both
     * fields are populated at construction; {@code known} is required to be immutable.
     */
    private record VersionSet(int current, Set<Integer> known) {
    }
}
