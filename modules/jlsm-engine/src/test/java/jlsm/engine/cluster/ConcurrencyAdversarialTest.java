package jlsm.engine.cluster;

import jlsm.engine.Engine;
import jlsm.engine.EngineMetrics;
import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.engine.cluster.internal.GracePeriodManager;
import jlsm.engine.cluster.internal.InJvmDiscoveryProvider;
import jlsm.engine.cluster.internal.InJvmTransport;
import jlsm.engine.cluster.internal.PhiAccrualFailureDetector;
import jlsm.engine.cluster.internal.RapidMembership;
import jlsm.engine.cluster.internal.RendezvousOwnership;
import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;

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

        // @spec F04.R93 — per-epoch cache must be bounded by maxEntriesPerEpoch, evicting the
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

    // @spec F04.R93 — the cache bound must be configurable and a smaller bound must cap the cache
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
}
