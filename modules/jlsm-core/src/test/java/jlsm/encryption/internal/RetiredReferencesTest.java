package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import jlsm.encryption.KekRef;

/**
 * Tests for {@link RetiredReferences} — retired KEK references with retention-until timestamps and
 * R33a grace-period invariant via liveness probe.
 *
 * @spec encryption.primitives-lifecycle R33
 * @spec encryption.primitives-lifecycle R33a
 */
class RetiredReferencesTest {

    private static final KekRef REF_1 = new KekRef("kek/v1");
    private static final KekRef REF_2 = new KekRef("kek/v2");
    private static final KekRef REF_3 = new KekRef("kek/v3");

    @Test
    void empty_hasNoEntries() {
        assertTrue(RetiredReferences.empty().entries().isEmpty());
    }

    @Test
    void empty_eligibleForGc_returnsEmpty() {
        // No retired refs at all → nothing eligible for GC.
        assertTrue(
                RetiredReferences.empty().eligibleForGc(Instant.now(), (kekRef) -> 0L).isEmpty());
    }

    @Test
    void constructor_nullEntries_throwsNpe() {
        assertThrows(NullPointerException.class, () -> new RetiredReferences(null));
    }

    @Test
    void constructor_nullKey_throwsNpe() {
        final Map<KekRef, Instant> bad = new HashMap<>();
        bad.put(null, Instant.EPOCH);
        assertThrows(NullPointerException.class, () -> new RetiredReferences(bad));
    }

    @Test
    void constructor_nullValue_throwsNpe() {
        final Map<KekRef, Instant> bad = new HashMap<>();
        bad.put(REF_1, null);
        assertThrows(NullPointerException.class, () -> new RetiredReferences(bad));
    }

    @Test
    void constructor_defensivelyCopiesEntries() {
        final Map<KekRef, Instant> mutable = new HashMap<>();
        mutable.put(REF_1, Instant.EPOCH);
        final RetiredReferences rr = new RetiredReferences(mutable);
        // Mutating the source must not affect the record.
        mutable.put(REF_2, Instant.EPOCH.plusSeconds(60));
        assertEquals(1, rr.entries().size());
        assertTrue(rr.entries().containsKey(REF_1));
        assertFalse(rr.entries().containsKey(REF_2));
    }

    @Test
    void markRetired_nullArgs_throwNpe() {
        final RetiredReferences rr = RetiredReferences.empty();
        assertThrows(NullPointerException.class, () -> rr.markRetired(null, Instant.EPOCH));
        assertThrows(NullPointerException.class, () -> rr.markRetired(REF_1, null));
    }

    @Test
    void markRetired_addsEntryToNewInstance_originalUnchanged() {
        // Records are immutable: markRetired returns a new instance.
        final RetiredReferences original = RetiredReferences.empty();
        final Instant retentionUntil = Instant.parse("2026-05-01T00:00:00Z");
        final RetiredReferences updated = original.markRetired(REF_1, retentionUntil);
        assertNotSame(original, updated);
        assertTrue(original.entries().isEmpty());
        assertEquals(1, updated.entries().size());
        assertEquals(retentionUntil, updated.entries().get(REF_1));
    }

    @Test
    void markRetired_overridesExistingEntry() {
        // Re-retiring the same ref with a new retention-until replaces the prior value.
        final Instant t1 = Instant.parse("2026-04-30T00:00:00Z");
        final Instant t2 = Instant.parse("2026-05-15T00:00:00Z");
        RetiredReferences rr = RetiredReferences.empty().markRetired(REF_1, t1);
        rr = rr.markRetired(REF_1, t2);
        assertEquals(1, rr.entries().size());
        assertEquals(t2, rr.entries().get(REF_1));
    }

    @Test
    void markRetired_keepsExistingOtherEntries() {
        final Instant t1 = Instant.parse("2026-04-30T00:00:00Z");
        final Instant t2 = Instant.parse("2026-05-15T00:00:00Z");
        RetiredReferences rr = RetiredReferences.empty().markRetired(REF_1, t1);
        rr = rr.markRetired(REF_2, t2);
        assertEquals(2, rr.entries().size());
        assertEquals(t1, rr.entries().get(REF_1));
        assertEquals(t2, rr.entries().get(REF_2));
    }

    @Test
    void eligibleForGc_nullArgs_throwNpe() {
        final RetiredReferences rr = RetiredReferences.empty();
        assertThrows(NullPointerException.class, () -> rr.eligibleForGc(null, kekRef -> 0L));
        assertThrows(NullPointerException.class, () -> rr.eligibleForGc(Instant.now(), null));
    }

    @Test
    void eligibleForGc_retentionElapsedAndZeroLiveness_returnsRef() {
        // R33: retention has elapsed AND liveness witness reports zero → eligible.
        final Instant retention = Instant.parse("2026-04-25T00:00:00Z");
        final Instant now = retention.plusSeconds(60);
        final RetiredReferences rr = RetiredReferences.empty().markRetired(REF_1, retention);
        final Set<KekRef> eligible = rr.eligibleForGc(now, kekRef -> 0L);
        assertEquals(Set.of(REF_1), eligible);
    }

    @Test
    void eligibleForGc_retentionNotElapsed_excludesRef() {
        // Retention not yet elapsed → not eligible even with zero liveness.
        final Instant retention = Instant.parse("2026-05-01T00:00:00Z");
        final Instant now = retention.minusSeconds(60);
        final RetiredReferences rr = RetiredReferences.empty().markRetired(REF_1, retention);
        final Set<KekRef> eligible = rr.eligibleForGc(now, kekRef -> 0L);
        assertTrue(eligible.isEmpty());
    }

    @Test
    void eligibleForGc_nonZeroLiveness_excludesRef_evenIfRetentionElapsed() {
        // R33a grace-period invariant: liveness > 0 → not eligible regardless of retention.
        final Instant retention = Instant.parse("2026-04-25T00:00:00Z");
        final Instant now = retention.plusSeconds(60);
        final RetiredReferences rr = RetiredReferences.empty().markRetired(REF_1, retention);
        final Set<KekRef> eligible = rr.eligibleForGc(now, kekRef -> 5L);
        assertTrue(eligible.isEmpty());
    }

    @Test
    void eligibleForGc_retentionExactBoundary_isEligible() {
        // Retention "on or before now" means equal is also eligible.
        final Instant retention = Instant.parse("2026-04-25T00:00:00Z");
        final RetiredReferences rr = RetiredReferences.empty().markRetired(REF_1, retention);
        final Set<KekRef> eligible = rr.eligibleForGc(retention, kekRef -> 0L);
        assertEquals(Set.of(REF_1), eligible);
    }

    @Test
    void eligibleForGc_mixedSet_partitionsByPredicate() {
        // Three retired refs: REF_1 elapsed+zero (eligible), REF_2 elapsed+nonzero (not),
        // REF_3 not elapsed (not).
        final Instant t = Instant.parse("2026-04-25T00:00:00Z");
        RetiredReferences rr = RetiredReferences.empty();
        rr = rr.markRetired(REF_1, t.minusSeconds(10));
        rr = rr.markRetired(REF_2, t.minusSeconds(10));
        rr = rr.markRetired(REF_3, t.plusSeconds(60));
        final Set<KekRef> eligible = rr.eligibleForGc(t, kekRef -> kekRef.equals(REF_2) ? 1L : 0L);
        assertEquals(Set.of(REF_1), eligible);
    }

    @Test
    void eligibleForGc_emptyContainer_returnsEmptySet() {
        final RetiredReferences rr = RetiredReferences.empty();
        assertSame(Set.of(), rr.eligibleForGc(Instant.now(), kekRef -> 0L),
                "empty container must return canonical empty set");
    }

    @Test
    void empty_isReusableAcrossCallers() {
        // empty() is permitted to return a shared instance; semantically must be empty regardless.
        final RetiredReferences a = RetiredReferences.empty();
        final RetiredReferences b = RetiredReferences.empty();
        assertEquals(a.entries(), b.entries());
    }
}
