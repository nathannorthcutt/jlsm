package jlsm.engine.cluster.internal;

import jlsm.cluster.NodeAddress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link GracePeriodManager}'s single-argument constructor defaults to a monotonic clock
 * rather than {@code Clock.systemUTC()} — governed by F04.R53.
 *
 * <p>
 * The direct NTP-backward simulation is infeasible without a mocked {@code System.nanoTime()};
 * instead these tests (a) verify the injected clock instance is {@link MonotonicClock} via
 * reflection, and (b) exercise the 1-arg constructor end-to-end so the default wiring is guaranteed
 * to behave correctly for typical grace-window computations.
 */
final class GracePeriodManagerMonotonicDefaultTest {

    private static final NodeAddress NODE = new NodeAddress("test-node", "localhost", 9001);

    @Test
    @Timeout(5)
    void defaultConstructor_usesMonotonicClock_notWallClock() throws Exception {
        final GracePeriodManager manager = new GracePeriodManager(Duration.ofSeconds(30));
        manager.recordDeparture(NODE, Instant.now().minusSeconds(10));

        assertTrue(manager.isInGracePeriod(NODE),
                "Node should be in grace period immediately after a 10s-past departure (30s grace)");

        Thread.sleep(50);
        assertTrue(manager.isInGracePeriod(NODE),
                "Node should still be in grace period after 50ms additional wait");

        Thread.sleep(100);
        assertTrue(manager.isInGracePeriod(NODE),
                "Node should still be in grace period after ~150ms total additional wait (grace=30s)");

        // Primary assertion: the injected clock is a MonotonicClock, not Clock.systemUTC().
        final Clock injected = readClockField(manager);
        assertNotNull(injected, "Injected clock must not be null");
        assertTrue(injected instanceof MonotonicClock,
                "Default GracePeriodManager(Duration) must use MonotonicClock; got "
                        + injected.getClass().getName());
    }

    @Test
    @Timeout(5)
    void defaultConstructor_graceWindowExpires() throws InterruptedException {
        final GracePeriodManager manager = new GracePeriodManager(Duration.ofMillis(50));
        manager.recordDeparture(NODE, Instant.now().minusMillis(25));

        assertTrue(manager.isInGracePeriod(NODE),
                "Node should be in grace period with 25ms of 50ms grace window consumed");

        Thread.sleep(100);
        assertFalse(manager.isInGracePeriod(NODE),
                "Node should NOT be in grace period after 50ms grace + 100ms sleep total elapsed");
    }

    @Test
    @Timeout(5)
    void defaultConstructor_doesNotUseWallClock_isolationSanity() throws Exception {
        final GracePeriodManager manager = new GracePeriodManager(Duration.ofSeconds(1));
        final Clock injected = readClockField(manager);

        assertNotNull(injected, "Injected clock must not be null");
        // Regression guard: the SystemClock returned by Clock.systemUTC() must not be the default.
        assertFalse("java.time.Clock$SystemClock".equals(injected.getClass().getName()),
                "Default clock must not be Clock.systemUTC() (Clock$SystemClock); got "
                        + injected.getClass().getName());
        assertTrue(injected.getClass().getName().endsWith("MonotonicClock"),
                "Default clock class name must end with 'MonotonicClock'; got "
                        + injected.getClass().getName());
    }

    private static Clock readClockField(GracePeriodManager manager) throws Exception {
        final Field field = GracePeriodManager.class.getDeclaredField("clock");
        field.setAccessible(true);
        return (Clock) field.get(manager);
    }
}
