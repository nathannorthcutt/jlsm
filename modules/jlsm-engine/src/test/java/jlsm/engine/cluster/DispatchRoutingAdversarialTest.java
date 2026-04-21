package jlsm.engine.cluster;

import jlsm.engine.cluster.internal.InJvmDiscoveryProvider;
import jlsm.engine.cluster.internal.InJvmTransport;
import jlsm.engine.cluster.internal.PhiAccrualFailureDetector;
import jlsm.engine.cluster.internal.RapidMembership;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for dispatch routing in the engine clustering subsystem.
 *
 * @spec engine.clustering.R17 — membership testing: ALIVE/SUSPECTED current vs DEAD departed
 * @spec engine.clustering.R82 — dead members rejected for membership checks / join blocking / leave processing
 * @spec engine.clustering.R88 — view-change proposals validated: non-member sender + malformed payload rejected
 * @spec engine.clustering.R91 — duplicate leave for already-DEAD member does not advance epoch
 */
final class DispatchRoutingAdversarialTest {

    private static final NodeAddress ADDR_1 = new NodeAddress("node-1", "localhost", 9001);
    private static final NodeAddress ADDR_2 = new NodeAddress("node-2", "localhost", 9002);

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

    // Finding: F-R1.dispatch_routing.1.1
    // Bug: Unknown sub-type bytes in VIEW_CHANGE payload silently return ACK — no error signal
    // Correct behavior: Unknown sub-type should cause the future to complete exceptionally
    // with IllegalArgumentException, signaling that the message was not recognized
    // Fix location: RapidMembership.handleViewChange, lines 264-268
    // Regression watch: Ensure known sub-types (0x01, 0x03, 0x04) still dispatch correctly
    @Test
    @Timeout(10)
    void test_handleViewChange_unknownSubType_rejectsWithException() throws Exception {
        // Start node 1 so its VIEW_CHANGE handler is registered
        var membership1 = createMembership(ADDR_1);
        membership1.start(List.of());

        // Create a separate transport for node 2 to send a message to node 1
        var transport2 = new InJvmTransport(ADDR_2);
        closeables.add(transport2);

        // Craft a VIEW_CHANGE message with unknown sub-type byte 0x7F
        // (0x05/0x06/0x07 are now reserved for SUSPICION_PROPOSAL / SUSPICION_VOTE / REFUTATION).
        var unknownSubTypeMsg = new Message(MessageType.VIEW_CHANGE, ADDR_2, 1, new byte[]{ 0x7F });

        // Send the message via request (which invokes the handler and returns the future)
        CompletableFuture<Message> responseFuture = transport2.request(ADDR_1, unknownSubTypeMsg);

        // The handler should reject the unknown sub-type with an exceptional completion
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> responseFuture.get(5, TimeUnit.SECONDS),
                "Unknown sub-type byte should cause exceptional completion, not a silent ACK");
        assertInstanceOf(IllegalArgumentException.class, ex.getCause(),
                "Cause should be IllegalArgumentException for unrecognized sub-type");
    }

    // Finding: F-R1.dispatch_routing.1.3
    // Bug: Empty payload returns ACK — masks truncated/corrupt messages
    // Correct behavior: Empty payload should cause the future to complete exceptionally
    // with IllegalArgumentException, signaling that the message was malformed
    // Fix location: RapidMembership.handleViewChange, lines 247-250
    // Regression watch: Ensure non-empty payloads with valid sub-types still dispatch correctly
    @Test
    @Timeout(10)
    void test_handleViewChange_emptyPayload_rejectsWithException() throws Exception {
        // Start node 1 so its VIEW_CHANGE handler is registered
        var membership1 = createMembership(ADDR_1);
        membership1.start(List.of());

        // Create a separate transport for node 2 to send a message to node 1
        var transport2 = new InJvmTransport(ADDR_2);
        closeables.add(transport2);

        // Craft a VIEW_CHANGE message with empty payload (simulates truncation/corruption)
        var emptyPayloadMsg = new Message(MessageType.VIEW_CHANGE, ADDR_2, 1, new byte[0]);

        // Send the message via request (which invokes the handler and returns the future)
        CompletableFuture<Message> responseFuture = transport2.request(ADDR_1, emptyPayloadMsg);

        // The handler should reject the empty payload with an exceptional completion
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> responseFuture.get(5, TimeUnit.SECONDS),
                "Empty payload should cause exceptional completion, not a silent ACK");
        assertInstanceOf(IllegalArgumentException.class, ex.getCause(),
                "Cause should be IllegalArgumentException for empty/truncated payload");
    }

    // Finding: F-R1.dispatch_routing.1.4
    // Bug: DEAD member blocks rejoin — isMember returns true for DEAD, so handleJoinRequest
    // returns the current view (with the node marked DEAD) instead of re-adding it as ALIVE
    // Correct behavior: A node whose state is DEAD should be treated as re-joinable; the handler
    // should replace the DEAD member with an ALIVE member and return the updated view
    // Fix location: RapidMembership.handleJoinRequest, line 284 (isMember check)
    // Regression watch: Ensure truly ALIVE duplicate joins still return the current view without
    // mutation
    @Test
    @Timeout(10)
    void test_handleJoinRequest_deadMember_allowsRejoinAsAlive() throws Exception {
        // Start node 1 — forms a single-member cluster
        var membership1 = createMembership(ADDR_1);
        membership1.start(List.of());

        // Create a transport for node 2 to send messages to node 1
        var transport2 = new InJvmTransport(ADDR_2);
        closeables.add(transport2);

        // Step 1: Node 2 joins the cluster via a JOIN_REQUEST to Node 1
        byte[] joinPayload = encodeJoinPayload(ADDR_2);
        var joinMsg = new Message(MessageType.VIEW_CHANGE, ADDR_2, 1, joinPayload);
        Message joinResp = transport2.request(ADDR_1, joinMsg).get(5, TimeUnit.SECONDS);
        assertNotNull(joinResp, "Join response should not be null");

        // Verify Node 2 is ALIVE in the response view
        MembershipView joinView = membership1.currentView();
        assertNotNull(joinView, "Current view should not be null after join");
        boolean foundAlive = false;
        for (Member m : joinView.members()) {
            if (m.address().equals(ADDR_2)) {
                assertEquals(MemberState.ALIVE, m.state(),
                        "Node 2 should be ALIVE after initial join");
                foundAlive = true;
            }
        }
        assertTrue(foundAlive, "Node 2 should be in the view after join");

        // Step 2: Node 2 leaves — send a LEAVE notification
        byte[] leavePayload = new byte[]{ 0x03 }; // MSG_LEAVE
        var leaveMsg = new Message(MessageType.VIEW_CHANGE, ADDR_2, 2, leavePayload);
        transport2.request(ADDR_1, leaveMsg).get(5, TimeUnit.SECONDS);

        // Verify Node 2 is now DEAD in the view
        MembershipView leaveView = membership1.currentView();
        for (Member m : leaveView.members()) {
            if (m.address().equals(ADDR_2)) {
                assertEquals(MemberState.DEAD, m.state(), "Node 2 should be DEAD after leave");
            }
        }

        // Step 3: Node 2 restarts and sends a new JOIN_REQUEST — should rejoin as ALIVE
        byte[] rejoinPayload = encodeJoinPayload(ADDR_2);
        var rejoinMsg = new Message(MessageType.VIEW_CHANGE, ADDR_2, 3, rejoinPayload);
        Message rejoinResp = transport2.request(ADDR_1, rejoinMsg).get(5, TimeUnit.SECONDS);
        assertNotNull(rejoinResp, "Rejoin response should not be null");

        // The critical assertion: after rejoin, Node 2 should be ALIVE in the view, not DEAD
        MembershipView rejoinView = membership1.currentView();
        boolean foundRejoinedAlive = false;
        for (Member m : rejoinView.members()) {
            if (m.address().equals(ADDR_2)) {
                assertEquals(MemberState.ALIVE, m.state(),
                        "Node 2 should be ALIVE after rejoin, not DEAD — "
                                + "isMember check should not block rejoin of DEAD members");
                foundRejoinedAlive = true;
            }
        }
        assertTrue(foundRejoinedAlive, "Node 2 should be in the view after rejoin");
    }

    // Finding: F-R1.dispatch_routing.1.5
    // Bug: Duplicate leave notification for an already-DEAD member still increments epoch
    // and fires view-change/member-left notifications — spurious epoch churn
    // Correct behavior: A leave notification for a member already in DEAD state should be
    // a no-op — no epoch bump, no notifications
    // Fix location: RapidMembership.handleLeaveNotification, lines 333-335 (isMember check)
    // Regression watch: Ensure first leave still transitions ALIVE→DEAD and bumps epoch
    @Test
    @Timeout(10)
    void test_handleLeaveNotification_duplicateLeave_doesNotBumpEpoch() throws Exception {
        // Start node 1 — forms a single-member cluster
        var membership1 = createMembership(ADDR_1);
        membership1.start(List.of());

        // Create a transport for node 2 to send messages to node 1
        var transport2 = new InJvmTransport(ADDR_2);
        closeables.add(transport2);

        // Step 1: Node 2 joins the cluster
        byte[] joinPayload = encodeJoinPayload(ADDR_2);
        var joinMsg = new Message(MessageType.VIEW_CHANGE, ADDR_2, 1, joinPayload);
        transport2.request(ADDR_1, joinMsg).get(5, TimeUnit.SECONDS);

        // Step 2: First leave — should transition Node 2 to DEAD and bump epoch
        byte[] leavePayload = new byte[]{ 0x03 }; // MSG_LEAVE
        var leaveMsg1 = new Message(MessageType.VIEW_CHANGE, ADDR_2, 2, leavePayload);
        transport2.request(ADDR_1, leaveMsg1).get(5, TimeUnit.SECONDS);

        MembershipView afterFirstLeave = membership1.currentView();
        long epochAfterFirstLeave = afterFirstLeave.epoch();

        // Verify Node 2 is DEAD after first leave
        for (Member m : afterFirstLeave.members()) {
            if (m.address().equals(ADDR_2)) {
                assertEquals(MemberState.DEAD, m.state(),
                        "Node 2 should be DEAD after first leave");
            }
        }

        // Step 3: Duplicate leave — should be a no-op (no epoch bump)
        var leaveMsg2 = new Message(MessageType.VIEW_CHANGE, ADDR_2, 3, leavePayload);
        transport2.request(ADDR_1, leaveMsg2).get(5, TimeUnit.SECONDS);

        MembershipView afterDuplicateLeave = membership1.currentView();
        assertEquals(epochAfterFirstLeave, afterDuplicateLeave.epoch(),
                "Duplicate leave for already-DEAD member should not bump epoch — " + "epoch was "
                        + epochAfterFirstLeave + " but became " + afterDuplicateLeave.epoch());
    }

    // Finding: F-R1.dispatch_routing.1.6
    // Bug: handleViewChangeProposal uses isMember to detect departures, but isMember returns true
    // for DEAD members — so a member transitioning ALIVE→DEAD in the proposed view is not
    // detected as a departure and notifyMemberLeft is never called
    // Correct behavior: When a proposed view transitions a member from ALIVE to DEAD,
    // onMemberLeft should fire for that member
    // Fix location: RapidMembership.handleViewChangeProposal, lines 393-396 (departure detection
    // loop)
    // Regression watch: Ensure members completely absent from proposed view still trigger
    // onMemberLeft
    @Test
    @Timeout(10)
    void test_handleViewChangeProposal_deadMemberInProposedView_firesOnMemberLeft()
            throws Exception {
        // Start node 1 — forms a single-member cluster
        var membership1 = createMembership(ADDR_1);
        membership1.start(List.of());

        // Create a transport for node 2 to send messages to node 1
        var transport2 = new InJvmTransport(ADDR_2);
        closeables.add(transport2);

        // Step 1: Node 2 joins the cluster via a JOIN_REQUEST to Node 1
        byte[] joinPayload = encodeJoinPayload(ADDR_2);
        var joinMsg = new Message(MessageType.VIEW_CHANGE, ADDR_2, 1, joinPayload);
        transport2.request(ADDR_1, joinMsg).get(5, TimeUnit.SECONDS);

        // Verify both nodes are ALIVE and get the current epoch
        MembershipView viewAfterJoin = membership1.currentView();
        long epochAfterJoin = viewAfterJoin.epoch();
        assertTrue(epochAfterJoin >= 1, "Epoch should be at least 1 after join");

        // Step 2: Register a listener to capture onMemberLeft events
        List<Member> leftMembers = new ArrayList<>();
        CountDownLatch leftLatch = new CountDownLatch(1);
        membership1.addListener(new MembershipListener() {
            @Override
            public void onViewChanged(MembershipView oldView, MembershipView newView) {
            }

            @Override
            public void onMemberJoined(Member member) {
            }

            @Override
            public void onMemberLeft(Member member) {
                leftMembers.add(member);
                leftLatch.countDown();
            }

            @Override
            public void onMemberSuspected(Member member) {
            }
        });

        // Step 3: Craft a VIEW_CHANGE_PROPOSAL with Node 2 marked as DEAD
        // The proposed view has a higher epoch and contains both nodes, but Node 2 is DEAD
        long proposedEpoch = epochAfterJoin + 1;
        byte[] proposalPayload = encodeViewChangeProposal(proposedEpoch,
                new Member(ADDR_1, MemberState.ALIVE, 0), new Member(ADDR_2, MemberState.DEAD, 0));

        var proposalMsg = new Message(MessageType.VIEW_CHANGE, ADDR_2, 4, proposalPayload);
        transport2.request(ADDR_1, proposalMsg).get(5, TimeUnit.SECONDS);

        // Listener dispatch is async (F04.R39) — wait for delivery before asserting.
        assertTrue(leftLatch.await(3, TimeUnit.SECONDS),
                "onMemberLeft should be delivered by the dispatcher within 3s");

        // The critical assertion: onMemberLeft should have fired for Node 2
        // because it transitioned from ALIVE to DEAD
        assertFalse(leftMembers.isEmpty(),
                "onMemberLeft should fire when a member transitions ALIVE→DEAD in a proposed view — "
                        + "isMember returns true for DEAD members so the departure loop misses it");
        assertEquals(ADDR_2, leftMembers.getFirst().address(),
                "The departed member should be Node 2");
    }

    // Finding: F-R1.dispatch_routing.1.7
    // Bug: handleViewChangeProposal does not verify the sender is a current member — any
    // non-member node can inject a fabricated view with a higher epoch
    // Correct behavior: A view change proposal from a non-member sender should be rejected
    // (no view change, no notifications) — the future should complete exceptionally
    // Fix location: RapidMembership.handleViewChangeProposal, line 368 (sender check)
    // Regression watch: Ensure view change proposals from actual members still work
    @Test
    @Timeout(10)
    void test_handleViewChangeProposal_nonMemberSender_rejectsWithException() throws Exception {
        // Start node 1 — forms a single-member cluster
        var membership1 = createMembership(ADDR_1);
        membership1.start(List.of());

        // Node 2 is NOT a member — it never joined
        var transport2 = new InJvmTransport(ADDR_2);
        closeables.add(transport2);

        // Capture initial state
        MembershipView viewBefore = membership1.currentView();
        long epochBefore = viewBefore.epoch();

        // Craft a VIEW_CHANGE_PROPOSAL from the non-member (ADDR_2) with a higher epoch
        // that tries to inject a completely fabricated view
        NodeAddress fakeNode = new NodeAddress("fake-node", "evil.host", 6666);
        long maliciousEpoch = epochBefore + 100;
        byte[] proposalPayload = encodeViewChangeProposal(maliciousEpoch,
                new Member(ADDR_1, MemberState.ALIVE, 0),
                new Member(fakeNode, MemberState.ALIVE, 0));

        var proposalMsg = new Message(MessageType.VIEW_CHANGE, ADDR_2, 1, proposalPayload);
        CompletableFuture<Message> responseFuture = transport2.request(ADDR_1, proposalMsg);

        // The handler should reject the proposal from a non-member sender
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> responseFuture.get(5, TimeUnit.SECONDS),
                "View change proposal from non-member should be rejected, not silently accepted");
        assertInstanceOf(IllegalArgumentException.class, ex.getCause(),
                "Cause should be IllegalArgumentException for non-member sender");

        // View should NOT have changed
        MembershipView viewAfter = membership1.currentView();
        assertEquals(epochBefore, viewAfter.epoch(),
                "Epoch should not change after rejected proposal from non-member");
    }

    // --- Helpers ---

    private InJvmTransport createTransport(NodeAddress addr) {
        var transport = new InJvmTransport(addr);
        closeables.add(transport);
        return transport;
    }

    private static byte[] encodeJoinPayload(NodeAddress addr) {
        final byte[] nodeIdBytes = addr.nodeId().getBytes(StandardCharsets.UTF_8);
        final byte[] hostBytes = addr.host().getBytes(StandardCharsets.UTF_8);
        // [subType:1][nodeIdLen:4][nodeId:n][hostLen:4][host:n][port:4]
        final ByteBuffer buf = ByteBuffer
                .allocate(1 + 4 + nodeIdBytes.length + 4 + hostBytes.length + 4);
        buf.put((byte) 0x01); // MSG_JOIN_REQUEST
        buf.putInt(nodeIdBytes.length);
        buf.put(nodeIdBytes);
        buf.putInt(hostBytes.length);
        buf.put(hostBytes);
        buf.putInt(addr.port());
        return buf.array();
    }

    private static byte[] encodeViewChangeProposal(long epoch, Member... members) {
        // [subType:1][epoch:8][memberCount:4][members...]
        // Each member: [nodeIdLen:4][nodeId][hostLen:4][host][port:4][state:1][incarnation:8]
        final byte subType = 0x04; // MSG_VIEW_CHANGE_PROPOSAL
        int size = 1 + 8 + 4;
        final var memberData = new ArrayList<byte[]>();
        for (Member m : members) {
            final byte[] nodeId = m.address().nodeId().getBytes(StandardCharsets.UTF_8);
            final byte[] host = m.address().host().getBytes(StandardCharsets.UTF_8);
            final int memberSize = 4 + nodeId.length + 4 + host.length + 4 + 1 + 8;
            size += memberSize;
            final ByteBuffer mbuf = ByteBuffer.allocate(memberSize);
            mbuf.putInt(nodeId.length);
            mbuf.put(nodeId);
            mbuf.putInt(host.length);
            mbuf.put(host);
            mbuf.putInt(m.address().port());
            mbuf.put((byte) m.state().ordinal());
            mbuf.putLong(m.incarnation());
            memberData.add(mbuf.array());
        }
        final ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(subType);
        buf.putLong(epoch);
        buf.putInt(members.length);
        for (byte[] md : memberData) {
            buf.put(md);
        }
        return buf.array();
    }

    private RapidMembership createMembership(NodeAddress addr) {
        var transport = createTransport(addr);
        var config = ClusterConfig.builder().protocolPeriod(Duration.ofMillis(100))
                .pingTimeout(Duration.ofMillis(50)).build();
        var detector = new PhiAccrualFailureDetector(10);
        var membership = new RapidMembership(addr, transport, new InJvmDiscoveryProvider(), config,
                detector);
        closeables.add(membership);
        return membership;
    }
}
