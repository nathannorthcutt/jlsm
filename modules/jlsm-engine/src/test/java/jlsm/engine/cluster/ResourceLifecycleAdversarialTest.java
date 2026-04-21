package jlsm.engine.cluster;

import jlsm.engine.cluster.internal.GracePeriodManager;
import jlsm.engine.cluster.internal.InJvmDiscoveryProvider;
import jlsm.engine.cluster.internal.InJvmTransport;
import jlsm.engine.cluster.internal.PhiAccrualFailureDetector;
import jlsm.engine.cluster.internal.RapidMembership;
import jlsm.engine.cluster.internal.RemotePartitionClient;
import jlsm.engine.TableMetadata;
import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;
import jlsm.table.TableEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for resource lifecycle concerns in the clustering subsystem.
 *
 * @spec engine.clustering.R80 — engine close accumulates shutdown errors then throws after release
 * @spec engine.clustering.R83 — failure detector evicts heartbeat history for departed members
 * @spec engine.clustering.R94 — grace period manager removes expired departure records
 * @spec engine.clustering.R97 — membership close deregisters discovery provider + transport handlers
 * @spec engine.clustering.R98 — clustered engine rolls back local table on proxy registration failure
 * @spec engine.clustering.R100 — scan closes remote clients on both normal and exceptional paths
 * @spec engine.clustering.R106 — close cancels in-flight scatter fanout tasks
 * @spec engine.clustering.R107 — cancelled scatter unblocks the servicing thread
 */
final class ResourceLifecycleAdversarialTest {

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

    // Finding: F-R1.resource_lifecycle.1.1
    // Bug: close() does not call discovery.deregister() — registration leak
    // Correct behavior: close() must call discovery.deregister(localAddress) so other nodes
    // stop discovering this node as a seed after shutdown
    // Fix location: RapidMembership.close (internal/RapidMembership.java:207-222)
    // Regression watch: ensure deregister is called even if scheduler shutdown throws
    @Test
    @Timeout(10)
    void test_RapidMembership_close_deregistersDiscoveryProvider() throws IOException {
        // Spy discovery provider that tracks deregister calls
        final var deregisterCalled = new AtomicBoolean(false);
        final var deregisteredAddress = new AtomicReference<NodeAddress>();
        DiscoveryProvider spyDiscovery = new DiscoveryProvider() {
            @Override
            public Set<NodeAddress> discoverSeeds() {
                return Set.of();
            }

            @Override
            public void register(NodeAddress self) {
                // no-op
            }

            @Override
            public void deregister(NodeAddress self) {
                deregisterCalled.set(true);
                deregisteredAddress.set(self);
            }
        };

        final var transport = new InJvmTransport(NODE_A);
        final var config = ClusterConfig.builder().build();
        final var fd = new PhiAccrualFailureDetector(10);
        final var membership = new RapidMembership(NODE_A, transport, spyDiscovery, config, fd);

        // Start as single-node cluster (only self as seed)
        membership.start(List.of(NODE_A));

        // Close should deregister from discovery
        membership.close();

        assertTrue(deregisterCalled.get(),
                "close() must call discovery.deregister() to prevent stale registrations");
        assertEquals(NODE_A, deregisteredAddress.get(),
                "deregister must be called with the local node address");
    }

    // Finding: F-R1.resource_lifecycle.1.3
    // Bug: start() partial failure leaves half-started state — scheduler never created,
    // handlers remain registered, started=true prevents retry
    // Correct behavior: start() should roll back on failure — reset started to false,
    // deregister handlers, so the caller can call close() cleanly without leaking handlers
    // Fix location: RapidMembership.start (internal/RapidMembership.java:98-162)
    // Regression watch: ensure successful start() still works normally after the rollback logic
    @Test
    @Timeout(10)
    void test_RapidMembership_start_partialFailureRollsBack() throws IOException {
        // Discovery provider that throws RuntimeException on discoverSeeds()
        // This propagates out of start() after started=true and handlers registered
        // but before scheduler creation at line 155
        final var deregisterCalled = new AtomicBoolean(false);
        DiscoveryProvider failingDiscovery = new DiscoveryProvider() {
            @Override
            public Set<NodeAddress> discoverSeeds() {
                throw new RuntimeException("Discovery infrastructure failure");
            }

            @Override
            public void register(NodeAddress self) {
                // no-op
            }

            @Override
            public void deregister(NodeAddress self) {
                deregisterCalled.set(true);
            }
        };

        final var transport = new InJvmTransport(NODE_A);
        final var config = ClusterConfig.builder().build();
        final var fd = new PhiAccrualFailureDetector(10);
        final var membership = new RapidMembership(NODE_A, transport, failingDiscovery, config, fd);

        // start() should propagate the exception
        final var failCount = new java.util.concurrent.atomic.AtomicInteger(0);
        assertThrows(RuntimeException.class, () -> membership.start(List.of(NODE_A)));

        // BUG: Without rollback, started=true persists after the failed start().
        // The caller cannot retry start() — it throws "Protocol already started"
        // even though the protocol never fully initialized (no scheduler running).
        // Per R55 (join rollback), start() must return the object to a clean state
        // on failure so the caller can retry or discard without calling close().

        // close() should still be safe to call
        membership.close();

        // Create a new instance that fails once then succeeds
        final var failOnce = new AtomicBoolean(true);
        DiscoveryProvider retryableDiscovery = new DiscoveryProvider() {
            @Override
            public Set<NodeAddress> discoverSeeds() {
                if (failOnce.compareAndSet(true, false)) {
                    throw new RuntimeException("Transient discovery failure");
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

        // Clear transport registry so we can create a fresh transport for retry test
        InJvmTransport.clearRegistry();
        final var transport2 = new InJvmTransport(NODE_A);
        final var retryMembership = new RapidMembership(NODE_A, transport2, retryableDiscovery,
                config, fd);

        // First attempt fails — discoverSeeds throws RuntimeException
        assertThrows(RuntimeException.class, () -> retryMembership.start(List.of(NODE_A)));

        // Second attempt should succeed — start() must have rolled back started=true
        // BUG: Without rollback, this throws IllegalStateException("Protocol already started")
        assertDoesNotThrow(() -> retryMembership.start(List.of(NODE_A)),
                "start() must roll back on failure so the caller can retry");
        assertNotNull(retryMembership.currentView(),
                "Protocol must be fully started after successful retry");
        retryMembership.close();
    }

    // Finding: F-R1.resource_lifecycle.1.4
    // Bug: leave() leaks scheduler if transport.send() throws unchecked exception
    // Correct behavior: leave() must call close() even when transport.send() throws a
    // RuntimeException, ensuring the scheduler is shut down and resources are released
    // Fix location: RapidMembership.leave (internal/RapidMembership.java:188-213)
    // Regression watch: ensure IOException-only sends still work (best-effort delivery)
    @Test
    @Timeout(10)
    void test_RapidMembership_leave_closesEvenWhenSendThrowsRuntimeException() throws Exception {
        final var nodeB = new NodeAddress("node-b", "localhost", 8002);
        final var deregisterCalled = new AtomicBoolean(false);
        final var throwOnSend = new AtomicBoolean(false);
        final var viewChangeHandler = new AtomicReference<MessageHandler>();

        DiscoveryProvider trackingDiscovery = new DiscoveryProvider() {
            @Override
            public Set<NodeAddress> discoverSeeds() {
                return Set.of();
            }

            @Override
            public void register(NodeAddress self) {
            }

            @Override
            public void deregister(NodeAddress self) {
                deregisterCalled.set(true);
            }
        };

        // Custom transport: captures VIEW_CHANGE handler, throws RuntimeException on send when
        // armed
        ClusterTransport controllableTransport = new ClusterTransport() {
            @Override
            public void send(NodeAddress target, Message msg) throws IOException {
                if (throwOnSend.get()) {
                    throw new NullPointerException(
                            "Simulated unchecked exception in transport.send()");
                }
            }

            @Override
            public CompletableFuture<Message> request(NodeAddress target, Message msg) {
                return CompletableFuture.failedFuture(new IOException("not implemented"));
            }

            @Override
            public void registerHandler(MessageType type, MessageHandler handler) {
                if (type == MessageType.VIEW_CHANGE) {
                    viewChangeHandler.set(handler);
                }
            }

            @Override
            public void deregisterHandler(MessageType type) {
            }

            @Override
            public void close() {
            }
        };

        final var config = ClusterConfig.builder().build();
        final var membership = new RapidMembership(NODE_A, controllableTransport, trackingDiscovery,
                config, new PhiAccrualFailureDetector(10));

        // Self-bootstrap: creates a single-node view with only NODE_A
        membership.start(List.of(NODE_A));
        assertNotNull(viewChangeHandler.get(), "VIEW_CHANGE handler must be registered");

        // Inject node-b into the view by delivering a JOIN_REQUEST through the handler.
        // This simulates node-b joining the cluster, adding it to the membership view.
        final byte[] nodeBId = nodeB.nodeId().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] nodeBHost = nodeB.host().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final java.nio.ByteBuffer joinBuf = java.nio.ByteBuffer
                .allocate(1 + 4 + nodeBId.length + 4 + nodeBHost.length + 4);
        joinBuf.put((byte) 0x01); // MSG_JOIN_REQUEST
        joinBuf.putInt(nodeBId.length);
        joinBuf.put(nodeBId);
        joinBuf.putInt(nodeBHost.length);
        joinBuf.put(nodeBHost);
        joinBuf.putInt(nodeB.port());

        final Message joinMsg = new Message(MessageType.VIEW_CHANGE, nodeB, 0L, joinBuf.array());
        viewChangeHandler.get().handle(nodeB, joinMsg).get(); // wait for completion

        // Verify that node-b is now in the view
        assertEquals(2, membership.currentView().members().size(),
                "View should now contain both NODE_A and node-b");

        // Arm the transport to throw RuntimeException on send
        throwOnSend.set(true);

        // leave() will iterate over view.members(), skip NODE_A (self), and try to send to node-b.
        // transport.send() throws NullPointerException (unchecked).
        // BUG: The RuntimeException propagates past close(), leaking the scheduler.
        // After the fix, leave() must call close() in a finally block, so deregister is called.
        try {
            membership.leave();
        } catch (RuntimeException _) {
            // Expected if the RuntimeException propagates — that's fine as long as close() ran
        }

        assertTrue(deregisterCalled.get(),
                "leave() must call close() even when transport.send() throws RuntimeException — "
                        + "scheduler and discovery registration leaked");
    }

    // @spec engine.clustering.R83 — RapidMembership must wire failureDetector.remove when a member departs so
    // the detector does not retain heartbeat history for nodes that have left the view. Without
    // this wiring, the remove() method exists but the membership protocol never calls it.
    @Test
    @Timeout(10)
    void test_RapidMembership_handleLeaveNotification_evictsFailureDetectorHistory()
            throws Exception {
        final var local = new NodeAddress("local", "localhost", 9101);
        final var peer = new NodeAddress("peer", "localhost", 9102);
        final var transport = new InJvmTransport(local);
        final var peerTransport = new InJvmTransport(peer);
        final var discovery = new InJvmDiscoveryProvider();
        final var config = ClusterConfig.builder().build();
        final var detector = new PhiAccrualFailureDetector(10);

        // Seed heartbeats for peer so it has phi history.
        long t0 = 1_000_000_000L;
        detector.recordHeartbeat(peer, t0);
        detector.recordHeartbeat(peer, t0 + 50_000_000L);
        assertTrue(detector.phi(peer, t0 + 100_000_000L) > 0.0,
                "precondition: peer must have positive phi before departure");

        final var rapid = new RapidMembership(local, transport, discovery, config, detector);
        try {
            rapid.start(List.of());

            // Force peer into the view so handleLeaveNotification recognises it.
            final var cvField = RapidMembership.class.getDeclaredField("currentView");
            cvField.setAccessible(true);
            cvField.set(rapid, new MembershipView(1, Set.of(new Member(local, MemberState.ALIVE, 0),
                    new Member(peer, MemberState.ALIVE, 0)), Instant.now()));

            // Send a LEAVE from peer to local — handleViewChange dispatches to
            // handleLeaveNotification.
            final byte[] leavePayload = new byte[]{ 0x03 }; // MSG_LEAVE
            peerTransport
                    .request(local, new Message(MessageType.VIEW_CHANGE, peer, 1, leavePayload))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);

            // R83: detector history for peer must be evicted on transition to DEAD.
            assertEquals(0.0, detector.phi(peer, t0 + 200_000_000L),
                    "failureDetector must no longer report phi for a departed member — "
                            + "RapidMembership.handleLeaveNotification must call "
                            + "failureDetector.remove(leavingNode)");
        } finally {
            rapid.close();
            transport.close();
            peerTransport.close();
        }
    }

    // Finding: F-R1.resource_lifecycle.1.5
    // Bug: PhiAccrualFailureDetector.heartbeatHistory grows unbounded — no eviction for departed
    // nodes
    // Correct behavior: PhiAccrualFailureDetector must provide a remove(NodeAddress) method so
    // callers can evict departed nodes, preventing unbounded map growth in long-running clusters
    // Fix location: PhiAccrualFailureDetector (internal/PhiAccrualFailureDetector.java:24-240)
    // Regression watch: ensure remove does not affect other nodes' histories
    @Test
    @Timeout(10)
    void test_PhiAccrualFailureDetector_remove_evictsDepartedNodeHistory() {
        final var fd = new PhiAccrualFailureDetector(10);
        final var departedNode = new NodeAddress("departed", "localhost", 9001);
        final var activeNode = new NodeAddress("active", "localhost", 9002);

        // Record heartbeats for both nodes — creates entries in the internal map
        long now = 1_000_000_000L;
        fd.recordHeartbeat(departedNode, now);
        fd.recordHeartbeat(departedNode, now + 100_000_000L);
        fd.recordHeartbeat(activeNode, now);
        fd.recordHeartbeat(activeNode, now + 100_000_000L);

        // Both nodes should have phi > 0 (have history with >= 2 heartbeats)
        double phiBefore = fd.phi(departedNode, now + 200_000_000L);
        assertTrue(phiBefore > 0.0, "departed node should have positive phi before removal");
        double activePhiBefore = fd.phi(activeNode, now + 200_000_000L);
        assertTrue(activePhiBefore > 0.0, "active node should have positive phi before removal");

        // Remove the departed node — this method must exist for resource lifecycle correctness
        fd.remove(departedNode);

        // After removal, phi should return 0.0 (no history — treated as unknown node)
        double phiAfter = fd.phi(departedNode, now + 200_000_000L);
        assertEquals(0.0, phiAfter,
                "phi must return 0.0 for a removed node — no history should remain");

        // Active node must be unaffected by the removal
        double activePhiAfter = fd.phi(activeNode, now + 200_000_000L);
        assertEquals(activePhiBefore, activePhiAfter, 0.001,
                "removing departed node must not affect active node's phi");
    }

    // Finding: F-R1.resource_lifecycle.1.6
    // Bug: close() does not clear listeners list — listener leak
    // Correct behavior: close() must clear the listeners list so listener references
    // are released and cannot be notified post-close
    // Fix location: RapidMembership.close (internal/RapidMembership.java:220-240)
    // Regression watch: ensure listeners still work normally before close
    @Test
    @Timeout(10)
    void test_RapidMembership_close_clearsListeners() throws IOException {
        DiscoveryProvider noopDiscovery = new DiscoveryProvider() {
            @Override
            public Set<NodeAddress> discoverSeeds() {
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
        final var membership = new RapidMembership(NODE_A, transport, noopDiscovery, config, fd);

        // Register a listener before starting
        membership.addListener(new MembershipListener() {
            @Override
            public void onViewChanged(MembershipView oldView, MembershipView newView) {
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

        membership.start(List.of(NODE_A));

        // Close should clear the listeners list to release references
        membership.close();

        // After close, adding and checking the listener count is not possible via public API,
        // but we can verify by adding another listener after close and checking the list size
        // indirectly. The real test: the listeners list should be empty after close.
        // We use reflection to check the listeners field since there is no public API to query
        // size.
        try {
            final var listenersField = RapidMembership.class.getDeclaredField("listeners");
            listenersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            final var listenersList = (java.util.List<MembershipListener>) listenersField
                    .get(membership);
            assertTrue(listenersList.isEmpty(),
                    "close() must clear the listeners list to prevent listener leaks — "
                            + "closed RapidMembership retains strong references to all listeners");
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("Could not access listeners field for verification: " + e.getMessage());
        }
    }

    // Finding: F-R1.resource_lifecycle.2.1
    // Bug: ClusteredEngine.close() never closes the transport — resource leak
    // Correct behavior: close() must call transport.close() so network resources are released
    // Fix location: ClusteredEngine.close (ClusteredEngine.java:155-201)
    // Regression watch: ensure transport.close() errors are collected in deferred exception pattern
    @Test
    @Timeout(10)
    void test_ClusteredEngine_close_closesTransport() throws IOException {
        // Spy transport that tracks close() calls
        final var transportClosed = new AtomicBoolean(false);
        ClusterTransport spyTransport = new ClusterTransport() {
            @Override
            public void send(NodeAddress target, Message msg) {
            }

            @Override
            public CompletableFuture<Message> request(NodeAddress target, Message msg) {
                return CompletableFuture.failedFuture(new IOException("not implemented"));
            }

            @Override
            public void registerHandler(MessageType type, MessageHandler handler) {
            }

            @Override
            public void deregisterHandler(MessageType type) {
            }

            @Override
            public void close() {
                transportClosed.set(true);
            }
        };

        // Stub membership protocol
        MembershipProtocol stubMembership = new MembershipProtocol() {
            @Override
            public void start(List<NodeAddress> seeds) {
            }

            @Override
            public MembershipView currentView() {
                return null;
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
        };

        // Stub local engine
        jlsm.engine.Engine stubEngine = new jlsm.engine.Engine() {
            @Override
            public jlsm.engine.Table createTable(String name, jlsm.table.JlsmSchema schema) {
                return null;
            }

            @Override
            public jlsm.engine.Table getTable(String name) {
                return null;
            }

            @Override
            public void dropTable(String name) {
            }

            @Override
            public java.util.Collection<jlsm.engine.TableMetadata> listTables() {
                return List.of();
            }

            @Override
            public jlsm.engine.TableMetadata tableMetadata(String name) {
                return null;
            }

            @Override
            public jlsm.engine.EngineMetrics metrics() {
                return null;
            }

            @Override
            public void close() {
            }
        };

        final var engine = ClusteredEngine.builder().localEngine(stubEngine)
                .membership(stubMembership)
                .ownership(new jlsm.engine.cluster.internal.RendezvousOwnership())
                .gracePeriodManager(new jlsm.engine.cluster.internal.GracePeriodManager(
                        java.time.Duration.ofMinutes(5)))
                .transport(spyTransport).config(ClusterConfig.builder().build())
                .localAddress(NODE_A).discovery(new InJvmDiscoveryProvider()).build();

        // Close the engine
        engine.close();

        // Transport must have been closed
        assertTrue(transportClosed.get(), "ClusteredEngine.close() must close the transport — "
                + "transport resource leak: network sockets/channels remain open");
    }

    // Finding: F-R1.resource_lifecycle.1.2
    // Bug: close() does not deregister transport handlers — handler leak
    // Correct behavior: close() must deregister PING and VIEW_CHANGE handlers from the transport
    // so post-close messages do not invoke handlers on a closed RapidMembership instance
    // Fix location: RapidMembership.close (internal/RapidMembership.java:207-224),
    // ClusterTransport (needs deregisterHandler method)
    // Regression watch: ensure deregisterHandler is called before scheduler shutdown
    @Test
    @Timeout(10)
    void test_RapidMembership_close_deregistersTransportHandlers() throws IOException {
        final var deregisteredTypes = EnumSet.noneOf(MessageType.class);
        final var handlers = new ConcurrentHashMap<MessageType, MessageHandler>();

        // Spy transport that tracks handler registration and deregistration
        ClusterTransport spyTransport = new ClusterTransport() {
            @Override
            public void send(NodeAddress target, Message msg) {
                // no-op
            }

            @Override
            public CompletableFuture<Message> request(NodeAddress target, Message msg) {
                return CompletableFuture.failedFuture(new IOException("not implemented"));
            }

            @Override
            public void registerHandler(MessageType type, MessageHandler handler) {
                handlers.put(type, handler);
            }

            @Override
            public void deregisterHandler(MessageType type) {
                handlers.remove(type);
                deregisteredTypes.add(type);
            }

            @Override
            public void close() {
                // no-op
            }
        };

        DiscoveryProvider noopDiscovery = new DiscoveryProvider() {
            @Override
            public Set<NodeAddress> discoverSeeds() {
                return Set.of();
            }

            @Override
            public void register(NodeAddress self) {
            }

            @Override
            public void deregister(NodeAddress self) {
            }
        };

        final var config = ClusterConfig.builder().build();
        final var fd = new PhiAccrualFailureDetector(10);
        final var membership = new RapidMembership(NODE_A, spyTransport, noopDiscovery, config, fd);

        // Start registers handlers for PING and VIEW_CHANGE
        membership.start(List.of(NODE_A));

        // Verify handlers are registered after start
        assertTrue(handlers.containsKey(MessageType.PING),
                "PING handler should be registered after start");
        assertTrue(handlers.containsKey(MessageType.VIEW_CHANGE),
                "VIEW_CHANGE handler should be registered after start");

        // Close should deregister handlers
        membership.close();

        // Verify deregisterHandler was called for both message types
        assertTrue(deregisteredTypes.contains(MessageType.PING),
                "close() must deregister PING handler to prevent handler leak");
        assertTrue(deregisteredTypes.contains(MessageType.VIEW_CHANGE),
                "close() must deregister VIEW_CHANGE handler to prevent handler leak");
    }

    // Finding: F-R1.resource_lifecycle.2.2
    // Bug: ClusteredTable.scan() leaks RemotePartitionClient instances — clients are created
    // via createClientForNode() for each live node but never closed after getRange() returns
    // Correct behavior: scan() must close each RemotePartitionClient in a finally block after
    // getRange() completes, since getRange() eagerly fetches data via sendRequestAndAwait()
    // Fix location: ClusteredTable.scan (ClusteredTable.java:170-179)
    // Regression watch: ensure CRUD methods (which already use try/finally) are unaffected
    @Test
    @Timeout(10)
    void test_ClusteredTable_scan_closesRemotePartitionClients() throws IOException {
        final var nodeB = new NodeAddress("node-b", "localhost", 8002);
        final var nodeC = new NodeAddress("node-c", "localhost", 8003);
        final var now = Instant.parse("2026-04-06T00:00:00Z");

        // Transport that completes requests immediately with a dummy response
        ClusterTransport dummyTransport = new ClusterTransport() {
            @Override
            public void send(NodeAddress target, Message msg) {
            }

            @Override
            public CompletableFuture<Message> request(NodeAddress target, Message msg) {
                // Return a completed future so sendRequestAndAwait() succeeds
                return CompletableFuture.completedFuture(
                        new Message(MessageType.QUERY_RESPONSE, target, 0L, new byte[0]));
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

        // Membership with 2 live remote nodes (NODE_A is local, nodeB and nodeC are remote)
        MembershipProtocol stubMembership = new MembershipProtocol() {
            @Override
            public void start(List<NodeAddress> seeds) {
            }

            @Override
            public MembershipView currentView() {
                return new MembershipView(1,
                        Set.of(new Member(NODE_A, MemberState.ALIVE, 0),
                                new Member(nodeB, MemberState.ALIVE, 0),
                                new Member(nodeC, MemberState.ALIVE, 0)),
                        now);
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
        };

        final var schema = JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .build();
        final var meta = new TableMetadata("users", schema, now, TableMetadata.TableState.READY);
        final var table = new ClusteredTable(meta, dummyTransport, stubMembership, NODE_A);

        // Reset the open-instance counter before our scan
        RemotePartitionClient.resetOpenInstanceCounter();

        // Call scan() — this creates one RemotePartitionClient per live node (3 nodes)
        Iterator<TableEntry<String>> result = table.scan("a", "z");

        // BUG: Without the fix, scan() creates 3 clients (one per live node) but never
        // closes them. The open-instance counter remains at 3 instead of 0.
        assertEquals(0, RemotePartitionClient.openInstances(),
                "scan() must close all RemotePartitionClient instances after getRange() — "
                        + "each scan() call leaks one client per live node");

        table.close();
    }

    // Finding: F-R1.resource_lifecycle.2.5
    // Bug: InJvmTransport.registerHandler() uses assert instead of runtime check for closed state
    // Correct behavior: registerHandler() must throw IllegalStateException when called on a closed
    // transport
    // Fix location: InJvmTransport.registerHandler (internal/InJvmTransport.java:93)
    // Regression watch: ensure registerHandler still works normally on open transport
    @Test
    @Timeout(10)
    void test_InJvmTransport_registerHandler_rejectsClosedTransport() {
        final var transport = new InJvmTransport(NODE_A);
        transport.close();

        // With assertions disabled (production default), the assert at line 93 is a no-op.
        // The handler is silently registered on a closed transport that will never deliver
        // messages.
        // A runtime check must reject this with IllegalStateException.
        assertThrows(IllegalStateException.class,
                () -> transport.registerHandler(MessageType.PING,
                        (sender, msg) -> CompletableFuture.completedFuture(msg)),
                "registerHandler() must reject calls on a closed transport with IllegalStateException — "
                        + "assert is a no-op when assertions are disabled");
    }

    // Finding: F-R1.resource_lifecycle.2.6
    // Bug: ClusteredEngine.createTable() does not roll back localEngine on ClusteredTable
    // construction failure
    // Correct behavior: If ClusteredTable constructor throws after localEngine.createTable()
    // succeeds,
    // the local table must be dropped (rolled back) to avoid an orphaned local table with no
    // clustered proxy
    // Fix location: ClusteredEngine.createTable (ClusteredEngine.java:89-97)
    // Regression watch: ensure normal createTable() still works when ClusteredTable constructor
    // succeeds
    @Test
    @Timeout(10)
    void test_ClusteredEngine_createTable_rollsBackLocalEngineOnClusteredTableFailure()
            throws IOException {
        final var dropCalled = new AtomicBoolean(false);
        final var droppedTableName = new AtomicReference<String>();

        // Stub Table whose metadata() returns null — triggers NullPointerException
        // in ClusteredTable constructor (Objects.requireNonNull(tableMetadata))
        jlsm.engine.Table nullMetadataTable = new jlsm.engine.Table() {
            @Override
            public void create(String key, jlsm.table.JlsmDocument doc) {
            }

            @Override
            public java.util.Optional<jlsm.table.JlsmDocument> get(String key) {
                return java.util.Optional.empty();
            }

            @Override
            public void update(String key, jlsm.table.JlsmDocument doc,
                    jlsm.table.UpdateMode mode) {
            }

            @Override
            public void delete(String key) {
            }

            @Override
            public void insert(jlsm.table.JlsmDocument doc) {
            }

            @Override
            public jlsm.table.TableQuery<String> query() {
                return null;
            }

            @Override
            public Iterator<TableEntry<String>> scan(String fromKey, String toKey) {
                return java.util.Collections.emptyIterator();
            }

            @Override
            public TableMetadata metadata() {
                return null;
            } // triggers NPE in ClusteredTable ctor

            @Override
            public void close() {
            }
        };

        // Stub local engine: createTable succeeds but returns a table with null metadata
        jlsm.engine.Engine stubEngine = new jlsm.engine.Engine() {
            @Override
            public jlsm.engine.Table createTable(String name, JlsmSchema schema) {
                return nullMetadataTable;
            }

            @Override
            public jlsm.engine.Table getTable(String name) {
                return null;
            }

            @Override
            public void dropTable(String name) {
                dropCalled.set(true);
                droppedTableName.set(name);
            }

            @Override
            public java.util.Collection<TableMetadata> listTables() {
                return List.of();
            }

            @Override
            public TableMetadata tableMetadata(String name) {
                return null;
            }

            @Override
            public jlsm.engine.EngineMetrics metrics() {
                return null;
            }

            @Override
            public void close() {
            }
        };

        // Stub membership and transport
        MembershipProtocol stubMembership = new MembershipProtocol() {
            @Override
            public void start(List<NodeAddress> seeds) {
            }

            @Override
            public MembershipView currentView() {
                return null;
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
        };

        ClusterTransport stubTransport = new ClusterTransport() {
            @Override
            public void send(NodeAddress target, Message msg) {
            }

            @Override
            public CompletableFuture<Message> request(NodeAddress target, Message msg) {
                return CompletableFuture.failedFuture(new IOException("not implemented"));
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

        final var engine = ClusteredEngine.builder().localEngine(stubEngine)
                .membership(stubMembership)
                .ownership(new jlsm.engine.cluster.internal.RendezvousOwnership())
                .gracePeriodManager(
                        new jlsm.engine.cluster.internal.GracePeriodManager(Duration.ofMinutes(5)))
                .transport(stubTransport).config(ClusterConfig.builder().build())
                .localAddress(NODE_A).discovery(new InJvmDiscoveryProvider()).build();

        final var schema = JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .build();

        // createTable should propagate the NullPointerException from ClusteredTable constructor
        assertThrows(NullPointerException.class, () -> engine.createTable("users", schema));

        // BUG: Without rollback, the local table persists but no clustered proxy was registered.
        // The engine is in an inconsistent state with an orphaned local table.
        assertTrue(dropCalled.get(),
                "createTable() must roll back localEngine.createTable() when ClusteredTable "
                        + "construction fails — orphaned local table with no clustered proxy");
        assertEquals("users", droppedTableName.get(),
                "dropTable must be called with the same table name that was created");

        engine.close();
    }

    // Finding: F-R1.resource_lifecycle.2.7
    // Bug: ClusteredTable creates a private RendezvousOwnership that is never cleaned up on close()
    // Correct behavior: close() must clear the RendezvousOwnership cache so cached assignments
    // do not persist after the table is closed, preventing memory leaks in long-lived JVMs
    // Fix location: ClusteredTable.close (ClusteredTable.java:204-207)
    // Regression watch: ensure ownership still works for operations before close()
    @Test
    @Timeout(10)
    @SuppressWarnings("unchecked")
    void test_ClusteredTable_close_clearsOwnershipCache() throws Exception {
        final var nodeB = new NodeAddress("node-b", "localhost", 8002);
        final var now = Instant.parse("2026-04-06T00:00:00Z");

        // Transport that completes requests immediately
        ClusterTransport dummyTransport = new ClusterTransport() {
            @Override
            public void send(NodeAddress target, Message msg) {
            }

            @Override
            public CompletableFuture<Message> request(NodeAddress target, Message msg) {
                return CompletableFuture.completedFuture(
                        new Message(MessageType.QUERY_RESPONSE, target, 0L, new byte[0]));
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

        // Membership with 2 live nodes
        MembershipProtocol stubMembership = new MembershipProtocol() {
            @Override
            public void start(List<NodeAddress> seeds) {
            }

            @Override
            public MembershipView currentView() {
                return new MembershipView(1, Set.of(new Member(NODE_A, MemberState.ALIVE, 0),
                        new Member(nodeB, MemberState.ALIVE, 0)), now);
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
        };

        final var schema = JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .build();
        final var meta = new TableMetadata("users", schema, now, TableMetadata.TableState.READY);
        final var table = new ClusteredTable(meta, dummyTransport, stubMembership, NODE_A);

        // Trigger ownership cache population by calling get() which invokes resolveOwner()
        try {
            table.get("some-key");
        } catch (IOException _) {
            // Expected — dummy transport returns empty response that may fail deserialization
        }

        // Access the private ownership field, then its internal cache
        final var ownershipField = ClusteredTable.class.getDeclaredField("ownership");
        ownershipField.setAccessible(true);
        final var ownership = ownershipField.get(table);

        final var cacheField = ownership.getClass().getDeclaredField("cache");
        cacheField.setAccessible(true);
        final var cache = (Map<?, ?>) cacheField.get(ownership);

        // The cache should have at least one entry from the get() call
        assertFalse(cache.isEmpty(), "Setup: ownership cache should be populated after get() call");

        // Close the table
        table.close();

        // BUG: Without the fix, the cache retains all entries after close().
        // The RendezvousOwnership is never cleaned, leaking cached assignments.
        assertTrue(cache.isEmpty(), "close() must clear the RendezvousOwnership cache — "
                + "cached assignments persist after table close, leaking memory");
    }

    // Finding: F-R1.resource_lifecycle.1.2
    // Bug: join() rollback only catches RuntimeException from discovery.deregister(); a
    // non-RuntimeException (e.g., a checked IOException thrown via sneakyThrow, or the
    // unchecked-IOException-but-with-a-cause-Exception path that close() handles symmetrically)
    // escapes the rollback block and hides the original membership.start failure.
    // Correct behavior: The rollback must catch Exception (matching the symmetric defence in
    // close() at L315-323), attach the rollback failure via addSuppressed on the original
    // membership.start failure, and rethrow the original failure — never the rollback failure.
    // Fix location: ClusteredEngine.join (ClusteredEngine.java:260-269)
    // Regression watch: normal rollback of RuntimeException must still work; the original
    // membership.start failure must still be the primary throw.
    @Test
    @Timeout(10)
    void test_ClusteredEngine_join_rollbackSurfacesMembershipStartFailureWhenDeregisterThrowsChecked()
            throws IOException {
        final var membershipStartFailure = new IOException("membership.start failed");
        final var deregisterFailure = new IOException("deregister blew up");

        // DiscoveryProvider whose deregister throws a checked IOException via sneakyThrow on the
        // first invocation (the rollback inside join), then no-ops on subsequent calls (the
        // defer-collection path inside close()). Simulates a non-compliant impl that surfaces
        // IOException through the Throwable hierarchy in a way that escapes
        // catch(RuntimeException).
        final var sneakyThrownOnce = new AtomicBoolean(false);
        DiscoveryProvider sneakyDiscovery = new DiscoveryProvider() {
            @Override
            public Set<NodeAddress> discoverSeeds() {
                return Set.of();
            }

            @Override
            public void register(NodeAddress self) {
                // no-op
            }

            @Override
            public void deregister(NodeAddress self) {
                if (sneakyThrownOnce.compareAndSet(false, true)) {
                    sneakyThrow(deregisterFailure);
                }
            }
        };

        // Membership whose start throws an IOException so rollback is exercised
        MembershipProtocol failingMembership = new MembershipProtocol() {
            @Override
            public void start(List<NodeAddress> seeds) throws IOException {
                throw membershipStartFailure;
            }

            @Override
            public MembershipView currentView() {
                return null;
            }

            @Override
            public void addListener(MembershipListener listener) {
            }

            @Override
            public void removeListener(MembershipListener listener) {
            }

            @Override
            public void leave() {
            }

            @Override
            public void close() {
            }
        };

        ClusterTransport noopTransport = new ClusterTransport() {
            @Override
            public void send(NodeAddress target, Message msg) {
            }

            @Override
            public CompletableFuture<Message> request(NodeAddress target, Message msg) {
                return CompletableFuture.failedFuture(new IOException("not implemented"));
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

        jlsm.engine.Engine stubEngine = new jlsm.engine.Engine() {
            @Override
            public jlsm.engine.Table createTable(String name, JlsmSchema schema) {
                return null;
            }

            @Override
            public jlsm.engine.Table getTable(String name) {
                return null;
            }

            @Override
            public void dropTable(String name) {
            }

            @Override
            public java.util.Collection<TableMetadata> listTables() {
                return List.of();
            }

            @Override
            public TableMetadata tableMetadata(String name) {
                return null;
            }

            @Override
            public jlsm.engine.EngineMetrics metrics() {
                return null;
            }

            @Override
            public void close() {
            }
        };

        final var engine = ClusteredEngine.builder().localEngine(stubEngine)
                .membership(failingMembership)
                .ownership(new jlsm.engine.cluster.internal.RendezvousOwnership())
                .gracePeriodManager(new GracePeriodManager(Duration.ofMinutes(1)))
                .transport(noopTransport).config(ClusterConfig.builder().build())
                .localAddress(NODE_A).discovery(sneakyDiscovery).build();

        try {
            // join() must propagate the ORIGINAL membership.start failure, with the deregister
            // failure attached via addSuppressed. The buggy code lets the deregister failure
            // escape and hide the primary failure.
            final IOException thrown = assertThrows(IOException.class,
                    () -> engine.join(List.of(NODE_A)));

            assertSame(membershipStartFailure, thrown,
                    "join() must propagate the original membership.start failure, not the "
                            + "rollback deregister failure — the primary cause must not be hidden");

            final Throwable[] suppressed = thrown.getSuppressed();
            assertEquals(1, suppressed.length,
                    "the rollback deregister failure must be attached via addSuppressed");
            assertSame(deregisterFailure, suppressed[0],
                    "the suppressed throwable must be the deregister failure");
        } finally {
            engine.close();
        }
    }

    // Finding: F-R1.resource_lifecycle.2.2
    // Bug: SCATTER_EXECUTOR is a static `Executors.newVirtualThreadPerTaskExecutor()` that is
    // never shut down; ClusteredTable.close() does not cancel in-flight fanout tasks, so tasks
    // parked on a stalled transport remain parked for JVM lifetime — no operator recourse.
    // Correct behavior: either (a) close() must cancel in-flight scatter futures, or (b) the
    // executor must be scoped to per-table lifecycle and close()d, or (c) a JVM shutdown hook
    // must register to close the executor cleanly on termination.
    // Fix location: ClusteredTable.close (ClusteredTable.java:434-437) and SCATTER_EXECUTOR
    // declaration (ClusteredTable.java:73).
    // Regression watch: ensure live tables' scatter executor remains usable when one table
    // closes; ensure the in-flight future does not complete normally after close().
    @Test
    @Timeout(10)
    void test_ClusteredTable_close_cancelsInFlightScatterTasks() throws Exception {
        final var nodeB = new NodeAddress("node-b", "localhost", 8002);
        final var now = Instant.parse("2026-04-06T00:00:00Z");

        // Transport whose request() returns a future that never completes — simulates the
        // pathological case where the transport stalls (timer thread died, custom transport
        // bypasses orTimeout, etc.). The scatter virtual thread will park indefinitely waiting
        // on the result.
        final var stalledFuture = new CompletableFuture<Message>();
        ClusterTransport stalledTransport = new ClusterTransport() {
            @Override
            public void send(NodeAddress target, Message msg) {
            }

            @Override
            public CompletableFuture<Message> request(NodeAddress target, Message msg) {
                return stalledFuture; // never completes
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

        MembershipProtocol stubMembership = new MembershipProtocol() {
            @Override
            public void start(List<NodeAddress> seeds) {
            }

            @Override
            public MembershipView currentView() {
                return new MembershipView(1, Set.of(new Member(NODE_A, MemberState.ALIVE, 0),
                        new Member(nodeB, MemberState.ALIVE, 0)), now);
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
        };

        final var schema = JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .build();
        final var meta = new TableMetadata("users", schema, now, TableMetadata.TableState.READY);
        final var table = new ClusteredTable(meta, stalledTransport, stubMembership, NODE_A);

        // Start a scatter scan in a separate thread — it will park on allOf().get() awaiting
        // the stalled request future.
        final var scanStarted = new java.util.concurrent.CountDownLatch(1);
        final var scanCompleted = new java.util.concurrent.CountDownLatch(1);
        final var scanThread = Thread.ofVirtual().unstarted(() -> {
            try {
                scanStarted.countDown();
                table.scan("a", "z");
            } catch (Exception _) {
                // Expected — scan is interrupted or times out
            } finally {
                scanCompleted.countDown();
            }
        });
        scanThread.start();
        assertTrue(scanStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
        // Give the scan a moment to submit its supplyAsync tasks to SCATTER_EXECUTOR
        Thread.sleep(200);

        // close() should signal the scatter fanout to cancel — a bounded-lifetime contract.
        // Without a cancellation mechanism, the scan thread and its scatter tasks remain
        // parked for JVM lifetime on the stalled future.
        table.close();

        // The scan thread must complete within a short window after close(). If it does not,
        // close() does not propagate cancellation to in-flight scatter tasks.
        final boolean scanEnded = scanCompleted.await(2, java.util.concurrent.TimeUnit.SECONDS);
        // Best-effort: interrupt the scan thread to prevent the test from leaking a parked
        // virtual thread regardless of whether close() cancels.
        scanThread.interrupt();
        assertTrue(scanEnded,
                "close() must cancel in-flight scatter tasks so they do not remain parked "
                        + "for JVM lifetime when the transport stalls past its timeout");
    }

    // Generic-trick sneakyThrow — used only to simulate a deregister impl that leaks a checked
    // exception past a RuntimeException-only catch block. This is a legitimate attack vector
    // because the DiscoveryProvider boundary contract does not forbid such impls and the
    // symmetric close() path catches Exception.
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    // Finding: F-R1.resource_lifecycle.2.4
    // Bug: GracePeriodManager.departures map grows without bound — expiredDepartures()
    // returns expired nodes but never removes them from the internal map
    // Correct behavior: expiredDepartures() must remove expired entries from the map
    // so permanently departed nodes do not accumulate indefinitely
    // Fix location: GracePeriodManager.expiredDepartures (internal/GracePeriodManager.java:79-90)
    // Regression watch: ensure nodes still in grace period are not removed
    @Test
    @Timeout(10)
    @SuppressWarnings("unchecked")
    void test_GracePeriodManager_expiredDepartures_removesExpiredEntries() throws Exception {
        final var gracePeriod = Duration.ofSeconds(1);
        final var manager = new GracePeriodManager(gracePeriod);

        // Record departures far enough in the past that they are already expired
        final Instant longAgo = Instant.now().minus(Duration.ofHours(1));
        final var departed1 = new NodeAddress("departed-1", "localhost", 9001);
        final var departed2 = new NodeAddress("departed-2", "localhost", 9002);
        final var departed3 = new NodeAddress("departed-3", "localhost", 9003);

        manager.recordDeparture(departed1, longAgo);
        manager.recordDeparture(departed2, longAgo);
        manager.recordDeparture(departed3, longAgo);

        // Also record a recent departure that is still in grace period
        final var recentNode = new NodeAddress("recent", "localhost", 9004);
        manager.recordDeparture(recentNode, Instant.now());

        // Access the internal departures map via reflection to verify cleanup
        final var departuresField = GracePeriodManager.class.getDeclaredField("departures");
        departuresField.setAccessible(true);
        final var departuresMap = (Map<NodeAddress, Instant>) departuresField.get(manager);

        // Before cleanup: 4 entries in the map
        assertEquals(4, departuresMap.size(),
                "Setup: should have 4 departure entries before expiredDepartures() call");

        // Call expiredDepartures() — should return the 3 expired nodes
        Set<NodeAddress> expired = manager.expiredDepartures();
        assertEquals(3, expired.size(), "Should return 3 expired departures");
        assertTrue(expired.contains(departed1));
        assertTrue(expired.contains(departed2));
        assertTrue(expired.contains(departed3));

        // BUG: Without the fix, all 4 entries remain in the map after expiredDepartures().
        // The 3 expired entries are returned but never removed, causing unbounded growth.
        assertEquals(1, departuresMap.size(),
                "expiredDepartures() must remove expired entries from the map — "
                        + "without cleanup, permanently departed nodes accumulate indefinitely");

        // The recent node must still be in the map (still in grace period)
        assertTrue(departuresMap.containsKey(recentNode),
                "Nodes still in grace period must not be removed");

        // The recent node should still report as in grace period
        assertTrue(manager.isInGracePeriod(recentNode),
                "Recent node must still be in grace period after cleanup of expired nodes");
    }

    // Finding: F-R1.resource_lifecycle.1.1
    // Bug: registerHandler failure at L91 leaks the membership listener registered at L78
    // Correct behavior: If transport.registerHandler throws during the ctor after
    // membership.addListener has been called, the listener must be removed from the
    // membership protocol — the ctor must either roll back the listener or avoid
    // registering it until after registerHandler succeeds
    // Fix location: ClusteredEngine ctor (ClusteredEngine.java:57-92)
    // Regression watch: ensure the successful-build path still registers exactly one
    // listener; ensure concurrency.1.3 ordering (handler visible implies listener visible)
    // is preserved or replaced with an equivalent guarantee.
    @Test
    @Timeout(10)
    void test_ClusteredEngine_ctor_registerHandlerFailureDoesNotLeakMembershipListener()
            throws IOException {
        // Tracking membership that records all listener adds/removes so the test can verify
        // the post-failure listener count.
        final var listenerCount = new java.util.concurrent.atomic.AtomicInteger(0);
        MembershipProtocol trackingMembership = new MembershipProtocol() {
            @Override
            public void start(List<NodeAddress> seeds) {
            }

            @Override
            public MembershipView currentView() {
                return null;
            }

            @Override
            public void addListener(MembershipListener listener) {
                listenerCount.incrementAndGet();
            }

            @Override
            public void removeListener(MembershipListener listener) {
                listenerCount.decrementAndGet();
            }

            @Override
            public void leave() {
            }

            @Override
            public void close() {
            }
        };

        // Transport whose registerHandler throws — simulates a closed/broken transport
        // encountered mid-construction, AFTER the membership listener has already been
        // registered at L78 of ClusteredEngine.
        ClusterTransport failingTransport = new ClusterTransport() {
            @Override
            public void send(NodeAddress target, Message msg) {
            }

            @Override
            public CompletableFuture<Message> request(NodeAddress target, Message msg) {
                return CompletableFuture.failedFuture(new IOException("not implemented"));
            }

            @Override
            public void registerHandler(MessageType type, MessageHandler handler) {
                throw new IllegalStateException("simulated registerHandler failure");
            }

            @Override
            public void deregisterHandler(MessageType type) {
            }

            @Override
            public void close() {
            }
        };

        jlsm.engine.Engine stubEngine = new jlsm.engine.Engine() {
            @Override
            public jlsm.engine.Table createTable(String name, JlsmSchema schema) {
                return null;
            }

            @Override
            public jlsm.engine.Table getTable(String name) {
                return null;
            }

            @Override
            public void dropTable(String name) {
            }

            @Override
            public java.util.Collection<TableMetadata> listTables() {
                return List.of();
            }

            @Override
            public TableMetadata tableMetadata(String name) {
                return null;
            }

            @Override
            public jlsm.engine.EngineMetrics metrics() {
                return null;
            }

            @Override
            public void close() {
            }
        };

        // Build must throw (we expect the IllegalStateException to propagate).
        assertThrows(IllegalStateException.class,
                () -> ClusteredEngine.builder().localEngine(stubEngine)
                        .membership(trackingMembership)
                        .ownership(new jlsm.engine.cluster.internal.RendezvousOwnership())
                        .gracePeriodManager(new GracePeriodManager(Duration.ofMinutes(1)))
                        .transport(failingTransport).config(ClusterConfig.builder().build())
                        .localAddress(NODE_A).discovery(new InJvmDiscoveryProvider()).build());

        // BUG: Without rollback, the listener count is 1 (leaked). A correct ctor either
        // removes the listener on failure (count back to 0) or never registers it before
        // the handler registration succeeds.
        assertEquals(0, listenerCount.get(),
                "ctor must not leak the membership listener when registerHandler fails — "
                        + "MembershipProtocol retains a strong reference to the listener "
                        + "(and via the inner class, to the half-initialized ClusteredEngine)");
    }
}
