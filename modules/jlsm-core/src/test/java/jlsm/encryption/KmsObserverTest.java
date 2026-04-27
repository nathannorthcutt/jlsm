package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link KmsObserver} SPI. Each callback has a default no-op implementation. Each
 * event payload must include eventSeq, eventCategory, seqDurability, timestamp, and tenantId (per
 * R83f, R76b-1).
 *
 * @spec encryption.primitives-lifecycle R83f
 * @spec encryption.primitives-lifecycle R76b-1
 * @spec encryption.primitives-lifecycle R83h
 */
class KmsObserverTest {

    private static final TenantId TENANT_A = new TenantId("tenant-A");
    private static final Instant NOW = Instant.parse("2026-04-27T12:34:56Z");

    private static KmsObserver.EventEnvelope envelope(EventCategory category, long seq) {
        return new KmsObserver.EventEnvelope(seq, category, category.isDurable(), NOW, TENANT_A,
                "corr-1");
    }

    @Test
    void defaultObserverIsNoOpOnAllCallbacks() {
        // No-op default — partial observers must compile cleanly.
        final KmsObserver observer = new KmsObserver() {
        };
        final KmsObserver.EventEnvelope env = envelope(EventCategory.STATE_TRANSITION, 1L);

        assertDoesNotThrow(() -> observer
                .onTenantKekStateTransition(new KmsObserver.TenantStateTransitionEvent(env,
                        TenantState.HEALTHY, TenantState.GRACE_READ_ONLY)));
        assertDoesNotThrow(() -> observer
                .onRekeyEvent(new KmsObserver.RekeyEvent(envelope(EventCategory.REKEY, 1L),
                        "started", new KekRef("old"), new KekRef("new"), 0L)));
        assertDoesNotThrow(() -> observer.onPollingEvent(new KmsObserver.PollingEvent(
                envelope(EventCategory.POLLING, 1L), new KekRef("ref"), "success")));
        assertDoesNotThrow(
                () -> observer.onUnclassifiedError(new KmsObserver.UnclassifiedErrorEvent(
                        envelope(EventCategory.UNCLASSIFIED_ERROR, 1L), "RuntimeException", 5L,
                        false)));
    }

    @Test
    void envelopeRejectsNullEventCategory() {
        assertThrows(NullPointerException.class,
                () -> new KmsObserver.EventEnvelope(0L, null, false, NOW, TENANT_A, "corr-1"));
    }

    @Test
    void envelopeRejectsNullTimestamp() {
        assertThrows(NullPointerException.class, () -> new KmsObserver.EventEnvelope(0L,
                EventCategory.STATE_TRANSITION, true, null, TENANT_A, "corr-1"));
    }

    @Test
    void envelopeRejectsNullCorrelationId() {
        assertThrows(NullPointerException.class, () -> new KmsObserver.EventEnvelope(0L,
                EventCategory.STATE_TRANSITION, true, NOW, TENANT_A, null));
    }

    @Test
    void envelopePermitsNullTenantIdForCrossTenantEvents() {
        // Cross-tenant / deployer-wide events may carry tenantId=null per the SPI contract.
        final KmsObserver.EventEnvelope env = new KmsObserver.EventEnvelope(0L,
                EventCategory.UNCLASSIFIED_ERROR, false, NOW, null, "corr-deployer");
        assertEquals(EventCategory.UNCLASSIFIED_ERROR, env.eventCategory());
        assertEquals(null, env.tenantId());
    }

    @Test
    void envelopeFieldsAreAccessible() {
        final KmsObserver.EventEnvelope env = envelope(EventCategory.STATE_TRANSITION, 42L);
        assertEquals(42L, env.eventSeq());
        assertEquals(EventCategory.STATE_TRANSITION, env.eventCategory());
        assertTrue(env.seqDurability(),
                "STATE_TRANSITION envelope must declare seqDurability=true (durable)");
        assertEquals(NOW, env.timestamp());
        assertEquals(TENANT_A, env.tenantId());
        assertEquals("corr-1", env.correlationId());
    }

    @Test
    void tenantStateTransitionEventRetainsFromAndToStates() {
        final KmsObserver.EventEnvelope env = envelope(EventCategory.STATE_TRANSITION, 1L);
        final var event = new KmsObserver.TenantStateTransitionEvent(env, TenantState.HEALTHY,
                TenantState.GRACE_READ_ONLY);
        assertSame(env, event.envelope());
        assertEquals(TenantState.HEALTHY, event.fromState());
        assertEquals(TenantState.GRACE_READ_ONLY, event.toState());
    }

    @Test
    void tenantStateTransitionEventRejectsNullStates() {
        final KmsObserver.EventEnvelope env = envelope(EventCategory.STATE_TRANSITION, 1L);
        assertThrows(NullPointerException.class,
                () -> new KmsObserver.TenantStateTransitionEvent(env, null, TenantState.FAILED));
        assertThrows(NullPointerException.class,
                () -> new KmsObserver.TenantStateTransitionEvent(env, TenantState.HEALTHY, null));
    }

    @Test
    void tenantStateTransitionEventRejectsNullEnvelope() {
        assertThrows(NullPointerException.class,
                () -> new KmsObserver.TenantStateTransitionEvent(null, TenantState.HEALTHY,
                        TenantState.FAILED));
    }

    @Test
    void rekeyEventRequiresAllRefs() {
        final KmsObserver.EventEnvelope env = envelope(EventCategory.REKEY, 1L);
        assertNotNull(new KmsObserver.RekeyEvent(env, "complete", new KekRef("old"),
                new KekRef("new"), 10L));
        assertThrows(NullPointerException.class,
                () -> new KmsObserver.RekeyEvent(env, "started", null, new KekRef("new"), 0L));
        assertThrows(NullPointerException.class,
                () -> new KmsObserver.RekeyEvent(env, "started", new KekRef("old"), null, 0L));
        assertThrows(NullPointerException.class, () -> new KmsObserver.RekeyEvent(env, null,
                new KekRef("old"), new KekRef("new"), 0L));
    }

    @Test
    void pollingEventRequiresKekRefAndOutcome() {
        final KmsObserver.EventEnvelope env = envelope(EventCategory.POLLING, 1L);
        assertNotNull(new KmsObserver.PollingEvent(env, new KekRef("ref"), "success"));
        assertThrows(NullPointerException.class,
                () -> new KmsObserver.PollingEvent(env, null, "success"));
        assertThrows(NullPointerException.class,
                () -> new KmsObserver.PollingEvent(env, new KekRef("ref"), null));
    }

    @Test
    void unclassifiedErrorEventRequiresErrorClass() {
        final KmsObserver.EventEnvelope env = envelope(EventCategory.UNCLASSIFIED_ERROR, 1L);
        assertNotNull(new KmsObserver.UnclassifiedErrorEvent(env, "RuntimeException", 100L, true));
        assertThrows(NullPointerException.class,
                () -> new KmsObserver.UnclassifiedErrorEvent(env, null, 0L, false));
    }
}
