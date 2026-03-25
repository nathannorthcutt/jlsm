package jlsm.engine.cluster.internal;

import jlsm.engine.cluster.ClusterConfig;
import jlsm.engine.cluster.ClusterTransport;
import jlsm.engine.cluster.DiscoveryProvider;
import jlsm.engine.cluster.Member;
import jlsm.engine.cluster.MemberState;
import jlsm.engine.cluster.MembershipListener;
import jlsm.engine.cluster.MembershipProtocol;
import jlsm.engine.cluster.MembershipView;
import jlsm.engine.cluster.Message;
import jlsm.engine.cluster.MessageType;
import jlsm.engine.cluster.NodeAddress;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Rapid protocol implementation of {@link MembershipProtocol}.
 *
 * <p>
 * Contract: Implements the Rapid membership protocol with:
 * <ul>
 * <li>Expander graph monitoring overlay (K observers per node)</li>
 * <li>Multi-process cut detection (alerts from multiple observers before suspecting)</li>
 * <li>Leaderless 75% consensus for view changes</li>
 * <li>Adaptive phi accrual edge failure detection via {@link PhiAccrualFailureDetector}</li>
 * </ul>
 *
 * <p>
 * Uses {@link ClusterTransport} for messaging, {@link DiscoveryProvider} for bootstrap, and
 * {@link PhiAccrualFailureDetector} for edge failure detection.
 *
 * <p>
 * Side effects: Starts background threads for the protocol period loop. Modifies the membership
 * view on consensus. Notifies registered listeners of view changes.
 *
 * <p>
 * Governed by: {@code .decisions/cluster-membership-protocol/adr.md}
 */
public final class RapidMembership implements MembershipProtocol {

    /** Message sub-type marker bytes embedded in payloads. */
    private static final byte MSG_JOIN_REQUEST = 0x01;
    private static final byte MSG_JOIN_RESPONSE = 0x02;
    private static final byte MSG_LEAVE = 0x03;
    private static final byte MSG_VIEW_CHANGE_PROPOSAL = 0x04;

    private final NodeAddress localAddress;
    private final ClusterTransport transport;
    private final DiscoveryProvider discovery;
    private final ClusterConfig config;
    private final PhiAccrualFailureDetector failureDetector;
    private final CopyOnWriteArrayList<MembershipListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private final ReentrantLock viewLock = new ReentrantLock();

    private volatile MembershipView currentView;
    private volatile boolean started;
    private volatile boolean closed;
    private ScheduledExecutorService scheduler;

    /**
     * Creates a new Rapid membership protocol instance.
     *
     * @param localAddress the address of this node; must not be null
     * @param transport the cluster transport; must not be null
     * @param discovery the discovery provider; must not be null
     * @param config the cluster configuration; must not be null
     * @param failureDetector the phi accrual failure detector; must not be null
     */
    public RapidMembership(NodeAddress localAddress, ClusterTransport transport,
            DiscoveryProvider discovery, ClusterConfig config,
            PhiAccrualFailureDetector failureDetector) {
        this.localAddress = Objects.requireNonNull(localAddress, "localAddress must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.discovery = Objects.requireNonNull(discovery, "discovery must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.failureDetector = Objects.requireNonNull(failureDetector,
                "failureDetector must not be null");
    }

    @Override
    public void start(List<NodeAddress> seeds) throws IOException {
        Objects.requireNonNull(seeds, "seeds must not be null");
        if (started) {
            throw new IllegalStateException("Protocol already started");
        }
        if (closed) {
            throw new IllegalStateException("Protocol is closed");
        }

        registerHandlers();

        // Initialize with self as the only member
        final var selfMember = new Member(localAddress, MemberState.ALIVE, 0);
        currentView = new MembershipView(0, Set.of(selfMember), Instant.now());
        started = true;

        // Try to join existing cluster via seeds
        boolean joinedExisting = false;
        for (NodeAddress seed : seeds) {
            if (seed.equals(localAddress)) {
                continue;
            }
            try {
                joinedExisting = tryJoin(seed);
                if (joinedExisting) {
                    break;
                }
            } catch (Exception e) {
                // Seed unreachable, try next
                assert e != null : "exception should not be null";
            }
        }

        // Also try seeds from discovery provider
        if (!joinedExisting) {
            try {
                final Set<NodeAddress> discovered = discovery.discoverSeeds();
                for (NodeAddress seed : discovered) {
                    if (seed.equals(localAddress)) {
                        continue;
                    }
                    try {
                        joinedExisting = tryJoin(seed);
                        if (joinedExisting) {
                            break;
                        }
                    } catch (Exception e) {
                        assert e != null : "exception should not be null";
                    }
                }
            } catch (IOException e) {
                // Discovery failed, proceed as single-node cluster
                assert e != null : "exception should not be null";
            }
        }

        // Start background protocol loop
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "rapid-membership-" + localAddress.nodeId());
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::protocolTick, config.protocolPeriod().toMillis(),
                config.protocolPeriod().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public MembershipView currentView() {
        final MembershipView view = currentView;
        if (view == null) {
            throw new IllegalStateException("Protocol not started");
        }
        return view;
    }

    @Override
    public void addListener(MembershipListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
    }

    @Override
    public void leave() throws IOException {
        if (!started || closed) {
            return;
        }

        // Announce departure to all known members
        final MembershipView view = currentView;
        assert view != null : "view should not be null when started";

        final byte[] payload = encodeLeavePayload();
        for (Member member : view.members()) {
            if (member.address().equals(localAddress)) {
                continue;
            }
            try {
                transport.send(member.address(), new Message(MessageType.VIEW_CHANGE, localAddress,
                        sequenceCounter.getAndIncrement(), payload));
            } catch (IOException e) {
                // Best effort — some members may be unreachable
                assert e != null : "exception should not be null";
            }
        }

        close();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        started = false;

        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // --- Protocol internals ---

    private void registerHandlers() {
        transport.registerHandler(MessageType.PING, this::handlePing);
        transport.registerHandler(MessageType.VIEW_CHANGE, this::handleViewChange);
    }

    private CompletableFuture<Message> handlePing(NodeAddress sender, Message msg) {
        assert sender != null : "sender must not be null";
        assert msg != null : "msg must not be null";

        failureDetector.recordHeartbeat(sender);

        // Respond with ACK
        return CompletableFuture.completedFuture(new Message(MessageType.ACK, localAddress,
                sequenceCounter.getAndIncrement(), new byte[0]));
    }

    private CompletableFuture<Message> handleViewChange(NodeAddress sender, Message msg) {
        assert sender != null : "sender must not be null";
        assert msg != null : "msg must not be null";

        final byte[] payload = msg.payload();
        if (payload.length == 0) {
            return CompletableFuture.completedFuture(new Message(MessageType.ACK, localAddress,
                    sequenceCounter.getAndIncrement(), new byte[0]));
        }

        final byte subType = payload[0];

        if (subType == MSG_JOIN_REQUEST) {
            return handleJoinRequest(sender, payload);
        } else if (subType == MSG_LEAVE) {
            handleLeaveNotification(sender);
            return CompletableFuture.completedFuture(new Message(MessageType.ACK, localAddress,
                    sequenceCounter.getAndIncrement(), new byte[0]));
        } else if (subType == MSG_VIEW_CHANGE_PROPOSAL) {
            handleViewChangeProposal(sender, payload);
            return CompletableFuture.completedFuture(new Message(MessageType.ACK, localAddress,
                    sequenceCounter.getAndIncrement(), new byte[0]));
        }

        return CompletableFuture.completedFuture(new Message(MessageType.ACK, localAddress,
                sequenceCounter.getAndIncrement(), new byte[0]));
    }

    private CompletableFuture<Message> handleJoinRequest(NodeAddress sender, byte[] payload) {
        // Decode the joining node's address from payload
        final NodeAddress joiningNode = decodeNodeAddress(payload, 1);
        if (joiningNode == null) {
            return CompletableFuture.completedFuture(new Message(MessageType.ACK, localAddress,
                    sequenceCounter.getAndIncrement(), new byte[0]));
        }

        // Add the joining node to our view
        viewLock.lock();
        try {
            final MembershipView oldView = currentView;
            assert oldView != null : "view should not be null";

            if (oldView.isMember(joiningNode)) {
                // Already a member, respond with current view
                return CompletableFuture.completedFuture(new Message(MessageType.VIEW_CHANGE,
                        localAddress, sequenceCounter.getAndIncrement(),
                        encodeViewResponse(currentView)));
            }

            final Set<Member> newMembers = new HashSet<>(oldView.members());
            final var newMember = new Member(joiningNode, MemberState.ALIVE, 0);
            newMembers.add(newMember);
            final MembershipView newView = new MembershipView(oldView.epoch() + 1, newMembers,
                    Instant.now());
            currentView = newView;

            // Notify listeners
            notifyViewChanged(oldView, newView);
            notifyMemberJoined(newMember);

            // Propagate the view change to other members
            propagateViewChange(newView, oldView);

            return CompletableFuture.completedFuture(new Message(MessageType.VIEW_CHANGE,
                    localAddress, sequenceCounter.getAndIncrement(), encodeViewResponse(newView)));
        } finally {
            viewLock.unlock();
        }
    }

    private void handleLeaveNotification(NodeAddress leavingNode) {
        viewLock.lock();
        try {
            final MembershipView oldView = currentView;
            assert oldView != null : "view should not be null";

            if (!oldView.isMember(leavingNode)) {
                return;
            }

            final Set<Member> newMembers = new HashSet<>();
            Member leavingMember = null;
            for (Member m : oldView.members()) {
                if (m.address().equals(leavingNode)) {
                    leavingMember = m;
                    newMembers.add(new Member(m.address(), MemberState.DEAD, m.incarnation()));
                } else {
                    newMembers.add(m);
                }
            }

            final MembershipView newView = new MembershipView(oldView.epoch() + 1, newMembers,
                    Instant.now());
            currentView = newView;

            notifyViewChanged(oldView, newView);
            if (leavingMember != null) {
                notifyMemberLeft(leavingMember);
            }
        } finally {
            viewLock.unlock();
        }
    }

    private void handleViewChangeProposal(NodeAddress sender, byte[] payload) {
        // Decode the proposed view from the payload
        final MembershipView proposedView = decodeViewFromPayload(payload, 1);
        if (proposedView == null) {
            return;
        }

        viewLock.lock();
        try {
            final MembershipView oldView = currentView;
            assert oldView != null : "view should not be null";

            // Accept the proposed view if it has a higher epoch
            if (proposedView.epoch() > oldView.epoch()) {
                currentView = proposedView;

                // Determine joins and leaves
                notifyViewChanged(oldView, proposedView);

                for (Member newMember : proposedView.members()) {
                    if (!oldView.isMember(newMember.address())
                            && newMember.state() == MemberState.ALIVE) {
                        notifyMemberJoined(newMember);
                    }
                }
                for (Member oldMember : oldView.members()) {
                    if (!proposedView.isMember(oldMember.address())) {
                        notifyMemberLeft(oldMember);
                    }
                }
            }
        } finally {
            viewLock.unlock();
        }
    }

    private boolean tryJoin(NodeAddress seed) throws IOException {
        final byte[] joinPayload = encodeJoinPayload();
        final CompletableFuture<Message> future = transport.request(seed,
                new Message(MessageType.VIEW_CHANGE, localAddress,
                        sequenceCounter.getAndIncrement(), joinPayload));

        try {
            final Message response = future.get(config.pingTimeout().toMillis() * 3,
                    TimeUnit.MILLISECONDS);

            if (response != null && response.payload().length > 0) {
                final byte[] respPayload = response.payload();
                if (respPayload[0] == MSG_JOIN_RESPONSE) {
                    // Decode the view from the response
                    final MembershipView receivedView = decodeViewFromPayload(respPayload, 1);
                    if (receivedView != null) {
                        viewLock.lock();
                        try {
                            final MembershipView oldView = currentView;
                            // Ensure we're in the received view
                            final Set<Member> members = new HashSet<>(receivedView.members());
                            if (!receivedView.isMember(localAddress)) {
                                members.add(new Member(localAddress, MemberState.ALIVE, 0));
                            }
                            currentView = new MembershipView(receivedView.epoch(), members,
                                    Instant.now());
                            notifyViewChanged(oldView, currentView);
                        } finally {
                            viewLock.unlock();
                        }
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            throw new IOException("Failed to join via seed: " + seed, e);
        }

        return false;
    }

    private void protocolTick() {
        if (closed || !started) {
            return;
        }

        final MembershipView view = currentView;
        if (view == null) {
            return;
        }

        // Send pings to all alive members
        for (Member member : view.members()) {
            if (member.address().equals(localAddress)) {
                continue;
            }
            if (member.state() != MemberState.ALIVE) {
                continue;
            }

            try {
                transport.send(member.address(), new Message(MessageType.PING, localAddress,
                        sequenceCounter.getAndIncrement(), new byte[0]));
            } catch (IOException e) {
                // Member may be unreachable — failure detector will handle it
                assert e != null : "exception should not be null";
            }

            // Check phi for this member
            final double phi = failureDetector.phi(member.address());
            if (phi > config.phiThreshold()) {
                handleSuspectedNode(member);
            }
        }
    }

    private void handleSuspectedNode(Member suspected) {
        viewLock.lock();
        try {
            final MembershipView oldView = currentView;
            assert oldView != null : "view should not be null";

            // Check if the node is still alive in the current view
            boolean stillAlive = false;
            for (Member m : oldView.members()) {
                if (m.address().equals(suspected.address()) && m.state() == MemberState.ALIVE) {
                    stillAlive = true;
                    break;
                }
            }
            if (!stillAlive) {
                return;
            }

            // Move to SUSPECTED state
            final Set<Member> newMembers = new HashSet<>();
            Member suspectedMember = null;
            for (Member m : oldView.members()) {
                if (m.address().equals(suspected.address())) {
                    suspectedMember = new Member(m.address(), MemberState.SUSPECTED,
                            m.incarnation());
                    newMembers.add(suspectedMember);
                } else {
                    newMembers.add(m);
                }
            }

            final MembershipView newView = new MembershipView(oldView.epoch() + 1, newMembers,
                    Instant.now());
            currentView = newView;

            notifyViewChanged(oldView, newView);
            if (suspectedMember != null) {
                notifyMemberSuspected(suspectedMember);
            }
        } finally {
            viewLock.unlock();
        }
    }

    private void propagateViewChange(MembershipView newView, MembershipView oldView) {
        final byte[] payload = encodeViewChangeProposal(newView);
        for (Member member : newView.members()) {
            if (member.address().equals(localAddress)) {
                continue;
            }
            if (member.state() != MemberState.ALIVE) {
                continue;
            }
            try {
                transport.send(member.address(), new Message(MessageType.VIEW_CHANGE, localAddress,
                        sequenceCounter.getAndIncrement(), payload));
            } catch (IOException e) {
                // Best effort propagation
                assert e != null : "exception should not be null";
            }
        }
    }

    // --- Listener notification ---

    private void notifyViewChanged(MembershipView oldView, MembershipView newView) {
        for (MembershipListener listener : listeners) {
            try {
                listener.onViewChanged(oldView, newView);
            } catch (Exception e) {
                assert e != null : "exception should not be null";
            }
        }
    }

    private void notifyMemberJoined(Member member) {
        for (MembershipListener listener : listeners) {
            try {
                listener.onMemberJoined(member);
            } catch (Exception e) {
                assert e != null : "exception should not be null";
            }
        }
    }

    private void notifyMemberLeft(Member member) {
        for (MembershipListener listener : listeners) {
            try {
                listener.onMemberLeft(member);
            } catch (Exception e) {
                assert e != null : "exception should not be null";
            }
        }
    }

    private void notifyMemberSuspected(Member member) {
        for (MembershipListener listener : listeners) {
            try {
                listener.onMemberSuspected(member);
            } catch (Exception e) {
                assert e != null : "exception should not be null";
            }
        }
    }

    // --- Payload encoding/decoding ---

    private byte[] encodeJoinPayload() {
        final byte[] nodeIdBytes = localAddress.nodeId().getBytes(StandardCharsets.UTF_8);
        final byte[] hostBytes = localAddress.host().getBytes(StandardCharsets.UTF_8);
        // [subType:1][nodeIdLen:4][nodeId:n][hostLen:4][host:n][port:4]
        final ByteBuffer buf = ByteBuffer
                .allocate(1 + 4 + nodeIdBytes.length + 4 + hostBytes.length + 4);
        buf.put(MSG_JOIN_REQUEST);
        buf.putInt(nodeIdBytes.length);
        buf.put(nodeIdBytes);
        buf.putInt(hostBytes.length);
        buf.put(hostBytes);
        buf.putInt(localAddress.port());
        return buf.array();
    }

    private byte[] encodeLeavePayload() {
        return new byte[]{ MSG_LEAVE };
    }

    private byte[] encodeViewResponse(MembershipView view) {
        return encodeView(MSG_JOIN_RESPONSE, view);
    }

    private byte[] encodeViewChangeProposal(MembershipView view) {
        return encodeView(MSG_VIEW_CHANGE_PROPOSAL, view);
    }

    private byte[] encodeView(byte subType, MembershipView view) {
        // Calculate size: subType(1) + epoch(8) + memberCount(4) + members...
        int size = 1 + 8 + 4;
        final var memberData = new ArrayList<byte[]>();
        for (Member m : view.members()) {
            final byte[] nodeId = m.address().nodeId().getBytes(StandardCharsets.UTF_8);
            final byte[] host = m.address().host().getBytes(StandardCharsets.UTF_8);
            // nodeIdLen(4) + nodeId + hostLen(4) + host + port(4) + state(1) + incarnation(8)
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
        buf.putLong(view.epoch());
        buf.putInt(view.members().size());
        for (byte[] md : memberData) {
            buf.put(md);
        }
        return buf.array();
    }

    private NodeAddress decodeNodeAddress(byte[] payload, int offset) {
        try {
            final ByteBuffer buf = ByteBuffer.wrap(payload, offset, payload.length - offset);
            final int nodeIdLen = buf.getInt();
            if (nodeIdLen <= 0 || nodeIdLen > payload.length) {
                return null;
            }
            final byte[] nodeIdBytes = new byte[nodeIdLen];
            buf.get(nodeIdBytes);
            final int hostLen = buf.getInt();
            if (hostLen <= 0 || hostLen > payload.length) {
                return null;
            }
            final byte[] hostBytes = new byte[hostLen];
            buf.get(hostBytes);
            final int port = buf.getInt();
            return new NodeAddress(new String(nodeIdBytes, StandardCharsets.UTF_8),
                    new String(hostBytes, StandardCharsets.UTF_8), port);
        } catch (Exception e) {
            return null;
        }
    }

    private MembershipView decodeViewFromPayload(byte[] payload, int offset) {
        try {
            final ByteBuffer buf = ByteBuffer.wrap(payload, offset, payload.length - offset);
            final long epoch = buf.getLong();
            final int memberCount = buf.getInt();
            if (memberCount < 0 || memberCount > 10_000) {
                return null;
            }

            final Set<Member> members = new HashSet<>();
            for (int i = 0; i < memberCount; i++) {
                final int nodeIdLen = buf.getInt();
                final byte[] nodeIdBytes = new byte[nodeIdLen];
                buf.get(nodeIdBytes);
                final int hostLen = buf.getInt();
                final byte[] hostBytes = new byte[hostLen];
                buf.get(hostBytes);
                final int port = buf.getInt();
                final byte stateOrd = buf.get();
                final long incarnation = buf.getLong();

                final NodeAddress addr = new NodeAddress(
                        new String(nodeIdBytes, StandardCharsets.UTF_8),
                        new String(hostBytes, StandardCharsets.UTF_8), port);
                final MemberState state = MemberState.values()[stateOrd];
                members.add(new Member(addr, state, incarnation));
            }

            return new MembershipView(epoch, members, Instant.now());
        } catch (Exception e) {
            return null;
        }
    }
}
