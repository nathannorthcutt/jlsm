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
import java.time.Clock;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Rapid protocol implementation of {@link MembershipProtocol}.
 *
 * <p>
 * Contract: Implements the RAPID protocol with:
 * <ul>
 * <li>Expander-graph monitoring overlay — each node pings only its outgoing monitors rather than
 * every ALIVE member (see {@link ExpanderGraphOverlay}).</li>
 * <li>Observer-quorum consensus before SUSPECT view changes — when a monitored peer's phi exceeds
 * threshold, a {@link ConsensusCoordinator} round is started; the view transitions only on a quorum
 * of agreeing observer votes.</li>
 * <li>Self-refutation via incarnation bump — a live node receiving a suspicion about itself
 * increments its incarnation and broadcasts an {@link AliveRefutation} so peers cancel pending
 * rounds.</li>
 * <li>Adaptive phi accrual edge failure detection via {@link PhiAccrualFailureDetector}.</li>
 * <li>View change propagation — view mutations are broadcast to alive members for convergence with
 * epoch-ordered acceptance.</li>
 * </ul>
 *
 * <p>
 * Uses {@link ClusterTransport} for messaging, {@link DiscoveryProvider} for bootstrap, and
 * {@link PhiAccrualFailureDetector} for edge failure detection.
 *
 * <p>
 * Side effects: Starts background threads for the protocol period loop. Modifies the membership
 * view on view change acceptance. Notifies registered listeners of view changes.
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
    private static final byte MSG_SUSPICION_PROPOSAL = SuspicionProposal.SUB_TYPE;
    private static final byte MSG_SUSPICION_VOTE = SuspicionVote.SUB_TYPE;
    private static final byte MSG_ALIVE_REFUTATION = AliveRefutation.SUB_TYPE;

    /** Listener dispatcher queue capacity — bounded to prevent unbounded memory growth. */
    private static final int LISTENER_QUEUE_CAPACITY = 1024;

    private final NodeAddress localAddress;
    private final ClusterTransport transport;
    private final DiscoveryProvider discovery;
    private final ClusterConfig config;
    private final PhiAccrualFailureDetector failureDetector;
    private final CopyOnWriteArrayList<MembershipListener> listeners = new CopyOnWriteArrayList<>();
    private final ListenerDispatcher<MembershipListener> listenerDispatcher;
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private final ReentrantLock viewLock = new ReentrantLock();

    /**
     * Expander-graph monitoring overlay. Rebuilt after every view mutation to reflect the current
     * ALIVE membership set. See {@link ExpanderGraphOverlay}.
     */
    private final ExpanderGraphOverlay expanderOverlay;

    /**
     * Consensus coordinator that brokers observer-quorum rounds before SUSPECT view changes are
     * committed. Installed at construction with a {@link MonotonicClock} by default.
     */
    private final ConsensusCoordinator consensusCoordinator;

    private volatile MembershipView currentView;
    private volatile boolean started;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;

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
        this(localAddress, transport, discovery, config, failureDetector, new MonotonicClock());
    }

    /**
     * Creates a new Rapid membership protocol instance with an explicit monotonic clock. For
     * testing; production callers should use the 5-arg constructor which supplies
     * {@link MonotonicClock}.
     *
     * @param localAddress the address of this node; must not be null
     * @param transport the cluster transport; must not be null
     * @param discovery the discovery provider; must not be null
     * @param config the cluster configuration; must not be null
     * @param failureDetector the phi accrual failure detector; must not be null
     * @param monotonicClock the monotonic clock supplied to the consensus coordinator; must not be
     *            null
     */
    public RapidMembership(NodeAddress localAddress, ClusterTransport transport,
            DiscoveryProvider discovery, ClusterConfig config,
            PhiAccrualFailureDetector failureDetector, Clock monotonicClock) {
        this.localAddress = Objects.requireNonNull(localAddress, "localAddress must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.discovery = Objects.requireNonNull(discovery, "discovery must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.failureDetector = Objects.requireNonNull(failureDetector,
                "failureDetector must not be null");
        Objects.requireNonNull(monotonicClock, "monotonicClock must not be null");
        this.listenerDispatcher = new ListenerDispatcher<>(
                "membership-listeners-" + localAddress.nodeId(), LISTENER_QUEUE_CAPACITY);
        this.expanderOverlay = new ExpanderGraphOverlay();
        this.consensusCoordinator = new ConsensusCoordinator(localAddress, transport,
                expanderOverlay, monotonicClock, config.consensusRoundTimeout(),
                config.consensusQuorumPercent(), new CoordinatorSink());
    }

    @Override
    public void start(List<NodeAddress> seeds) throws IOException {
        Objects.requireNonNull(seeds, "seeds must not be null");
        viewLock.lock();
        try {
            if (started) {
                throw new IllegalStateException("Protocol already started");
            }
            if (closed.get()) {
                throw new IllegalStateException("Protocol is closed");
            }
            started = true;
        } finally {
            viewLock.unlock();
        }

        registerHandlers();

        // Initialize with self as the only member
        final var selfMember = new Member(localAddress, MemberState.ALIVE, 0);
        currentView = new MembershipView(0, Set.of(selfMember), Instant.now());
        rebuildOverlayAndNotifyCoordinator(currentView);

        try {
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
            final var sched = Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread t = new Thread(r, "rapid-membership-" + localAddress.nodeId());
                t.setDaemon(true);
                return t;
            });
            scheduler = sched;

            // Guard against concurrent close(): if close() ran between started=true and
            // this point, it saw scheduler==null and skipped shutdown. We must detect that
            // and shut down the scheduler ourselves to prevent a thread leak.
            if (closed.get()) {
                sched.shutdownNow();
                started = false;
                currentView = null;
                transport.deregisterHandler(MessageType.PING);
                transport.deregisterHandler(MessageType.VIEW_CHANGE);
                return;
            }

            sched.scheduleAtFixedRate(this::protocolTick, config.protocolPeriod().toMillis(),
                    config.protocolPeriod().toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException | Error ex) {
            // Roll back partial start — deregister handlers, reset state
            started = false;
            currentView = null;
            transport.deregisterHandler(MessageType.PING);
            transport.deregisterHandler(MessageType.VIEW_CHANGE);
            throw ex;
        }
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
        if (!started || closed.get()) {
            return;
        }

        // Announce departure to all known members, then close.
        // close() must run even if transport.send() throws an unchecked exception —
        // otherwise the scheduler and discovery registration leak.
        try {
            final MembershipView view = currentView;
            assert view != null : "view should not be null when started";

            final byte[] payload = encodeLeavePayload();
            for (Member member : view.members()) {
                if (closed.get()) {
                    break; // close() ran concurrently — abort send loop
                }
                if (member.address().equals(localAddress)) {
                    continue;
                }
                try {
                    transport.send(member.address(), new Message(MessageType.VIEW_CHANGE,
                            localAddress, sequenceCounter.getAndIncrement(), payload));
                } catch (IOException e) {
                    // Best effort — some members may be unreachable
                    assert e != null : "exception should not be null";
                }
            }
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        started = false;

        discovery.deregister(localAddress);

        transport.deregisterHandler(MessageType.PING);
        transport.deregisterHandler(MessageType.VIEW_CHANGE);

        // Cancel any in-flight consensus rounds before tearing down listeners so sink
        // invocations triggered by cancellation drain through the dispatcher.
        consensusCoordinator.close();

        // Close the dispatcher before clearing listeners so any in-flight callbacks
        // drain to terminal state before the listener list is emptied. The dispatcher
        // is single-threaded and awaits up to 1s, then forces shutdown.
        listenerDispatcher.close();

        listeners.clear();

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

        // Only record heartbeats from nodes in the current membership view.
        // Without this guard, removed or unknown nodes pollute the failure detector's state.
        final MembershipView view = currentView;
        if (view != null && view.isMember(sender)) {
            failureDetector.recordHeartbeat(sender);
        }

        // Respond with ACK
        return CompletableFuture.completedFuture(new Message(MessageType.ACK, localAddress,
                sequenceCounter.getAndIncrement(), new byte[0]));
    }

    private CompletableFuture<Message> handleViewChange(NodeAddress sender, Message msg) {
        assert sender != null : "sender must not be null";
        assert msg != null : "msg must not be null";

        final byte[] payload = msg.payload();
        if (payload.length == 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Empty VIEW_CHANGE payload — possible truncation or corruption"));
        }

        final byte subType = payload[0];

        if (subType == MSG_JOIN_REQUEST) {
            return handleJoinRequest(sender, payload);
        } else if (subType == MSG_LEAVE) {
            handleLeaveNotification(sender);
            return CompletableFuture.completedFuture(new Message(MessageType.ACK, localAddress,
                    sequenceCounter.getAndIncrement(), new byte[0]));
        } else if (subType == MSG_VIEW_CHANGE_PROPOSAL) {
            // Verify the sender is a current member — reject view injection from non-members
            final MembershipView view = currentView;
            if (view == null || !view.isMember(sender)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "View change proposal rejected — sender is not a current member: "
                                + sender));
            }
            handleViewChangeProposal(sender, payload);
            return CompletableFuture.completedFuture(new Message(MessageType.ACK, localAddress,
                    sequenceCounter.getAndIncrement(), new byte[0]));
        } else if (subType == MSG_SUSPICION_PROPOSAL) {
            handleSuspicionProposal(payload);
            return CompletableFuture.completedFuture(new Message(MessageType.ACK, localAddress,
                    sequenceCounter.getAndIncrement(), new byte[0]));
        } else if (subType == MSG_SUSPICION_VOTE) {
            handleSuspicionVote(payload);
            return CompletableFuture.completedFuture(new Message(MessageType.ACK, localAddress,
                    sequenceCounter.getAndIncrement(), new byte[0]));
        } else if (subType == MSG_ALIVE_REFUTATION) {
            handleAliveRefutation(payload);
            return CompletableFuture.completedFuture(new Message(MessageType.ACK, localAddress,
                    sequenceCounter.getAndIncrement(), new byte[0]));
        }

        return CompletableFuture.failedFuture(new IllegalArgumentException(
                "Unknown VIEW_CHANGE sub-type: 0x" + String.format("%02x", subType)));
    }

    private void handleSuspicionProposal(byte[] payload) {
        final SuspicionProposal proposal;
        try {
            proposal = SuspicionProposal.deserialize(payload);
        } catch (RuntimeException e) {
            // Malformed payload — log via assert and drop (best-effort per transport contract).
            assert e != null : "exception should not be null";
            return;
        }
        consensusCoordinator.onProposalReceived(proposal,
                addr -> failureDetector.phi(addr) > config.phiThreshold());
    }

    private void handleSuspicionVote(byte[] payload) {
        final SuspicionVote vote;
        try {
            vote = SuspicionVote.deserialize(payload);
        } catch (RuntimeException e) {
            assert e != null : "exception should not be null";
            return;
        }
        consensusCoordinator.onVoteReceived(vote);
    }

    private void handleAliveRefutation(byte[] payload) {
        final AliveRefutation refutation;
        try {
            refutation = AliveRefutation.deserialize(payload);
        } catch (RuntimeException e) {
            assert e != null : "exception should not be null";
            return;
        }

        // 1) Cancel any active round targeting the refuter.
        consensusCoordinator.onRefutationReceived(refutation);

        // 2) If the refutation's subject is in our view with a lower incarnation, update it to
        // ALIVE at the refutation's incarnation. A higher-incarnation ALIVE supersedes any
        // lower-incarnation SUSPECT/DEAD record per R38.
        if (refutation.subject().equals(localAddress)) {
            // No-op for self — refutations about self do not mutate the local view.
            return;
        }
        applyIncarnationRefresh(refutation.subject(), refutation.incarnation());
    }

    private void applyIncarnationRefresh(NodeAddress subject, long newIncarnation) {
        MembershipView oldViewForNotify = null;
        MembershipView newViewForNotify = null;

        viewLock.lock();
        try {
            final MembershipView oldView = currentView;
            if (oldView == null) {
                return;
            }
            Member existing = null;
            for (Member m : oldView.members()) {
                if (m.address().equals(subject)) {
                    existing = m;
                    break;
                }
            }
            if (existing == null) {
                return;
            }
            if (newIncarnation <= existing.incarnation() && existing.state() == MemberState.ALIVE) {
                // No change required — existing record already dominates.
                return;
            }
            final Set<Member> newMembers = new HashSet<>();
            for (Member m : oldView.members()) {
                if (m.address().equals(subject)) {
                    newMembers.add(new Member(subject, MemberState.ALIVE,
                            Math.max(newIncarnation, existing.incarnation())));
                } else {
                    newMembers.add(m);
                }
            }
            final MembershipView newView = new MembershipView(oldView.epoch() + 1, newMembers,
                    Instant.now());
            currentView = newView;
            oldViewForNotify = oldView;
            newViewForNotify = newView;
        } finally {
            viewLock.unlock();
        }

        if (oldViewForNotify != null) {
            rebuildOverlayAndNotifyCoordinator(newViewForNotify);
            notifyViewChanged(oldViewForNotify, newViewForNotify);
        }
    }

    private CompletableFuture<Message> handleJoinRequest(NodeAddress sender, byte[] payload) {
        // Decode the joining node's address from payload
        final NodeAddress joiningNode = decodeNodeAddress(payload, 1);
        if (joiningNode == null) {
            return CompletableFuture.completedFuture(new Message(MessageType.ACK, localAddress,
                    sequenceCounter.getAndIncrement(), new byte[0]));
        }

        // View mutation under viewLock; listener notification and network I/O outside.
        // Notifications and propagation must not hold viewLock — holding it during callbacks
        // or network sends blocks all concurrent view mutations and risks deadlock.
        MembershipView viewToPropagate = null;
        MembershipView oldViewForPropagation = null;
        MembershipView responseView = null;
        Member joinedMember = null;

        viewLock.lock();
        try {
            final MembershipView oldView = currentView;
            assert oldView != null : "view should not be null";

            // Check if already an ALIVE member — if so, return current view without mutation
            Member existing = null;
            for (Member m : oldView.members()) {
                if (m.address().equals(joiningNode)) {
                    existing = m;
                    break;
                }
            }
            if (existing != null && existing.state() == MemberState.ALIVE) {
                // Already alive, respond with current view
                return CompletableFuture.completedFuture(new Message(MessageType.VIEW_CHANGE,
                        localAddress, sequenceCounter.getAndIncrement(),
                        encodeViewResponse(currentView)));
            }

            // Either new member or DEAD member rejoining — build updated membership set
            final Set<Member> newMembers = new HashSet<>();
            for (Member m : oldView.members()) {
                if (!m.address().equals(joiningNode)) {
                    newMembers.add(m);
                }
            }
            final var newMember = new Member(joiningNode, MemberState.ALIVE,
                    existing != null ? existing.incarnation() + 1 : 0);
            newMembers.add(newMember);
            final MembershipView newView = new MembershipView(oldView.epoch() + 1, newMembers,
                    Instant.now());
            currentView = newView;

            // Capture for notification and propagation outside the lock
            viewToPropagate = newView;
            oldViewForPropagation = oldView;
            responseView = newView;
            joinedMember = newMember;
        } finally {
            viewLock.unlock();
        }

        // Rebuild the monitoring overlay and inform the coordinator outside viewLock.
        rebuildOverlayAndNotifyCoordinator(viewToPropagate);

        // Notify listeners outside viewLock — callbacks must not block view mutations
        notifyViewChanged(oldViewForPropagation, viewToPropagate);
        notifyMemberJoined(joinedMember);

        // Propagate view change outside viewLock — network I/O must not block view mutations
        propagateViewChange(viewToPropagate, oldViewForPropagation);

        return CompletableFuture.completedFuture(new Message(MessageType.VIEW_CHANGE, localAddress,
                sequenceCounter.getAndIncrement(), encodeViewResponse(responseView)));
    }

    private void handleLeaveNotification(NodeAddress leavingNode) {
        MembershipView oldViewForNotify = null;
        MembershipView newViewForNotify = null;
        Member leftMember = null;

        viewLock.lock();
        try {
            final MembershipView oldView = currentView;
            assert oldView != null : "view should not be null";

            if (!oldView.isMember(leavingNode)) {
                return;
            }

            // Skip if the member is already DEAD — duplicate leave should not bump epoch
            for (Member m : oldView.members()) {
                if (m.address().equals(leavingNode) && m.state() == MemberState.DEAD) {
                    return;
                }
            }

            // Reap previously DEAD members to prevent unbounded set growth (F-R1.ss.1.4).
            // The currently leaving member is marked DEAD; all prior DEAD members are removed.
            final Set<Member> newMembers = new HashSet<>();
            Member leavingMember = null;
            for (Member m : oldView.members()) {
                if (m.address().equals(leavingNode)) {
                    leavingMember = m;
                    newMembers.add(new Member(m.address(), MemberState.DEAD, m.incarnation()));
                } else if (m.state() != MemberState.DEAD) {
                    newMembers.add(m);
                }
                // else: prior DEAD member — reaped to bound member set size
            }

            final MembershipView newView = new MembershipView(oldView.epoch() + 1, newMembers,
                    Instant.now());
            currentView = newView;

            // Capture for notification outside the lock
            oldViewForNotify = oldView;
            newViewForNotify = newView;
            leftMember = leavingMember;
        } finally {
            viewLock.unlock();
        }

        // Notify listeners outside viewLock — callbacks must not block view mutations
        if (oldViewForNotify != null) {
            // @spec F04.R83 — evict heartbeat history for the departed node so the failure
            // detector does not accumulate records for members that have left the view.
            if (leftMember != null) {
                failureDetector.remove(leftMember.address());
            }
            rebuildOverlayAndNotifyCoordinator(newViewForNotify);
            notifyViewChanged(oldViewForNotify, newViewForNotify);
            if (leftMember != null) {
                notifyMemberLeft(leftMember);
            }
        }
    }

    private void handleViewChangeProposal(NodeAddress sender, byte[] payload) {
        // Decode the proposed view from the payload
        final MembershipView proposedView = decodeViewFromPayload(payload, 1);
        if (proposedView == null) {
            return;
        }

        MembershipView oldViewForNotify = null;
        boolean accepted = false;

        viewLock.lock();
        try {
            final MembershipView oldView = currentView;
            assert oldView != null : "view should not be null";

            // @spec F04.R90 — reject proposals that drop ALIVE members from the membership set.
            // A member present in the proposed view with state DEAD is not "dropped" — it is
            // still known (isKnown=true) and carries the required DEAD record. Using isKnown
            // here rather than isMember preserves R90 intent now that isMember excludes DEAD.
            if (proposedView.epoch() > oldView.epoch()) {
                boolean dropsAlive = false;
                for (Member existing : oldView.members()) {
                    if (existing.state() == MemberState.ALIVE
                            && !proposedView.isKnown(existing.address())) {
                        dropsAlive = true;
                        break;
                    }
                }
                if (!dropsAlive) {
                    currentView = proposedView;
                    oldViewForNotify = oldView;
                    accepted = true;
                }
            }
        } finally {
            viewLock.unlock();
        }

        // Notify listeners outside viewLock — callbacks must not block view mutations
        if (accepted) {
            rebuildOverlayAndNotifyCoordinator(proposedView);
            notifyViewChanged(oldViewForNotify, proposedView);

            for (Member newMember : proposedView.members()) {
                if (newMember.state() == MemberState.ALIVE
                        && !oldViewForNotify.isMember(newMember.address())) {
                    // isMember now excludes DEAD, so an absent-or-DEAD old record both
                    // resolve to the same "was not a current member" branch — rejoin and
                    // first-time-join converge here.
                    notifyMemberJoined(newMember);
                }
            }
            for (Member oldMember : oldViewForNotify.members()) {
                if (oldMember.state() == MemberState.DEAD) {
                    // Already-departed members must not re-fire notifyMemberLeft — they
                    // were announced when first marked DEAD.
                    continue;
                }
                if (!proposedView.isMember(oldMember.address())) {
                    // Previously ALIVE or SUSPECTED, now absent or DEAD in proposed view —
                    // this is a departure transition.
                    // @spec F04.R83 — evict heartbeat history on ALIVE/SUSPECTED → DEAD.
                    failureDetector.remove(oldMember.address());
                    notifyMemberLeft(oldMember);
                }
            }
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
                        MembershipView oldViewForNotify;
                        MembershipView newViewForNotify;
                        viewLock.lock();
                        try {
                            oldViewForNotify = currentView;
                            // Epoch monotonicity guard: reject stale join responses whose
                            // epoch is not strictly greater than the current view's epoch.
                            // Without this guard, a concurrent view change (via
                            // handleJoinRequest or handleViewChangeProposal) that advanced
                            // the epoch would be overwritten by a stale seed response.
                            if (receivedView.epoch() <= oldViewForNotify.epoch()) {
                                return false;
                            }
                            // Ensure we're in the received view
                            final Set<Member> members = new HashSet<>(receivedView.members());
                            if (!receivedView.isMember(localAddress)) {
                                members.add(new Member(localAddress, MemberState.ALIVE, 0));
                            }
                            currentView = new MembershipView(receivedView.epoch(), members,
                                    Instant.now());
                            newViewForNotify = currentView;
                        } finally {
                            viewLock.unlock();
                        }
                        // Notify listeners outside viewLock
                        rebuildOverlayAndNotifyCoordinator(newViewForNotify);
                        notifyViewChanged(oldViewForNotify, newViewForNotify);
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
        if (closed.get() || !started) {
            return;
        }

        final MembershipView view = currentView;
        if (view == null) {
            return;
        }

        // Under the RAPID overlay, each node pings only its outgoing monitor edges — NOT every
        // ALIVE member. When the overlay has no edges (empty graph, degree == 0, singleton
        // cluster), the loop is a no-op and the coordinator never observes any phi breach.
        final Set<NodeAddress> monitors = expanderOverlay.monitorsOf(localAddress);

        for (NodeAddress target : monitors) {
            // Re-resolve the target against the current view — overlay snapshots may lag a
            // concurrent view mutation. Skip targets that have departed or been suspected.
            final MembershipView viewBeforeRequest = currentView;
            if (viewBeforeRequest == null) {
                return;
            }
            if (findAliveMember(viewBeforeRequest, target) == null) {
                continue;
            }

            transport
                    .request(target,
                            new Message(MessageType.PING, localAddress,
                                    sequenceCounter.getAndIncrement(), new byte[0]))
                    .whenComplete((response, ex) -> {
                        if (ex == null && response != null) {
                            // ACK received — the remote node is responsive.
                            // Record a heartbeat so the local failure detector reflects
                            // the node's liveness symmetrically (not just from incoming
                            // pings the remote node initiates).
                            failureDetector.recordHeartbeat(target);
                        }
                        // On failure (ex != null): member may be unreachable — the phi
                        // accrual detector will handle suspicion via elapsed time.
                    });

            // Re-check the current view after the request — a concurrent leave may have
            // transitioned the target from ALIVE to DEAD during the ping round-trip. Without
            // this second check, the phi==0 seed path below would inject a heartbeat for an
            // already-departed member, polluting the failure detector's history.
            final MembershipView freshView = currentView;
            if (freshView == null) {
                return;
            }
            final Member member = findAliveMember(freshView, target);
            if (member == null) {
                continue;
            }

            // Check phi for this member
            final double phi = failureDetector.phi(target);
            if (phi == 0.0) {
                // No heartbeat history for this node — it joined but has never responded.
                // Seed a heartbeat now so the phi clock starts ticking. On subsequent ticks,
                // if no real heartbeat arrives from the node, elapsed time grows and phi
                // will eventually exceed the threshold, triggering a consensus round. Without
                // this seed, phi() returns 0.0 forever for unresponsive nodes, making them
                // immune to failure detection.
                failureDetector.recordHeartbeat(target);
            } else if (phi > config.phiThreshold()) {
                // RAPID consensus replaces the unilateral transition: initiate an observer
                // quorum round instead of bumping the view directly. The coordinator cancels
                // or commits based on observer votes.
                try {
                    consensusCoordinator.startRound(target, freshView.epoch(),
                            member.incarnation());
                } catch (IllegalStateException e) {
                    // Coordinator closed concurrently — stop emitting rounds.
                    assert e != null : "exception should not be null";
                    return;
                } catch (RuntimeException e) {
                    // Defensive: never let a round-start failure take down the tick loop.
                    assert e != null : "exception should not be null";
                }
            }
        }

        // Expire any rounds whose deadline has passed — bounded work per tick.
        try {
            consensusCoordinator.tick();
        } catch (IllegalStateException e) {
            assert e != null : "exception should not be null";
        }
    }

    /** Returns the ALIVE {@link Member} matching {@code addr} in {@code view}, or null. */
    private static Member findAliveMember(MembershipView view, NodeAddress addr) {
        for (Member m : view.members()) {
            if (m.address().equals(addr) && m.state() == MemberState.ALIVE) {
                return m;
            }
        }
        return null;
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
        if (closed.get()) {
            return;
        }
        listenerDispatcher.dispatch(listeners, l -> l.onViewChanged(oldView, newView));
    }

    private void notifyMemberJoined(Member member) {
        if (closed.get()) {
            return;
        }
        listenerDispatcher.dispatch(listeners, l -> l.onMemberJoined(member));
    }

    private void notifyMemberLeft(Member member) {
        if (closed.get()) {
            return;
        }
        listenerDispatcher.dispatch(listeners, l -> l.onMemberLeft(member));
    }

    private void notifyMemberSuspected(Member member) {
        if (closed.get()) {
            return;
        }
        listenerDispatcher.dispatch(listeners, l -> l.onMemberSuspected(member));
    }

    // --- Overlay / coordinator wiring ---

    /**
     * Recomputes the expander-graph overlay for the new view's ALIVE members and informs the
     * coordinator. Must be called after every view mutation (join, leave, proposal apply, consensus
     * commit). Computes an auto-degree of ceil(log2(n)) when
     * {@link ClusterConfig#expanderGraphDegree()} is 0.
     */
    private void rebuildOverlayAndNotifyCoordinator(MembershipView newView) {
        assert newView != null : "newView must not be null";
        final Set<NodeAddress> aliveAddrs = new HashSet<>();
        for (Member m : newView.members()) {
            if (m.state() == MemberState.ALIVE) {
                aliveAddrs.add(m.address());
            }
        }
        final int configured = config.expanderGraphDegree();
        final int degree;
        if (configured > 0) {
            degree = configured;
        } else if (aliveAddrs.size() <= 1) {
            degree = 0;
        } else {
            degree = Math.max(2, (int) Math.ceil(Math.log(aliveAddrs.size()) / Math.log(2)));
        }
        expanderOverlay.rebuild(aliveAddrs, degree, newView.epoch());
        try {
            consensusCoordinator.onViewChanged(newView);
        } catch (IllegalStateException e) {
            // Coordinator has been closed — safe to ignore during shutdown.
            assert e != null : "exception should not be null";
        }
    }

    /**
     * Coordinator sink: commits a SUSPECT view change when a round reaches QUORUM_AGREE. The
     * transition mirrors {@link #handleSuspectedNode} but is driven by observer-quorum consensus
     * instead of a unilateral phi breach.
     */
    private final class CoordinatorSink implements ConsensusCoordinator.ViewChangeSink {
        @Override
        public void applySuspicion(NodeAddress subject, long roundEpoch) {
            MembershipView oldViewForNotify = null;
            MembershipView newViewForNotify = null;
            Member suspectedForNotify = null;

            viewLock.lock();
            try {
                final MembershipView oldView = currentView;
                if (oldView == null) {
                    return;
                }
                // Only transition if the subject is still ALIVE — a stale commit against an
                // already-DEAD or absent member must be silently dropped.
                boolean stillAlive = false;
                for (Member m : oldView.members()) {
                    if (m.address().equals(subject) && m.state() == MemberState.ALIVE) {
                        stillAlive = true;
                        break;
                    }
                }
                if (!stillAlive) {
                    return;
                }
                final Set<Member> newMembers = new HashSet<>();
                Member suspectedMember = null;
                for (Member m : oldView.members()) {
                    if (m.address().equals(subject)) {
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
                oldViewForNotify = oldView;
                newViewForNotify = newView;
                suspectedForNotify = suspectedMember;
            } finally {
                viewLock.unlock();
            }

            assert roundEpoch >= 0 : "roundEpoch must be non-negative";
            if (oldViewForNotify != null) {
                rebuildOverlayAndNotifyCoordinator(newViewForNotify);
                notifyViewChanged(oldViewForNotify, newViewForNotify);
                if (suspectedForNotify != null) {
                    notifyMemberSuspected(suspectedForNotify);
                }
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
