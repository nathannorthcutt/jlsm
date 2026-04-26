package jlsm.engine.cluster.internal;

import jlsm.cluster.internal.InJvmTransport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import jlsm.engine.cluster.ClusterConfig;
import jlsm.cluster.ClusterTransport;
import jlsm.engine.cluster.Member;
import jlsm.engine.cluster.MemberState;
import jlsm.engine.cluster.MembershipView;
import jlsm.cluster.Message;
import jlsm.cluster.MessageHandler;
import jlsm.cluster.MessageType;
import jlsm.cluster.NodeAddress;

/**
 * Integration tests for the RAPID consensus protocol wiring in {@link RapidMembership} (WU-4).
 *
 * <p>
 * Verifies that:
 * <ul>
 * <li>protocolTick pings only expander-overlay monitors, not every ALIVE member</li>
 * <li>phi-threshold breach starts a consensus round (not an immediate SUSPECTED transition)</li>
 * <li>QUORUM_AGREE triggers a view change via the ViewChangeSink</li>
 * <li>AliveRefutation cancels in-flight rounds</li>
 * <li>self-refutation bumps incarnation and propagates via the view</li>
 * <li>the overlay rebuilds on view change</li>
 * <li>ClusterConfig's new parameters are exposed end-to-end</li>
 * </ul>
 */
@Timeout(15)
final class RapidMembershipConsensusIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-04-19T00:00:00Z");

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

    // =======================================================================
    // 1. protocolTick uses overlay monitors
    // =======================================================================

    @Test
    void protocolTick_pingsOnlyOverlayMonitors_notEveryAliveMember() throws Exception {
        // 10-node cluster, degree=3. Local node should ping only 3 overlay neighbors.
        final NodeAddress local = new NodeAddress("n0", "localhost", 10000);
        final List<NodeAddress> peers = new ArrayList<>();
        for (int i = 1; i < 10; i++) {
            peers.add(new NodeAddress("n" + i, "localhost", 10000 + i));
        }

        final CountingTransport transport = new CountingTransport(local);
        closeables.add(transport);

        final var config = ClusterConfig.builder().protocolPeriod(Duration.ofHours(1))
                .pingTimeout(Duration.ofMillis(50)).expanderGraphDegree(3).build();
        final var detector = new PhiAccrualFailureDetector(10);
        final var discovery = new InJvmDiscoveryProvider();
        final var membership = new RapidMembership(local, transport, discovery, config, detector);
        closeables.add(membership);

        final Set<Member> members = new HashSet<>();
        members.add(new Member(local, MemberState.ALIVE, 0));
        for (NodeAddress p : peers) {
            members.add(new Member(p, MemberState.ALIVE, 0));
        }
        final var view = new MembershipView(1, members, NOW);

        setStarted(membership, true);
        setCurrentView(membership, view);
        invokeRebuildOverlay(membership, view);

        // Resolve the overlay's monitors for local.
        final ExpanderGraphOverlay overlay = getOverlay(membership);
        final Set<NodeAddress> monitors = overlay.monitorsOf(local);
        assertEquals(3, monitors.size(),
                "overlay must produce degree-3 monitors for 10-node cluster");

        invokeProtocolTick(membership);

        // Only PING requests sent — count distinct targets.
        final Set<NodeAddress> pingTargets = transport.pingTargets();
        assertEquals(monitors, pingTargets,
                "protocolTick must ping only overlay monitors; expected " + monitors + " but got "
                        + pingTargets);
        assertTrue(pingTargets.size() <= 3, "should not ping more than configured degree monitors");
    }

    // =======================================================================
    // 2. suspicionFlow — 2-node consensus triggers view change
    // =======================================================================

    @Test
    void suspicionFlow_twoNodeConsensus_appliesViewChange() throws Exception {
        // 2-node cluster: local observes remote. Single-observer quorum. A phi breach on local
        // should cascade through the coordinator's self-dispatched proposal → vote → commit
        // path because for n=2, the overlay has observers[remote] = {local}.
        final NodeAddress local = new NodeAddress("n0", "localhost", 11000);
        final NodeAddress remote = new NodeAddress("n1", "localhost", 11001);

        final InJvmTransport transport = registerTransport(local);
        // Deliberately NOT registering a PING handler on the remote transport — the ping request
        // completes exceptionally so the protocol-tick's whenComplete does not refresh the
        // heartbeat clock, leaving phi high for the subsequent consensus round.
        registerTransport(remote);

        final var config = ClusterConfig.builder().protocolPeriod(Duration.ofHours(1))
                .pingTimeout(Duration.ofMillis(50)).expanderGraphDegree(1)
                .consensusQuorumPercent(100).consensusRoundTimeout(Duration.ofSeconds(10)).build();
        final var detector = new PhiAccrualFailureDetector(10);
        final var discovery = new InJvmDiscoveryProvider();
        final var membership = new RapidMembership(local, transport, discovery, config, detector);
        closeables.add(membership);

        final Set<Member> members = Set.of(new Member(local, MemberState.ALIVE, 0),
                new Member(remote, MemberState.ALIVE, 0));
        final var view = new MembershipView(1, members, NOW);

        setStarted(membership, true);
        setCurrentView(membership, view);
        invokeRebuildOverlay(membership, view);
        registerHandlers(membership);

        // Inject heartbeat history with a large elapsed gap so phi > threshold at the tick.
        final long base = System.nanoTime() - 60_000_000_000L;
        detector.recordHeartbeat(remote, base);
        detector.recordHeartbeat(remote, base + 1_000_000L);

        invokeProtocolTick(membership);

        // After the tick, InJvmTransport has synchronously delivered proposal + vote, so the
        // round should be committed and remote marked SUSPECTED.
        final MembershipView afterView = membership.currentView();
        assertSuspected(afterView, remote,
                "remote must transition to SUSPECTED after 2-node consensus quorum");
    }

    // =======================================================================
    // 3. 5-node cluster: 3 agrees reach quorum
    // =======================================================================

    @Test
    void suspicionFlow_fiveNodeCluster_threeAgreesReachQuorum() throws Exception {
        // 5 nodes, degree=4 (full mesh). Default 75% quorum: requiredAgree = ceil(4*0.75)=3.
        final NodeAddress local = new NodeAddress("n0", "localhost", 12000);
        final NodeAddress[] peers = { new NodeAddress("n1", "localhost", 12001),
                new NodeAddress("n2", "localhost", 12002),
                new NodeAddress("n3", "localhost", 12003),
                new NodeAddress("n4", "localhost", 12004) };
        final NodeAddress subject = peers[3]; // n4 is the target

        final InJvmTransport transport = registerTransport(local);
        for (NodeAddress p : peers) {
            registerTransport(p);
        }

        final var config = ClusterConfig.builder().protocolPeriod(Duration.ofHours(1))
                .pingTimeout(Duration.ofMillis(50)).expanderGraphDegree(4)
                .consensusRoundTimeout(Duration.ofSeconds(10)).build();
        final var detector = new PhiAccrualFailureDetector(10);
        final var discovery = new InJvmDiscoveryProvider();
        final var membership = new RapidMembership(local, transport, discovery, config, detector);
        closeables.add(membership);

        final Set<Member> members = new HashSet<>();
        members.add(new Member(local, MemberState.ALIVE, 0));
        for (NodeAddress p : peers) {
            members.add(new Member(p, MemberState.ALIVE, 0));
        }
        final var view = new MembershipView(1, members, NOW);
        setStarted(membership, true);
        setCurrentView(membership, view);
        invokeRebuildOverlay(membership, view);
        registerHandlers(membership);

        // Start a round manually by calling the coordinator.
        final ConsensusCoordinator coord = getCoordinator(membership);
        coord.onViewChanged(view);
        final long roundId = coord.startRound(subject, view.epoch(), 0L);

        // Feed 3 agree votes from non-local observers (n1, n2, n3).
        coord.onVoteReceived(new SuspicionVote(roundId, peers[0], subject, true, 0L));
        coord.onVoteReceived(new SuspicionVote(roundId, peers[1], subject, true, 0L));
        coord.onVoteReceived(new SuspicionVote(roundId, peers[2], subject, true, 0L));

        final MembershipView afterView = membership.currentView();
        assertSuspected(afterView, subject,
                "subject must be SUSPECTED after quorum of 3 agree votes in a 5-node cluster");
    }

    // =======================================================================
    // 4. Round expires without quorum → no view change
    // =======================================================================

    @Test
    void suspicionFlow_insufficientAgreement_roundExpires_noViewChange() throws Exception {
        final NodeAddress local = new NodeAddress("n0", "localhost", 13000);
        final NodeAddress[] peers = { new NodeAddress("n1", "localhost", 13001),
                new NodeAddress("n2", "localhost", 13002),
                new NodeAddress("n3", "localhost", 13003),
                new NodeAddress("n4", "localhost", 13004) };
        final NodeAddress subject = peers[3];

        final InJvmTransport transport = registerTransport(local);
        for (NodeAddress p : peers) {
            registerTransport(p);
        }

        final var config = ClusterConfig.builder().protocolPeriod(Duration.ofHours(1))
                .pingTimeout(Duration.ofMillis(50)).expanderGraphDegree(4)
                .consensusRoundTimeout(Duration.ofMillis(50)).build();
        final var detector = new PhiAccrualFailureDetector(10);
        final var discovery = new InJvmDiscoveryProvider();
        final var membership = new RapidMembership(local, transport, discovery, config, detector);
        closeables.add(membership);

        final Set<Member> members = new HashSet<>();
        members.add(new Member(local, MemberState.ALIVE, 0));
        for (NodeAddress p : peers) {
            members.add(new Member(p, MemberState.ALIVE, 0));
        }
        final var view = new MembershipView(1, members, NOW);
        setStarted(membership, true);
        setCurrentView(membership, view);
        invokeRebuildOverlay(membership, view);
        registerHandlers(membership);

        final ConsensusCoordinator coord = getCoordinator(membership);
        coord.onViewChanged(view);
        final long roundId = coord.startRound(subject, view.epoch(), 0L);

        // Only one agree vote — below the 3 required for 75% of 4 observers.
        coord.onVoteReceived(new SuspicionVote(roundId, peers[0], subject, true, 0L));

        // Wait for the timeout to elapse then tick.
        Thread.sleep(120);
        coord.tick();

        final MembershipView afterView = membership.currentView();
        Member s = findMember(afterView, subject);
        assertNotNull(s);
        assertEquals(MemberState.ALIVE, s.state(),
                "subject must remain ALIVE because quorum was not reached before expiry");
    }

    // =======================================================================
    // 5. Self-refutation cancels an in-flight round
    // =======================================================================

    @Test
    void selfRefutation_aliveRefuteCancelsConsensusRound() throws Exception {
        final NodeAddress local = new NodeAddress("n0", "localhost", 14000);
        final NodeAddress peerA = new NodeAddress("n1", "localhost", 14001);
        final NodeAddress peerB = new NodeAddress("n2", "localhost", 14002);

        final InJvmTransport transport = registerTransport(local);
        registerTransport(peerA);
        registerTransport(peerB);

        final var config = ClusterConfig.builder().protocolPeriod(Duration.ofHours(1))
                .pingTimeout(Duration.ofMillis(50)).expanderGraphDegree(2)
                .consensusRoundTimeout(Duration.ofSeconds(10)).consensusQuorumPercent(100) // require
                                                                                           // every
                                                                                           // observer
                .build();
        final var detector = new PhiAccrualFailureDetector(10);
        final var discovery = new InJvmDiscoveryProvider();
        final var membership = new RapidMembership(local, transport, discovery, config, detector);
        closeables.add(membership);

        final Set<Member> members = Set.of(new Member(local, MemberState.ALIVE, 0),
                new Member(peerA, MemberState.ALIVE, 0), new Member(peerB, MemberState.ALIVE, 0));
        final var view = new MembershipView(1, members, NOW);
        setStarted(membership, true);
        setCurrentView(membership, view);
        invokeRebuildOverlay(membership, view);
        registerHandlers(membership);

        final ConsensusCoordinator coord = getCoordinator(membership);
        coord.onViewChanged(view);
        final long roundId = coord.startRound(peerA, view.epoch(), 0L);

        // Before quorum, peerA refutes with a higher incarnation.
        coord.onRefutationReceived(new AliveRefutation(peerA, 1L, view.epoch()));

        // Even if a late agree vote arrives, the round is gone → no applySuspicion.
        coord.onVoteReceived(new SuspicionVote(roundId, peerB, peerA, true, 0L));

        final MembershipView afterView = membership.currentView();
        Member m = findMember(afterView, peerA);
        assertNotNull(m);
        assertEquals(MemberState.ALIVE, m.state(),
                "peerA must stay ALIVE because the refutation cancelled the round before quorum");
    }

    // =======================================================================
    // 6. Incarnation bump propagates via view on receipt of AliveRefutation
    // =======================================================================

    @Test
    void selfRefutation_incarnationBumpPropagatesViaView() throws Exception {
        final NodeAddress local = new NodeAddress("n0", "localhost", 15000);
        final NodeAddress peer = new NodeAddress("n1", "localhost", 15001);

        final InJvmTransport transport = registerTransport(local);
        registerTransport(peer);

        final var config = ClusterConfig.builder().protocolPeriod(Duration.ofHours(1))
                .pingTimeout(Duration.ofMillis(50)).expanderGraphDegree(1)
                .consensusRoundTimeout(Duration.ofSeconds(10)).build();
        final var detector = new PhiAccrualFailureDetector(10);
        final var discovery = new InJvmDiscoveryProvider();
        final var membership = new RapidMembership(local, transport, discovery, config, detector);
        closeables.add(membership);

        final Set<Member> members = Set.of(new Member(local, MemberState.ALIVE, 0),
                new Member(peer, MemberState.ALIVE, 0));
        final var view = new MembershipView(1, members, NOW);
        setStarted(membership, true);
        setCurrentView(membership, view);
        invokeRebuildOverlay(membership, view);
        registerHandlers(membership);

        // Dispatch an ALIVE_REFUTATION for peer into the local handler via the sub-type codec.
        final AliveRefutation refutation = new AliveRefutation(peer, 5L, view.epoch());
        dispatchViewChangePayload(membership, peer, refutation.serialize());

        final MembershipView afterView = membership.currentView();
        Member m = findMember(afterView, peer);
        assertNotNull(m);
        assertEquals(5L, m.incarnation(),
                "peer's incarnation must reflect the refutation's higher value");
    }

    // =======================================================================
    // 7. Overlay rebuilds on view change
    // =======================================================================

    @Test
    void overlayRebuildsOnViewChange() throws Exception {
        final NodeAddress local = new NodeAddress("n0", "localhost", 16000);
        final NodeAddress[] peers = { new NodeAddress("n1", "localhost", 16001),
                new NodeAddress("n2", "localhost", 16002),
                new NodeAddress("n3", "localhost", 16003),
                new NodeAddress("n4", "localhost", 16004) };

        final InJvmTransport transport = registerTransport(local);
        for (NodeAddress p : peers) {
            registerTransport(p);
        }

        final var config = ClusterConfig.builder().protocolPeriod(Duration.ofHours(1))
                .pingTimeout(Duration.ofMillis(50)).expanderGraphDegree(3).build();
        final var detector = new PhiAccrualFailureDetector(10);
        final var discovery = new InJvmDiscoveryProvider();
        final var membership = new RapidMembership(local, transport, discovery, config, detector);
        closeables.add(membership);

        final Set<Member> firstFive = new HashSet<>();
        firstFive.add(new Member(local, MemberState.ALIVE, 0));
        for (NodeAddress p : peers) {
            firstFive.add(new Member(p, MemberState.ALIVE, 0));
        }
        final var view1 = new MembershipView(1, firstFive, NOW);
        setStarted(membership, true);
        setCurrentView(membership, view1);
        invokeRebuildOverlay(membership, view1);

        final ExpanderGraphOverlay overlay = getOverlay(membership);
        final int count1 = overlay.memberCount();
        assertEquals(5, count1);

        // Now add a 6th node and rebuild.
        final NodeAddress newPeer = new NodeAddress("n5", "localhost", 16005);
        registerTransport(newPeer);
        final Set<Member> six = new HashSet<>(firstFive);
        six.add(new Member(newPeer, MemberState.ALIVE, 0));
        final var view2 = new MembershipView(2, six, NOW);
        setCurrentView(membership, view2);
        invokeRebuildOverlay(membership, view2);

        assertEquals(6, overlay.memberCount(),
                "overlay must be rebuilt to include the 6th node after the view change");
    }

    // =======================================================================
    // 8. Observer-set snapshot is stable across mid-round view changes
    // =======================================================================

    @Test
    void roundDoesNotAutoCommitWhenObserversDepartMidFlight() throws Exception {
        // Start with 5 nodes, begin a round, then remove an observer.
        final NodeAddress local = new NodeAddress("n0", "localhost", 17000);
        final NodeAddress[] peers = { new NodeAddress("n1", "localhost", 17001),
                new NodeAddress("n2", "localhost", 17002),
                new NodeAddress("n3", "localhost", 17003),
                new NodeAddress("n4", "localhost", 17004) };
        final NodeAddress subject = peers[3];

        final InJvmTransport transport = registerTransport(local);
        for (NodeAddress p : peers) {
            registerTransport(p);
        }

        final var config = ClusterConfig.builder().protocolPeriod(Duration.ofHours(1))
                .pingTimeout(Duration.ofMillis(50)).expanderGraphDegree(4)
                .consensusRoundTimeout(Duration.ofMillis(150)).consensusQuorumPercent(100) // require
                                                                                           // every
                                                                                           // observer
                                                                                           // agreement
                .build();
        final var detector = new PhiAccrualFailureDetector(10);
        final var discovery = new InJvmDiscoveryProvider();
        final var membership = new RapidMembership(local, transport, discovery, config, detector);
        closeables.add(membership);

        final Set<Member> members = new HashSet<>();
        members.add(new Member(local, MemberState.ALIVE, 0));
        for (NodeAddress p : peers) {
            members.add(new Member(p, MemberState.ALIVE, 0));
        }
        final var view = new MembershipView(1, members, NOW);
        setStarted(membership, true);
        setCurrentView(membership, view);
        invokeRebuildOverlay(membership, view);
        registerHandlers(membership);

        final ConsensusCoordinator coord = getCoordinator(membership);
        coord.onViewChanged(view);
        coord.startRound(subject, view.epoch(), 0L);

        // Observer n1 departs via LEAVE — triggering a view change.
        dispatchViewChangePayload(membership, peers[0], new byte[]{ 0x03 });

        // Wait for the round timeout and tick.
        Thread.sleep(200);
        coord.tick();

        final MembershipView afterView = membership.currentView();
        Member s = findMember(afterView, subject);
        assertNotNull(s);
        assertEquals(MemberState.ALIVE, s.state(),
                "subject must remain ALIVE — coordinator must not auto-commit when observers depart");
    }

    // =======================================================================
    // 9. Flapping scenario: repeated refute/suspect cycles converge
    // =======================================================================

    @Test
    void flappingNodeScenario_repeatedRefutationCyclesConverge() throws Exception {
        final NodeAddress local = new NodeAddress("n0", "localhost", 18000);
        final NodeAddress peer = new NodeAddress("n1", "localhost", 18001);

        final InJvmTransport transport = registerTransport(local);
        registerTransport(peer);

        final var config = ClusterConfig.builder().protocolPeriod(Duration.ofHours(1))
                .pingTimeout(Duration.ofMillis(50)).expanderGraphDegree(1)
                .consensusRoundTimeout(Duration.ofSeconds(10)).build();
        final var detector = new PhiAccrualFailureDetector(10);
        final var discovery = new InJvmDiscoveryProvider();
        final var membership = new RapidMembership(local, transport, discovery, config, detector);
        closeables.add(membership);

        final Set<Member> members = Set.of(new Member(local, MemberState.ALIVE, 0),
                new Member(peer, MemberState.ALIVE, 0));
        final var view = new MembershipView(1, members, NOW);
        setStarted(membership, true);
        setCurrentView(membership, view);
        invokeRebuildOverlay(membership, view);
        registerHandlers(membership);

        final ConsensusCoordinator coord = getCoordinator(membership);
        coord.onViewChanged(view);

        // Run several rounds, each cancelled by refutation — verify no infinite loop, no crash.
        for (int i = 1; i <= 5; i++) {
            coord.startRound(peer, view.epoch(), 0L);
            coord.onRefutationReceived(new AliveRefutation(peer, i, view.epoch()));
        }

        // After flapping, no active rounds, state remains consistent.
        final MembershipView afterView = membership.currentView();
        Member m = findMember(afterView, peer);
        assertNotNull(m);
        // peer should be ALIVE (no quorum reached) or SUSPECTED — never DEAD — and no crash.
        assertTrue(m.state() == MemberState.ALIVE || m.state() == MemberState.SUSPECTED,
                "after flapping, peer state must not be DEAD (only consensus-reached transitions)");
        assertDoesNotThrow(() -> coord.tick(), "tick after flapping must not crash");
    }

    // =======================================================================
    // 10. Refutation about self when not being suspected locally is a no-op
    // =======================================================================

    @Test
    void refutation_aboutSelf_whenNotBeingSuspectedLocally_isNoOp() throws Exception {
        final NodeAddress local = new NodeAddress("n0", "localhost", 19000);
        final NodeAddress peer = new NodeAddress("n1", "localhost", 19001);

        final InJvmTransport transport = registerTransport(local);
        registerTransport(peer);

        final var config = ClusterConfig.builder().protocolPeriod(Duration.ofHours(1))
                .pingTimeout(Duration.ofMillis(50)).expanderGraphDegree(1)
                .consensusRoundTimeout(Duration.ofSeconds(10)).build();
        final var detector = new PhiAccrualFailureDetector(10);
        final var discovery = new InJvmDiscoveryProvider();
        final var membership = new RapidMembership(local, transport, discovery, config, detector);
        closeables.add(membership);

        final Set<Member> members = Set.of(new Member(local, MemberState.ALIVE, 0),
                new Member(peer, MemberState.ALIVE, 0));
        final var view = new MembershipView(1, members, NOW);
        setStarted(membership, true);
        setCurrentView(membership, view);
        invokeRebuildOverlay(membership, view);
        registerHandlers(membership);

        // No active rounds — dispatch a refutation about self.
        final AliveRefutation r = new AliveRefutation(local, 1L, view.epoch());
        assertDoesNotThrow(() -> dispatchViewChangePayload(membership, peer, r.serialize()),
                "refutation about self with no active round must silently drop");
    }

    // =======================================================================
    // 11. Close tears down coordinator
    // =======================================================================

    @Test
    void closeDoesNotThrow_whenCoordinatorActive() throws Exception {
        final NodeAddress local = new NodeAddress("n0", "localhost", 20000);
        final NodeAddress peer = new NodeAddress("n1", "localhost", 20001);

        final InJvmTransport transport = registerTransport(local);
        registerTransport(peer);

        final var config = ClusterConfig.builder().protocolPeriod(Duration.ofHours(1))
                .pingTimeout(Duration.ofMillis(50)).expanderGraphDegree(1)
                .consensusRoundTimeout(Duration.ofSeconds(10)).build();
        final var detector = new PhiAccrualFailureDetector(10);
        final var discovery = new InJvmDiscoveryProvider();
        final var membership = new RapidMembership(local, transport, discovery, config, detector);
        closeables.add(membership);

        final Set<Member> members = Set.of(new Member(local, MemberState.ALIVE, 0),
                new Member(peer, MemberState.ALIVE, 0));
        final var view = new MembershipView(1, members, NOW);
        setStarted(membership, true);
        setCurrentView(membership, view);
        invokeRebuildOverlay(membership, view);
        registerHandlers(membership);

        // Create an in-flight round; closing should cancel it without throwing.
        final ConsensusCoordinator coord = getCoordinator(membership);
        coord.onViewChanged(view);
        coord.startRound(peer, view.epoch(), 0L);

        assertDoesNotThrow(() -> membership.close(), "close with active round must not throw");
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    private InJvmTransport registerTransport(NodeAddress addr) {
        final var transport = new InJvmTransport(addr);
        closeables.add(transport);
        return transport;
    }

    private static void setStarted(RapidMembership m, boolean value) throws Exception {
        final Field f = RapidMembership.class.getDeclaredField("started");
        f.setAccessible(true);
        f.set(m, value);
    }

    private static void setCurrentView(RapidMembership m, MembershipView view) throws Exception {
        final Field f = RapidMembership.class.getDeclaredField("currentView");
        f.setAccessible(true);
        f.set(m, view);
    }

    private static void invokeRebuildOverlay(RapidMembership m, MembershipView view)
            throws Exception {
        final Method meth = RapidMembership.class
                .getDeclaredMethod("rebuildOverlayAndNotifyCoordinator", MembershipView.class);
        meth.setAccessible(true);
        meth.invoke(m, view);
    }

    private static void invokeProtocolTick(RapidMembership m) throws Exception {
        final Method meth = RapidMembership.class.getDeclaredMethod("protocolTick");
        meth.setAccessible(true);
        meth.invoke(m);
    }

    private static void registerHandlers(RapidMembership m) throws Exception {
        final Method meth = RapidMembership.class.getDeclaredMethod("registerHandlers");
        meth.setAccessible(true);
        meth.invoke(m);
    }

    private static ExpanderGraphOverlay getOverlay(RapidMembership m) throws Exception {
        final Field f = RapidMembership.class.getDeclaredField("expanderOverlay");
        f.setAccessible(true);
        return (ExpanderGraphOverlay) f.get(m);
    }

    private static ConsensusCoordinator getCoordinator(RapidMembership m) throws Exception {
        final Field f = RapidMembership.class.getDeclaredField("consensusCoordinator");
        f.setAccessible(true);
        return (ConsensusCoordinator) f.get(m);
    }

    /**
     * Dispatch a raw VIEW_CHANGE payload to the membership's registered handler, mimicking the
     * on-wire path (sender must be a view member for proposals; use peer who is in-view).
     */
    private static void dispatchViewChangePayload(RapidMembership m, NodeAddress sender,
            byte[] payload) throws Exception {
        // Use reflection to invoke handleViewChange directly.
        final Method meth = RapidMembership.class.getDeclaredMethod("handleViewChange",
                NodeAddress.class, Message.class);
        meth.setAccessible(true);
        final Message msg = new Message(MessageType.VIEW_CHANGE, sender, 1L, payload);
        meth.invoke(m, sender, msg);
    }

    private static Member findMember(MembershipView view, NodeAddress addr) {
        for (Member m : view.members()) {
            if (m.address().equals(addr)) {
                return m;
            }
        }
        return null;
    }

    private static void assertSuspected(MembershipView view, NodeAddress addr, String message) {
        Member m = findMember(view, addr);
        assertNotNull(m, message + " — member not found");
        assertEquals(MemberState.SUSPECTED, m.state(), message);
    }

    /** Counting transport that records PING targets for assertion. */
    private static final class CountingTransport implements ClusterTransport, AutoCloseable {

        private final NodeAddress local;
        private final Map<MessageType, MessageHandler> handlers = new ConcurrentHashMap<>();
        private final Set<NodeAddress> pingTargets = ConcurrentHashMap.newKeySet();
        private final AtomicInteger sendCount = new AtomicInteger();

        CountingTransport(NodeAddress local) {
            this.local = local;
        }

        Set<NodeAddress> pingTargets() {
            return Set.copyOf(pingTargets);
        }

        @SuppressWarnings("unused")
        int sendCount() {
            return sendCount.get();
        }

        @Override
        public void send(NodeAddress target, Message msg) throws IOException {
            sendCount.incrementAndGet();
        }

        @Override
        public java.util.concurrent.CompletableFuture<Message> request(NodeAddress target,
                Message msg) {
            if (msg.type() == MessageType.PING) {
                pingTargets.add(target);
            }
            sendCount.incrementAndGet();
            // Return a never-completing future so protocolTick's whenComplete is a no-op.
            return new java.util.concurrent.CompletableFuture<>();
        }

        @Override
        public void registerHandler(MessageType type, MessageHandler handler) {
            handlers.put(type, handler);
        }

        @Override
        public void deregisterHandler(MessageType type) {
            handlers.remove(type);
        }

        @Override
        public void close() {
            handlers.clear();
        }
    }
}
