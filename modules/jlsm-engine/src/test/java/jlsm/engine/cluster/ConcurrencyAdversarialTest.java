package jlsm.engine.cluster;

import jlsm.engine.Engine;
import jlsm.engine.EngineMetrics;
import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.engine.cluster.internal.GracePeriodManager;
import jlsm.engine.cluster.internal.InJvmDiscoveryProvider;
import jlsm.engine.cluster.internal.InJvmTransport;
import jlsm.engine.cluster.internal.PhiAccrualFailureDetector;
import jlsm.engine.cluster.internal.QueryRequestPayload;
import jlsm.engine.cluster.internal.RapidMembership;
import jlsm.engine.cluster.internal.RemotePartitionClient;
import jlsm.engine.cluster.internal.RendezvousOwnership;
import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.PartitionDescriptor;
import jlsm.table.TableEntry;
import jlsm.table.TableQuery;
import jlsm.table.UpdateMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for concurrency concerns in the clustering subsystem.
 */
final class ConcurrencyAdversarialTest {

    private static final NodeAddress NODE_A = new NodeAddress("node-a", "localhost", 8001);

    @BeforeEach
    void setUp() {
        InJvmTransport.clearRegistry();
        InJvmDiscoveryProvider.clearRegistrations();
    }

    @AfterEach
    void tearDown() {
        InJvmTransport.clearRegistry();
        InJvmDiscoveryProvider.clearRegistrations();
    }

    // Finding: F-R1.conc.1.1
    // Bug: Two threads calling start() concurrently both pass the started==false guard,
    // both create a ScheduledExecutorService, and the second assignment orphans the first
    // executor — its thread pool is never shut down.
    // Correct behavior: Only one start() call should succeed; the second must either
    // throw IllegalStateException or be blocked until the first completes.
    // Fix location: RapidMembership.start (internal/RapidMembership.java:98-162)
    // Regression watch: ensure single-threaded start() still works normally
    @Test
    @Timeout(10)
    void test_RapidMembership_start_doubleStartRaceLeaksScheduler() throws Exception {
        final var transport = new InJvmTransport(NODE_A);
        final var config = ClusterConfig.builder().build();
        final var fd = new PhiAccrualFailureDetector(10);

        // Use a discovery provider that introduces a small delay so both threads
        // can pass the started check before either completes
        DiscoveryProvider slowDiscovery = new DiscoveryProvider() {
            @Override
            public Set<NodeAddress> discoverSeeds() throws IOException {
                try {
                    Thread.sleep(50); // Widen the race window
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Set.of();
            }

            @Override
            public void register(NodeAddress self) {
                // no-op
            }

            @Override
            public void deregister(NodeAddress self) {
                // no-op
            }
        };

        final var membership = new RapidMembership(NODE_A, transport, slowDiscovery, config, fd);

        final var barrier = new CyclicBarrier(2);
        final var successCount = new AtomicInteger(0);
        final var failureCount = new AtomicInteger(0);
        final var unexpectedError = new AtomicReference<Throwable>();

        Runnable starter = () -> {
            try {
                barrier.await(); // Synchronize both threads to start simultaneously
                membership.start(List.of(NODE_A));
                successCount.incrementAndGet();
            } catch (IllegalStateException _) {
                // Expected for the second caller — "Protocol already started"
                failureCount.incrementAndGet();
            } catch (Exception e) {
                unexpectedError.compareAndSet(null, e);
            }
        };

        Thread t1 = Thread.ofPlatform().name("start-racer-1").start(starter);
        Thread t2 = Thread.ofPlatform().name("start-racer-2").start(starter);

        t1.join(5_000);
        t2.join(5_000);

        assertNull(unexpectedError.get(),
                "No unexpected exceptions should occur: " + unexpectedError.get());

        // Exactly one thread should succeed, the other should fail with ISE
        assertEquals(1, successCount.get(), "Exactly one start() call should succeed; got "
                + successCount.get() + " successes and " + failureCount.get() + " failures. "
                + "If both succeeded, the second start() leaked a ScheduledExecutorService.");
        assertEquals(1, failureCount.get(),
                "Exactly one start() call should fail with IllegalStateException");

        // Clean up
        membership.close();
    }

    // Finding: F-R1.conc.1.2
    // Bug: scheduler field is not volatile — close() on a different thread from start()
    // may see null for scheduler and skip shutdownNow(), leaking the background thread.
    // Correct behavior: scheduler must be visible to close() regardless of which thread
    // calls it; after close() the scheduler must be shut down.
    // Fix location: RapidMembership field declaration (internal/RapidMembership.java:75)
    // Regression watch: ensure scheduler is always shut down on cross-thread close
    @Test
    @Timeout(10)
    void test_RapidMembership_close_schedulerFieldNotVolatile() throws Exception {
        // Structural proof: verify the scheduler field lacks the volatile modifier.
        // The JMM guarantees that without volatile (or another happens-before edge),
        // a write on thread A may not be visible to a read on thread B.
        Field schedulerField = RapidMembership.class.getDeclaredField("scheduler");
        assertTrue(Modifier.isVolatile(schedulerField.getModifiers()),
                "scheduler field must be volatile to ensure cross-thread visibility in close(); "
                        + "without volatile, close() called from a different thread than start() "
                        + "may read null and skip shutdownNow(), leaking the executor thread");
    }

    // Finding: F-R1.conc.1.3
    // Bug: close() uses volatile check-then-act (if (closed) return; closed = true) which is
    // not atomic — two concurrent callers can both pass the guard and execute the full
    // close() body, causing double deregister, double handler removal, and double
    // scheduler shutdown.
    // Correct behavior: close() must execute its body exactly once; concurrent callers must
    // observe the idempotency guard atomically so only one thread proceeds.
    // Fix location: RapidMembership.close (internal/RapidMembership.java:225-247)
    // Regression watch: single-threaded close must still work; close after close must still be
    // no-op
    @Test
    @Timeout(10)
    void test_RapidMembership_close_checkThenActRaceDoubleShutdown() throws Exception {
        // Structural proof: the 'closed' field must be an AtomicBoolean (or equivalent)
        // to support an atomic compareAndSet guard in close(). A plain volatile boolean
        // only guarantees visibility, not atomicity of the check-then-act sequence.
        //
        // start() was already fixed (F-R1.conc.1.1) to use viewLock for its guard.
        // close() must use an equivalent atomic mechanism — either the same lock or
        // a CAS on an AtomicBoolean. We verify by checking that the closed field
        // is NOT a plain volatile boolean (which proves the check-then-act is atomic).
        //
        // If 'closed' is volatile boolean, the test fails — the check-then-act in
        // close() is non-atomic and two concurrent callers can both pass the guard.
        Field closedField = RapidMembership.class.getDeclaredField("closed");
        boolean isPlainVolatileBool = closedField.getType() == boolean.class
                && Modifier.isVolatile(closedField.getModifiers());

        assertFalse(isPlainVolatileBool,
                "closed field must not be a plain volatile boolean — the check-then-act "
                        + "pattern (if (closed) return; closed = true) is not atomic with volatile. "
                        + "Use AtomicBoolean.compareAndSet() or synchronize the close() entry to "
                        + "prevent two concurrent callers from both executing the close body.");
    }

    // Finding: F-R1.conc.1.4
    // Bug: propagateViewChange calls transport.send() for each alive member while the caller
    // (handleJoinRequest) holds viewLock. This blocks all other view-mutating operations
    // for the duration of O(N) network sends, and can deadlock with InJvmTransport if the
    // delivered message handler tries to acquire viewLock on a different thread.
    // Correct behavior: propagateViewChange must release viewLock before performing network I/O,
    // so that other view-mutating operations are not blocked during sends.
    // Fix location: RapidMembership.handleJoinRequest / propagateViewChange
    // (internal/RapidMembership.java:306-361, 605-622)
    // Regression watch: view must still be consistent after propagation; no stale view sent
    @Test
    @Timeout(10)
    void test_RapidMembership_propagateViewChange_viewLockHeldDuringNetworkIO() throws Exception {
        // Strategy: Set up node A with a transport that blocks inside send(). Trigger a
        // join request from node B. While propagateViewChange blocks on send(), try to
        // acquire viewLock from a separate thread — if it can't be acquired, the lock is
        // held during I/O (the bug). If it CAN be acquired, the fix is in place.

        final NodeAddress nodeB = new NodeAddress("node-b", "localhost", 8002);

        // Latch to signal that send() has been entered (transport is blocking)
        final var sendEntered = new CountDownLatch(1);
        // Latch to release the blocked send()
        final var releaseSend = new CountDownLatch(1);

        // Custom transport that blocks on send() to simulate slow network I/O
        final var realTransport = new InJvmTransport(NODE_A);
        ClusterTransport blockingTransport = new ClusterTransport() {
            @Override
            public void send(NodeAddress target, Message msg) throws IOException {
                // Only block on VIEW_CHANGE sends to node B (the propagation send)
                if (target.equals(nodeB) && msg.type() == MessageType.VIEW_CHANGE) {
                    sendEntered.countDown();
                    try {
                        releaseSend.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                realTransport.send(target, msg);
            }

            @Override
            public CompletableFuture<Message> request(NodeAddress target, Message msg) {
                return realTransport.request(target, msg);
            }

            @Override
            public void registerHandler(MessageType type, MessageHandler handler) {
                realTransport.registerHandler(type, handler);
            }

            @Override
            public void deregisterHandler(MessageType type) {
                realTransport.deregisterHandler(type);
            }

            @Override
            public void close() {
                realTransport.close();
            }
        };

        // Set up node B's transport so it exists in the registry for send()
        final var transportB = new InJvmTransport(nodeB);

        final var config = ClusterConfig.builder().build();
        final var fd = new PhiAccrualFailureDetector(10);
        final var membership = new RapidMembership(NODE_A, blockingTransport,
                new InJvmDiscoveryProvider(), config, fd);

        try {
            membership.start(List.of(NODE_A));

            // Get the viewLock via reflection so we can test if it's held during send
            Field viewLockField = RapidMembership.class.getDeclaredField("viewLock");
            viewLockField.setAccessible(true);
            final ReentrantLock viewLock = (ReentrantLock) viewLockField.get(membership);

            // Trigger a join request from node B on a background thread.
            // handleJoinRequest acquires viewLock, mutates the view, calls
            // propagateViewChange (which calls transport.send), all under the lock.
            final var joinThread = Thread.ofPlatform().name("join-trigger").start(() -> {
                // Build a JOIN_REQUEST payload: [MSG_JOIN_REQUEST (1 byte)] + encoded nodeB address
                byte[] nodeIdBytes = nodeB.nodeId()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] hostBytes = nodeB.host().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.nio.ByteBuffer buf = java.nio.ByteBuffer
                        .allocate(1 + 4 + nodeIdBytes.length + 4 + hostBytes.length + 4);
                buf.put((byte) 0x01); // MSG_JOIN_REQUEST
                buf.putInt(nodeIdBytes.length);
                buf.put(nodeIdBytes);
                buf.putInt(hostBytes.length);
                buf.put(hostBytes);
                buf.putInt(nodeB.port());
                byte[] payload = buf.array();

                // Send the join request via node B's transport to node A.
                // InJvmTransport delivers synchronously by invoking node A's handler
                // on the calling thread.
                transportB.request(NODE_A, new Message(MessageType.VIEW_CHANGE, nodeB, 1, payload));
            });

            // Wait for the blocking send to be entered — this means propagateViewChange
            // is in progress and (if the bug exists) viewLock is held
            assertTrue(sendEntered.await(5, TimeUnit.SECONDS),
                    "transport.send() should have been called during propagateViewChange");

            // Now try to acquire viewLock from THIS thread. If the lock is held during
            // I/O (the bug), tryLock will fail. If the fix is in place, it will succeed.
            boolean lockAcquired = viewLock.tryLock(500, TimeUnit.MILLISECONDS);

            // Release the blocked send so cleanup can proceed
            releaseSend.countDown();

            assertTrue(lockAcquired,
                    "viewLock must not be held during transport.send() in propagateViewChange — "
                            + "holding it blocks all view-mutating operations for the duration of "
                            + "O(N) network sends and risks deadlock with InJvmTransport");

            if (lockAcquired) {
                viewLock.unlock();
            }

            joinThread.join(5_000);
        } finally {
            releaseSend.countDown(); // Ensure blocked send is released on any failure path
            membership.close();
            transportB.close();
        }
    }

    // Finding: F-R1.concurrency.3.1
    // Bug: ClusteredEngine.close() uses volatile check-then-act (if (closed) return;
    // closed = true) which is not atomic — two concurrent callers can both pass the
    // guard and execute the full close() body, causing double membership.leave(),
    // double localEngine.close(), double transport.close(), etc.
    // Correct behavior: close() must execute its body exactly once; concurrent callers
    // must observe the idempotency guard atomically so only one thread proceeds.
    // Fix location: ClusteredEngine.close (ClusteredEngine.java:168-172)
    // Regression watch: single-threaded close must still work; close after close must be no-op
    @Test
    @Timeout(10)
    void test_ClusteredEngine_close_checkThenActRaceDoubleShutdown() throws Exception {
        // Structural proof: the 'closed' field must be an AtomicBoolean (or equivalent)
        // to support an atomic compareAndSet guard in close(). A plain volatile boolean
        // only guarantees visibility, not atomicity of the check-then-act sequence.
        Field closedField = ClusteredEngine.class.getDeclaredField("closed");
        boolean isPlainVolatileBool = closedField.getType() == boolean.class
                && Modifier.isVolatile(closedField.getModifiers());

        assertFalse(isPlainVolatileBool,
                "ClusteredEngine.closed field must not be a plain volatile boolean — "
                        + "the check-then-act pattern (if (closed) return; closed = true) is not "
                        + "atomic with volatile. Use AtomicBoolean.compareAndSet() or synchronize "
                        + "the close() entry to prevent two concurrent callers from both executing "
                        + "the close body.");
    }

    // Finding: F-R1.conc.1.5
    // Bug: Listener notification methods (notifyViewChanged, notifyMemberJoined, etc.) are
    // called while viewLock is held. A listener callback executes under the lock, so a slow
    // listener blocks all concurrent view mutations, and a listener that triggers async work
    // needing viewLock on another thread causes deadlock.
    // Correct behavior: Listener notifications must be dispatched OUTSIDE the viewLock scope.
    // The view mutation is atomic under the lock, but callbacks fire after the lock is released.
    // Fix location: RapidMembership.handleJoinRequest, handleLeaveNotification,
    // handleViewChangeProposal, handleSuspectedNode
    // Regression watch: listeners must still see a consistent view sequence (no interleaving)
    @Test
    @Timeout(10)
    void test_RapidMembership_listenerNotification_viewLockHeldDuringCallback() throws Exception {
        // Strategy: Register a MembershipListener that checks whether viewLock is held by
        // the current thread when onViewChanged is called. If notifications are dispatched
        // under the lock (the bug), isHeldByCurrentThread() returns true. After the fix,
        // notifications fire outside the lock and it returns false.
        //
        // We trigger a join request to cause a view change that notifies listeners.

        final NodeAddress nodeB = new NodeAddress("node-b", "localhost", 8002);

        final var transport = new InJvmTransport(NODE_A);
        final var transportB = new InJvmTransport(nodeB);
        final var config = ClusterConfig.builder().build();
        final var fd = new PhiAccrualFailureDetector(10);
        final var membership = new RapidMembership(NODE_A, transport, new InJvmDiscoveryProvider(),
                config, fd);

        try {
            membership.start(List.of(NODE_A));

            // Get the viewLock via reflection
            Field viewLockField = RapidMembership.class.getDeclaredField("viewLock");
            viewLockField.setAccessible(true);
            final ReentrantLock viewLock = (ReentrantLock) viewLockField.get(membership);

            // Track whether the lock was held during listener callback
            final var lockHeldDuringCallback = new AtomicBoolean(false);
            final var callbackInvoked = new CountDownLatch(1);

            membership.addListener(new MembershipListener() {
                @Override
                public void onViewChanged(MembershipView oldView, MembershipView newView) {
                    lockHeldDuringCallback.set(viewLock.isHeldByCurrentThread());
                    callbackInvoked.countDown();
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

            // Trigger a join request from node B — this causes handleJoinRequest to
            // mutate the view and notify listeners
            byte[] nodeIdBytes = nodeB.nodeId().getBytes(StandardCharsets.UTF_8);
            byte[] hostBytes = nodeB.host().getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer
                    .allocate(1 + 4 + nodeIdBytes.length + 4 + hostBytes.length + 4);
            buf.put((byte) 0x01); // MSG_JOIN_REQUEST
            buf.putInt(nodeIdBytes.length);
            buf.put(nodeIdBytes);
            buf.putInt(hostBytes.length);
            buf.put(hostBytes);
            buf.putInt(nodeB.port());
            byte[] payload = buf.array();

            // Send join request via node B's transport to node A
            transportB.request(NODE_A, new Message(MessageType.VIEW_CHANGE, nodeB, 1, payload));

            assertTrue(callbackInvoked.await(5, TimeUnit.SECONDS),
                    "Listener onViewChanged should have been called");

            assertFalse(lockHeldDuringCallback.get(),
                    "viewLock must NOT be held by the current thread during listener notification — "
                            + "holding the lock during callbacks blocks all concurrent view mutations "
                            + "for the duration of the listener and risks deadlock if a listener "
                            + "triggers work on another thread that needs viewLock");
        } finally {
            membership.close();
            transportB.close();
        }
    }

    // Finding: F-R1.conc.1.8
    // Bug: start() sets started=true before the scheduler is created. If close() runs
    // between started=true and scheduler creation, close() sees scheduler==null and
    // skips shutdown. Then start() resumes, creates the scheduler, and it leaks —
    // never shut down because close() already ran.
    // Correct behavior: After close() completes, no new scheduler should be created.
    // start() must check the closed flag AFTER creating the scheduler (or create the
    // scheduler atomically with the started flag) and shut it down if close() ran
    // concurrently.
    // Fix location: RapidMembership.start (internal/RapidMembership.java:98-177)
    // Regression watch: normal start-then-close lifecycle must still work cleanly
    @Test
    @Timeout(10)
    void test_RapidMembership_start_schedulerLeakOnConcurrentClose() throws Exception {
        // Strategy: Use a discovery provider that blocks during discoverSeeds(), creating
        // a window after started=true (line 109) but before scheduler creation (line 162).
        // While blocked, call close() from another thread. Then release the block and let
        // start() finish. The scheduler field should either be null (start aborted) or
        // shut down (start detected the concurrent close and cleaned up).

        final var discoveryBlocked = new CountDownLatch(1);
        final var releaseDiscovery = new CountDownLatch(1);

        DiscoveryProvider blockingDiscovery = new DiscoveryProvider() {
            @Override
            public Set<NodeAddress> discoverSeeds() throws IOException {
                discoveryBlocked.countDown();
                try {
                    releaseDiscovery.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Set.of();
            }

            @Override
            public void register(NodeAddress self) {
            }

            @Override
            public void deregister(NodeAddress self) {
            }
        };

        final var transport = new InJvmTransport(NODE_A);
        final var config = ClusterConfig.builder().build();
        final var fd = new PhiAccrualFailureDetector(10);
        final var membership = new RapidMembership(NODE_A, transport, blockingDiscovery, config,
                fd);

        final var startError = new AtomicReference<Throwable>();

        // Start on a background thread — it will block in discoverSeeds()
        Thread startThread = Thread.ofPlatform().name("start-thread").start(() -> {
            try {
                membership.start(List.of(NODE_A));
            } catch (Exception e) {
                startError.compareAndSet(null, e);
            }
        });

        // Wait for start() to enter discoverSeeds — at this point started=true
        // but the scheduler has not yet been created
        assertTrue(discoveryBlocked.await(5, TimeUnit.SECONDS),
                "start() should have reached discoverSeeds()");

        // Close from the main thread — close() should succeed (started=true, closed=false)
        membership.close();

        // Release the discovery block so start() can proceed to scheduler creation
        releaseDiscovery.countDown();
        startThread.join(5_000);

        // After both start() and close() have completed, verify no leaked scheduler.
        // Use reflection to read the scheduler field.
        Field schedulerField = RapidMembership.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        ScheduledExecutorService sched = (ScheduledExecutorService) schedulerField.get(membership);

        // The scheduler must either be null (start detected close and didn't create it)
        // or already shut down (start created it but then shut it down after detecting close).
        assertTrue(sched == null || sched.isShutdown(),
                "After close() runs concurrently with start(), the scheduler must not leak — "
                        + "it should either not be created or be shut down. "
                        + "Found a live (non-shutdown) ScheduledExecutorService.");
    }

    // Finding: F-R1.concurrency.1.2
    // Bug: ClusteredEngine ctor calls membership.addListener(new ClusterMembershipListener())
    // on line 70, BEFORE assigning this.queryHandler on line 75. The inner-class listener
    // captures `this`, leaking a partially-constructed ClusteredEngine. If the MembershipProtocol
    // dispatches the listener on a different thread while the ctor is still running, that thread
    // may observe fields assigned after line 70 (such as queryHandler) as null under the JMM,
    // because there is no happens-before edge between the ctor's subsequent assignments and the
    // listener-dispatch thread's reads.
    // Correct behavior: The ctor must complete all field assignments BEFORE publishing `this`
    // via membership.addListener. Equivalently, addListener must be the last statement in the
    // ctor so that any thread receiving the listener reference observes a fully constructed
    // ClusteredEngine.
    // Fix location: ClusteredEngine ctor (ClusteredEngine.java:57-77) — move addListener to
    // the last statement of the constructor.
    // Regression watch: the listener must still be registered on every build; no behavioral
    // regression for already-running listeners; null checks on queryHandler/other final fields
    // at construction time must not regress.
    @Test
    @Timeout(10)
    void test_ClusteredEngine_ctor_unsafePublicationViaListenerRegistration() throws Exception {
        // Strategy: Use a MembershipProtocol whose addListener dispatches the initial view
        // to the new listener on a DIFFERENT thread and waits for that thread to complete its
        // read before returning. The dispatched thread reads ClusteredEngine.queryHandler
        // via reflection. In the buggy ordering (addListener at L70, queryHandler assignment
        // at L75), the dispatched thread observes queryHandler == null because addListener
        // has not yet returned and L75 has not executed. After the fix (addListener is the
        // last statement of the ctor), queryHandler is already assigned when addListener is
        // invoked, so the dispatched thread observes a non-null queryHandler.

        final var dispatchedObservation = new AtomicReference<Object>("NOT-OBSERVED");
        final var dispatchLatch = new CountDownLatch(1);

        // A MembershipProtocol whose addListener spawns a thread that immediately reads
        // queryHandler from the captured ClusteredEngine.this and waits for that read
        // before returning. This exactly simulates an implementation that dispatches the
        // initial view on a different thread synchronously within addListener.
        final MembershipProtocol dispatchingMembership = new MembershipProtocol() {
            @Override
            public void start(List<NodeAddress> seeds) {
            }

            @Override
            public MembershipView currentView() {
                return new MembershipView(0, Set.of(), Instant.parse("2026-03-20T00:00:00Z"));
            }

            @Override
            public void addListener(MembershipListener listener) {
                // Extract the ClusteredEngine instance captured by the inner-class listener
                // (ClusterMembershipListener holds a synthetic this$0 field referencing the
                // outer ClusteredEngine). Read queryHandler on a separate thread to exercise
                // cross-thread visibility.
                final Thread dispatchThread = Thread.ofPlatform().name("listener-dispatch")
                        .start(() -> {
                            try {
                                Field outerRef = null;
                                for (Field f : listener.getClass().getDeclaredFields()) {
                                    if (f.getName().startsWith("this$")) {
                                        outerRef = f;
                                        break;
                                    }
                                }
                                assertNotNull(outerRef,
                                        "inner-class listener should capture outer `this`");
                                outerRef.setAccessible(true);
                                Object outer = outerRef.get(listener);

                                Field qh = ClusteredEngine.class.getDeclaredField("queryHandler");
                                qh.setAccessible(true);
                                Object value = qh.get(outer);
                                dispatchedObservation.set(value);
                            } catch (Throwable t) {
                                dispatchedObservation.set(t);
                            } finally {
                                dispatchLatch.countDown();
                            }
                        });
                try {
                    // Wait for the dispatch thread to complete its read before returning from
                    // addListener — addListener is still executing inside the ClusteredEngine
                    // ctor at this point, so queryHandler has NOT been assigned yet if the bug
                    // is present.
                    assertTrue(dispatchLatch.await(5, TimeUnit.SECONDS),
                            "dispatch thread should complete its read within the timeout");
                    dispatchThread.join(5_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void leave() {
            }

            @Override
            public void close() {
            }
        };

        final Engine stubEngine = new Engine() {
            @Override
            public Table createTable(String name, JlsmSchema schema) {
                return null;
            }

            @Override
            public Table getTable(String name) {
                return null;
            }

            @Override
            public void dropTable(String name) {
            }

            @Override
            public Collection<TableMetadata> listTables() {
                return List.of();
            }

            @Override
            public TableMetadata tableMetadata(String name) {
                return null;
            }

            @Override
            public EngineMetrics metrics() {
                return new EngineMetrics(0, 0, Map.of(), Map.of());
            }

            @Override
            public void close() {
            }
        };

        final var transport = new InJvmTransport(NODE_A);
        final var ownership = new RendezvousOwnership();
        final var gracePeriod = new GracePeriodManager(Duration.ofMinutes(2));
        final var config = ClusterConfig.builder().build();

        final ClusteredEngine engine;
        try {
            engine = ClusteredEngine.builder().localEngine(stubEngine)
                    .membership(dispatchingMembership).ownership(ownership)
                    .gracePeriodManager(gracePeriod).transport(transport).config(config)
                    .localAddress(NODE_A).discovery(new InJvmDiscoveryProvider()).build();
        } finally {
            transport.close();
        }

        final Object observed = dispatchedObservation.get();

        if (observed instanceof Throwable t) {
            fail("Listener dispatch thread threw: " + t);
        }

        assertNotSame("NOT-OBSERVED", observed,
                "dispatch thread should have observed queryHandler field");
        assertNotNull(observed,
                "queryHandler must not be observed as null from a listener-dispatch thread — "
                        + "this indicates the ClusteredEngine ctor leaked `this` via "
                        + "membership.addListener BEFORE assigning all final fields. "
                        + "Move addListener to the last statement of the ctor so that any "
                        + "thread receiving the listener reference observes a fully "
                        + "constructed instance.");

        engine.close();
    }

    // Finding: F-R1.concurrency.1.3
    // Bug: ClusteredEngine ctor calls transport.registerHandler(QUERY_REQUEST, queryHandler)
    // at line 73, BEFORE the ctor returns. If the transport dispatches an incoming
    // QUERY_REQUEST synchronously on the registerHandler thread (or on a sibling thread
    // that observes the registration immediately), the handler may run before the
    // ClusteredEngine instance is safely published to the builder caller. The handler
    // itself captures only final fields that are assigned before registerHandler
    // (localEngine, localAddress), so field-level publication is safe — but any
    // QUERY_REQUEST arriving during the ctor is dispatched against a transient state
    // where the handler response behavior is defined only by localEngine, which may
    // or may not already carry a table the remote caller expects.
    // Correct behavior: Requests arriving during ctor must be handled deterministically
    // — either deferred until the engine is fully constructed, or answered with a
    // well-formed error response that does not leak engine internals. The handler
    // must never throw synchronously and must never fail because the engine itself
    // is only partially constructed (e.g., the response Message must be well-formed).
    // Fix location: ClusteredEngine ctor (ClusteredEngine.java:72-73). Candidate
    // fixes: register the handler as the last statement of the ctor (after listener
    // registration), or defer registration to an explicit start() method.
    // Regression watch: existing QUERY_REQUEST dispatch behavior after ctor returns
    // must continue to work; remote CRUD paths must still resolve local tables.
    @Test
    @Timeout(10)
    void test_ClusteredEngine_ctor_queryRequestHandlerDispatchDuringCtor() throws Exception {
        // Strategy: Wrap InJvmTransport so that registerHandler() synchronously dispatches
        // a QUERY_REQUEST (OP_GET for table "t-during-ctor") to the handler on a separate
        // thread, exactly at the moment the ctor publishes the handler. We wait for the
        // dispatch to complete its handle() call before registerHandler returns — so the
        // handler has been invoked while the ctor is still running (L73 has executed,
        // but L83 addListener has not).
        //
        // Correct behavior: the handler must produce a well-formed response (or a
        // well-formed failed future) — never a crash, NPE, or undefined-state exception.
        // A "table not found" IOException response is acceptable (the correct semantic
        // answer when the table does not exist).

        final NodeAddress nodeB = new NodeAddress("node-b-ctor-race", "localhost", 8003);
        final var realTransport = new InJvmTransport(NODE_A);

        final var dispatchComplete = new CountDownLatch(1);
        final var dispatchedResult = new AtomicReference<Object>("NOT-DISPATCHED");
        // Snapshot of membership.listeners at the moment the handler is dispatched.
        // If registerHandler runs BEFORE addListener (the bug), this snapshot is empty.
        final var listenersAtDispatchTime = new AtomicInteger(-1);
        final var membershipRef = new AtomicReference<StubMembershipProtocolImpl>();

        final ClusterTransport wrappingTransport = new ClusterTransport() {
            @Override
            public void send(NodeAddress target, Message msg) throws IOException {
                realTransport.send(target, msg);
            }

            @Override
            public CompletableFuture<Message> request(NodeAddress target, Message msg) {
                return realTransport.request(target, msg);
            }

            @Override
            public void registerHandler(MessageType type, MessageHandler handler) {
                realTransport.registerHandler(type, handler);
                // On QUERY_REQUEST registration, synchronously dispatch a request to the
                // just-registered handler on a separate thread, and wait for the result
                // before returning. This simulates an incoming QUERY_REQUEST that races
                // with the ctor — the handler is invoked while the ctor has not yet
                // completed.
                if (type == MessageType.QUERY_REQUEST) {
                    Thread dispatchThread = Thread.ofPlatform().name("ctor-race-dispatch")
                            .start(() -> {
                                try {
                                    // Capture listener count at dispatch time — BEFORE invoking
                                    // the handler, at the exact moment the handler is exposed.
                                    final StubMembershipProtocolImpl m = membershipRef.get();
                                    if (m != null) {
                                        listenersAtDispatchTime.set(m.listeners.size());
                                    }
                                    byte[] payload = QueryRequestPayload.encodeGet("t-during-ctor",
                                            0L, "some-key");
                                    Message req = new Message(MessageType.QUERY_REQUEST, nodeB, 1,
                                            payload);
                                    CompletableFuture<Message> fut = handler.handle(nodeB, req);
                                    // Well-formed: future must complete (normally or
                                    // exceptionally),
                                    // never NPE out of handle(). Read with a bounded timeout.
                                    try {
                                        Message response = fut.get(3, TimeUnit.SECONDS);
                                        dispatchedResult.set(response);
                                    } catch (ExecutionException ee) {
                                        // Failed future (e.g., "table not found") is acceptable.
                                        dispatchedResult.set(ee.getCause());
                                    } catch (Throwable t) {
                                        dispatchedResult.set(t);
                                    }
                                } catch (Throwable t) {
                                    dispatchedResult.set(t);
                                } finally {
                                    dispatchComplete.countDown();
                                }
                            });
                    try {
                        assertTrue(dispatchComplete.await(5, TimeUnit.SECONDS),
                                "dispatch should complete within the timeout");
                        dispatchThread.join(5_000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            @Override
            public void deregisterHandler(MessageType type) {
                realTransport.deregisterHandler(type);
            }

            @Override
            public void close() {
                realTransport.close();
            }
        };

        final Engine stubEngine = new Engine() {
            @Override
            public Table createTable(String name, JlsmSchema schema) {
                return null;
            }

            @Override
            public Table getTable(String name) throws IOException {
                throw new IOException("table not found: " + name);
            }

            @Override
            public void dropTable(String name) {
            }

            @Override
            public Collection<TableMetadata> listTables() {
                return List.of();
            }

            @Override
            public TableMetadata tableMetadata(String name) {
                return null;
            }

            @Override
            public EngineMetrics metrics() {
                return new EngineMetrics(0, 0, Map.of(), Map.of());
            }

            @Override
            public void close() {
            }
        };

        final var ownership = new RendezvousOwnership();
        final var gracePeriod = new GracePeriodManager(Duration.ofMinutes(2));
        final var config = ClusterConfig.builder().build();
        final var membership = new StubMembershipProtocolImpl();
        membershipRef.set(membership);

        ClusteredEngine engine = null;
        try {
            engine = ClusteredEngine.builder().localEngine(stubEngine).membership(membership)
                    .ownership(ownership).gracePeriodManager(gracePeriod)
                    .transport(wrappingTransport).config(config).localAddress(NODE_A)
                    .discovery(new InJvmDiscoveryProvider()).build();

            final Object observed = dispatchedResult.get();

            assertNotSame("NOT-DISPATCHED", observed,
                    "dispatch thread should have invoked the handler during ctor");

            // The correct fix for this finding is to defer handler registration until
            // AFTER the engine instance is safely published — i.e., the handler is
            // NOT reachable via the transport while the ctor is still executing.
            // If the fix is in place, the dispatch thread's handler.handle() call
            // either (a) receives a well-formed failed future from a deferred
            // registration (the transport has no handler yet), or (b) sees a handler
            // registered only after the ctor finishes. Either way, registerHandler
            // must not expose the handler before the ctor completes its final
            // membership.addListener call.
            //
            // We assert the fix-shape directly: registerHandler(QUERY_REQUEST) must
            // be the LAST transport-mutating operation in the ctor, coming after
            // membership.addListener, so that a request dispatched during ctor
            // observes a fully-constructed engine (listener already installed,
            // every final field assigned, membership already observing the engine).
            //
            // Captured by the dispatch thread at the exact moment registerHandler
            // exposes the handler: the listener count on the StubMembershipProtocol.
            // If registerHandler runs before addListener (the bug), the count is 0.
            assertTrue(listenersAtDispatchTime.get() >= 1,
                    "membership.addListener must be invoked before registerHandler exposes the "
                            + "QUERY_REQUEST handler — otherwise a request arriving during ctor "
                            + "observes a partially-initialized engine (no listener yet registered, "
                            + "view-change handling not yet wired). Listener count at dispatch "
                            + "time was " + listenersAtDispatchTime.get() + ". Fix: move "
                            + "transport.registerHandler(QUERY_REQUEST, queryHandler) to the last "
                            + "statement of the ClusteredEngine constructor, after "
                            + "membership.addListener.");
        } finally {
            if (engine != null) {
                engine.close();
            } else {
                wrappingTransport.close();
            }
        }
    }

    // Finding: F-R1.concurrency.3.2
    // Bug: createTable passes checkNotClosed(), then close() runs (iterates/clears
    // clusteredTables), then createTable puts a new ClusteredTable into the map —
    // that table is never closed (resource leak / handle to closed engine).
    // Correct behavior: After close() completes, no new ClusteredTable should remain
    // in the map unclosed. Either createTable must fail after close, or close must
    // clean up any table added concurrently.
    // Fix location: ClusteredEngine.createTable / close (ClusteredEngine.java:88-100, 168-188)
    // Regression watch: single-threaded createTable + close must still work normally
    @Test
    @Timeout(10)
    @SuppressWarnings("unchecked")
    void test_ClusteredEngine_createTableClose_orphanedClusteredTableNeverClosed()
            throws Exception {
        // Strategy: Use a StubEngine whose createTable blocks via a latch. This widens the
        // race window so close() can execute fully while createTable is in progress.
        //
        // 1. Thread A: engine.createTable("t1", schema) — passes checkNotClosed(), enters
        // stub's createTable which blocks.
        // 2. Main thread: engine.close() — sets closed, iterates clusteredTables (empty),
        // clears map, closes membership, localEngine.
        // 3. Release the latch — Thread A resumes, creates ClusteredTable, puts it in map.
        // 4. Assert: the orphaned ClusteredTable exists in the map but was never closed.

        final var blockInCreate = new CountDownLatch(1);
        final var releaseCreate = new CountDownLatch(1);
        final Instant now = Instant.parse("2026-03-20T00:00:00Z");
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("id", FieldType.Primitive.STRING).build();

        // Stub engine that blocks in createTable after doing its work
        final Engine blockingEngine = new Engine() {
            private final ConcurrentHashMap<String, TableMetadata> tables = new ConcurrentHashMap<>();
            volatile boolean closed;

            @Override
            public Table createTable(String name, JlsmSchema s) throws IOException {
                final TableMetadata meta = new TableMetadata(name, s, now,
                        TableMetadata.TableState.READY);
                tables.put(name, meta);
                // Signal that we are inside createTable, then block
                blockInCreate.countDown();
                try {
                    releaseCreate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new StubTableImpl(meta);
            }

            @Override
            public Table getTable(String name) throws IOException {
                return new StubTableImpl(tables.get(name));
            }

            @Override
            public void dropTable(String name) {
                tables.remove(name);
            }

            @Override
            public Collection<TableMetadata> listTables() {
                return List.copyOf(tables.values());
            }

            @Override
            public TableMetadata tableMetadata(String name) {
                return tables.get(name);
            }

            @Override
            public EngineMetrics metrics() {
                return new EngineMetrics(0, 0, Map.of(), Map.of());
            }

            @Override
            public void close() {
                closed = true;
            }
        };

        final var transport = new InJvmTransport(NODE_A);
        final var membership = new StubMembershipProtocolImpl();
        final var ownership = new RendezvousOwnership();
        final var gracePeriod = new GracePeriodManager(Duration.ofMinutes(2));
        final var config = ClusterConfig.builder().build();

        final ClusteredEngine engine = ClusteredEngine.builder().localEngine(blockingEngine)
                .membership(membership).ownership(ownership).gracePeriodManager(gracePeriod)
                .transport(transport).config(config).localAddress(NODE_A)
                .discovery(new InJvmDiscoveryProvider()).build();

        final var createError = new AtomicReference<Throwable>();

        // Thread A: createTable — will block inside the stub engine's createTable
        Thread createThread = Thread.ofPlatform().name("create-racer").start(() -> {
            try {
                engine.createTable("t1", schema);
            } catch (Exception e) {
                createError.compareAndSet(null, e);
            }
        });

        // Wait for Thread A to enter createTable (past checkNotClosed)
        assertTrue(blockInCreate.await(5, TimeUnit.SECONDS),
                "createTable should have been entered");

        // Main thread: close the engine while createTable is blocked
        engine.close();

        // Release the block so createTable resumes — it will put ClusteredTable into the map
        releaseCreate.countDown();
        createThread.join(5_000);

        // Read the clusteredTables map via reflection
        Field tablesField = ClusteredEngine.class.getDeclaredField("clusteredTables");
        tablesField.setAccessible(true);
        ConcurrentHashMap<String, ?> clusteredTables = (ConcurrentHashMap<String, ?>) tablesField
                .get(engine);

        // If createTable threw IOException ("ClusteredEngine is closed"), that's the correct
        // behavior — the table was never orphaned. Check that.
        if (createError.get() instanceof IOException) {
            // Correct: createTable detected the close and refused. No orphan.
            assertTrue(clusteredTables.isEmpty(),
                    "If createTable threw on close, clusteredTables should be empty");
        } else {
            // The bug: createTable succeeded after close() finished — an orphaned
            // ClusteredTable is in the map. Verify it was properly closed.
            // If the map is non-empty, the entry was never closed by close() (which
            // already ran), so it is an orphan.
            assertNull(createError.get(),
                    "createTable should either succeed or throw IOException, not: "
                            + createError.get());
            assertTrue(clusteredTables.isEmpty(),
                    "After close() completes, no ClusteredTable should remain in the map — "
                            + "found " + clusteredTables.size() + " orphaned entry/entries that "
                            + "will never be closed (resource leak). createTable must either fail "
                            + "with IOException after close, or close must clean up late arrivals.");
        }

        transport.close();
    }

    // Finding: F-R1.concurrency.3.4
    // Bug: InJvmTransport.close() uses volatile check-then-act (if (closed) return;
    // closed = true) which is not atomic — two concurrent callers can both pass the
    // guard and execute the full close() body twice (double REGISTRY.remove, double
    // handlers.clear).
    // Correct behavior: close() must execute its body exactly once; concurrent callers
    // must observe the idempotency guard atomically so only one thread proceeds.
    // Fix location: InJvmTransport.close / closed field (internal/InJvmTransport.java:34, 106-113)
    // Regression watch: single-threaded close must still work; close after close must be no-op
    @Test
    @Timeout(10)
    void test_InJvmTransport_close_checkThenActRaceDoubleExecution() throws Exception {
        // Structural proof: the 'closed' field must be an AtomicBoolean (or equivalent)
        // to support an atomic compareAndSet guard in close(). A plain volatile boolean
        // only guarantees visibility, not atomicity of the check-then-act sequence.
        Field closedField = InJvmTransport.class.getDeclaredField("closed");
        boolean isPlainVolatileBool = closedField.getType() == boolean.class
                && Modifier.isVolatile(closedField.getModifiers());

        assertFalse(isPlainVolatileBool,
                "InJvmTransport.closed field must not be a plain volatile boolean — "
                        + "the check-then-act pattern (if (closed) return; closed = true) is not "
                        + "atomic with volatile. Use AtomicBoolean.compareAndSet() or synchronize "
                        + "the close() entry to prevent two concurrent callers from both executing "
                        + "the close body.");
    }

    // Finding: F-R1.concurrency.3.6
    // Bug: InJvmTransport.send() invokes handler.handle() without catching exceptions —
    // if the handler throws a synchronous RuntimeException (e.g., because the target
    // transport is closing), the exception propagates to the sender, violating the
    // fire-and-forget contract documented at line 67: "no handler means silent drop".
    // Correct behavior: send() must swallow exceptions from handler invocation so that
    // fire-and-forget semantics are preserved — the sender should never observe handler failures.
    // Fix location: InJvmTransport.send (internal/InJvmTransport.java:63-66)
    // Regression watch: request() must still propagate handler exceptions (it is request-response,
    // not fire-and-forget)
    @Test
    @Timeout(10)
    void test_InJvmTransport_send_handlerExceptionViolatesFireAndForget() throws Exception {
        // Strategy: Register a handler on node A that throws a RuntimeException.
        // Send a message from node B to node A via send(). If the bug exists,
        // the RuntimeException propagates out of send() as an unchecked exception.
        // After the fix, send() swallows the exception silently.

        final NodeAddress nodeB = new NodeAddress("node-b", "localhost", 8002);
        final var transportA = new InJvmTransport(NODE_A);
        final var transportB = new InJvmTransport(nodeB);

        try {
            // Register a handler on node A that always throws
            transportA.registerHandler(MessageType.PING, (sender, msg) -> {
                throw new RuntimeException("Handler blew up during close transition");
            });

            final var msg = new Message(MessageType.PING, nodeB, 1, new byte[0]);

            // send() is fire-and-forget — it must not propagate handler exceptions
            assertDoesNotThrow(() -> transportB.send(NODE_A, msg),
                    "send() is fire-and-forget — handler exceptions must be swallowed, "
                            + "not propagated to the sender. A handler that throws during a "
                            + "close transition should not crash the sending node.");
        } finally {
            transportA.close();
            transportB.close();
        }
    }

    // Finding: F-R1.concurrency.4.2
    // Bug: RendezvousOwnership.cache inner ConcurrentHashMap per epoch grows without bound
    // as unique partition IDs are inserted. There is no per-ID eviction or size cap within
    // an epoch — millions of unique IDs cause unbounded heap growth.
    // Correct behavior: The per-epoch cache must be bounded. After a configurable maximum
    // number of entries, further inserts should either evict old entries or bypass the cache.
    // Fix location: RendezvousOwnership.assignOwner (internal/RendezvousOwnership.java:49-61)
    // Regression watch: cache must still return correct ownership for cached IDs
    @Test
    @Timeout(10)
    @SuppressWarnings("unchecked")
    void test_RendezvousOwnership_cache_unboundedGrowthAcrossUniqueIds() throws Exception {
        final var ownership = new RendezvousOwnership();

        final Member member = new Member(NODE_A, MemberState.ALIVE, 1);
        final MembershipView view = new MembershipView(1, Set.of(member),
                Instant.parse("2026-03-20T00:00:00Z"));

        // Insert a large number of unique IDs into the cache for a single epoch.
        // Without a bound, the inner map would grow to exactly this size.
        final int uniqueIds = 100_000;
        for (int i = 0; i < uniqueIds; i++) {
            ownership.assignOwner("partition-" + i, view);
        }

        // @spec engine.clustering.R93 — per-epoch cache must be bounded by maxEntriesPerEpoch, evicting the
        // oldest entry when the bound is reached. Inspect the cache via reflection through the
        // EpochCache.size() method.
        Field cacheField = RendezvousOwnership.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        ConcurrentHashMap<Long, Object> cache = (ConcurrentHashMap<Long, Object>) cacheField
                .get(ownership);

        Object epochCache = cache.get(1L);
        assertNotNull(epochCache, "Epoch cache should exist for epoch 1");

        final java.lang.reflect.Method sizeMethod = epochCache.getClass().getDeclaredMethod("size");
        sizeMethod.setAccessible(true);
        final int size = (int) sizeMethod.invoke(epochCache);
        assertTrue(size <= ownership.maxEntriesPerEpoch(),
                "Per-epoch cache must be bounded by maxEntriesPerEpoch — found " + size
                        + " entries for " + uniqueIds + " unique IDs with bound "
                        + ownership.maxEntriesPerEpoch() + ".");
    }

    // @spec engine.clustering.R93 — the cache bound must be configurable and a smaller bound must cap the cache
    // strictly at that value, evicting the oldest entry when a new key arrives at the limit.
    @Test
    @Timeout(10)
    @SuppressWarnings("unchecked")
    void test_RendezvousOwnership_cache_configurableBoundAndLruEviction() throws Exception {
        final var ownership = new RendezvousOwnership(5);
        final var member = new Member(NODE_A, MemberState.ALIVE, 1);
        final var view = new MembershipView(1, Set.of(member),
                Instant.parse("2026-03-20T00:00:00Z"));

        for (int i = 0; i < 20; i++) {
            ownership.assignOwner("partition-" + i, view);
        }

        Field cacheField = RendezvousOwnership.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        ConcurrentHashMap<Long, Object> cache = (ConcurrentHashMap<Long, Object>) cacheField
                .get(ownership);
        Object epochCache = cache.get(1L);
        final java.lang.reflect.Method sizeMethod = epochCache.getClass().getDeclaredMethod("size");
        sizeMethod.setAccessible(true);
        final int size = (int) sizeMethod.invoke(epochCache);
        assertEquals(5, size,
                "Cache must be capped at configured maxEntriesPerEpoch with LRU eviction");
    }

    // ---- Shared stubs for ClusteredEngine tests ----

    private static final class StubMembershipProtocolImpl implements MembershipProtocol {
        final List<MembershipListener> listeners = new CopyOnWriteArrayList<>();
        private volatile MembershipView view = new MembershipView(0, Set.of(),
                Instant.parse("2026-03-20T00:00:00Z"));
        volatile boolean started;
        volatile boolean left;
        volatile boolean closed;

        @Override
        public void start(List<NodeAddress> seeds) {
            started = true;
        }

        @Override
        public MembershipView currentView() {
            return view;
        }

        @Override
        public void addListener(MembershipListener listener) {
            listeners.add(listener);
        }

        @Override
        public void leave() {
            left = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class StubTableImpl implements Table {
        private final TableMetadata metadata;

        StubTableImpl(TableMetadata metadata) {
            this.metadata = metadata;
        }

        @Override
        public void create(String key, JlsmDocument doc) {
        }

        @Override
        public Optional<JlsmDocument> get(String key) {
            return Optional.empty();
        }

        @Override
        public void update(String key, JlsmDocument doc, UpdateMode mode) {
        }

        @Override
        public void delete(String key) {
        }

        @Override
        public void insert(JlsmDocument doc) {
        }

        @Override
        public TableQuery<String> query() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<TableEntry<String>> scan(String from, String to) {
            return Collections.emptyIterator();
        }

        @Override
        public TableMetadata metadata() {
            return metadata;
        }

        @Override
        public void close() {
        }
    }

    // Finding: F-R1.concurrency.1.1
    // Bug: RemotePartitionClient.close() uses volatile check-then-set (if (!closed) { closed =
    // true;
    // OPEN_INSTANCES.decrementAndGet(); }) which is not atomic — two concurrent callers can both
    // observe closed==false, both set it to true, and both decrement OPEN_INSTANCES, corrupting
    // the counter (it will end up lower than the true number of active clients).
    // Correct behavior: close() must decrement OPEN_INSTANCES at most once per instance;
    // concurrent callers must observe the idempotency guard atomically so only one thread
    // proceeds with the decrement.
    // Fix location: RemotePartitionClient.close (internal/RemotePartitionClient.java:227-232)
    // Regression watch: single-threaded close must still work; close after close must still be
    // a no-op (no further decrement).
    @Test
    @Timeout(10)
    void test_RemotePartitionClient_close_checkThenSetRaceCorruptsOpenInstances() throws Exception {
        // Structural proof: the 'closed' field must be an AtomicBoolean (or equivalent)
        // to support an atomic compareAndSet guard in close(). A plain volatile boolean
        // only guarantees visibility, not atomicity of the check-then-set sequence —
        // two concurrent callers can both observe closed==false, both set it to true,
        // and both decrement OPEN_INSTANCES, corrupting the counter.
        //
        // This matches the pattern already established in sibling tests for
        // RapidMembership.close, ClusteredEngine.close, and InJvmTransport.close
        // (findings F-R1.conc.1.3, F-R1.concurrency.3.1, F-R1.concurrency.3.4).
        Field closedField = RemotePartitionClient.class.getDeclaredField("closed");
        boolean isPlainVolatileBool = closedField.getType() == boolean.class
                && Modifier.isVolatile(closedField.getModifiers());

        assertFalse(isPlainVolatileBool,
                "RemotePartitionClient.closed field must not be a plain volatile boolean — "
                        + "the check-then-set pattern (if (!closed) { closed = true; "
                        + "OPEN_INSTANCES.decrementAndGet(); }) is not atomic with volatile. "
                        + "Use AtomicBoolean.compareAndSet() or synchronize the close() entry "
                        + "to prevent two concurrent callers from both decrementing the counter.");
    }

    // Finding: F-R1.concurrency.1.10
    // Bug: getRangeAsync uses transportFuture.orTimeout(...) which completes the source
    // transport future in-place with TimeoutException. The subsequent transportFuture.cancel(true)
    // in the error handler is a no-op because the future is already completed — the transport
    // receives no cancellation signal and any server-side work (e.g., handler still processing
    // the request, scheduled response delivery) continues and delivers an orphaned response.
    // Correct behavior: on timeout, the SOURCE transport future must be observably cancelled
    // (isCancelled() == true) so a whenComplete callback registered by the transport can detect
    // the client has given up and release associated server-side resources.
    // Fix location: RemotePartitionClient.getRangeAsync
    // (internal/RemotePartitionClient.java:304-315)
    // Regression watch: normal-completion path (no timeout) must still deliver the response; the
    // transport-error path (transport completes future exceptionally) must still propagate the
    // transport error without masking it as a CancellationException.
    @Test
    @Timeout(10)
    void test_RemotePartitionClient_getRangeAsync_timeoutCancelsSourceFutureForTransportCleanup()
            throws Exception {
        // Strategy: Use a stub ClusterTransport whose request() returns a future that is never
        // completed by the transport. This guarantees that the timeout path is exercised — the
        // client's getRangeAsync future must complete exceptionally via the orTimeout+handle chain.
        // After the client future completes with an IOException (TimeoutException wrapped), the
        // source transport future MUST be cancelled (isCancelled()==true). If the source is instead
        // completed-exceptionally-with-TimeoutException (current behavior), the transport cannot
        // distinguish "client gave up" from "transport error" via the standard isCancelled() probe,
        // and any server-side state tied to that seq is leaked.

        final AtomicReference<CompletableFuture<Message>> sourceFutureRef = new AtomicReference<>();

        final ClusterTransport stubTransport = new ClusterTransport() {
            @Override
            public void send(NodeAddress target, Message msg) {
            }

            @Override
            public CompletableFuture<Message> request(NodeAddress target, Message msg) {
                final CompletableFuture<Message> pending = new CompletableFuture<>();
                sourceFutureRef.set(pending);
                return pending;
            }

            @Override
            public void registerHandler(MessageType type, MessageHandler handler) {
            }

            @Override
            public void deregisterHandler(MessageType type) {
            }

            @Override
            public void close() {
            }
        };

        final NodeAddress remote = new NodeAddress("remote-leak", "localhost", 9501);
        final jlsm.table.PartitionDescriptor descriptor = new jlsm.table.PartitionDescriptor(7L,
                java.lang.foreign.MemorySegment.ofArray(new byte[0]),
                java.lang.foreign.MemorySegment.ofArray(new byte[]{ (byte) 0xFF }), remote.nodeId(),
                0L);
        final JlsmSchema schema = JlsmSchema.builder("leak-table", 1)
                .field("id", FieldType.Primitive.STRING).build();
        final long timeoutMs = 100L;

        final RemotePartitionClient client = new RemotePartitionClient(descriptor, remote,
                stubTransport, NODE_A, schema, timeoutMs, "leak-table");

        try {
            final CompletableFuture<Iterator<TableEntry<String>>> resultFuture = client
                    .getRangeAsync("a", "z");

            // Wait for the client's future to complete (timeout should fire within timeoutMs).
            final ExecutionException ee = assertThrows(ExecutionException.class,
                    () -> resultFuture.get(5, TimeUnit.SECONDS),
                    "getRangeAsync future must complete exceptionally after timeout");
            assertInstanceOf(IOException.class, ee.getCause(),
                    "timeout error must be wrapped as IOException for caller");

            final CompletableFuture<Message> sourceFuture = sourceFutureRef.get();
            assertNotNull(sourceFuture, "transport.request should have been invoked");

            // The source transport future MUST be cancelled so that transport implementations
            // can register a whenComplete callback that detects client give-up (isCancelled()
            // returns true) and release server-side state. Currently orTimeout completes the
            // source future in-place with TimeoutException, so the subsequent cancel(true) is
            // a no-op: isCancelled() returns false and the transport sees the future as
            // "completed normally with a failure" rather than "cancelled by client".
            assertTrue(sourceFuture.isCancelled(),
                    "Source transport future must be cancelled on timeout so the transport can "
                            + "release server-side resources. cancel(true) on an already-completed "
                            + "future (completed by orTimeout) is a no-op — the transport cannot "
                            + "distinguish 'client gave up' from 'transport delivered an error' "
                            + "and any per-request server-side state tied to this seq is leaked.");
        } finally {
            client.close();
        }
    }

    // Finding: F-R1.concurrency.1.4
    // Bug: In ClusteredTable.scan scatter whenComplete (lines 312-319), the catch block
    // only catches IOException and swallows it via `assert closeFailure != null` — a
    // tautology that is a no-op under -da (the default JVM mode). Additionally, if
    // client.close() were to throw a non-IOException (e.g., RuntimeException from a future
    // change or aspect wrapping), it escapes the catch entirely, fails the whenComplete
    // stage, and is then silently swallowed by the subsequent `.handle((__,___)->null)`
    // at line 327 — completely invisible to callers and logs.
    // Correct behavior: exceptions thrown from client.close() in the whenComplete terminal
    // stage must be observable (logged via System.Logger, or otherwise surfaced) — not
    // silently discarded. The catch should cover any Throwable from close(), not only
    // IOException, and the handler must record the failure via a real logging mechanism
    // (not an assert tautology that is a no-op under -da).
    // Fix location: ClusteredTable.scan whenComplete (ClusteredTable.java:312-319)
    // Regression watch: the successful close path must remain side-effect-free; the scatter
    // future must still complete normally; assertions must not be the sole observability
    // mechanism.
    @Test
    @Timeout(10)
    void test_ClusteredTable_scanWhenComplete_closeFailureIsNotSilentlySwallowed()
            throws Exception {
        // Structural proof: scan the ClusteredTable.java source for the whenComplete block
        // and verify that (a) the catch is NOT restricted to IOException only, AND (b) the
        // handler body does NOT rely exclusively on `assert` for observability.
        //
        // The finding has two failure modes:
        // 1. Under -da (assertions disabled, default JVM mode), the body
        // `assert closeFailure != null` is a no-op — the IOException is silently
        // discarded. Assertions are NOT a valid observability mechanism per
        // .claude/rules/code-quality.md.
        // 2. A RuntimeException from close() (permitted by Closeable contract) escapes
        // the IOException-only catch, fails the whenComplete stage, and is then
        // silently swallowed by the subsequent `.handle((__,___) -> null)` barrier
        // wrapper at line 327.
        //
        // The fix requires catching broader exception types AND recording failures via
        // a real logging mechanism (System.Logger) rather than an assert tautology.
        //
        // This structural check is the only tractable approach: createClientForNode is
        // private and directly instantiates RemotePartitionClient (not injected), so a
        // throwing client cannot be substituted at runtime without modifying the construct.
        java.nio.file.Path source = java.nio.file.Paths
                .get("src/main/java/jlsm/engine/cluster/ClusteredTable.java");
        if (!java.nio.file.Files.exists(source)) {
            // When running under Gradle, the working directory differs by submodule.
            source = java.nio.file.Paths.get(
                    "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/ClusteredTable.java");
        }
        assertTrue(java.nio.file.Files.exists(source),
                "ClusteredTable.java source must exist at " + source.toAbsolutePath());
        final String src = java.nio.file.Files.readString(source);

        // Locate the whenComplete block by the marker comment for @spec F04.R100 / H-RL-6.
        final int markerIdx = src.indexOf("@spec F04.R100");
        assertTrue(markerIdx >= 0, "Expected @spec F04.R100 marker at the whenComplete close site");
        // Scope: from marker to the end of the whenComplete lambda (closing brace + ");").
        // Use a window wide enough to cover the entire lambda body.
        final int windowEnd = Math.min(src.length(), markerIdx + 800);
        final String window = src.substring(markerIdx, windowEnd);

        // (a) The catch must NOT be restricted to IOException only — otherwise a
        // RuntimeException from close() escapes and the whenComplete stage fails
        // silently (the allOf-wrapping handle() swallows the failure).
        final boolean onlyIoException = window.contains("catch (IOException")
                && !window.contains("catch (Exception") && !window.contains("catch (Throwable")
                && !window.contains("catch (RuntimeException");
        assertFalse(onlyIoException,
                "ClusteredTable.scan whenComplete close handler must catch non-IOException "
                        + "throwables (Exception, Throwable, or RuntimeException) — otherwise a "
                        + "RuntimeException from client.close() escapes the catch, fails the "
                        + "whenComplete stage, and is then silently swallowed by the "
                        + "handle((__,___) -> null) barrier wrapper. The Closeable contract "
                        + "permits non-IOException throwables from close(), and this silent "
                        + "swallow destroys diagnostics.");

        // (b) The handler body must not rely exclusively on `assert` for observability —
        // assertions are disabled under -da (default JVM mode), and per
        // .claude/rules/code-quality.md, asserts "must never be the sole mechanism
        // satisfying a spec requirement" (F04.R100, H-RL-6). The handler must use a
        // real logging mechanism (System.Logger or equivalent runtime-observable call).
        final boolean hasAssertOnly = window.contains("assert closeFailure")
                && !window.contains("Logger") && !window.contains("LOGGER");
        assertFalse(hasAssertOnly,
                "ClusteredTable.scan whenComplete close handler must record failures via a "
                        + "real logging mechanism (System.Logger) — an `assert closeFailure != null` "
                        + "tautology is a no-op under -da (default JVM) and does not log even when "
                        + "assertions are enabled. This violates F04.R100/H-RL-6 by making close() "
                        + "failures invisible to operators and diagnostics.");
    }

    // Finding: F-R1.concurrency.1.7
    // Bug: `lastPartialResult` is a single volatile field shared across all callers. Two
    // concurrent scans on the same ClusteredTable both write this field, so a caller who
    // invokes scan() and then reads lastPartialResultMetadata() can observe metadata that
    // belongs to a different caller's scan (last-writer-wins). The call-to-result
    // association is not preserved — a caller may believe its scan was complete when in
    // fact the metadata it reads came from a different scan with different completeness.
    // Correct behavior: lastPartialResultMetadata() called after a scan on the same thread
    // must return the metadata produced by THAT scan, regardless of other concurrent scans
    // on other threads.
    // Fix location: ClusteredTable.lastPartialResult + scan + lastPartialResultMetadata
    // (modules/jlsm-engine/src/main/java/jlsm/engine/cluster/ClusteredTable.java:82, 285, 369, 395)
    // Regression watch: single-threaded scan followed by metadata read must still return
    // the scan's own metadata. Existing tests in ClusteredTableScanParallelTest,
    // ClusteredTableLocalShortCircuitTest, and ClusteredTableTest must continue to pass.
    @Test
    @Timeout(10)
    void test_ClusteredTable_scan_lastPartialResultMetadataIsPerCallerThread() throws Exception {
        // Strategy: Thread A scans against a 1-live-node membership — its metadata records
        // totalPartitionsQueried=1. Before Thread A reads lastPartialResultMetadata(), Thread B
        // scans against a 0-live-node membership (liveNodes.isEmpty() path writes
        // totalPartitionsQueried=0). After Thread B's scan completes, Thread A reads the
        // metadata. Under the buggy shared-volatile design, Thread A observes Thread B's
        // write (total=0). The fix must preserve per-call association so Thread A observes
        // its own scan's metadata (total=1).

        final NodeAddress localAddr = new NodeAddress("local-1-7", "localhost", 9771);
        final NodeAddress remoteAddr = new NodeAddress("remote-1-7", "localhost", 9772);
        final Instant now = Instant.parse("2026-04-19T00:00:00Z");
        final JlsmSchema schema = JlsmSchema.builder("users-1-7", 1)
                .field("id", FieldType.Primitive.STRING).build();
        final TableMetadata tableMeta = new TableMetadata("users-1-7", schema, now,
                TableMetadata.TableState.READY);

        final InJvmTransport localTransport = new InJvmTransport(localAddr);
        final InJvmTransport remoteTransport = new InJvmTransport(remoteAddr);

        // Remote responds with an empty-entries payload so scan A succeeds with total=1.
        final ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putInt(0);
        final byte[] payload = buf.array();
        remoteTransport
                .registerHandler(MessageType.QUERY_REQUEST,
                        (sender, msg) -> CompletableFuture
                                .completedFuture(new Message(MessageType.QUERY_RESPONSE, remoteAddr,
                                        msg.sequenceNumber(), payload)));

        final StubMembershipProtocolImpl membership = new StubMembershipProtocolImpl();
        // Start with 1 live node (remote), so A's scan sees totalPartitionsQueried=1.
        setMembershipView(membership,
                new MembershipView(1, Set.of(new Member(remoteAddr, MemberState.ALIVE, 0)), now));

        final ClusteredTable table = new ClusteredTable(tableMeta, localTransport, membership,
                localAddr);
        try {
            final CountDownLatch aDoneScanning = new CountDownLatch(1);
            final CountDownLatch bDoneScanning = new CountDownLatch(1);
            final AtomicReference<PartialResultMetadata> aObservedMetadata = new AtomicReference<>();
            final AtomicReference<Throwable> aError = new AtomicReference<>();

            final Thread threadA = new Thread(() -> {
                try {
                    final Iterator<TableEntry<String>> iter = table.scan("a", "z");
                    // Drain so A's scan fully completes and lastPartialResult is published.
                    while (iter.hasNext()) {
                        iter.next();
                    }
                    aDoneScanning.countDown();
                    // Wait for Thread B to complete its scan (and overwrite the shared field,
                    // if the bug is present).
                    if (!bDoneScanning.await(5, TimeUnit.SECONDS)) {
                        aError.set(new AssertionError("Thread B did not complete within timeout"));
                        return;
                    }
                    // Read metadata for Thread A's own scan.
                    aObservedMetadata.set(table.lastPartialResultMetadata());
                } catch (Throwable t) {
                    aError.set(t);
                }
            }, "scanThreadA-1-7");
            threadA.setDaemon(true);
            threadA.start();

            // Wait for A to complete its scan so we know its metadata has been "published".
            assertTrue(aDoneScanning.await(5, TimeUnit.SECONDS),
                    "Thread A did not complete its scan in time");

            // Change membership to 0 live nodes, so Thread B's scan takes the
            // liveNodes.isEmpty() path at ClusteredTable.java:284 and writes
            // lastPartialResult with totalPartitionsQueried=0.
            setMembershipView(membership, new MembershipView(2, Set.of(), now));

            final Thread threadB = new Thread(() -> {
                try {
                    final Iterator<TableEntry<String>> iter = table.scan("a", "z");
                    while (iter.hasNext()) {
                        iter.next();
                    }
                } catch (Throwable _) {
                    // Irrelevant — we only care about the side-effect on lastPartialResult.
                } finally {
                    bDoneScanning.countDown();
                }
            }, "scanThreadB-1-7");
            threadB.setDaemon(true);
            threadB.start();

            threadA.join(10_000);
            threadB.join(10_000);

            assertNull(aError.get(),
                    "Thread A errored: " + (aError.get() == null ? "" : aError.get().toString()));

            final PartialResultMetadata aMeta = aObservedMetadata.get();
            assertNotNull(aMeta, "Thread A must observe non-null metadata after its own scan");

            // Contract claim: per-caller association must be preserved. Thread A's scan
            // targeted 1 live node, so A must observe totalPartitionsQueried==1. Under the
            // shared-volatile bug, A observes Thread B's write (totalPartitionsQueried==0).
            assertEquals(1, aMeta.totalPartitionsQueried(),
                    "lastPartialResultMetadata() must return the calling thread's own scan "
                            + "metadata (totalPartitionsQueried=1 for A's 1-node scan), not a "
                            + "different thread's scan metadata (B's 0-node scan). Observed "
                            + "totalPartitionsQueried=" + aMeta.totalPartitionsQueried()
                            + " — this is call-to-result cross-talk under concurrent scans.");
        } finally {
            table.close();
            localTransport.close();
            remoteTransport.close();
        }
    }

    private static void setMembershipView(StubMembershipProtocolImpl membership,
            MembershipView view) throws Exception {
        final Field viewField = StubMembershipProtocolImpl.class.getDeclaredField("view");
        viewField.setAccessible(true);
        viewField.set(membership, view);
    }

    // Finding: F-R1.concurrency.4.3
    // Bug: isInGracePeriod uses Instant.now() but departedAt is caller-provided — a future
    // departedAt extends the grace window beyond the configured Duration silently.
    // recordDeparture(node, Instant.now().plusMinutes(10)) with a 5-minute grace period
    // means isInGracePeriod returns true for 15 minutes instead of 5.
    // Correct behavior: recordDeparture must reject a departedAt that is in the future
    // (after Instant.now()), throwing IllegalArgumentException, so that the grace period
    // duration always matches the configured Duration.
    // Fix location: GracePeriodManager.recordDeparture (internal/GracePeriodManager.java:52-56)
    // Regression watch: ensure past/present departedAt values are still accepted
    @Test
    @Timeout(10)
    void test_GracePeriodManager_recordDeparture_futureDepartedAtExtendsGraceWindow() {
        final var manager = new GracePeriodManager(Duration.ofMinutes(5));
        final var node = new NodeAddress("node-x", "localhost", 9001);

        // A departedAt 10 minutes in the future should be rejected — if accepted,
        // isInGracePeriod would return true for 15 minutes (10 future + 5 grace)
        // instead of the configured 5 minutes.
        final Instant futureDepartedAt = Instant.now().plus(Duration.ofMinutes(10));

        assertThrows(IllegalArgumentException.class,
                () -> manager.recordDeparture(node, futureDepartedAt),
                "recordDeparture must reject a future departedAt — accepting it would cause "
                        + "isInGracePeriod to return true for longer than the configured grace period");
    }

    // Finding: F-R1.concurrency.1.9
    // Bug: RemotePartitionClient.sendRequestAndAwait only uses `assert future != null` to guard
    // against a null value returned from transport.request(). With assertions disabled (-da, the
    // default JVM mode), a null return causes an NPE at future.get(...) instead of a wrapped
    // IOException, violating the class contract that every sync operation surfaces failures as
    // IOException. The async path getRangeAsync already performs an explicit null-check that
    // returns a failedFuture — the sync path must do the same.
    // Correct behavior: When transport.request returns null, the sync path must throw IOException
    // (wrapping the contract violation) rather than NullPointerException.
    // Fix location: RemotePartitionClient.sendRequestAndAwait
    // (modules/jlsm-engine/src/main/java/jlsm/engine/cluster/internal/RemotePartitionClient.java:422)
    // Regression watch: the existing `assert` may be removed or left in place; a runtime guard
    // equivalent to the async path's explicit if-null-return is required.
    @Test
    @Timeout(10)
    void test_RemotePartitionClient_sendRequestAndAwait_nullTransportFutureSurfacesAsIOException()
            throws Exception {
        final PartitionDescriptor descriptor = new PartitionDescriptor(1L,
                MemorySegment.ofArray("a".getBytes(StandardCharsets.UTF_8)),
                MemorySegment.ofArray("z".getBytes(StandardCharsets.UTF_8)), "remote", 0L);
        final NodeAddress owner = new NodeAddress("remote", "remote-host", 9999);

        // A buggy transport that returns a null future from request() — violates the SPI contract,
        // but the sync path must handle it defensively (mirroring getRangeAsync's null-check).
        final ClusterTransport nullReturningTransport = new ClusterTransport() {
            @Override
            public void send(NodeAddress target, Message msg) {
                // no-op
            }

            @Override
            public CompletableFuture<Message> request(NodeAddress target, Message msg) {
                return null;
            }

            @Override
            public void registerHandler(MessageType type, MessageHandler handler) {
                // no-op
            }

            @Override
            public void deregisterHandler(MessageType type) {
                // no-op
            }

            @Override
            public void close() {
                // no-op
            }
        };

        final RemotePartitionClient client = new RemotePartitionClient(descriptor, owner,
                nullReturningTransport, NODE_A, "tbl-null-future");
        try {
            // doDelete exercises the sync sendRequestAndAwait path without requiring a schema or
            // a constructed JlsmDocument. In -da mode the assertion in sendRequestAndAwait is
            // stripped and a null transport future causes an NPE at future.get(...). The async
            // getRangeAsync path already has an explicit if-null-return — the sync path must
            // match. We assert IOException (not NullPointerException, not AssertionError) is
            // what the caller sees: the runtime guard must be a real `if` check, not an assert.
            final IOException thrown = assertThrows(IOException.class, () -> client.doDelete("k1"),
                    "sync sendRequestAndAwait must surface a null transport future as IOException, "
                            + "not NullPointerException or AssertionError — the async getRangeAsync "
                            + "path already does this via an explicit runtime check");
            // Additionally confirm no NPE is thrown (guarding against a regression where a
            // runtime guard is introduced but the wrong exception type escapes).
            assertNotNull(thrown.getMessage(),
                    "IOException from null-future guard must have a descriptive message");
        } finally {
            client.close();
        }
    }
}
