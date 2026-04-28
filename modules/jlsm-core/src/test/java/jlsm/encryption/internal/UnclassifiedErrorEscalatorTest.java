package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import jlsm.encryption.TenantId;

/**
 * Tests for {@link UnclassifiedErrorEscalator} (R76a-2).
 *
 * @spec encryption.primitives-lifecycle R76a-2
 */
class UnclassifiedErrorEscalatorTest {

    private static final TenantId TENANT_A = new TenantId("tenant-A");
    private static final TenantId TENANT_B = new TenantId("tenant-B");

    @Test
    void withDefaultsConstructsViableInstance() {
        final UnclassifiedErrorEscalator esc = UnclassifiedErrorEscalator.withDefaults();
        assertNotNull(esc);
        assertEquals(0L, esc.currentCount(TENANT_A));
    }

    @Test
    void withConfigRejectsNonPositiveThreshold() {
        assertThrows(IllegalArgumentException.class,
                () -> UnclassifiedErrorEscalator.withConfig(0, Duration.ofSeconds(60)));
        assertThrows(IllegalArgumentException.class,
                () -> UnclassifiedErrorEscalator.withConfig(-1, Duration.ofSeconds(60)));
    }

    @Test
    void withConfigRejectsNullWindow() {
        assertThrows(NullPointerException.class,
                () -> UnclassifiedErrorEscalator.withConfig(100, null));
    }

    @Test
    void withConfigRejectsZeroOrNegativeWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> UnclassifiedErrorEscalator.withConfig(100, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> UnclassifiedErrorEscalator.withConfig(100, Duration.ofSeconds(-1)));
    }

    @Test
    void recordRejectsNullTenant() {
        final UnclassifiedErrorEscalator esc = UnclassifiedErrorEscalator.withDefaults();
        assertThrows(NullPointerException.class, () -> esc.recordUnclassified(null));
    }

    @Test
    void currentCountRejectsNullTenant() {
        final UnclassifiedErrorEscalator esc = UnclassifiedErrorEscalator.withDefaults();
        assertThrows(NullPointerException.class, () -> esc.currentCount(null));
    }

    @Test
    void firstNMinus1RecordsDoNotEscalate() {
        final UnclassifiedErrorEscalator esc = UnclassifiedErrorEscalator.withConfig(5,
                Duration.ofSeconds(60));
        for (int i = 0; i < 4; i++) {
            assertFalse(esc.recordUnclassified(TENANT_A),
                    "Sub-threshold record must not signal escalation (i=" + i + ")");
        }
        assertEquals(4L, esc.currentCount(TENANT_A));
    }

    @Test
    void thresholdCrossingRecordSignalsEscalation() {
        final UnclassifiedErrorEscalator esc = UnclassifiedErrorEscalator.withConfig(5,
                Duration.ofSeconds(60));
        for (int i = 0; i < 4; i++) {
            esc.recordUnclassified(TENANT_A);
        }
        // The 5th record is the threshold-crossing event.
        assertTrue(esc.recordUnclassified(TENANT_A),
                "The threshold-crossing record must signal escalation");
    }

    @Test
    void postEscalationRecordsDoNotResignal() {
        final UnclassifiedErrorEscalator esc = UnclassifiedErrorEscalator.withConfig(5,
                Duration.ofSeconds(60));
        for (int i = 0; i < 5; i++) {
            esc.recordUnclassified(TENANT_A);
        }
        // The 6th record is past the threshold; no fresh escalation signal.
        assertFalse(esc.recordUnclassified(TENANT_A),
                "Past-threshold records must not re-signal escalation within the same window");
    }

    @Test
    void perTenantIsolation() {
        final UnclassifiedErrorEscalator esc = UnclassifiedErrorEscalator.withConfig(5,
                Duration.ofSeconds(60));
        for (int i = 0; i < 5; i++) {
            esc.recordUnclassified(TENANT_A);
        }
        // TENANT_B's count must remain 0.
        assertEquals(0L, esc.currentCount(TENANT_B));
        // And tenant B is allowed its own threshold crossing independently.
        for (int i = 0; i < 4; i++) {
            assertFalse(esc.recordUnclassified(TENANT_B));
        }
        assertTrue(esc.recordUnclassified(TENANT_B));
    }

    @Test
    void countReflectsRecordsWithinWindow() {
        final UnclassifiedErrorEscalator esc = UnclassifiedErrorEscalator.withConfig(100,
                Duration.ofSeconds(60));
        esc.recordUnclassified(TENANT_A);
        esc.recordUnclassified(TENANT_A);
        esc.recordUnclassified(TENANT_A);
        assertEquals(3L, esc.currentCount(TENANT_A));
    }

    @Test
    void rollingWindowEvictsExpiredRecords() throws InterruptedException {
        // Use a small window (200ms) to keep this test cheap.
        final UnclassifiedErrorEscalator esc = UnclassifiedErrorEscalator.withConfig(100,
                Duration.ofMillis(200));
        esc.recordUnclassified(TENANT_A);
        esc.recordUnclassified(TENANT_A);
        assertTrue(esc.currentCount(TENANT_A) >= 2L);

        // Sleep past the window.
        Thread.sleep(300);
        // After window expiry, count must reset to 0 for fresh observations.
        assertEquals(0L, esc.currentCount(TENANT_A),
                "Records outside the rolling window must not count");
    }

    @Test
    void escalationAtDefaultThreshold() {
        // R76a-2 default: 100 events / 60s.
        final UnclassifiedErrorEscalator esc = UnclassifiedErrorEscalator.withDefaults();
        for (int i = 0; i < 99; i++) {
            assertFalse(esc.recordUnclassified(TENANT_A),
                    "Sub-default-threshold (i=" + i + ") must not signal");
        }
        assertTrue(esc.recordUnclassified(TENANT_A),
                "100th record at default threshold must signal escalation");
    }
}
