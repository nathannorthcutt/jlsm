package jlsm.engine.cluster;

import jlsm.engine.Engine;
import jlsm.engine.EngineMetrics;
import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.engine.cluster.internal.GracePeriodManager;
import jlsm.engine.cluster.internal.InJvmDiscoveryProvider;
import jlsm.engine.cluster.internal.InJvmTransport;
import jlsm.engine.cluster.internal.PhiAccrualFailureDetector;
import jlsm.engine.cluster.internal.QueryRequestHandler;
import jlsm.engine.cluster.internal.QueryRequestPayload;
import jlsm.engine.cluster.internal.RapidMembership;
import jlsm.engine.cluster.internal.RemotePartitionClient;
import jlsm.engine.cluster.internal.RendezvousOwnership;
import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import jlsm.table.JlsmDocument;
import jlsm.table.PartitionDescriptor;
import jlsm.table.TableEntry;
import jlsm.table.TableQuery;
import jlsm.table.UpdateMode;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for contract boundary violations in the engine clustering subsystem.
 */
final class ContractBoundariesAdversarialTest {

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
    private InJvmTransport remoteTransport;
    private StubMembershipProtocol membership;
    private ClusteredTable table;

    @BeforeEach
    void setUp() {
        InJvmTransport.clearRegistry();
        localTransport = new InJvmTransport(LOCAL);
        remoteTransport = new InJvmTransport(REMOTE_A);
        membership = new StubMembershipProtocol();

        membership.view = new MembershipView(1, Set.of(new Member(LOCAL, MemberState.ALIVE, 0),
                new Member(REMOTE_A, MemberState.ALIVE, 0)), NOW);

        // Register a handler so remote requests complete
        remoteTransport
                .registerHandler(MessageType.QUERY_REQUEST,
                        (sender, msg) -> CompletableFuture
                                .completedFuture(new Message(MessageType.QUERY_RESPONSE, REMOTE_A,
                                        msg.sequenceNumber(), new byte[0])));

        table = new ClusteredTable(TABLE_META, localTransport, membership, LOCAL);
    }

    @AfterEach
    void tearDown() {
        table.close();
        localTransport.close();
        remoteTransport.close();
        InJvmTransport.clearRegistry();
    }

    // Finding: F-R1.cb.1.1
    // Bug: ClusteredTable creates a private RendezvousOwnership whose cache is never evicted
    // on view changes — old epoch entries accumulate without bound
    // Correct behavior: After a new epoch is observed, old epoch cache entries should be evicted
    // Fix location: ClusteredTable.resolveOwner or collectLiveNodes — evict before current epoch
    // Regression watch: Ensure ownership resolution still works correctly after eviction
    @Test
    @Timeout(10)
    @SuppressWarnings("unchecked")
    void test_ClusteredTable_ownership_staleCacheAfterViewChange() throws Exception {
        // Trigger a get at epoch 1 — this populates the ownership cache for epoch 1
        try {
            table.get("key1");
        } catch (Exception ignored) {
            // The remote response may not deserialize properly — we only need the cache side effect
        }

        // Access the private ownership field and its internal cache via reflection
        final Field ownershipField = ClusteredTable.class.getDeclaredField("ownership");
        ownershipField.setAccessible(true);
        final RendezvousOwnership ownership = (RendezvousOwnership) ownershipField.get(table);

        final Field cacheField = RendezvousOwnership.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        final ConcurrentHashMap<Long, ?> cache = (ConcurrentHashMap<Long, ?>) cacheField
                .get(ownership);

        // Epoch 1 should be in the cache now
        assertTrue(cache.containsKey(1L),
                "Cache should contain epoch 1 after resolving ownership at epoch 1");

        // Simulate a view change: advance to epoch 2
        membership.view = new MembershipView(2, Set.of(new Member(LOCAL, MemberState.ALIVE, 0),
                new Member(REMOTE_B, MemberState.ALIVE, 0)), NOW);

        // Trigger a get at epoch 2 — this should evict epoch 1 from the cache
        try {
            table.get("key1");
        } catch (Exception ignored) {
            // Same — we only need the cache side effect
        }

        // After resolving at epoch 2, epoch 1 entries should have been evicted.
        // BUG: ClusteredTable never calls evictBefore on its private RendezvousOwnership,
        // so epoch 1 entries remain forever.
        assertFalse(cache.containsKey(1L),
                "Cache should NOT contain epoch 1 after ownership was resolved at epoch 2 — "
                        + "stale epoch entries must be evicted to prevent unbounded cache growth");
        assertTrue(cache.containsKey(2L),
                "Cache should contain epoch 2 after resolving ownership at epoch 2");
    }

    // Finding: F-R1.cb.1.2
    // Bug: RemotePartitionClient.create encodes only opcode+key — the JlsmDocument is silently
    // discarded
    // Correct behavior: The create payload must include the document content so the remote node can
    // persist it
    // Fix location: RemotePartitionClient.create (line 119) and encodeKeyPayload helper
    // Regression watch: Payload format change must not break existing deserialization expectations
    @Test
    @Timeout(10)
    void test_RemotePartitionClient_create_documentContentIncludedInPayload() throws Exception {
        // Capture the payload sent to the remote transport
        final AtomicReference<byte[]> capturedPayload = new AtomicReference<>();
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            capturedPayload.set(msg.payload());
            return CompletableFuture.completedFuture(new Message(MessageType.QUERY_RESPONSE,
                    REMOTE_A, msg.sequenceNumber(), new byte[0]));
        });

        final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "user-42", "value", "hello-world");

        table.create("key1", doc);

        final byte[] payload = capturedPayload.get();
        assertNotNull(payload, "No payload was captured — request was not sent");

        // The payload must be longer than just [opcode(1)][key_len(4)][key_bytes(4)] = 9 bytes
        // because it must also include the document content.
        final String keyStr = "key1";
        final int keyOnlyLength = 1 + 4 + keyStr.getBytes(StandardCharsets.UTF_8).length;

        assertTrue(payload.length > keyOnlyLength,
                "Payload length is " + payload.length + " bytes — only contains opcode+key ("
                        + keyOnlyLength + " bytes). The JlsmDocument content was not included in "
                        + "the payload, violating the PartitionClient.create contract.");

        // Additionally verify that the document field values appear somewhere in the payload.
        // At minimum, "user-42" and "hello-world" must be present.
        final String payloadStr = new String(payload, StandardCharsets.UTF_8);
        // Use a bytes-based search since the payload is binary with embedded strings
        final byte[] idBytes = "user-42".getBytes(StandardCharsets.UTF_8);
        final byte[] valueBytes = "hello-world".getBytes(StandardCharsets.UTF_8);
        assertTrue(containsBytes(payload, idBytes),
                "Payload does not contain document field value 'user-42' — "
                        + "document content was discarded");
        assertTrue(containsBytes(payload, valueBytes),
                "Payload does not contain document field value 'hello-world' — "
                        + "document content was discarded");
    }

    /**
     * Checks whether {@code haystack} contains the byte sequence {@code needle}.
     */
    private static boolean containsBytes(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    // Finding: F-R1.cb.1.3
    // Bug: RemotePartitionClient.get always returns Optional.empty — data fetched but discarded
    // Correct behavior: When the response payload is non-empty, get() should deserialize
    // the document and return Optional.of(document)
    // Fix location: RemotePartitionClient.get (line 140) — return deserialized doc instead of empty
    // Regression watch: Empty-payload responses must still return Optional.empty
    @Test
    @Timeout(10)
    void test_RemotePartitionClient_get_returnsDocumentWhenPayloadNonEmpty() throws Exception {
        // Build a document with known field values
        final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "user-42", "value", "hello-world");
        final byte[] docJsonBytes = doc.toJson().getBytes(StandardCharsets.UTF_8);

        // Register a handler that responds with the document's JSON bytes as payload
        remoteTransport
                .registerHandler(MessageType.QUERY_REQUEST,
                        (sender, msg) -> CompletableFuture
                                .completedFuture(new Message(MessageType.QUERY_RESPONSE, REMOTE_A,
                                        msg.sequenceNumber(), docJsonBytes)));

        // Create a RemotePartitionClient with schema for document deserialization
        final PartitionDescriptor desc = new PartitionDescriptor(0L,
                MemorySegment.ofArray(new byte[0]),
                MemorySegment.ofArray(new byte[]{ (byte) 0xFF }), REMOTE_A.nodeId(), 0L);
        final RemotePartitionClient client = new RemotePartitionClient(desc, REMOTE_A,
                localTransport, LOCAL, SCHEMA, "users");
        try {
            final Optional<JlsmDocument> result = client.get("existing-key");

            // BUG: result is always Optional.empty() even though the response had a non-empty
            // document payload. The get() method fetches data but discards it.
            assertTrue(result.isPresent(),
                    "get() returned Optional.empty() despite a non-empty response payload — "
                            + "the document was fetched from the remote but silently discarded");

            // Verify the document contents are correct
            final JlsmDocument returned = result.get();
            assertEquals("user-42", returned.getString("id"),
                    "Deserialized document should have the correct 'id' field value");
            assertEquals("hello-world", returned.getString("value"),
                    "Deserialized document should have the correct 'value' field value");
        } finally {
            client.close();
        }
    }

    // Finding: F-R1.cb.1.4
    // Bug: RemotePartitionClient.update encodes only opcode+key — doc and mode are silently
    // discarded
    // Correct behavior: The update payload must include the document content and the update mode
    // so the remote node can apply the correct update semantics
    // Fix location: RemotePartitionClient.update (line 174) — use an encoding that includes doc and
    // mode
    // Regression watch: Payload format must include mode byte so remote can distinguish REPLACE vs
    // PATCH
    @Test
    @Timeout(10)
    void test_RemotePartitionClient_update_documentAndModeIncludedInPayload() throws Exception {
        // Capture the payload sent to the remote transport
        final AtomicReference<byte[]> capturedPayload = new AtomicReference<>();
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            capturedPayload.set(msg.payload());
            return CompletableFuture.completedFuture(new Message(MessageType.QUERY_RESPONSE,
                    REMOTE_A, msg.sequenceNumber(), new byte[0]));
        });

        final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "user-42", "value", "updated-value");

        table.update("key1", doc, UpdateMode.REPLACE);

        final byte[] payload = capturedPayload.get();
        assertNotNull(payload, "No payload was captured — request was not sent");

        // The payload must be longer than just [opcode(1)][key_len(4)][key_bytes(4)] = 9 bytes
        // because it must also include the document content and mode.
        final String keyStr = "key1";
        final int keyOnlyLength = 1 + 4 + keyStr.getBytes(StandardCharsets.UTF_8).length;

        assertTrue(payload.length > keyOnlyLength, "Payload length is " + payload.length
                + " bytes — only contains opcode+key (" + keyOnlyLength
                + " bytes). The JlsmDocument and UpdateMode were not "
                + "included in the payload, violating the PartitionClient.update contract.");

        // Verify that document field values appear in the payload
        final byte[] idBytes = "user-42".getBytes(StandardCharsets.UTF_8);
        final byte[] valueBytes = "updated-value".getBytes(StandardCharsets.UTF_8);
        assertTrue(containsBytes(payload, idBytes),
                "Payload does not contain document field value 'user-42' — "
                        + "document content was discarded");
        assertTrue(containsBytes(payload, valueBytes),
                "Payload does not contain document field value 'updated-value' — "
                        + "document content was discarded");

        // Verify that the update mode is encoded somewhere in the payload.
        // The mode name "REPLACE" should appear in the payload (as part of the serialization).
        final byte[] modeBytes = "REPLACE".getBytes(StandardCharsets.UTF_8);
        assertTrue(containsBytes(payload, modeBytes),
                "Payload does not contain the UpdateMode 'REPLACE' — "
                        + "the update mode was discarded");
    }

    // Finding: F-R1.cb.1.5
    // Bug: RemotePartitionClient.getRange always returns Collections.emptyIterator() — response
    // discarded
    // Correct behavior: When the response payload contains serialized TableEntry data, getRange()
    // should deserialize and return an iterator over those entries
    // Fix location: RemotePartitionClient.getRange (line 202-204) — deserialize response instead of
    // returning empty
    // Regression watch: Empty response payloads must still return an empty iterator
    @Test
    @Timeout(10)
    void test_RemotePartitionClient_getRange_returnsDeserializedEntries() throws Exception {
        // Build two documents to return as range results
        final JlsmDocument doc1 = JlsmDocument.of(SCHEMA, "id", "user-1", "value", "alpha");
        final JlsmDocument doc2 = JlsmDocument.of(SCHEMA, "id", "user-2", "value", "beta");

        // Encode the response payload:
        // [4-byte entry count][entry1: key-len, key-bytes, doc-len, doc-json-bytes][entry2: ...]
        final byte[] key1Bytes = "key-a".getBytes(StandardCharsets.UTF_8);
        final byte[] doc1Bytes = doc1.toJson().getBytes(StandardCharsets.UTF_8);
        final byte[] key2Bytes = "key-b".getBytes(StandardCharsets.UTF_8);
        final byte[] doc2Bytes = doc2.toJson().getBytes(StandardCharsets.UTF_8);

        final ByteBuffer responseBuf = ByteBuffer
                .allocate(4 + (4 + key1Bytes.length + 4 + doc1Bytes.length)
                        + (4 + key2Bytes.length + 4 + doc2Bytes.length));
        responseBuf.putInt(2); // entry count
        responseBuf.putInt(key1Bytes.length);
        responseBuf.put(key1Bytes);
        responseBuf.putInt(doc1Bytes.length);
        responseBuf.put(doc1Bytes);
        responseBuf.putInt(key2Bytes.length);
        responseBuf.put(key2Bytes);
        responseBuf.putInt(doc2Bytes.length);
        responseBuf.put(doc2Bytes);

        final byte[] responsePayload = responseBuf.array();

        // Register a handler that responds with the encoded entries
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST,
                (sender, msg) -> CompletableFuture
                        .completedFuture(new Message(MessageType.QUERY_RESPONSE, REMOTE_A,
                                msg.sequenceNumber(), responsePayload)));

        // Create a RemotePartitionClient with schema for deserialization
        final PartitionDescriptor desc = new PartitionDescriptor(0L,
                MemorySegment.ofArray(new byte[0]),
                MemorySegment.ofArray(new byte[]{ (byte) 0xFF }), REMOTE_A.nodeId(), 0L);
        final RemotePartitionClient client = new RemotePartitionClient(desc, REMOTE_A,
                localTransport, LOCAL, SCHEMA, "users");
        try {
            final Iterator<TableEntry<String>> result = client.getRange("a", "z");

            // BUG: result is always an empty iterator even though the response contained
            // serialized entries. getRange() sends the request but discards the response.
            assertTrue(result.hasNext(),
                    "getRange() returned an empty iterator despite a non-empty response payload — "
                            + "scan results were fetched from the remote but silently discarded");

            final TableEntry<String> entry1 = result.next();
            assertEquals("key-a", entry1.key(), "First entry should have key 'key-a'");
            assertEquals("user-1", entry1.document().getString("id"),
                    "First entry document should have id 'user-1'");

            assertTrue(result.hasNext(), "Iterator should have a second entry");
            final TableEntry<String> entry2 = result.next();
            assertEquals("key-b", entry2.key(), "Second entry should have key 'key-b'");
            assertEquals("user-2", entry2.document().getString("id"),
                    "Second entry document should have id 'user-2'");

            assertFalse(result.hasNext(), "Iterator should be exhausted after two entries");
        } finally {
            client.close();
        }
    }

    // Finding: F-R1.cb.1.6
    // Bug: ClusteredEngine.createTable overwrites clustered proxy without closing old one
    // Correct behavior: When a table name already exists in the clusteredTables map, the old
    // ClusteredTable proxy should be closed before being replaced
    // Fix location: ClusteredEngine.createTable — close old proxy before put
    // Regression watch: Ensure normal (non-duplicate) createTable still works
    @Test
    @Timeout(10)
    @SuppressWarnings("unchecked")
    void test_ClusteredEngine_createTable_closesOldProxyOnOverwrite() throws Exception {
        final PermissiveStubEngine stubEngine = new PermissiveStubEngine();
        final ClusteredEngine engine = ClusteredEngine.builder().localEngine(stubEngine)
                .membership(membership).ownership(new RendezvousOwnership())
                .gracePeriodManager(new GracePeriodManager(Duration.ofMinutes(2)))
                .transport(localTransport).config(ClusterConfig.builder().build())
                .localAddress(LOCAL).discovery(new InJvmDiscoveryProvider()).build();
        try {
            // First createTable — stores a ClusteredTable proxy
            engine.createTable("t1", SCHEMA);

            // Grab the first proxy from the internal map via reflection
            final Field ctField = ClusteredEngine.class.getDeclaredField("clusteredTables");
            ctField.setAccessible(true);
            final ConcurrentHashMap<String, ClusteredTable> ctMap = (ConcurrentHashMap<String, ClusteredTable>) ctField
                    .get(engine);
            final ClusteredTable firstProxy = ctMap.get("t1");
            assertNotNull(firstProxy, "First proxy should be stored in clusteredTables map");

            // Access the closed flag on the first proxy via reflection
            final Field closedField = ClusteredTable.class.getDeclaredField("closed");
            closedField.setAccessible(true);
            assertFalse((boolean) closedField.get(firstProxy),
                    "First proxy should not be closed before overwrite");

            // Second createTable with the same name — the stub engine allows this
            final JlsmSchema schema2 = JlsmSchema.builder("test2", 1)
                    .field("id", FieldType.Primitive.STRING)
                    .field("name", FieldType.Primitive.STRING).build();
            engine.createTable("t1", schema2);

            // The first proxy should have been closed before being replaced
            // BUG: The old ClusteredTable proxy is leaked — its closed flag is never set,
            // and it is orphaned in memory. ClusteredEngine.close() won't close it because
            // it has been replaced in the map.
            assertTrue((boolean) closedField.get(firstProxy),
                    "Old ClusteredTable proxy must be closed when overwritten by a new "
                            + "createTable call — the orphaned proxy leaks resources");

            // The new proxy should be in the map and not closed
            final ClusteredTable secondProxy = ctMap.get("t1");
            assertNotNull(secondProxy, "New proxy should be stored in clusteredTables map");
            assertNotSame(firstProxy, secondProxy,
                    "Second createTable should create a distinct proxy");
            assertFalse((boolean) closedField.get(secondProxy), "New proxy should not be closed");
        } finally {
            engine.close();
        }
    }

    // Finding: F-R1.cb.1.7
    // Bug: ClusteredEngine.close does not wrap ClusteredTable.close in try-catch —
    // if one table's close throws, remaining tables and subsequent resources are skipped
    // Correct behavior: Each ClusteredTable.close() should be wrapped in try-catch,
    // exceptions collected in the deferred errors list, and all resources still closed
    // Fix location: ClusteredEngine.close (lines 176-178) — wrap ct.close() in try-catch
    // Regression watch: Ensure deferred exception is still thrown after all resources close
    @Test
    @Timeout(10)
    @SuppressWarnings("unchecked")
    void test_ClusteredEngine_close_collectsExceptionsFromClusteredTableClose() throws Exception {
        final TrackingStubEngine stubEngine = new TrackingStubEngine();
        final ClusteredEngine engine = ClusteredEngine.builder().localEngine(stubEngine)
                .membership(membership).ownership(new RendezvousOwnership())
                .gracePeriodManager(new GracePeriodManager(Duration.ofMinutes(2)))
                .transport(localTransport).config(ClusterConfig.builder().build())
                .localAddress(LOCAL).discovery(new InJvmDiscoveryProvider()).build();

        // Create two tables so two proxies exist
        engine.createTable("t1", SCHEMA);
        engine.createTable("t2", SCHEMA);

        // Access internal clusteredTables map via reflection
        final Field ctField = ClusteredEngine.class.getDeclaredField("clusteredTables");
        ctField.setAccessible(true);
        final ConcurrentHashMap<String, ClusteredTable> ctMap = (ConcurrentHashMap<String, ClusteredTable>) ctField
                .get(engine);

        // Sabotage the first table's ownership field to null so close() throws NPE
        final ClusteredTable firstTable = ctMap.get("t1");
        final ClusteredTable secondTable = ctMap.get("t2");
        assertNotNull(firstTable);
        assertNotNull(secondTable);

        final Field ownershipField = ClusteredTable.class.getDeclaredField("ownership");
        ownershipField.setAccessible(true);
        ownershipField.set(firstTable, null);

        // Close the engine — t1.close() will throw NPE
        // BUG: Without try-catch around ct.close(), the NPE from t1 prevents
        // t2 from being closed and prevents localEngine.close() from running.
        try {
            engine.close();
        } catch (Exception ignored) {
            // We expect an exception — the question is whether subsequent resources were closed
        }

        // Verify the second table was still closed despite the first table throwing
        final Field closedField = ClusteredTable.class.getDeclaredField("closed");
        closedField.setAccessible(true);
        assertTrue((boolean) closedField.get(secondTable),
                "Second ClusteredTable must be closed even when the first table's close() "
                        + "throws — ClusteredEngine.close must use deferred exception collection");

        // Verify the local engine was still closed (subsequent resource cleanup ran)
        assertTrue(stubEngine.closeCalled,
                "localEngine.close() must be called even when a ClusteredTable.close() "
                        + "throws — the deferred exception pattern requires all resources to be released");
    }

    // Finding: F-R1.cb.1.8
    // Bug: ClusteredEngine.createTable returns localTable, not clusteredTable — caller bypasses
    // clustering
    // Correct behavior: createTable must return the ClusteredTable proxy so callers route through
    // the clustering layer (partition routing, scatter-gather)
    // Fix location: ClusteredEngine.createTable (line 111) — return clustered proxy instead of
    // localTable
    // Regression watch: Ensure the returned Table still exposes correct metadata
    @Test
    @Timeout(10)
    void test_ClusteredEngine_createTable_returnsClusteredProxy() throws Exception {
        final PermissiveStubEngine stubEngine = new PermissiveStubEngine();
        final ClusteredEngine engine = ClusteredEngine.builder().localEngine(stubEngine)
                .membership(membership).ownership(new RendezvousOwnership())
                .gracePeriodManager(new GracePeriodManager(Duration.ofMinutes(2)))
                .transport(localTransport).config(ClusterConfig.builder().build())
                .localAddress(LOCAL).discovery(new InJvmDiscoveryProvider()).build();
        try {
            final Table returned = engine.createTable("t1", SCHEMA);

            // The returned Table must be a ClusteredTable (the partition-aware proxy),
            // NOT the raw local table. If it's the local table, callers bypass the
            // entire clustering layer — partition routing and scatter-gather are dead code.
            assertInstanceOf(ClusteredTable.class, returned,
                    "createTable must return the ClusteredTable proxy, not the raw local table — "
                            + "returning the local table bypasses partition routing and scatter-gather");

            // The metadata on the returned proxy must still be correct
            assertEquals("t1", returned.metadata().name(),
                    "Returned ClusteredTable should have the correct table name");
        } finally {
            engine.close();
        }
    }

    // Finding: F-R1.cb.1.9
    // Bug: GracePeriodManager departures map grows unboundedly — no cleanup mechanism invoked
    // by ClusteredEngine.onViewChanged. Permanently crashed nodes accumulate entries forever.
    // Correct behavior: ClusteredEngine.onViewChanged should invoke expiredDepartures() to
    // clean up expired departure entries, preventing unbounded map growth.
    // Fix location: ClusteredEngine.onViewChanged — add call to
    // gracePeriodManager.expiredDepartures()
    // Regression watch: Ensure grace period nodes still tracked correctly before expiry
    @Test
    @Timeout(10)
    @SuppressWarnings("unchecked")
    void test_GracePeriodManager_departuresCleanedByViewChange() throws Exception {
        // Use a grace period of 50ms — long enough to survive one onViewChanged call,
        // short enough to expire before the second one after a sleep
        final GracePeriodManager gpm = new GracePeriodManager(Duration.ofMillis(50));
        final ListenerCapturingMembershipProtocol capturingMembership = new ListenerCapturingMembershipProtocol();

        final NodeAddress crashedNode1 = new NodeAddress("crashed-1", "localhost", 10001);
        final NodeAddress crashedNode2 = new NodeAddress("crashed-2", "localhost", 10002);
        final NodeAddress crashedNode3 = new NodeAddress("crashed-3", "localhost", 10003);

        final ClusteredEngine engine = ClusteredEngine.builder()
                .localEngine(new PermissiveStubEngine()).membership(capturingMembership)
                .ownership(new RendezvousOwnership()).gracePeriodManager(gpm)
                .transport(localTransport).config(ClusterConfig.builder().build())
                .localAddress(LOCAL).discovery(new InJvmDiscoveryProvider()).build();
        try {
            assertNotNull(capturingMembership.listener,
                    "ClusteredEngine must register a MembershipListener");

            // Epoch 1: LOCAL + 3 nodes alive
            final MembershipView view1 = new MembershipView(1,
                    Set.of(new Member(LOCAL, MemberState.ALIVE, 0),
                            new Member(crashedNode1, MemberState.ALIVE, 0),
                            new Member(crashedNode2, MemberState.ALIVE, 0),
                            new Member(crashedNode3, MemberState.ALIVE, 0)),
                    NOW);
            capturingMembership.view = view1;

            // Epoch 2: all 3 remote nodes crash (gone from view)
            final MembershipView view2 = new MembershipView(2,
                    Set.of(new Member(LOCAL, MemberState.ALIVE, 0)), NOW);
            capturingMembership.view = view2;
            capturingMembership.listener.onViewChanged(view1, view2);

            // Now 3 departure entries exist. With 50ms grace period, they are still active.
            // Access the departures map via reflection
            final Field departuresField = GracePeriodManager.class.getDeclaredField("departures");
            departuresField.setAccessible(true);
            final ConcurrentHashMap<NodeAddress, Instant> departures = (ConcurrentHashMap<NodeAddress, Instant>) departuresField
                    .get(gpm);

            assertEquals(3, departures.size(),
                    "After 3 nodes crash, departures map should have 3 entries");

            // Sleep beyond the 50ms grace period so entries expire before the next view change
            Thread.sleep(100);

            // Epoch 3: another view change (no new members, just a heartbeat epoch bump).
            // This should trigger cleanup of expired departures.
            final MembershipView view3 = new MembershipView(3,
                    Set.of(new Member(LOCAL, MemberState.ALIVE, 0)), NOW);
            capturingMembership.view = view3;
            capturingMembership.listener.onViewChanged(view2, view3);

            // BUG: onViewChanged never calls expiredDepartures(), so all 3 expired entries
            // remain in the map indefinitely. In a long-running cluster with node churn,
            // this map grows without bound.
            assertTrue(departures.isEmpty(),
                    "After grace period has expired and a new view change occurred, "
                            + "departed entries must be cleaned up — departures map has "
                            + departures.size() + " stale entries (expected 0). "
                            + "ClusteredEngine.onViewChanged must invoke "
                            + "gracePeriodManager.expiredDepartures() to prevent unbounded growth.");
        } finally {
            engine.close();
        }
    }

    // Finding: F-R1.cb.1.10
    // Bug: ClusteredEngine.onViewChanged uses assert for null-checking oldView/newView parameters.
    // With assertions disabled in production, null oldView causes NullPointerException at
    // oldView.members() instead of a clear, eagerly-thrown NullPointerException from a runtime
    // check.
    // Correct behavior: onViewChanged must use Objects.requireNonNull (runtime check) for both
    // parameters, throwing NullPointerException with an informative message regardless of -ea flag.
    // Fix location: ClusteredEngine.onViewChanged lines 253-254 — replace assert with
    // requireNonNull
    // Regression watch: Ensure the method still works correctly with valid (non-null) views
    @Test
    @Timeout(10)
    void test_ClusteredEngine_onViewChanged_rejectsNullViewWithRuntimeCheck() throws Exception {
        final ListenerCapturingMembershipProtocol capturingMembership = new ListenerCapturingMembershipProtocol();

        final MembershipView validView = new MembershipView(1,
                Set.of(new Member(LOCAL, MemberState.ALIVE, 0)), NOW);
        capturingMembership.view = validView;

        final ClusteredEngine engine = ClusteredEngine.builder()
                .localEngine(new PermissiveStubEngine()).membership(capturingMembership)
                .ownership(new RendezvousOwnership())
                .gracePeriodManager(new GracePeriodManager(Duration.ofMinutes(2)))
                .transport(localTransport).config(ClusterConfig.builder().build())
                .localAddress(LOCAL).discovery(new InJvmDiscoveryProvider()).build();
        try {
            assertNotNull(capturingMembership.listener,
                    "ClusteredEngine must register a MembershipListener");

            // Passing null oldView must throw NullPointerException from a runtime check
            // (Objects.requireNonNull), NOT AssertionError from an assert statement.
            // With -ea enabled (test JVM), the current assert fires as AssertionError —
            // that is the wrong exception type for a public API boundary guard.
            final NullPointerException npe = assertThrows(NullPointerException.class,
                    () -> capturingMembership.listener.onViewChanged(null, validView),
                    "onViewChanged(null, validView) must throw NullPointerException via "
                            + "runtime check — assert-only guards are disabled in production");

            // The exception message should identify the parameter
            assertNotNull(npe.getMessage(),
                    "NullPointerException should have an informative message identifying "
                            + "the null parameter");

            // Also verify null newView is rejected with the same runtime check
            final NullPointerException npe2 = assertThrows(NullPointerException.class,
                    () -> capturingMembership.listener.onViewChanged(validView, null),
                    "onViewChanged(validView, null) must throw NullPointerException via "
                            + "runtime check — assert-only guards are disabled in production");

            assertNotNull(npe2.getMessage(),
                    "NullPointerException should have an informative message identifying "
                            + "the null parameter");
        } finally {
            engine.close();
        }
    }

    // Finding: F-R1.cb.1.12
    // Bug: ClusteredTable.resolveOwner throws unchecked IllegalStateException("No live members
    // in the cluster") through create/get/update/delete which declare throws IOException.
    // Callers catching only IOException will not catch the IllegalStateException.
    // Correct behavior: When no live members exist, the exception should be IOException (or
    // wrapped as IOException) so it matches the declared throws clause of the calling methods.
    // Fix location: ClusteredTable.resolveOwner (line 227) — wrap IllegalStateException in
    // IOException
    // Regression watch: Ensure the error message is preserved and the cause chain is intact
    @Test
    @Timeout(10)
    void test_ClusteredTable_resolveOwner_throwsIOExceptionWhenNoLiveMembers() throws Exception {
        // Set up a membership view with no ALIVE members (only SUSPECT or empty)
        membership.view = new MembershipView(1, Set.of(), NOW);

        // All four CRUD methods call resolveOwner, which throws IllegalStateException
        // when no live members exist. The bug is that this unchecked exception escapes
        // through methods that declare only IOException — callers catching IOException
        // will miss it.

        // Verify create throws IOException (not IllegalStateException)
        final JlsmDocument doc = JlsmDocument.of(SCHEMA, "id", "test", "value", "v");
        final IOException createEx = assertThrows(IOException.class,
                () -> table.create("key1", doc),
                "create() must throw IOException when no live members exist — "
                        + "IllegalStateException violates the declared throws clause");
        assertTrue(createEx.getMessage().contains("No live members"),
                "IOException message should describe the no-live-members condition, got: "
                        + createEx.getMessage());

        // Verify get throws IOException (not IllegalStateException)
        final IOException getEx = assertThrows(IOException.class, () -> table.get("key1"),
                "get() must throw IOException when no live members exist — "
                        + "IllegalStateException violates the declared throws clause");
        assertTrue(getEx.getMessage().contains("No live members"),
                "IOException message should describe the no-live-members condition, got: "
                        + getEx.getMessage());

        // Verify delete throws IOException (not IllegalStateException)
        final IOException deleteEx = assertThrows(IOException.class, () -> table.delete("key1"),
                "delete() must throw IOException when no live members exist — "
                        + "IllegalStateException violates the declared throws clause");
        assertTrue(deleteEx.getMessage().contains("No live members"),
                "IOException message should describe the no-live-members condition, got: "
                        + deleteEx.getMessage());
    }

    // Finding: F-R1.cb.2.1
    // Bug: protocolTick uses "phi > threshold" which evaluates to false for NaN — a node whose
    // phi() returns NaN is never suspected, silently bypassing the failure detector.
    // isAvailable() uses "phi < threshold" (returns false for NaN = fail-safe), but
    // protocolTick bypasses isAvailable and calls phi() directly with the unsafe ">" operator.
    // Correct behavior: protocolTick should use isAvailable() or equivalent NaN-safe comparison
    // so that NaN phi results in suspicion (fail-safe)
    // Fix location: RapidMembership.protocolTick — replace "phi > threshold" with
    // "!failureDetector.isAvailable(member.address(), config.phiThreshold())"
    // Regression watch: Ensure normal (non-NaN) phi comparisons still work correctly
    @Test
    @Timeout(10)
    void test_protocolTick_nanPhi_shouldSuspectNode() throws Exception {
        InJvmTransport.clearRegistry();
        final InJvmTransport transport = new InJvmTransport(LOCAL);
        try {
            final InJvmDiscoveryProvider discovery = new InJvmDiscoveryProvider();
            // 100% quorum with degree=1 means the single observer (LOCAL) agreeing is sufficient.
            // NOTE: under the WU-4 RAPID wiring, protocolTick starts a consensus round on phi
            // breach rather than transitioning the view unilaterally. The round still completes
            // synchronously in-JVM because observers[REMOTE_A] = {LOCAL} for a 2-node cluster.
            final ClusterConfig config = ClusterConfig.builder().expanderGraphDegree(1)
                    .consensusQuorumPercent(100).build();
            final PhiAccrualFailureDetector detector = new PhiAccrualFailureDetector(2);

            final RapidMembership rapid = new RapidMembership(LOCAL, transport, discovery, config,
                    detector);
            try {
                // Set started = true and currentView via reflection so protocolTick runs
                final Field startedField = RapidMembership.class.getDeclaredField("started");
                startedField.setAccessible(true);
                startedField.set(rapid, true);

                final MembershipView initialView = new MembershipView(1,
                        Set.of(new Member(LOCAL, MemberState.ALIVE, 0),
                                new Member(REMOTE_A, MemberState.ALIVE, 0)),
                        NOW);
                final Field viewField = RapidMembership.class.getDeclaredField("currentView");
                viewField.setAccessible(true);
                viewField.set(rapid, initialView);

                // Rebuild the overlay so protocolTick can observe REMOTE_A as a monitor.
                final var rebuildMethod = RapidMembership.class.getDeclaredMethod(
                        "rebuildOverlayAndNotifyCoordinator", MembershipView.class);
                rebuildMethod.setAccessible(true);
                rebuildMethod.invoke(rapid, initialView);

                // Register handlers so VIEW_CHANGE messages (for the SUSPICION_PROPOSAL → VOTE
                // self-dispatch path) are routed back into the coordinator.
                final var registerHandlersMethod = RapidMembership.class
                        .getDeclaredMethod("registerHandlers");
                registerHandlersMethod.setAccessible(true);
                registerHandlersMethod.invoke(rapid);

                // Record two heartbeats so the detector has history for REMOTE_A
                final long baseNanos = System.nanoTime() - 60_000_000_000L;
                detector.recordHeartbeat(REMOTE_A, baseNanos);
                detector.recordHeartbeat(REMOTE_A, baseNanos + 1_000_000_000L);

                // Now corrupt the HeartbeatHistory's sum field to NaN via reflection.
                // This simulates the torn-read scenario described in F-R1.cb.2.6 where
                // concurrent access produces NaN in the history's running statistics.
                // NaN propagates through mean() → computePhi() where Math.max(0.0, NaN)
                // silently clamps the result to 0.0 — the node appears perfectly healthy.
                final Field historyMapField = PhiAccrualFailureDetector.class
                        .getDeclaredField("heartbeatHistory");
                historyMapField.setAccessible(true);
                @SuppressWarnings("unchecked")
                final ConcurrentHashMap<NodeAddress, Object> historyMap = (ConcurrentHashMap<NodeAddress, Object>) historyMapField
                        .get(detector);
                final Object history = historyMap.get(REMOTE_A);
                assertNotNull(history, "HeartbeatHistory must exist for REMOTE_A after recording");

                // Set sum = NaN so mean() returns NaN, propagating through computePhi.
                // NaN flows: mean() → NaN → computePhi stddev=NaN → effectiveStddev=NaN.
                // With -ea: assert "stddev > 0" fires (NaN > 0 is false) → AssertionError.
                // Without -ea (production): NaN propagates to phi result, then
                // "NaN > threshold" in protocolTick evaluates to false → node not suspected.
                // The fix must add a runtime NaN guard (not just assert) that maps NaN to
                // MAX_VALUE so the node is failed-safe to suspicion.
                final Field sumField = history.getClass().getDeclaredField("sum");
                sumField.setAccessible(true);
                sumField.set(history, Double.NaN);

                // phi() must return a fail-safe high value (e.g. MAX_VALUE) when the
                // computation produces NaN, not throw AssertionError or return NaN/0.0.
                // Before the fix: with -ea enabled, this throws AssertionError from
                // "assert stddev > 0" in computePhi. Without -ea, it returns NaN which
                // then silently passes through "phi > threshold" as false.
                // After the fix: a runtime NaN guard returns MAX_VALUE.
                final double phi = detector.phi(REMOTE_A);
                assertFalse(Double.isNaN(phi),
                        "phi() must not return NaN — NaN should be caught by a runtime "
                                + "guard and mapped to a fail-safe value");
                assertTrue(phi > config.phiThreshold(),
                        "phi() returned " + phi + " after NaN corruption — should return a "
                                + "fail-safe value (>= MAX_VALUE or > threshold) instead of "
                                + "NaN or 0.0");

                // Also verify via protocolTick: the node must be suspected
                final var tickMethod = RapidMembership.class.getDeclaredMethod("protocolTick");
                tickMethod.setAccessible(true);
                tickMethod.invoke(rapid);

                final MembershipView afterView = rapid.currentView();
                boolean remoteSuspected = false;
                for (Member m : afterView.members()) {
                    if (m.address().equals(REMOTE_A)) {
                        remoteSuspected = (m.state() == MemberState.SUSPECTED);
                        break;
                    }
                }
                assertTrue(remoteSuspected,
                        "REMOTE_A should be SUSPECTED when phi computation is corrupted — "
                                + "NaN must fail safe to suspicion, not be silently clamped to 0.0");
            } finally {
                rapid.close();
            }
        } finally {
            transport.close();
            InJvmTransport.clearRegistry();
            InJvmDiscoveryProvider.clearRegistrations();
        }
    }

    // Finding: F-R1.cb.2.2
    // Bug: phi() returns 0.0 for nodes with no heartbeat history — newly joined members
    // that never respond to pings are immune to suspicion because 0.0 < threshold is always true.
    // protocolTick sends fire-and-forget pings (transport.send) but only the *receiver* records
    // heartbeats in handlePing. If the new node never responds, no heartbeat is ever recorded
    // on the local side, so phi() keeps returning 0.0 forever.
    // Correct behavior: protocolTick must seed a heartbeat for nodes with no history so the phi
    // clock starts ticking. If the node never responds, elapsed time grows and phi eventually
    // exceeds the threshold, causing suspicion.
    // Fix location: RapidMembership.protocolTick — seed heartbeat for nodes with no history
    // Regression watch: Ensure nodes that do respond are not falsely suspected after seeding
    @Test
    @Timeout(10)
    void test_protocolTick_noHeartbeatHistory_shouldEventuallySuspect() throws Exception {
        InJvmTransport.clearRegistry();
        final InJvmTransport transport = new InJvmTransport(LOCAL);
        try {
            final InJvmDiscoveryProvider discovery = new InJvmDiscoveryProvider();
            final ClusterConfig config = ClusterConfig.builder().expanderGraphDegree(1).build();
            final PhiAccrualFailureDetector detector = new PhiAccrualFailureDetector(2);

            final RapidMembership rapid = new RapidMembership(LOCAL, transport, discovery, config,
                    detector);
            try {
                // Set started = true and currentView via reflection so protocolTick runs
                final Field startedField = RapidMembership.class.getDeclaredField("started");
                startedField.setAccessible(true);
                startedField.set(rapid, true);

                final MembershipView initialView = new MembershipView(1,
                        Set.of(new Member(LOCAL, MemberState.ALIVE, 0),
                                new Member(REMOTE_A, MemberState.ALIVE, 0)),
                        NOW);
                final Field viewField = RapidMembership.class.getDeclaredField("currentView");
                viewField.setAccessible(true);
                viewField.set(rapid, initialView);

                // Rebuild the overlay so protocolTick observes REMOTE_A as a monitor target;
                // without this, the overlay remains empty and no seeding happens.
                final var rebuildMethod = RapidMembership.class.getDeclaredMethod(
                        "rebuildOverlayAndNotifyCoordinator", MembershipView.class);
                rebuildMethod.setAccessible(true);
                rebuildMethod.invoke(rapid, initialView);

                // REMOTE_A has NO heartbeat history at all — it just joined and never responded.
                // Before the fix: phi(REMOTE_A) returns 0.0, which is < threshold, so
                // protocolTick never suspects it no matter how many ticks pass.

                // Run protocolTick twice — first tick seeds a first heartbeat (count=1, phi
                // still returns 0.0 because count < 2). Second tick seeds again (count=2,
                // phi can now compute a real value based on elapsed time).
                final var tickMethod = RapidMembership.class.getDeclaredMethod("protocolTick");
                tickMethod.setAccessible(true);
                tickMethod.invoke(rapid);
                tickMethod.invoke(rapid);

                // After two ticks with seeded heartbeats, the detector has enough history.
                // Simulate time passing: call phi with a far-future timestamp.
                // If seeding worked, the detector now has a baseline, and a large elapsed
                // time produces a high phi (node hasn't responded in 600 seconds).
                final long farFutureNanos = System.nanoTime() + 600_000_000_000L; // +600 seconds
                final double phi = detector.phi(REMOTE_A, farFutureNanos);

                assertTrue(phi > config.phiThreshold(),
                        "After seeding a heartbeat and 600s without response, phi should exceed "
                                + "threshold (" + config.phiThreshold() + ") but was " + phi
                                + ". Without seeding, phi returns 0.0 forever for unknown nodes, "
                                + "making newly joined non-responsive nodes immune to suspicion.");
            } finally {
                rapid.close();
            }
        } finally {
            transport.close();
            InJvmTransport.clearRegistry();
            InJvmDiscoveryProvider.clearRegistrations();
        }
    }

    // Finding: F-R1.cb.2.3
    // Bug: protocolTick sends pings via fire-and-forget transport.send() and never processes
    // the ACK response. Failure detection is asymmetric — the local node only detects failures
    // based on incoming pings from the remote node's own protocolTick, not from ping responses.
    // A responsive node whose scheduler is delayed (no outbound pings) gets falsely suspected.
    // Correct behavior: protocolTick should use transport.request() and record a heartbeat when
    // the ACK arrives, making failure detection symmetric (responsive nodes are not suspected).
    // Fix location: RapidMembership.protocolTick — change send() to request() and record
    // heartbeat on ACK
    // Regression watch: Ensure unreachable nodes (request fails/times out) are still suspected
    @Test
    @Timeout(10)
    void test_protocolTick_responsiveNode_shouldNotBeSuspected() throws Exception {
        InJvmTransport.clearRegistry();
        final InJvmTransport transport = new InJvmTransport(LOCAL);
        // Create a remote transport that will respond to PINGs with ACKs
        final InJvmTransport remoteTransportForPing = new InJvmTransport(REMOTE_A);
        remoteTransportForPing.registerHandler(MessageType.PING,
                (sender, msg) -> CompletableFuture.completedFuture(
                        new Message(MessageType.ACK, REMOTE_A, msg.sequenceNumber(), new byte[0])));
        try {
            final InJvmDiscoveryProvider discovery = new InJvmDiscoveryProvider();
            final ClusterConfig config = ClusterConfig.builder().build();
            final PhiAccrualFailureDetector detector = new PhiAccrualFailureDetector(2);

            final RapidMembership rapid = new RapidMembership(LOCAL, transport, discovery, config,
                    detector);
            try {
                // Set started = true and currentView via reflection so protocolTick runs
                final Field startedField = RapidMembership.class.getDeclaredField("started");
                startedField.setAccessible(true);
                startedField.set(rapid, true);

                final MembershipView initialView = new MembershipView(1,
                        Set.of(new Member(LOCAL, MemberState.ALIVE, 0),
                                new Member(REMOTE_A, MemberState.ALIVE, 0)),
                        NOW);
                final Field viewField = RapidMembership.class.getDeclaredField("currentView");
                viewField.setAccessible(true);
                viewField.set(rapid, initialView);

                // Run several protocolTick iterations. REMOTE_A is responsive (ACKs work)
                // but never initiates its own pings to LOCAL.
                // If protocolTick uses request() and records heartbeats from ACKs,
                // each tick refreshes the heartbeat for REMOTE_A, and it stays ALIVE.
                // If protocolTick uses fire-and-forget send(), the only heartbeats are
                // the seeds from the phi==0.0 guard, and eventually phi exceeds threshold.
                final var tickMethod = RapidMembership.class.getDeclaredMethod("protocolTick");
                tickMethod.setAccessible(true);

                // Run enough ticks for the detector to have history
                for (int i = 0; i < 5; i++) {
                    tickMethod.invoke(rapid);
                    // Small sleep to create realistic inter-tick intervals
                    Thread.sleep(50);
                }

                // After 5 ticks with a responsive remote node, the remote should still be ALIVE.
                // The phi value should be low because heartbeats from ACK responses keep it fresh.
                final MembershipView afterView = rapid.currentView();
                boolean remoteStillAlive = false;
                for (Member m : afterView.members()) {
                    if (m.address().equals(REMOTE_A)) {
                        remoteStillAlive = (m.state() == MemberState.ALIVE);
                        break;
                    }
                }
                assertTrue(remoteStillAlive,
                        "REMOTE_A responds to pings (ACKs arrive) but was suspected — "
                                + "protocolTick must record heartbeats from ping responses to make "
                                + "failure detection symmetric. A node that responds should not be "
                                + "falsely suspected just because it hasn't sent its own pings.");
            } finally {
                rapid.close();
            }
        } finally {
            transport.close();
            remoteTransportForPing.close();
            InJvmTransport.clearRegistry();
            InJvmDiscoveryProvider.clearRegistrations();
        }
    }

    // Finding: F-R1.cb.2.4
    // Bug: handlePing records heartbeat from any sender — no membership check.
    // A removed/unknown node can send PING messages and have its heartbeat
    // recorded, polluting the failure detector's state with entries for non-members.
    // Correct behavior: handlePing should ignore pings from senders that are not
    // members of the current view.
    // Fix location: RapidMembership.handlePing — add currentView membership check before recording
    // Regression watch: Ensure pings from valid members are still recorded correctly
    @Test
    @Timeout(10)
    void test_handlePing_nonMemberSender_heartbeatNotRecorded() throws Exception {
        InJvmTransport.clearRegistry();
        final InJvmTransport transport = new InJvmTransport(LOCAL);
        // Create a transport for the non-member so it can send messages
        final NodeAddress nonMember = new NodeAddress("unknown", "localhost", 9999);
        final InJvmTransport nonMemberTransport = new InJvmTransport(nonMember);
        try {
            final InJvmDiscoveryProvider discovery = new InJvmDiscoveryProvider();
            final ClusterConfig config = ClusterConfig.builder().build();
            final PhiAccrualFailureDetector detector = new PhiAccrualFailureDetector(2);

            final RapidMembership rapid = new RapidMembership(LOCAL, transport, discovery, config,
                    detector);
            try {
                // Start the protocol so handlers are registered.
                // Use an empty seeds list — LOCAL is the only member.
                rapid.start(List.of());

                // The current view has only LOCAL as a member.
                // Verify the non-member is not in the view.
                assertFalse(rapid.currentView().isMember(nonMember),
                        "Non-member should not be in the current view");

                // Send a PING from the non-member node to LOCAL via transport.
                // handlePing is registered as the PING handler, so this invokes it.
                final Message ping = new Message(MessageType.PING, nonMember, 1, new byte[0]);
                nonMemberTransport.request(LOCAL, ping).join();

                // Check if the failure detector recorded a heartbeat for the non-member.
                // Access the internal heartbeatHistory map to verify.
                final Field historyMapField = PhiAccrualFailureDetector.class
                        .getDeclaredField("heartbeatHistory");
                historyMapField.setAccessible(true);
                @SuppressWarnings("unchecked")
                final ConcurrentHashMap<NodeAddress, ?> historyMap = (ConcurrentHashMap<NodeAddress, ?>) historyMapField
                        .get(detector);

                // BUG: handlePing records the heartbeat unconditionally — the non-member
                // now has an entry in the failure detector's heartbeat history.
                assertFalse(historyMap.containsKey(nonMember),
                        "handlePing recorded a heartbeat for a non-member sender (" + nonMember
                                + ") — the failure detector's state is polluted with "
                                + "entries for nodes not in the current membership view. "
                                + "handlePing must check currentView.isMember(sender) before "
                                + "recording a heartbeat.");
            } finally {
                rapid.close();
            }
        } finally {
            transport.close();
            nonMemberTransport.close();
            InJvmTransport.clearRegistry();
            InJvmDiscoveryProvider.clearRegistrations();
        }
    }

    // Finding: F-R1.cb.2.5
    // Bug: protocolTick reads currentView without viewLock — a concurrent leave notification
    // can remove a member from the view while protocolTick still iterates the stale snapshot,
    // causing pings and phi checks for already-departed nodes
    // Correct behavior: protocolTick should hold viewLock while reading the view so that
    // concurrent view mutations cannot interleave with the iteration
    // Fix location: RapidMembership.protocolTick (line 490) — acquire viewLock before reading
    // currentView
    // Regression watch: viewLock contention — ensure handleLeaveNotification can still proceed
    // when protocolTick is not running
    @Test
    @Timeout(10)
    void test_RapidMembership_protocolTick_staleViewAfterConcurrentLeave() throws Exception {
        // Clean up shared state from @BeforeEach — this test manages its own lifecycle
        table.close();
        localTransport.close();
        remoteTransport.close();
        InJvmTransport.clearRegistry();

        // Holder so the intercepting transport can reference the RapidMembership instance
        // after construction (breaks the circular dependency).
        final var rapidRef = new AtomicReference<RapidMembership>();
        final var viewAtPingTime = new AtomicReference<MembershipView>();
        final var leaveTriggered = new AtomicBoolean(false);

        // Use distinct addresses to avoid collisions
        final var tickLocal = new NodeAddress("tick-local", "localhost", 9010);
        final var tickRemote = new NodeAddress("tick-remote", "localhost", 9011);

        final var baseTransport = new InJvmTransport(tickLocal);
        final var interceptingTransport = new ClusterTransport() {
            private final ConcurrentHashMap<MessageType, MessageHandler> handlers = new ConcurrentHashMap<>();

            @Override
            public void send(NodeAddress target, Message msg) throws IOException {
                baseTransport.send(target, msg);
            }

            @Override
            public CompletableFuture<Message> request(NodeAddress target, Message msg) {
                if (msg.type() == MessageType.PING && target.equals(tickRemote)
                        && !leaveTriggered.getAndSet(true)) {
                    // Trigger a leave for tickRemote by invoking the VIEW_CHANGE handler.
                    // This simulates tickRemote departing while protocolTick is mid-iteration.
                    final MessageHandler viewChangeHandler = handlers.get(MessageType.VIEW_CHANGE);
                    if (viewChangeHandler != null) {
                        viewChangeHandler.handle(tickRemote, new Message(MessageType.VIEW_CHANGE,
                                tickRemote, 99, new byte[]{ 0x03 })); // MSG_LEAVE = 0x03
                    }

                    // Capture currentView AFTER the leave was processed
                    final RapidMembership rapid = rapidRef.get();
                    if (rapid != null) {
                        try {
                            final Field cvField = RapidMembership.class
                                    .getDeclaredField("currentView");
                            cvField.setAccessible(true);
                            viewAtPingTime.set((MembershipView) cvField.get(rapid));
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }

                // Return a completed ACK
                return CompletableFuture.completedFuture(
                        new Message(MessageType.ACK, target, msg.sequenceNumber(), new byte[0]));
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
                baseTransport.close();
            }
        };

        // long protocolPeriod so we can invoke protocolTick manually
        final var config = ClusterConfig.builder().protocolPeriod(Duration.ofSeconds(60))
                .expanderGraphDegree(1).build();
        final var detector = new PhiAccrualFailureDetector(100);
        final var discovery = new InJvmDiscoveryProvider();

        final var rapid = new RapidMembership(tickLocal, interceptingTransport, discovery, config,
                detector);
        rapidRef.set(rapid);

        try {
            // Start single-node
            rapid.start(List.of());

            // Manually set currentView to include tickRemote as ALIVE and rebuild overlay so
            // protocolTick sees tickRemote as a monitor target.
            final Field cvField = RapidMembership.class.getDeclaredField("currentView");
            cvField.setAccessible(true);
            final MembershipView injected = new MembershipView(1,
                    Set.of(new Member(tickLocal, MemberState.ALIVE, 0),
                            new Member(tickRemote, MemberState.ALIVE, 0)),
                    Instant.now());
            cvField.set(rapid, injected);
            final var rebuildMethod = RapidMembership.class
                    .getDeclaredMethod("rebuildOverlayAndNotifyCoordinator", MembershipView.class);
            rebuildMethod.setAccessible(true);
            rebuildMethod.invoke(rapid, injected);

            // Invoke protocolTick directly via reflection
            final java.lang.reflect.Method tickMethod = RapidMembership.class
                    .getDeclaredMethod("protocolTick");
            tickMethod.setAccessible(true);
            tickMethod.invoke(rapid);

            // After protocolTick: the leave was triggered during the PING to tickRemote.
            // The currentView now has tickRemote as DEAD.
            assertTrue(leaveTriggered.get(),
                    "Leave should have been triggered during protocolTick");

            final MembershipView viewDuringPing = viewAtPingTime.get();
            assertNotNull(viewDuringPing,
                    "Should have captured the view after the leave was processed");

            // Verify the leave actually took effect — tickRemote is now DEAD in currentView
            boolean remoteDead = false;
            for (Member m : viewDuringPing.members()) {
                if (m.address().equals(tickRemote) && m.state() == MemberState.DEAD) {
                    remoteDead = true;
                    break;
                }
            }
            assertTrue(remoteDead,
                    "Precondition: tickRemote should be DEAD in currentView after the leave");

            // CORRECT BEHAVIOR: protocolTick should NOT seed a heartbeat for a member that
            // is DEAD in the current view. The failure detector should return 0.0 for
            // tickRemote (meaning no heartbeat history exists).
            // BUG: protocolTick reads currentView without viewLock, iterates a stale snapshot,
            // and seeds a heartbeat for the departed member via the phi==0.0 path (line 528).
            final double phiForDead = detector.phi(tickRemote);
            assertEquals(0.0, phiForDead,
                    "Failure detector should NOT have heartbeat history for a DEAD member — "
                            + "protocolTick should not seed heartbeats for members that have "
                            + "already departed. Got phi=" + phiForDead + " (non-zero means "
                            + "protocolTick operated on a stale view snapshot)");
        } finally {
            rapid.close();
            interceptingTransport.close();
            InJvmTransport.clearRegistry();
            InJvmDiscoveryProvider.clearRegistrations();
        }
    }

    // Finding: F-R1.contract_boundaries.1.1
    // Bug: QueryRequestHandler.handle() invokes msg.payload() twice (once for decodeHeader
    // and once for dispatch), and Message.payload() clones the backing byte[] on every
    // call. This doubles the defensive-clone allocation per dispatch.
    // Correct behavior: handle() should call msg.payload() exactly once per dispatch, storing
    // the cloned result in a local variable and passing it to both decodeHeader and
    // dispatch.
    // Fix location: QueryRequestHandler.handle (L78-108) — hoist msg.payload() to a single
    // local variable.
    // Regression watch: both decodeHeader and dispatch must continue to receive the cloned
    // payload byte[]; do not re-introduce a second payload() call.
    @Test
    @Timeout(10)
    void test_QueryRequestHandler_handle_payloadClonedOncePerDispatch() throws Exception {
        // Locate the QueryRequestHandler source file. The test asserts a source-level
        // property: handle() must call msg.payload() at most once. Calling it twice
        // doubles defensive-clone allocation per dispatch, because Message.payload()
        // clones the backing byte[] on every invocation (Message.java:40-42).
        final Path source = Path
                .of("src/main/java/jlsm/engine/cluster/internal/QueryRequestHandler.java");
        assertTrue(Files.exists(source),
                "QueryRequestHandler source file not found at expected path: "
                        + source.toAbsolutePath());
        final String src = Files.readString(source);

        // Extract the handle(...) method body. The method signature on one line simplifies
        // the extraction — find "public CompletableFuture<Message> handle(" and read until
        // the next method declaration or the class closer.
        final int handleStart = src.indexOf("public CompletableFuture<Message> handle(");
        assertTrue(handleStart >= 0, "handle() method not found in QueryRequestHandler source");
        // The body ends at the first "private " (dispatch is the next private method).
        final int handleEnd = src.indexOf("private byte[] dispatch(", handleStart);
        assertTrue(handleEnd > handleStart, "Could not locate end of handle() method");
        final String handleBody = src.substring(handleStart, handleEnd);

        // Count occurrences of "msg.payload()" in the handle() method body.
        int count = 0;
        int idx = 0;
        while ((idx = handleBody.indexOf("msg.payload()", idx)) >= 0) {
            count++;
            idx += "msg.payload()".length();
        }

        assertTrue(count <= 1,
                "QueryRequestHandler.handle() invokes msg.payload() " + count + " times — "
                        + "Message.payload() clones the payload byte[] on every call, so each "
                        + "extra invocation doubles defensive-clone allocation. Hoist the "
                        + "payload to a single local variable and pass it to both decodeHeader "
                        + "and dispatch.");

        // Sanity: ensure the test also verifies functional behavior — a request with a
        // non-trivial payload still routes correctly through handle() after the fix.
        final Engine engine = new PermissiveStubEngine();
        engine.createTable("users", SCHEMA);
        final QueryRequestHandler handler = new QueryRequestHandler(engine, LOCAL);
        final byte[] payload = QueryRequestPayload.encodeGet("users", 0L, "key1");
        final Message request = new Message(MessageType.QUERY_REQUEST, REMOTE_A, 1L, payload);
        final Message response = handler.handle(REMOTE_A, request).get(5, TimeUnit.SECONDS);
        assertEquals(MessageType.QUERY_RESPONSE, response.type(),
                "handle() must still produce a QUERY_RESPONSE after the fix");
        assertEquals(1L, response.sequenceNumber(),
                "Response sequence number must match request after the fix");
        engine.close();
    }

    // --- Helpers ---

    /**
     * Stub MembershipProtocol for testing (does not capture listener).
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

    /**
     * Stub MembershipProtocol that captures the registered listener for direct invocation.
     */
    private static final class ListenerCapturingMembershipProtocol implements MembershipProtocol {
        volatile MembershipView view = new MembershipView(0, Set.of(), NOW);
        volatile MembershipListener listener;

        @Override
        public void start(List<NodeAddress> seeds) {
        }

        @Override
        public MembershipView currentView() {
            return view;
        }

        @Override
        public void addListener(MembershipListener listener) {
            this.listener = listener;
        }

        @Override
        public void leave() {
        }

        @Override
        public void close() {
        }
    }

    /**
     * Stub engine that allows duplicate createTable calls (does not throw on existing names).
     */
    private static final class PermissiveStubEngine implements Engine {
        private final ConcurrentHashMap<String, TableMetadata> tables = new ConcurrentHashMap<>();

        @Override
        public Table createTable(String name, JlsmSchema schema) {
            final TableMetadata meta = new TableMetadata(name, schema, NOW,
                    TableMetadata.TableState.READY);
            tables.put(name, meta);
            return new PermissiveStubTable(meta);
        }

        @Override
        public Table getTable(String name) throws IOException {
            final TableMetadata meta = tables.get(name);
            if (meta == null) {
                throw new IOException("Table not found: " + name);
            }
            return new PermissiveStubTable(meta);
        }

        @Override
        public void dropTable(String name) {
            tables.remove(name);
        }

        @Override
        public Collection<TableMetadata> listTables() {
            return List.copyOf(tables.values());
        }

        @Override
        public TableMetadata tableMetadata(String name) {
            return tables.get(name);
        }

        @Override
        public EngineMetrics metrics() {
            return new EngineMetrics(tables.size(), 0, Map.of(), Map.of());
        }

        @Override
        public void close() {
        }
    }

    /**
     * Minimal stub table for the permissive engine.
     */
    private static final class PermissiveStubTable implements Table {
        private final TableMetadata metadata;

        PermissiveStubTable(TableMetadata metadata) {
            this.metadata = metadata;
        }

        @Override
        public void create(String key, JlsmDocument doc) {
        }

        @Override
        public Optional<JlsmDocument> get(String key) {
            return Optional.empty();
        }

        @Override
        public void update(String key, JlsmDocument doc, UpdateMode mode) {
        }

        @Override
        public void delete(String key) {
        }

        @Override
        public void insert(JlsmDocument doc) {
        }

        @Override
        public TableQuery<String> query() {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public Iterator<TableEntry<String>> scan(String from, String to) {
            return Collections.emptyIterator();
        }

        @Override
        public TableMetadata metadata() {
            return metadata;
        }

        @Override
        public void close() {
        }
    }

    /**
     * Stub engine that tracks whether close() was called.
     */
    private static final class TrackingStubEngine implements Engine {
        private final ConcurrentHashMap<String, TableMetadata> tables = new ConcurrentHashMap<>();
        volatile boolean closeCalled;

        @Override
        public Table createTable(String name, JlsmSchema schema) {
            final TableMetadata meta = new TableMetadata(name, schema, NOW,
                    TableMetadata.TableState.READY);
            tables.put(name, meta);
            return new PermissiveStubTable(meta);
        }

        @Override
        public Table getTable(String name) throws IOException {
            final TableMetadata meta = tables.get(name);
            if (meta == null) {
                throw new IOException("Table not found: " + name);
            }
            return new PermissiveStubTable(meta);
        }

        @Override
        public void dropTable(String name) {
            tables.remove(name);
        }

        @Override
        public Collection<TableMetadata> listTables() {
            return List.copyOf(tables.values());
        }

        @Override
        public TableMetadata tableMetadata(String name) {
            return tables.get(name);
        }

        @Override
        public EngineMetrics metrics() {
            return new EngineMetrics(tables.size(), 0, Map.of(), Map.of());
        }

        @Override
        public void close() {
            closeCalled = true;
        }
    }

    // Finding: F-R1.cb.2.6
    // Bug: phi() reads HeartbeatHistory fields via ConcurrentHashMap.get() outside compute(),
    // allowing torn reads on a non-thread-safe class while recordHeartbeat() mutates it
    // inside compute(). Concurrent access can produce inconsistent field combinations
    // (e.g., stale lastNanos with updated sum/size, or size incremented before intervals
    // written) leading to incorrect phi values.
    // Correct behavior: phi() must read HeartbeatHistory fields atomically — either by
    // snapshotting inside compute() or by making HeartbeatHistory thread-safe.
    // Fix location: PhiAccrualFailureDetector.phi — replace heartbeatHistory.get() with
    // an atomic snapshot mechanism (e.g., compute() that returns a snapshot record)
    // Regression watch: Ensure phi() still returns 0.0 for unknown nodes and that the
    // atomic read does not introduce contention that blocks recordHeartbeat()
    @Test
    @Timeout(30)
    void test_phi_tornReads_concurrentRecordAndPhi_shouldProduceConsistentResults()
            throws Exception {
        // Use a detector with a small window (2) so the sliding window eviction path
        // is exercised on every record() call, maximizing field mutation surface.
        final PhiAccrualFailureDetector detector = new PhiAccrualFailureDetector(2);

        // Seed with two heartbeats so phi() returns non-zero and size >= 1.
        // After this, every recordHeartbeat mutates head, lastNanos, sum, sumSquares,
        // and intervals — 5 fields updated non-atomically within a single record() call.
        final long baseNanos = 1_000_000_000_000L;
        detector.recordHeartbeat(REMOTE_A, baseNanos);
        detector.recordHeartbeat(REMOTE_A, baseNanos + 100_000_000L); // 100ms interval

        final int writerThreads = 4;
        final int readerThreads = 4;
        final int iterationsPerThread = 200_000;
        final CyclicBarrier barrier = new CyclicBarrier(writerThreads + readerThreads);
        final AtomicBoolean sawInconsistency = new AtomicBoolean(false);
        final AtomicReference<String> inconsistencyDetail = new AtomicReference<>();
        final AtomicInteger phiCallCount = new AtomicInteger(0);

        // Writers publish their latest timestamp so readers can use a nowNanos that
        // is always ahead of the latest recorded heartbeat. This ensures that under
        // an atomic snapshot, elapsed is always non-negative.
        final var maxWriterTs = new java.util.concurrent.atomic.AtomicLong(
                baseNanos + 100_000_000L);

        final ExecutorService pool = Executors.newFixedThreadPool(writerThreads + readerThreads);
        try {
            // Writer threads: continuously record heartbeats via the public API.
            // recordHeartbeat() uses compute(), which holds the bucket lock during
            // mutation. But phi() (before the fix) uses get(), which does NOT hold
            // the lock — this is the contract violation.
            for (int w = 0; w < writerThreads; w++) {
                final int writerId = w;
                pool.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        long ts = baseNanos + 200_000_000L
                                + (long) writerId * iterationsPerThread * 1_000_000L;
                        for (int i = 0; i < iterationsPerThread; i++) {
                            ts += 1_000_000L; // 1ms increments
                            detector.recordHeartbeat(REMOTE_A, ts);
                            maxWriterTs.accumulateAndGet(ts, Math::max);
                        }
                    } catch (Exception e) {
                        // Barrier or interrupt — stop gracefully
                    }
                });
            }

            // Reader threads: call phi() with nowNanos slightly ahead of the latest
            // writer timestamp. Under the buggy code, phi() reads HeartbeatHistory
            // fields via get() without holding the bucket lock. A concurrent
            // recordHeartbeat() can push lastNanos beyond the nowNanos that was
            // computed from maxWriterTs a moment before, causing negative elapsed
            // and an AssertionError. With the fix (compute-based snapshot), the
            // snapshot is atomic and elapsed is always consistent.
            for (int r = 0; r < readerThreads; r++) {
                pool.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        for (int i = 0; i < iterationsPerThread; i++) {
                            // Read max writer timestamp and add a small margin.
                            // Under the buggy code, between reading maxWriterTs and
                            // phi() reading lastNanos via get(), a writer can push
                            // lastNanos beyond nowNanos, causing negative elapsed.
                            // Use the maximum writer timestamp + a large margin (100s).
                            // This margin must be large enough that no writer can advance
                            // lastNanos beyond nowNanos between the read of maxWriterTs
                            // and the phi() call. Writers advance at 1ms/iteration, and
                            // 4 writers * 200k iterations * 1ms = 800s total range, but
                            // each individual advance is 1ms, so even with scheduling
                            // delays the gap between maxWriterTs.get() and the compute()
                            // lock acquisition is at most a few thousand iterations = a
                            // few seconds. 100s margin provides ample headroom.
                            final long nowNanos = maxWriterTs.get() + 100_000_000_000L;
                            try {
                                final double phi = detector.phi(REMOTE_A, nowNanos);
                                phiCallCount.incrementAndGet();

                                // Consistency checks on phi values:
                                if (Double.isNaN(phi)) {
                                    sawInconsistency.set(true);
                                    inconsistencyDetail.compareAndSet(null,
                                            "phi() returned NaN — torn read produced "
                                                    + "inconsistent HeartbeatHistory fields");
                                } else if (phi < 0.0) {
                                    sawInconsistency.set(true);
                                    inconsistencyDetail.compareAndSet(null,
                                            "phi() returned negative: " + phi);
                                } else if (Double.isInfinite(phi)) {
                                    sawInconsistency.set(true);
                                    inconsistencyDetail.compareAndSet(null,
                                            "phi() returned Infinite — torn read produced "
                                                    + "corrupted statistics");
                                }
                            } catch (AssertionError ae) {
                                // With -ea enabled, torn reads trigger assert failures
                                // inside phi() or HeartbeatHistory (e.g., "elapsed time
                                // must be non-negative" when lastNanos is torn-read as a
                                // value beyond nowNanos, or "cannot compute mean with no
                                // intervals" when size is torn-read as 0).
                                sawInconsistency.set(true);
                                inconsistencyDetail.compareAndSet(null,
                                        "AssertionError from torn read: " + ae.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        // Barrier or interrupt — stop gracefully
                    }
                });
            }

            pool.shutdown();
            assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS),
                    "Thread pool did not terminate in time");

            assertTrue(phiCallCount.get() > 0, "phi() must have been called at least once");
            assertFalse(sawInconsistency.get(),
                    "Torn read detected during concurrent phi()/recordHeartbeat(): "
                            + inconsistencyDetail.get()
                            + " — phi() must read HeartbeatHistory fields atomically "
                            + "(inside compute(), not via get())");
        } finally {
            pool.shutdownNow();
        }
    }
}
