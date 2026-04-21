package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.MembershipListener;
import jlsm.engine.cluster.MembershipProtocol;
import jlsm.engine.cluster.MembershipView;
import jlsm.engine.cluster.NodeAddress;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SeedRetryTask} — periodic background task that re-invokes
 * {@link MembershipProtocol#start(List)} while quorum is lost.
 *
 * @spec engine.clustering.R42 — SeedRetryTask re-invokes membership.start(seeds) at configurable interval;
 *       idempotent start/stop; swallows failures that caller cannot act on
 */
final class SeedRetryTaskTest {

    private static final NodeAddress SEED_A = new NodeAddress("seed-a", "host-a", 8001);
    private static final NodeAddress SEED_B = new NodeAddress("seed-b", "host-b", 8002);

    private ScheduledExecutorService scheduler;
    private RecordingMembership membership;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "seed-retry-test-scheduler");
            t.setDaemon(true);
            return t;
        });
        membership = new RecordingMembership();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    // --- constructor validation ---

    @Test
    void constructor_nullMembership_throws() {
        assertThrows(NullPointerException.class, () -> new SeedRetryTask(null, List.of(SEED_A),
                () -> true, scheduler, Duration.ofMillis(10)));
    }

    @Test
    void constructor_nullSeeds_throws() {
        assertThrows(NullPointerException.class, () -> new SeedRetryTask(membership, null,
                () -> true, scheduler, Duration.ofMillis(10)));
    }

    @Test
    void constructor_nullQuorumSupplier_throws() {
        assertThrows(NullPointerException.class, () -> new SeedRetryTask(membership,
                List.of(SEED_A), null, scheduler, Duration.ofMillis(10)));
    }

    @Test
    void constructor_nullScheduler_throws() {
        assertThrows(NullPointerException.class, () -> new SeedRetryTask(membership,
                List.of(SEED_A), () -> true, null, Duration.ofMillis(10)));
    }

    @Test
    void constructor_nullInterval_throws() {
        assertThrows(NullPointerException.class,
                () -> new SeedRetryTask(membership, List.of(SEED_A), () -> true, scheduler, null));
    }

    @Test
    void constructor_zeroInterval_throws() {
        assertThrows(IllegalArgumentException.class, () -> new SeedRetryTask(membership,
                List.of(SEED_A), () -> true, scheduler, Duration.ZERO));
    }

    @Test
    void constructor_negativeInterval_throws() {
        assertThrows(IllegalArgumentException.class, () -> new SeedRetryTask(membership,
                List.of(SEED_A), () -> true, scheduler, Duration.ofMillis(-1)));
    }

    // --- lifecycle ---

    @Test
    void start_invokesMembershipStartWhileQuorumLost() throws Exception {
        AtomicBoolean quorumLost = new AtomicBoolean(true);
        SeedRetryTask task = new SeedRetryTask(membership, List.of(SEED_A, SEED_B), quorumLost::get,
                scheduler, Duration.ofMillis(30));
        task.start();
        try {
            // Wait up to 1 second for at least 2 retry invocations
            assertTrue(waitForInvocations(membership, 2, 2_000),
                    "SeedRetryTask must invoke membership.start while quorum is lost; got="
                            + membership.startCount.get());
            assertEquals(List.of(SEED_A, SEED_B), membership.lastSeeds);
        } finally {
            task.stop();
        }
    }

    @Test
    void start_skipsWhenQuorumPresent() throws Exception {
        AtomicBoolean quorumLost = new AtomicBoolean(false);
        SeedRetryTask task = new SeedRetryTask(membership, List.of(SEED_A), quorumLost::get,
                scheduler, Duration.ofMillis(20));
        task.start();
        try {
            Thread.sleep(150);
            assertEquals(0, membership.startCount.get(),
                    "SeedRetryTask must not retry when quorum is present");
        } finally {
            task.stop();
        }
    }

    @Test
    void start_transitionFromLostToPresent_stopsRetrying() throws Exception {
        AtomicBoolean quorumLost = new AtomicBoolean(true);
        SeedRetryTask task = new SeedRetryTask(membership, List.of(SEED_A), quorumLost::get,
                scheduler, Duration.ofMillis(20));
        task.start();
        try {
            assertTrue(waitForInvocations(membership, 1, 1_000),
                    "must retry at least once while quorum lost");
            int afterLost = membership.startCount.get();
            quorumLost.set(false);
            Thread.sleep(150);
            int afterPresent = membership.startCount.get();
            // It's fine if a single in-flight run added to afterLost; no further increments
            // should happen once quorum is present.
            assertTrue(afterPresent - afterLost <= 1,
                    "no further retries after quorum recovers; beforeRecovery=" + afterLost
                            + " afterRecovery=" + afterPresent);
        } finally {
            task.stop();
        }
    }

    @Test
    void start_isIdempotent() throws Exception {
        AtomicBoolean quorumLost = new AtomicBoolean(true);
        SeedRetryTask task = new SeedRetryTask(membership, List.of(SEED_A), quorumLost::get,
                scheduler, Duration.ofMillis(30));
        task.start();
        task.start(); // second start must be a no-op
        try {
            Thread.sleep(120);
            // Roughly 3-4 invocations in 120ms at 30ms cadence; a double-scheduled task would
            // produce ~6-8. Use an upper bound well below the doubled rate.
            assertTrue(membership.startCount.get() <= 6,
                    "redundant start() must not double-schedule; got="
                            + membership.startCount.get());
        } finally {
            task.stop();
        }
    }

    @Test
    void stop_cancelsRetries() throws Exception {
        AtomicBoolean quorumLost = new AtomicBoolean(true);
        SeedRetryTask task = new SeedRetryTask(membership, List.of(SEED_A), quorumLost::get,
                scheduler, Duration.ofMillis(15));
        task.start();
        assertTrue(waitForInvocations(membership, 1, 1_000),
                "task must invoke membership.start at least once before stop");
        task.stop();
        int afterStop = membership.startCount.get();
        Thread.sleep(120);
        int wellAfterStop = membership.startCount.get();
        assertTrue(wellAfterStop - afterStop <= 1,
                "no retries after stop; afterStop=" + afterStop + " later=" + wellAfterStop);
    }

    @Test
    void stop_idempotent_noStart() {
        SeedRetryTask task = new SeedRetryTask(membership, List.of(SEED_A), () -> true, scheduler,
                Duration.ofMillis(30));
        assertDoesNotThrow(task::stop, "stop before start must be a no-op");
        assertDoesNotThrow(task::stop, "stop called twice must be a no-op");
    }

    @Test
    void start_afterStop_noOp() throws Exception {
        AtomicBoolean quorumLost = new AtomicBoolean(true);
        SeedRetryTask task = new SeedRetryTask(membership, List.of(SEED_A), quorumLost::get,
                scheduler, Duration.ofMillis(15));
        task.start();
        task.stop();
        int afterStop = membership.startCount.get();
        task.start(); // re-start after stop: guarded per contract (no-op)
        Thread.sleep(80);
        int later = membership.startCount.get();
        assertEquals(afterStop, later,
                "start after stop must be a no-op; afterStop=" + afterStop + " later=" + later);
    }

    @Test
    void start_catchesThrowingMembershipStart() throws Exception {
        membership.throwOnStart = new IOException("simulated transient failure");
        AtomicBoolean quorumLost = new AtomicBoolean(true);
        SeedRetryTask task = new SeedRetryTask(membership, List.of(SEED_A), quorumLost::get,
                scheduler, Duration.ofMillis(20));
        task.start();
        try {
            assertTrue(waitForInvocations(membership, 3, 2_000),
                    "task must keep retrying after exceptions; got=" + membership.startCount.get());
        } finally {
            task.stop();
        }
    }

    // --- helpers ---

    private static boolean waitForInvocations(RecordingMembership m, int target, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (m.startCount.get() >= target) {
                return true;
            }
            Thread.sleep(10);
        }
        return m.startCount.get() >= target;
    }

    /**
     * Fake {@link MembershipProtocol} recording invocations of {@link #start(List)}.
     */
    private static final class RecordingMembership implements MembershipProtocol {
        final AtomicInteger startCount = new AtomicInteger();
        volatile List<NodeAddress> lastSeeds = List.of();
        volatile Throwable throwOnStart;

        @Override
        public void start(List<NodeAddress> seeds) throws IOException {
            startCount.incrementAndGet();
            lastSeeds = new ArrayList<>(seeds);
            Throwable ex = throwOnStart;
            if (ex != null) {
                if (ex instanceof IOException ioe) {
                    throw ioe;
                }
                if (ex instanceof RuntimeException re) {
                    throw re;
                }
                throw new IOException(ex);
            }
        }

        @Override
        public MembershipView currentView() {
            return new MembershipView(0, Set.of(), Instant.now());
        }

        @Override
        public void addListener(MembershipListener listener) {
        }

        @Override
        public void leave() {
        }

        @Override
        public void close() {
        }
    }
}
