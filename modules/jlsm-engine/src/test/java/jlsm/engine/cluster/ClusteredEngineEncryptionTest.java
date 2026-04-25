package jlsm.engine.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import jlsm.engine.Engine;
import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.engine.cluster.internal.GracePeriodManager;
import jlsm.engine.cluster.internal.InJvmDiscoveryProvider;
import jlsm.engine.cluster.internal.InJvmTransport;
import jlsm.engine.cluster.internal.RendezvousOwnership;
import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;

/**
 * Tests for {@link ClusteredEngine} encryption API surface — createEncryptedTable +
 * enableEncryption round-trip via the local-engine delegate. The cluster control-plane ordering
 * protocol is documented in WU-3 cycle-log and exercised by the underlying local engine state
 * machine here; cross-node broadcast/ack is the documented limitation captured in the cycle-log.
 *
 * @spec sstable.footer-encryption-scope.R7
 * @spec sstable.footer-encryption-scope.R7b
 * @spec sstable.footer-encryption-scope.R8e
 */
final class ClusteredEngineEncryptionTest {

    private static final NodeAddress NODE = new NodeAddress("node-a", "localhost", 9001);
    private static final JlsmSchema SCHEMA = JlsmSchema.builder("test", 1)
            .field("id", FieldType.Primitive.STRING).build();

    private InJvmTransport transport;
    private RendezvousOwnership ownership;
    private GracePeriodManager grace;
    private ClusterConfig config;

    @TempDir
    Path tempDir;

    private static TableScope scope() {
        return new TableScope(new TenantId("tenant-A"), new DomainId("domain-X"),
                new TableId("table-Z"));
    }

    @BeforeEach
    void setUp() {
        InJvmTransport.clearRegistry();
        InJvmDiscoveryProvider.clearRegistrations();
        transport = new InJvmTransport(NODE);
        ownership = new RendezvousOwnership();
        grace = new GracePeriodManager(Duration.ofMinutes(2));
        config = ClusterConfig.builder().build();
    }

    @AfterEach
    void tearDown() {
        transport.close();
        InJvmTransport.clearRegistry();
        InJvmDiscoveryProvider.clearRegistrations();
    }

    private ClusteredEngine cluster(Engine local) {
        return ClusteredEngine.builder().localEngine(local).membership(new NoopMembership())
                .ownership(ownership).gracePeriodManager(grace).transport(transport).config(config)
                .localAddress(NODE).discovery(new InJvmDiscoveryProvider()).build();
    }

    private Engine localEngine() throws IOException {
        return Engine.builder().rootDirectory(tempDir).build();
    }

    // ---- happy path: createEncryptedTable on cluster path ----

    @Test
    void createEncryptedTable_persistsEncryptionMetadata() throws IOException {
        // covers: R7 — cluster-mode createEncryptedTable round-trips encryption metadata
        // through the local engine delegate.
        try (final Engine local = localEngine(); final ClusteredEngine cl = cluster(local)) {
            final Table t = cl.createEncryptedTable("secret", SCHEMA, scope());
            try {
                assertNotNull(t);
                assertTrue(t.metadata().encryption().isPresent(), "R7");
                assertEquals(scope(), t.metadata().encryption().orElseThrow().scope());
            } finally {
                t.close();
            }
        }
    }

    @Test
    void createEncryptedTable_nullScope_throwsNPE() throws IOException {
        try (final Engine local = localEngine(); final ClusteredEngine cl = cluster(local)) {
            assertThrows(NullPointerException.class,
                    () -> cl.createEncryptedTable("secret", SCHEMA, null));
        }
    }

    // ---- enableEncryption via cluster path ----

    @Test
    void enableEncryption_transitionsEmptyToPresent_onClusterPath() throws IOException {
        // covers: R7b — cluster-mode enableEncryption flips Optional.empty() → present.
        try (final Engine local = localEngine(); final ClusteredEngine cl = cluster(local)) {
            try (final Table t = cl.createTable("plain", SCHEMA)) {
                assertFalse(t.metadata().encryption().isPresent());
            }
            cl.enableEncryption("plain", scope());
            final TableMetadata m = cl.tableMetadata("plain");
            assertNotNull(m);
            assertTrue(m.encryption().isPresent(), "R7b on cluster path");
        }
    }

    @Test
    void enableEncryption_idempotentRejection_onClusterPath() throws IOException {
        // covers: R7b — second enable on cluster path throws ISE (one-way).
        try (final Engine local = localEngine(); final ClusteredEngine cl = cluster(local)) {
            try (final Table t = cl.createTable("plain", SCHEMA)) {
                // ignore
            }
            cl.enableEncryption("plain", scope());
            assertThrows(IllegalStateException.class, () -> cl.enableEncryption("plain", scope()));
        }
    }

    // ---- input validation ----

    @Test
    void enableEncryption_nullScope_throwsNPE() throws IOException {
        try (final Engine local = localEngine(); final ClusteredEngine cl = cluster(local)) {
            assertThrows(NullPointerException.class, () -> cl.enableEncryption("any", null));
        }
    }

    @Test
    void enableEncryption_unknownTable_throwsIOException() throws IOException {
        try (final Engine local = localEngine(); final ClusteredEngine cl = cluster(local)) {
            assertThrows(IOException.class, () -> cl.enableEncryption("ghost", scope()));
        }
    }

    // ---- minimal no-op membership stub ----

    private static final class NoopMembership implements MembershipProtocol {
        private volatile MembershipView view = new MembershipView(0, java.util.Set.of(),
                java.time.Instant.parse("2026-04-24T00:00:00Z"));

        @Override
        public void start(java.util.List<NodeAddress> seeds) {
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
