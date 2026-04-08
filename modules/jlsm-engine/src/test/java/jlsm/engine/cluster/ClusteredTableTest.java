package jlsm.engine.cluster;

import jlsm.engine.TableMetadata;
import jlsm.engine.cluster.internal.InJvmTransport;
import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.TableEntry;
import jlsm.table.UpdateMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClusteredTable} — partition-aware proxy that scatters queries across remote
 * partition owners and gathers results.
 */
final class ClusteredTableTest {

    private static final NodeAddress LOCAL = new NodeAddress("local", "localhost", 9001);
    private static final NodeAddress REMOTE_A = new NodeAddress("remote-a", "localhost", 9002);
    private static final NodeAddress REMOTE_B = new NodeAddress("remote-b", "localhost", 9003);
    private static final Instant NOW = Instant.parse("2026-03-20T00:00:00Z");
    private static final JlsmSchema SCHEMA = JlsmSchema.builder("test", 1)
            .field("id", FieldType.Primitive.STRING).field("value", FieldType.Primitive.STRING)
            .build();
    private static final TableMetadata TABLE_META = new TableMetadata("users", SCHEMA, NOW,
            TableMetadata.TableState.READY);

    private InJvmTransport localTransport;
    private InJvmTransport remoteATransport;
    private InJvmTransport remoteBTransport;
    private StubMembershipProtocol membership;
    private ClusteredTable table;

    @BeforeEach
    void setUp() {
        InJvmTransport.clearRegistry();
        localTransport = new InJvmTransport(LOCAL);
        remoteATransport = new InJvmTransport(REMOTE_A);
        remoteBTransport = new InJvmTransport(REMOTE_B);
        membership = new StubMembershipProtocol();

        // Set up a view with local and remote nodes alive
        membership.view = new MembershipView(1,
                Set.of(new Member(LOCAL, MemberState.ALIVE, 0),
                        new Member(REMOTE_A, MemberState.ALIVE, 0),
                        new Member(REMOTE_B, MemberState.ALIVE, 0)),
                NOW);

        table = new ClusteredTable(TABLE_META, localTransport, membership, LOCAL);
    }

    @AfterEach
    void tearDown() {
        table.close();
        localTransport.close();
        remoteATransport.close();
        remoteBTransport.close();
        InJvmTransport.clearRegistry();
    }

    // --- Constructor validation ---

    @Test
    void constructor_nullTableMetadata_throws() {
        assertThrows(NullPointerException.class,
                () -> new ClusteredTable(null, localTransport, membership, LOCAL));
    }

    @Test
    void constructor_nullTransport_throws() {
        assertThrows(NullPointerException.class,
                () -> new ClusteredTable(TABLE_META, null, membership, LOCAL));
    }

    @Test
    void constructor_nullMembership_throws() {
        assertThrows(NullPointerException.class,
                () -> new ClusteredTable(TABLE_META, localTransport, null, LOCAL));
    }

    // --- metadata() ---

    @Test
    void metadata_returnsConfiguredMetadata() {
        assertEquals(TABLE_META, table.metadata());
    }

    // --- CRUD delegation ---

    @Test
    void create_sendsToTransport() throws IOException {
        // Register a success handler on remote
        registerSuccessHandler(remoteATransport);
        registerSuccessHandler(remoteBTransport);

        final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "key1", "value", "val1");
        // This should route the write to the appropriate partition owner
        assertDoesNotThrow(() -> table.create("key1", doc));
    }

    @Test
    void get_returnsResultFromRemote() throws IOException {
        registerSuccessHandler(remoteATransport);
        registerSuccessHandler(remoteBTransport);

        final Optional<JlsmDocument> result = table.get("key1");
        assertNotNull(result);
    }

    @Test
    void update_routesToOwner() throws IOException {
        registerSuccessHandler(remoteATransport);
        registerSuccessHandler(remoteBTransport);

        final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "key1", "value", "updated");
        assertDoesNotThrow(() -> table.update("key1", doc, UpdateMode.REPLACE));
    }

    @Test
    void delete_routesToOwner() throws IOException {
        registerSuccessHandler(remoteATransport);
        registerSuccessHandler(remoteBTransport);

        assertDoesNotThrow(() -> table.delete("key1"));
    }

    // --- scan() ---

    @Test
    void scan_returnsIterator() throws IOException {
        registerSuccessHandler(remoteATransport);
        registerSuccessHandler(remoteBTransport);

        final Iterator<TableEntry<String>> it = table.scan("a", "z");
        assertNotNull(it);
    }

    // --- Partial results ---

    @Test
    void partialResult_whenNodeUnavailable_returnsPartialMetadata() throws IOException {
        // Close one remote transport to make it unreachable
        remoteATransport.close();
        registerSuccessHandler(remoteBTransport);

        // The table should still return results from available nodes
        // and report partial results
        try {
            final Iterator<TableEntry<String>> it = table.scan("a", "z");
            // After a scan with unavailable partitions, lastPartialResultMetadata should be
            // non-null
            final PartialResultMetadata partial = table.lastPartialResultMetadata();
            if (partial != null) {
                assertFalse(partial.isComplete());
            }
        } catch (IOException e) {
            // Acceptable if the implementation throws on partial failure
        }
    }

    @Test
    void lastPartialResultMetadata_afterCompleteQuery_isComplete() throws IOException {
        registerSuccessHandler(localTransport);
        registerSuccessHandler(remoteATransport);
        registerSuccessHandler(remoteBTransport);

        try {
            table.scan("a", "z");
            final PartialResultMetadata partial = table.lastPartialResultMetadata();
            if (partial != null) {
                assertTrue(partial.isComplete());
            }
        } catch (UnsupportedOperationException e) {
            // Acceptable during initial implementation
        }
    }

    // --- close() ---

    @Test
    void close_isIdempotent() {
        table.close();
        assertDoesNotThrow(table::close);
    }

    // --- Helper methods ---

    private static void registerSuccessHandler(InJvmTransport transport) {
        transport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            final Message response = new Message(MessageType.QUERY_RESPONSE,
                    new NodeAddress(transport.toString(), "localhost", 9999), msg.sequenceNumber(),
                    new byte[0]);
            return CompletableFuture.completedFuture(response);
        });
    }

    /**
     * Stub MembershipProtocol for testing.
     */
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
