package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.ClusterConfig;
import jlsm.engine.cluster.Member;
import jlsm.engine.cluster.MemberState;
import jlsm.engine.cluster.MembershipView;
import jlsm.engine.cluster.Message;
import jlsm.engine.cluster.MessageType;
import jlsm.engine.cluster.NodeAddress;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@link RapidMembership#handleViewChangeProposal} delegates per-member reconciliation
 * to {@link ViewReconciler} on an epoch advance (F04.R43).
 *
 * <p>
 * The protocol must not wholesale-replace the local view with the proposed view — it must merge
 * per-address records using incarnation/severity rules.
 */
final class RapidMembershipReconciliationTest {

    private static final NodeAddress ADDR_1 = new NodeAddress("node-1", "localhost", 9101);
    private static final NodeAddress ADDR_2 = new NodeAddress("node-2", "localhost", 9102);
    private static final NodeAddress ADDR_3 = new NodeAddress("node-3", "localhost", 9103);

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

    // @spec engine.clustering.R43 — on view-change acceptance, per-member reconciliation preserves the
    // dominant per-member record. The proposal must satisfy R90 (no-drop-alive). Within that
    // constraint, reconciliation must pick the higher-incarnation record per address rather
    // than wholesale-replacing the local view.
    @Test
    @Timeout(10)
    void viewChangeProposal_preservesHigherIncarnationOnAcceptance() throws Exception {
        // Start node-1 and have node-2 join, then leave, then rejoin — the rejoin bumps ADDR_2's
        // incarnation to 1 in node-1's view.
        RapidMembership membership1 = createMembership(ADDR_1);
        membership1.start(List.of());

        InJvmTransport transport2 = new InJvmTransport(ADDR_2);
        closeables.add(transport2);

        // First join: ADDR_2 enters with incarnation 0
        transport2
                .request(ADDR_1,
                        new Message(MessageType.VIEW_CHANGE, ADDR_2, 1, encodeJoinPayload(ADDR_2)))
                .get(5, TimeUnit.SECONDS);
        // Leave: ADDR_2 marked DEAD@0
        transport2
                .request(ADDR_1,
                        new Message(MessageType.VIEW_CHANGE, ADDR_2, 2, new byte[]{ (byte) 0x03 }))
                .get(5, TimeUnit.SECONDS);
        // Rejoin: ADDR_2 becomes ALIVE@1 (existing DEAD@0 → existing.incarnation()+1)
        transport2
                .request(ADDR_1,
                        new Message(MessageType.VIEW_CHANGE, ADDR_2, 3, encodeJoinPayload(ADDR_2)))
                .get(5, TimeUnit.SECONDS);

        MembershipView afterRejoin = membership1.currentView();
        long localIncarnationOfTwo = 0;
        for (Member m : afterRejoin.members()) {
            if (m.address().equals(ADDR_2)) {
                localIncarnationOfTwo = m.incarnation();
            }
        }
        assertTrue(localIncarnationOfTwo >= 1,
                "ADDR_2's incarnation must have bumped via rejoin, got=" + localIncarnationOfTwo);

        // Now node-2 sends a VIEW_CHANGE_PROPOSAL that includes both ADDR_1 and ADDR_2 but
        // reports ADDR_2 at a LOWER incarnation than node-1 currently holds. R90 is satisfied
        // because ADDR_2 is still known (ALIVE) in the proposal — the proposal does not drop
        // any ALIVE member. Reconciliation must preserve the higher local incarnation for
        // ADDR_2 rather than adopt the proposed lower incarnation.
        long proposedEpoch = afterRejoin.epoch() + 1;
        byte[] proposal = encodeViewChangeProposal(proposedEpoch,
                new Member(ADDR_1, MemberState.ALIVE, 0), new Member(ADDR_2, MemberState.ALIVE, 0));
        transport2.request(ADDR_1, new Message(MessageType.VIEW_CHANGE, ADDR_2, 4, proposal)).get(5,
                TimeUnit.SECONDS);

        MembershipView reconciled = membership1.currentView();
        assertTrue(reconciled.epoch() >= proposedEpoch, "epoch must advance on acceptance");
        Member two = null;
        for (Member m : reconciled.members()) {
            if (m.address().equals(ADDR_2)) {
                two = m;
            }
        }
        assertNotNull(two, "ADDR_2 must be present in reconciled view");
        assertEquals(localIncarnationOfTwo, two.incarnation(),
                "higher-incarnation local record must survive reconciliation (not overwritten "
                        + "by proposal's lower incarnation)");
        assertEquals(MemberState.ALIVE, two.state(),
                "ADDR_2 remains ALIVE — both records are ALIVE, higher incarnation wins");
    }

    // --- helpers: payload encoders copied from DispatchRoutingAdversarialTest ---

    private RapidMembership createMembership(NodeAddress addr) {
        InJvmTransport transport = new InJvmTransport(addr);
        closeables.add(transport);
        ClusterConfig config = ClusterConfig.builder().protocolPeriod(Duration.ofMillis(200))
                .pingTimeout(Duration.ofMillis(50)).build();
        PhiAccrualFailureDetector detector = new PhiAccrualFailureDetector(10);
        RapidMembership m = new RapidMembership(addr, transport, new InJvmDiscoveryProvider(),
                config, detector);
        closeables.add(m);
        return m;
    }

    private static byte[] encodeJoinPayload(NodeAddress addr) {
        byte[] nodeId = addr.nodeId().getBytes(StandardCharsets.UTF_8);
        byte[] host = addr.host().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + nodeId.length + 4 + host.length + 4);
        buf.put((byte) 0x01);
        buf.putInt(nodeId.length);
        buf.put(nodeId);
        buf.putInt(host.length);
        buf.put(host);
        buf.putInt(addr.port());
        return buf.array();
    }

    private static byte[] encodeViewChangeProposal(long epoch, Member... members) {
        int size = 1 + 8 + 4;
        List<byte[]> parts = new ArrayList<>();
        for (Member m : members) {
            byte[] nodeId = m.address().nodeId().getBytes(StandardCharsets.UTF_8);
            byte[] host = m.address().host().getBytes(StandardCharsets.UTF_8);
            int memberSize = 4 + nodeId.length + 4 + host.length + 4 + 1 + 8;
            size += memberSize;
            ByteBuffer mbuf = ByteBuffer.allocate(memberSize);
            mbuf.putInt(nodeId.length);
            mbuf.put(nodeId);
            mbuf.putInt(host.length);
            mbuf.put(host);
            mbuf.putInt(m.address().port());
            mbuf.put((byte) m.state().ordinal());
            mbuf.putLong(m.incarnation());
            parts.add(mbuf.array());
        }
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put((byte) 0x04);
        buf.putLong(epoch);
        buf.putInt(members.length);
        for (byte[] p : parts) {
            buf.put(p);
        }
        return buf.array();
    }
}
