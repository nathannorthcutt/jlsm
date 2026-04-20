package jlsm.engine.cluster;

import jlsm.engine.cluster.internal.InJvmDiscoveryProvider;
import jlsm.engine.cluster.internal.InJvmTransport;
import jlsm.engine.cluster.internal.PhiAccrualFailureDetector;
import jlsm.engine.cluster.internal.RapidMembership;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests covering F04.R39 — async listener notification.
 *
 * <p>
 * Each test drives a two-node cluster and asserts the listener contract does not bleed into the
 * protocol thread (no blocking, no crash, in-order delivery for a single event).
 */
final class RapidMembershipAsyncListenerTest {

    private static final NodeAddress ADDR_1 = new NodeAddress("node-1", "localhost", 10001);
    private static final NodeAddress ADDR_2 = new NodeAddress("node-2", "localhost", 10002);

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @BeforeEach
    void setUp() {
        InJvmTransport.clearRegistry();
        InJvmDiscoveryProvider.clearRegistrations();
    }

    @AfterEach
    void tearDown() throws Exception {
        Exception first = null;
        for (int i = closeables.size() - 1; i >= 0; i--) {
            try {
                closeables.get(i).close();
            } catch (Exception e) {
                if (first == null)
                    first = e;
                else
                    first.addSuppressed(e);
            }
        }
        closeables.clear();
        InJvmTransport.clearRegistry();
        InJvmDiscoveryProvider.clearRegistrations();
        if (first != null)
            throw first;
    }

    // --- Slow listener does not block protocol thread ---

    @Test
    @Timeout(10)
    void notifyListener_isAsynchronous_slowListenerDoesNotBlockProtocolThread() throws Exception {
        var discovery = new InJvmDiscoveryProvider();

        var m1 = createMembership(ADDR_1, discovery);
        var listenerStarted = new CountDownLatch(1);
        var listenerReleased = new AtomicBoolean(false);

        m1.addListener(new MembershipListener() {
            @Override
            public void onViewChanged(MembershipView oldView, MembershipView newView) {
                listenerStarted.countDown();
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    listenerReleased.set(true);
                }
            }

            @Override
            public void onMemberJoined(Member member) {
                listenerStarted.countDown();
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void onMemberLeft(Member member) {
            }

            @Override
            public void onMemberSuspected(Member member) {
            }
        });

        m1.start(List.of());
        discovery.register(ADDR_1);

        var m2 = createMembership(ADDR_2, discovery);

        long t0 = System.nanoTime();
        m2.start(List.of(ADDR_1));
        long startElapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertTrue(startElapsedMs < 500L,
                "m2.start() must return promptly (< 500ms) even while m1's listener sleeps; actual="
                        + startElapsedMs + "ms");

        assertTrue(listenerStarted.await(3, TimeUnit.SECONDS),
                "listener should have started on the dispatcher thread");

        // At this point the listener is still sleeping — it has NOT released yet.
        // The fact that m2.start() returned already demonstrates async dispatch.
        assertFalse(listenerReleased.get(),
                "listener should still be asleep while protocol progresses");
    }

    // --- Throwing listener does not crash protocol thread ---

    @Test
    @Timeout(10)
    void notifyListener_throwingListener_doesNotCrashProtocolThread() throws Exception {
        var discovery = new InJvmDiscoveryProvider();

        var m1 = createMembership(ADDR_1, discovery);
        var throwCount = new AtomicInteger();

        m1.addListener(new MembershipListener() {
            @Override
            public void onViewChanged(MembershipView oldView, MembershipView newView) {
                throwCount.incrementAndGet();
                throw new RuntimeException("listener failure — should be isolated");
            }

            @Override
            public void onMemberJoined(Member member) {
                throw new RuntimeException("join listener failure — should be isolated");
            }

            @Override
            public void onMemberLeft(Member member) {
            }

            @Override
            public void onMemberSuspected(Member member) {
            }
        });

        m1.start(List.of());
        discovery.register(ADDR_1);

        var m2 = createMembership(ADDR_2, discovery);
        m2.start(List.of(ADDR_1));

        // Allow the protocol to converge and the dispatcher to deliver the failing callback.
        Thread.sleep(500);

        // The protocol must still be operational — a second join must still be observable
        // from node-1's view.
        MembershipView v1 = m1.currentView();
        assertTrue(v1.isMember(ADDR_2),
                "protocol must still process messages after listener threw; view=" + v1);

        assertTrue(throwCount.get() >= 1,
                "throwing listener should have been invoked at least once");
    }

    // --- Ordering preserved across listeners for a single event ---

    @Test
    @Timeout(10)
    void notifyListener_orderingPreservedForSingleEvent() throws Exception {
        var discovery = new InJvmDiscoveryProvider();
        var m1 = createMembership(ADDR_1, discovery);

        var order = new CopyOnWriteArrayList<Integer>();
        var latch = new CountDownLatch(3);

        for (int i = 0; i < 3; i++) {
            final int id = i;
            m1.addListener(new MembershipListener() {
                @Override
                public void onViewChanged(MembershipView oldView, MembershipView newView) {
                    order.add(id);
                    latch.countDown();
                }

                @Override
                public void onMemberJoined(Member member) {
                }

                @Override
                public void onMemberLeft(Member member) {
                }

                @Override
                public void onMemberSuspected(Member member) {
                }
            });
        }

        m1.start(List.of());
        discovery.register(ADDR_1);

        var m2 = createMembership(ADDR_2, discovery);
        m2.start(List.of(ADDR_1));

        assertTrue(latch.await(3, TimeUnit.SECONDS),
                "all 3 listeners should see the view change; observed=" + order);

        // Take first 3 entries (there may be multiple view changes; only the first batch
        // for the first event is what's ordering-relevant for a single event). Since a
        // single-thread dispatcher invokes siblings in submission order, the first 3 entries
        // must be 0,1,2 in registration order.
        assertEquals(List.of(0, 1, 2), order.subList(0, 3),
                "siblings must see the event in registration order; observed=" + order);
    }

    // --- Helpers ---

    private RapidMembership createMembership(NodeAddress addr, DiscoveryProvider discovery) {
        var transport = new InJvmTransport(addr);
        closeables.add(transport);
        var config = ClusterConfig.builder().protocolPeriod(Duration.ofMillis(100))
                .pingTimeout(Duration.ofMillis(50)).build();
        var detector = new PhiAccrualFailureDetector(10);
        var m = new RapidMembership(addr, transport, discovery, config, detector);
        closeables.add(m);
        return m;
    }
}
