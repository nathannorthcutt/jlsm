package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReadContext} — open contract dependency landed in WU-1 carrying
 * {@code allowedDekVersions} between SSTable reader (WU-2 producer, after footer parse) and
 * field-encryption dispatch (WU-4 consumer, R3e gate).
 *
 * <p>
 * Spec ties: {@code sstable.footer-encryption-scope} R3e (Set&lt;Integer&gt; materialised at
 * footer-parse time and dispatch-checked before any DEK lookup).
 */
class ReadContextTest {

    // ---------- Happy path ----------

    @Test
    void constructor_acceptsNonEmptySet_andExposesAllowedVersions() {
        // covers: R3e — record carries Set<Integer> allowedDekVersions
        final Set<Integer> versions = Set.of(1, 2, 3);
        final ReadContext ctx = new ReadContext(versions);

        assertEquals(versions, ctx.allowedDekVersions());
    }

    @Test
    void constructor_acceptsEmptySet_forEmptyDekSetSstable() {
        // covers: R3e + R3c — a v6 SSTable with dek-version-count=0 produces an empty set;
        // the ReadContext must accept it (subsequent envelope membership-check fails by
        // R3f rather than at construction).
        final ReadContext ctx = new ReadContext(Set.of());

        assertTrue(ctx.allowedDekVersions().isEmpty(),
                "ReadContext must accept empty allowed-versions set");
    }

    // ---------- Error / null rejection ----------

    @Test
    void constructor_rejectsNullAllowedVersions() {
        // covers: R3e — eager null-rejection on canonical record constructor
        assertThrows(NullPointerException.class, () -> new ReadContext(null));
    }

    // ---------- Defensive (Lens B — IMPL-RISK shared mutable state) ----------

    @Test
    void allowedDekVersions_isDefensivelyCopied_atConstruction() {
        // Lens B finding (IMPL-RISK): a record component holding a Set must be
        // defensively copied at construction so that an external caller's later
        // mutation of the source set cannot poison the read-path R3e check. Without
        // this, a writer holding the source set could insert a forbidden version
        // after the SSTable reader handed off the ReadContext, defeating the gate.
        final Set<Integer> source = new LinkedHashSet<>();
        source.add(1);
        source.add(2);

        final ReadContext ctx = new ReadContext(source);

        // Mutate source AFTER construction — should not leak into ctx.
        source.add(99);

        assertEquals(Set.of(1, 2), ctx.allowedDekVersions(),
                "ReadContext must defensively copy allowedDekVersions to avoid post-construction "
                        + "mutation of the R3e gate set");
    }

    @Test
    void allowedDekVersions_returnedSet_isUnmodifiable() {
        // Lens B finding (IMPL-RISK): returned references must not expose a mutable
        // internal collection. A consumer that mutates the returned set could shift
        // the R3e gate at runtime.
        final ReadContext ctx = new ReadContext(Set.of(1, 2));

        assertThrows(UnsupportedOperationException.class, () -> ctx.allowedDekVersions().add(3),
                "ReadContext.allowedDekVersions() must return an unmodifiable view");
    }

    // ---------- Equality ----------

    @Test
    void equality_isComponentWise() {
        final ReadContext a = new ReadContext(Set.of(1, 2));
        final ReadContext b = new ReadContext(Set.of(2, 1));
        final ReadContext c = new ReadContext(Set.of(1, 3));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void recordComponent_returnsSameInstanceAcrossAccessors() {
        // Lens B (IMPL-RISK): repeated accesses of allowedDekVersions() must return a
        // stable (same-identity) reference so consumers can cache the unmodifiable view
        // without paying repeated copy costs on hot read-path R3e checks.
        final ReadContext ctx = new ReadContext(Set.of(1, 2, 3));

        assertSame(ctx.allowedDekVersions(), ctx.allowedDekVersions(),
                "ReadContext.allowedDekVersions() must return the same cached unmodifiable view "
                        + "across calls (avoid hot-path allocation)");
    }
}
