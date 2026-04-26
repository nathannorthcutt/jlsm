package jlsm.engine.cluster.internal;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.cluster.ClusterTransport;
import jlsm.cluster.Message;
import jlsm.cluster.MessageHandler;
import jlsm.cluster.MessageType;
import jlsm.engine.cluster.MembershipListener;
import jlsm.engine.cluster.MembershipProtocol;
import jlsm.engine.cluster.MembershipView;
import jlsm.cluster.NodeAddress;
import jlsm.table.JlsmDocument;
import jlsm.table.TableEntry;
import jlsm.table.TableQuery;
import jlsm.table.UpdateMode;

/**
 * Test-only factory and stub implementations of {@link Table} used by the cluster test suite. Per
 * R8g the production {@link Table} interface is sealed with two permits — production code cannot
 * implement {@code Table} directly. This file hosts the test stubs that previously declared
 * {@code implements Table}; they now extend {@link CatalogClusteredTable} (a non-sealed permit) and
 * override only the methods relevant to each test.
 *
 * <p>
 * This file is intentionally placed in {@code jlsm.engine.cluster.internal} so the stubs can call
 * {@code CatalogClusteredTable}'s package-private constructor without {@code --add-opens}
 * gymnastics — the test source tree is the only place outside production where this access is
 * permitted, gated by the build's {@code --add-exports} flag.
 *
 * @spec sstable.footer-encryption-scope.R8g
 */
public final class TestTableStubs {

    private TestTableStubs() {
    }

    /**
     * Stub factory that constructs a fixed-metadata Table whose CRUD methods are no-ops. Replaces
     * the various {@code StubTable}/{@code StubTableImpl} test stubs that previously declared
     * {@code implements Table}.
     */
    public static Table forMetadata(TableMetadata metadata) {
        return new MetadataOnlyStub(metadata);
    }

    /** Test stub: fixed metadata, no-op CRUD, empty scans. */
    public static class MetadataOnlyStub extends CatalogClusteredTable {

        private final TableMetadata stubMetadata;

        public MetadataOnlyStub(TableMetadata metadata) {
            super(metadata, NoOpClusterTransport.INSTANCE, NoOpMembershipProtocol.INSTANCE,
                    new NodeAddress("test-stub", "127.0.0.1", 1), new RendezvousOwnership(), null);
            this.stubMetadata = metadata;
        }

        @Override
        public TableMetadata metadata() {
            return stubMetadata;
        }

        @Override
        public void create(String key, JlsmDocument doc) {
            /* no-op */
        }

        @Override
        public Optional<JlsmDocument> get(String key) {
            return Optional.empty();
        }

        @Override
        public void update(String key, JlsmDocument doc, UpdateMode mode) {
            /* no-op */
        }

        @Override
        public void delete(String key) {
            /* no-op */
        }

        @Override
        public void insert(JlsmDocument doc) {
            /* no-op */
        }

        @Override
        public TableQuery<String> query() {
            throw new UnsupportedOperationException("not implemented in stub");
        }

        @Override
        public Iterator<TableEntry<String>> scan(String fromKey, String toKey) throws IOException {
            return Collections.emptyIterator();
        }

        @Override
        public void close() {
            /* no-op */
        }
    }

    /** Permissive stub — accepts every input, used by adversarial-boundary tests. */
    public static final class PermissiveStub extends CatalogClusteredTable {

        private final TableMetadata stubMetadata;

        public PermissiveStub(TableMetadata metadata) {
            super(metadata, NoOpClusterTransport.INSTANCE, NoOpMembershipProtocol.INSTANCE,
                    new NodeAddress("test-permissive", "127.0.0.1", 1), new RendezvousOwnership(),
                    null);
            this.stubMetadata = metadata;
        }

        @Override
        public TableMetadata metadata() {
            return stubMetadata;
        }

        @Override
        public void create(String key, JlsmDocument doc) {
            /* permissive */ }

        @Override
        public Optional<JlsmDocument> get(String key) {
            return Optional.empty();
        }

        @Override
        public void update(String key, JlsmDocument doc, UpdateMode mode) {
            /* permissive */ }

        @Override
        public void delete(String key) {
            /* permissive */ }

        @Override
        public void insert(JlsmDocument doc) {
            /* permissive */ }

        @Override
        public TableQuery<String> query() {
            throw new UnsupportedOperationException("not implemented in permissive stub");
        }

        @Override
        public Iterator<TableEntry<String>> scan(String fromKey, String toKey) {
            return Collections.emptyIterator();
        }

        @Override
        public void close() {
            /* no-op */
        }
    }

    /**
     * Recording stub used by ClusteredTableLocalShortCircuitTest — captures keys passed to each
     * CRUD method so the test can assert that local short-circuit routing dispatched the call.
     */
    public static final class RecordingTable extends CatalogClusteredTable {

        public final List<String> creates = new java.util.concurrent.CopyOnWriteArrayList<>();
        public final List<String> gets = new java.util.concurrent.CopyOnWriteArrayList<>();
        public final List<String> updates = new java.util.concurrent.CopyOnWriteArrayList<>();
        public final List<String> deletes = new java.util.concurrent.CopyOnWriteArrayList<>();
        public final AtomicInteger inserts = new AtomicInteger();
        public final List<String[]> scans = new java.util.concurrent.CopyOnWriteArrayList<>();
        public final AtomicInteger closeCount = new AtomicInteger();
        private final TableMetadata stubMetadata;

        public RecordingTable(TableMetadata metadata) {
            super(metadata, NoOpClusterTransport.INSTANCE, NoOpMembershipProtocol.INSTANCE,
                    new NodeAddress("test-recording", "127.0.0.1", 1), new RendezvousOwnership(),
                    null);
            this.stubMetadata = metadata;
        }

        @Override
        public TableMetadata metadata() {
            return stubMetadata;
        }

        @Override
        public void create(String key, JlsmDocument doc) {
            creates.add(key);
        }

        @Override
        public Optional<JlsmDocument> get(String key) {
            gets.add(key);
            return Optional.empty();
        }

        @Override
        public void update(String key, JlsmDocument doc, UpdateMode mode) {
            updates.add(key);
        }

        @Override
        public void delete(String key) {
            deletes.add(key);
        }

        @Override
        public void insert(JlsmDocument doc) {
            inserts.incrementAndGet();
        }

        @Override
        public TableQuery<String> query() {
            throw new UnsupportedOperationException("not implemented in recording stub");
        }

        @Override
        public Iterator<TableEntry<String>> scan(String fromKey, String toKey) {
            scans.add(new String[]{ fromKey, toKey });
            return Collections.emptyIterator();
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    /** Minimal cluster transport stub: no-op send, never delivers responses. */
    static final class NoOpClusterTransport implements ClusterTransport {

        static final NoOpClusterTransport INSTANCE = new NoOpClusterTransport();

        @Override
        public void send(NodeAddress target, Message msg) {
            /* drop */ }

        @Override
        public CompletableFuture<Message> request(NodeAddress target, Message msg) {
            return CompletableFuture.failedFuture(new IOException("no-op transport"));
        }

        @Override
        public void registerHandler(MessageType type, MessageHandler handler) {
            /* drop */ }

        @Override
        public void deregisterHandler(MessageType type) {
            /* drop */ }

        @Override
        public void close() {
            /* no-op */ }
    }

    /** Minimal membership stub: empty view, no listeners. */
    static final class NoOpMembershipProtocol implements MembershipProtocol {

        static final NoOpMembershipProtocol INSTANCE = new NoOpMembershipProtocol();

        @Override
        public MembershipView currentView() {
            return new MembershipView(0L, Set.of(), Instant.EPOCH);
        }

        @Override
        public void start(List<NodeAddress> seeds) {
            /* no-op */ }

        @Override
        public void leave() {
            /* no-op */ }

        @Override
        public void addListener(MembershipListener listener) {
            /* no-op */ }

        @Override
        public void removeListener(MembershipListener listener) {
            /* no-op */ }

        @Override
        public void close() {
            /* no-op */ }
    }
}
