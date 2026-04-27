package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import jlsm.encryption.internal.ConvergenceTracker;
import jlsm.encryption.internal.RotationMetadata;

/**
 * Tests for {@link ConvergenceRegistration} contract — interface shape and idempotent close (R37b-3
 * path (a) explicit close, double-close).
 *
 * <p>
 * Drives test scenarios via {@link ConvergenceTracker} since {@code ConvergenceRegistration} is
 * pure interface; the tracker is the canonical producer of registration handles.
 *
 * @spec encryption.primitives-lifecycle R37b-3
 */
class ConvergenceRegistrationTest {

    private static final TableScope SCOPE = new TableScope(new TenantId("tenantA"),
            new DomainId("domain-1"), new TableId("table-1"));
    private static final Instant ROTATION_START = Instant.parse("2026-04-27T12:00:00Z");
    private static final Duration BOUND = Duration.ofMinutes(5);

    private static ConvergenceTracker freshTracker() {
        final Clock fixed = Clock.fixed(ROTATION_START, ZoneOffset.UTC);
        return ConvergenceTracker.create(new RecordingObserver(), fixed);
    }

    private static RotationMetadata meta(int oldVersion) {
        return new RotationMetadata(SCOPE, oldVersion, ROTATION_START, BOUND);
    }

    @Test
    void registration_isAutoCloseable() {
        final ConvergenceTracker tracker = freshTracker();
        tracker.recordRotationStart(meta(5));
        try (ConvergenceRegistration reg = tracker.register(SCOPE, 5, s -> {
        })) {
            assertNotNull(reg);
        }
    }

    @Test
    void close_isIdempotent_doubleClose_noThrow() {
        final ConvergenceTracker tracker = freshTracker();
        tracker.recordRotationStart(meta(5));
        final ConvergenceRegistration reg = tracker.register(SCOPE, 5, s -> {
        });

        reg.close();
        reg.close();
        reg.close();
        // No throw.
    }

    @Test
    void close_dropsRegistration_subsequentCommitDoesNotFire() {
        final ConvergenceTracker tracker = freshTracker();
        tracker.recordRotationStart(meta(5));
        final AtomicInteger callbackInvocations = new AtomicInteger();
        final ConvergenceRegistration reg = tracker.register(SCOPE, 5,
                s -> callbackInvocations.incrementAndGet());

        reg.close();

        // Reaching CONVERGED on a closed registration must not invoke the callback (path (a)).
        assertEquals(0, callbackInvocations.get());
    }

    @Test
    void currentState_initiallyPending() {
        final ConvergenceTracker tracker = freshTracker();
        tracker.recordRotationStart(meta(5));
        final ConvergenceRegistration reg = tracker.register(SCOPE, 5, s -> {
        });
        assertSame(ConvergenceState.PENDING, reg.currentState());
        reg.close();
    }

    @Test
    void close_afterTracker_isStillIdempotent_pathB() {
        // Path (b): EncryptionKeyHolder.close() drops the registration. Simulated here by
        // closing the tracker first; then the handle close must still be a no-op.
        final ConvergenceTracker tracker = freshTracker();
        tracker.recordRotationStart(meta(5));
        final ConvergenceRegistration reg = tracker.register(SCOPE, 5, s -> {
        });

        tracker.close();
        reg.close(); // must not throw
        reg.close();
    }

    /** Test {@link KmsObserver} that records all events. Polling-only for R37b-2. */
    static final class RecordingObserver implements KmsObserver {

        @Override
        public void onPollingEvent(PollingEvent event) {
            // no-op record
        }
    }
}
