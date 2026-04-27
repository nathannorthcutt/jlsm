package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;

/**
 * Tests for {@link RotationMetadata} — pinned rotation parameters captured at start time (R37a,
 * R37b-1 P4-4).
 *
 * @spec encryption.primitives-lifecycle R37a
 * @spec encryption.primitives-lifecycle R37b-1
 */
class RotationMetadataTest {

    private static final TableScope SCOPE = new TableScope(new TenantId("tenantA"),
            new DomainId("domain-1"), new TableId("table-1"));
    private static final Instant NOW = Instant.parse("2026-04-27T12:00:00Z");
    private static final Duration BOUND = Duration.ofMinutes(5);

    @Test
    void canonicalConstructor_acceptsValidArguments() {
        final RotationMetadata meta = new RotationMetadata(SCOPE, 7, NOW, BOUND);
        assertSame(SCOPE, meta.scope());
        assertEquals(7, meta.oldDekVersion());
        assertSame(NOW, meta.startedAt());
        assertSame(BOUND, meta.r37aBoundAtStart());
    }

    @Test
    void nullScope_throwsNpe() {
        assertThrows(NullPointerException.class, () -> new RotationMetadata(null, 1, NOW, BOUND));
    }

    @Test
    void nullStartedAt_throwsNpe() {
        assertThrows(NullPointerException.class, () -> new RotationMetadata(SCOPE, 1, null, BOUND));
    }

    @Test
    void nullR37aBound_throwsNpe() {
        assertThrows(NullPointerException.class, () -> new RotationMetadata(SCOPE, 1, NOW, null));
    }

    @Test
    void zeroOldDekVersion_throwsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> new RotationMetadata(SCOPE, 0, NOW, BOUND));
    }

    @Test
    void negativeOldDekVersion_throwsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> new RotationMetadata(SCOPE, -1, NOW, BOUND));
    }

    @Test
    void zeroR37aBound_throwsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> new RotationMetadata(SCOPE, 1, NOW, Duration.ZERO));
    }

    @Test
    void negativeR37aBound_throwsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> new RotationMetadata(SCOPE, 1, NOW, Duration.ofSeconds(-1)));
    }

    @Test
    void minimumValidOldDekVersion_isOne() {
        // Boundary: oldDekVersion = 1 must be accepted (smallest positive int)
        final RotationMetadata meta = new RotationMetadata(SCOPE, 1, NOW, BOUND);
        assertEquals(1, meta.oldDekVersion());
    }

    @Test
    void recordEquality_byComponents() {
        final RotationMetadata m1 = new RotationMetadata(SCOPE, 7, NOW, BOUND);
        final RotationMetadata m2 = new RotationMetadata(SCOPE, 7, NOW, BOUND);
        final RotationMetadata m3 = new RotationMetadata(SCOPE, 8, NOW, BOUND);
        assertEquals(m1, m2);
        assertEquals(m1.hashCode(), m2.hashCode());
        assertNotEquals(m1, m3);
    }

    @Test
    void boundIsPinned_distinctBoundsProduceDistinctMetadata() {
        // R37b-1 P4-4: a subsequent dynamic config change to R37a's bound must not
        // retroactively change classifications; the bound captured here is by-value.
        final RotationMetadata m1 = new RotationMetadata(SCOPE, 1, NOW, Duration.ofMinutes(5));
        final RotationMetadata m2 = new RotationMetadata(SCOPE, 1, NOW, Duration.ofMinutes(10));
        assertNotEquals(m1, m2);
        assertEquals(Duration.ofMinutes(5), m1.r37aBoundAtStart());
        assertEquals(Duration.ofMinutes(10), m2.r37aBoundAtStart());
    }
}
