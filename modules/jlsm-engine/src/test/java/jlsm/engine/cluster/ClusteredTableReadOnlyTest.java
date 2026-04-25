package jlsm.engine.cluster;

import jlsm.engine.cluster.internal.CatalogClusteredTable;

import jlsm.engine.TableMetadata;
import jlsm.engine.cluster.internal.InJvmTransport;
import jlsm.engine.cluster.internal.RendezvousOwnership;
import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.UpdateMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@link CatalogClusteredTable} write operations throw {@link QuorumLostException} when
 * the engine reports {@link ClusterOperationalMode#READ_ONLY} (@spec engine.clustering.R41), while
 * reads continue to succeed.
 */
final class ClusteredTableReadOnlyTest {

    private static final NodeAddress LOCAL = new NodeAddress("local", "localhost", 9101);
    private static final NodeAddress REMOTE = new NodeAddress("remote", "localhost", 9102);
    private static final Instant NOW = Instant.parse("2026-04-20T00:00:00Z");
    private static final JlsmSchema SCHEMA = JlsmSchema.builder("test", 1)
            .field("id", FieldType.Primitive.STRING).field("value", FieldType.Primitive.STRING)
            .build();
    private static final TableMetadata META = new TableMetadata("users", SCHEMA, NOW,
            TableMetadata.TableState.READY);

    private InJvmTransport transport;
    private StubMembershipProtocol membership;
    private AtomicReference<ClusterOperationalMode> mode;
    private CatalogClusteredTable table;

    @BeforeEach
    void setUp() {
        InJvmTransport.clearRegistry();
        transport = new InJvmTransport(LOCAL);
        membership = new StubMembershipProtocol();
        membership.view = new MembershipView(1, Set.of(new Member(LOCAL, MemberState.ALIVE, 0),
                new Member(REMOTE, MemberState.ALIVE, 0)), NOW);
        mode = new AtomicReference<>(ClusterOperationalMode.NORMAL);
        table = CatalogClusteredTable.forEngine(META, transport, membership, LOCAL,
                new RendezvousOwnership(), null, mode::get);
    }

    @AfterEach
    void tearDown() {
        table.close();
        transport.close();
        InJvmTransport.clearRegistry();
    }

    @Test
    void create_throwsQuorumLost_whenReadOnly() {
        mode.set(ClusterOperationalMode.READ_ONLY);
        JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "k", "value", "v");
        assertThrows(QuorumLostException.class, () -> table.create("k", doc));
    }

    @Test
    void update_throwsQuorumLost_whenReadOnly() {
        mode.set(ClusterOperationalMode.READ_ONLY);
        JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "k", "value", "v");
        assertThrows(QuorumLostException.class, () -> table.update("k", doc, UpdateMode.REPLACE));
    }

    @Test
    void delete_throwsQuorumLost_whenReadOnly() {
        mode.set(ClusterOperationalMode.READ_ONLY);
        assertThrows(QuorumLostException.class, () -> table.delete("k"));
    }

    @Test
    void insert_throwsQuorumLost_whenReadOnly() {
        mode.set(ClusterOperationalMode.READ_ONLY);
        JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "k", "value", "v");
        assertThrows(QuorumLostException.class, () -> table.insert(doc));
    }

    @Test
    void writes_allowed_whenNormal() {
        mode.set(ClusterOperationalMode.NORMAL);
        JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "k", "value", "v");
        // The transport call will ultimately fail (no handler registered), but the READ_ONLY gate
        // must not be what fails first. Any exception thrown must NOT be QuorumLostException.
        try {
            table.create("k", doc);
        } catch (QuorumLostException e) {
            fail("NORMAL mode must not trigger QuorumLostException: " + e);
        } catch (Exception expected) {
            // other failures (transport, etc.) are acceptable for this test
        }
    }

    @Test
    void get_stillWorks_whenReadOnly() {
        mode.set(ClusterOperationalMode.READ_ONLY);
        // get() must not throw QuorumLostException — READ_ONLY gates only writes.
        try {
            table.get("k");
        } catch (QuorumLostException e) {
            fail("READ_ONLY mode must not block reads: " + e);
        } catch (Exception expected) {
            // transport failure is OK — we only care that the read-only gate did not fire
        }
    }

    @Test
    void scan_stillWorks_whenReadOnly() {
        mode.set(ClusterOperationalMode.READ_ONLY);
        try {
            table.scan("a", "z");
        } catch (QuorumLostException e) {
            fail("READ_ONLY mode must not block scans: " + e);
        } catch (Exception expected) {
            // Other failures (transport) are OK
        }
    }

    private static final class StubMembershipProtocol implements MembershipProtocol {
        volatile MembershipView view = new MembershipView(0, Set.of(), NOW);

        @Override
        public void start(List<NodeAddress> seeds) {
        }

        @Override
        public MembershipView currentView() {
            return view;
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
    }
}
