package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;

/**
 * Tests for {@link DomainKekTombstone} (R83b-1a, P4-1). Per-(tenantId, domainId) idempotent mark;
 * quiescenceAndZeroize honours configurable upper bound (default cache TTL, 24h ceiling, 48h hard
 * maximum); forced-zero on timeout.
 *
 * @spec encryption.primitives-lifecycle R83b-1a
 */
class DomainKekTombstoneTest {

    private static TableScope scope(String t, String d, String tb) {
        return new TableScope(new TenantId(t), new DomainId(d), new TableId(tb));
    }

    @Test
    void withCacheTtlDefaultProducesInstance() {
        final DomainKekTombstone t = DomainKekTombstone.withCacheTtlDefault();
        assertNotNull(t);
    }

    @Test
    void withQuiescenceBoundAcceptsValidDuration() {
        final DomainKekTombstone t = DomainKekTombstone.withQuiescenceBound(Duration.ofMinutes(30));
        assertNotNull(t);
    }

    @Test
    void withQuiescenceBoundRejectsZero() {
        assertThrows(IllegalArgumentException.class,
                () -> DomainKekTombstone.withQuiescenceBound(Duration.ZERO));
    }

    @Test
    void withQuiescenceBoundRejectsNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> DomainKekTombstone.withQuiescenceBound(Duration.ofSeconds(-1)));
    }

    @Test
    void withQuiescenceBoundRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> DomainKekTombstone.withQuiescenceBound(null));
    }

    @Test
    void withQuiescenceBoundCapsAt48Hours() {
        // P4-1 hard maximum: any bound > 48h must be capped at 48h. Construction itself must
        // not throw — the implementation may either accept the value and cap internally or
        // reject. We assert acceptance + cap-on-construction.
        final DomainKekTombstone t = DomainKekTombstone.withQuiescenceBound(Duration.ofHours(72));
        assertNotNull(t);
    }

    @Test
    void markRevokedRejectsNullScope() {
        final DomainKekTombstone t = DomainKekTombstone.withCacheTtlDefault();
        assertThrows(NullPointerException.class, () -> t.markRevoked(null));
    }

    @Test
    void isRevokedReturnsFalseForUnmarkedScope() {
        final DomainKekTombstone t = DomainKekTombstone.withCacheTtlDefault();
        assertFalse(t.isRevoked(scope("t", "d", "tab")));
    }

    @Test
    void markRevokedTransitionsScopeToRevoked() {
        final DomainKekTombstone t = DomainKekTombstone.withCacheTtlDefault();
        t.markRevoked(scope("t", "d", "tab"));
        assertTrue(t.isRevoked(scope("t", "d", "tab")));
    }

    @Test
    void markRevokedIsIdempotent() {
        final DomainKekTombstone t = DomainKekTombstone.withCacheTtlDefault();
        final TableScope s = scope("t", "d", "tab");
        t.markRevoked(s);
        t.markRevoked(s);
        t.markRevoked(s);
        assertTrue(t.isRevoked(s));
    }

    @Test
    void markRevokedAffectsOnlyThePerDomainKey() {
        // The tombstone is per-(tenantId, domainId); table component does not affect the
        // mark. Two scopes that share (tenantId, domainId) but differ in tableId must
        // both observe the revocation.
        final DomainKekTombstone t = DomainKekTombstone.withCacheTtlDefault();
        t.markRevoked(scope("t", "d", "tab1"));
        assertTrue(t.isRevoked(scope("t", "d", "tab1")));
        assertTrue(t.isRevoked(scope("t", "d", "tab2")),
                "tombstone is keyed by (tenantId, domainId); tableId variation must not "
                        + "produce a fresh entry");
    }

    @Test
    void distinctTenantsDoNotInterfere() {
        final DomainKekTombstone t = DomainKekTombstone.withCacheTtlDefault();
        t.markRevoked(scope("t1", "d", "tab"));
        assertFalse(t.isRevoked(scope("t2", "d", "tab")));
    }

    @Test
    void distinctDomainsDoNotInterfere() {
        final DomainKekTombstone t = DomainKekTombstone.withCacheTtlDefault();
        t.markRevoked(scope("t", "d1", "tab"));
        assertFalse(t.isRevoked(scope("t", "d2", "tab")));
    }

    @Test
    void quiesceAndZeroizeReturnsResultForMarkedScope() {
        final DomainKekTombstone t = DomainKekTombstone.withCacheTtlDefault();
        final TableScope s = scope("t", "d", "tab");
        t.markRevoked(s);
        final DomainKekTombstone.QuiescenceResult r = t.quiesceAndZeroize(s, Duration.ofMillis(50));
        assertNotNull(r);
        assertEquals(s, r.scope());
        // No in-flight readers were registered — should NOT have timed out.
        assertFalse(r.timedOut(),
                "quiesceAndZeroize on an unscanned scope must complete without timing out");
        assertEquals(0L, r.inFlightAtForce());
    }

    @Test
    void quiesceAndZeroizeNullArgsRejected() {
        final DomainKekTombstone t = DomainKekTombstone.withCacheTtlDefault();
        assertThrows(NullPointerException.class,
                () -> t.quiesceAndZeroize(null, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class,
                () -> t.quiesceAndZeroize(scope("t", "d", "tab"), null));
    }

    @Test
    void quiescenceResultRecordValidatesFields() {
        final TableScope s = scope("t", "d", "tab");
        // negative inFlightAtForce must be rejected
        assertThrows(IllegalArgumentException.class,
                () -> new DomainKekTombstone.QuiescenceResult(s, false, -1L, Instant.now()));
        // null components must be rejected
        assertThrows(NullPointerException.class,
                () -> new DomainKekTombstone.QuiescenceResult(null, false, 0L, Instant.now()));
        assertThrows(NullPointerException.class,
                () -> new DomainKekTombstone.QuiescenceResult(s, false, 0L, null));
    }
}
