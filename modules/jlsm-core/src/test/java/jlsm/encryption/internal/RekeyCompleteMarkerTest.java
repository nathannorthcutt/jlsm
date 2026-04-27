package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import jlsm.encryption.KekRef;

/**
 * Tests for {@link RekeyCompleteMarker} — atomic shard-resident marker recording rekey completion
 * (R78f).
 *
 * @spec encryption.primitives-lifecycle R78f
 */
class RekeyCompleteMarkerTest {

    private static final KekRef REF = new KekRef("kek/v2");
    private static final Instant NOW = Instant.parse("2026-04-27T12:00:00Z");

    @Test
    void construct_validArgs_succeeds() {
        final RekeyCompleteMarker marker = new RekeyCompleteMarker(REF, NOW);
        assertEquals(REF, marker.completedKekRef());
        assertEquals(NOW, marker.timestamp());
    }

    @Test
    void construct_nullCompletedKekRef_throwsNpe() {
        assertThrows(NullPointerException.class, () -> new RekeyCompleteMarker(null, NOW));
    }

    @Test
    void construct_nullTimestamp_throwsNpe() {
        assertThrows(NullPointerException.class, () -> new RekeyCompleteMarker(REF, null));
    }

    @Test
    void equals_sameValues_isTrue() {
        final RekeyCompleteMarker a = new RekeyCompleteMarker(REF, NOW);
        final RekeyCompleteMarker b = new RekeyCompleteMarker(REF, NOW);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentRef_isFalse() {
        final RekeyCompleteMarker a = new RekeyCompleteMarker(REF, NOW);
        final RekeyCompleteMarker b = new RekeyCompleteMarker(new KekRef("kek/v3"), NOW);
        assertNotEquals(a, b);
    }
}
