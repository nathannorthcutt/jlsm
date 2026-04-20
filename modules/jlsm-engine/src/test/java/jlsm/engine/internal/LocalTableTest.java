package jlsm.engine.internal;

import jlsm.engine.HandleEvictedException;
import jlsm.engine.AllocationTracking;
import jlsm.engine.TableMetadata;
import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.JlsmTable;
import jlsm.table.TableEntry;
import jlsm.table.UpdateMode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LocalTable} — verifies delegation, handle validity checks, and null validation.
 *
 * <p>
 * Uses a stub {@link JlsmTable.StringKeyed} to isolate LocalTable logic from the real LSM stack.
 */
class LocalTableTest {

    private JlsmSchema schema;
    private TableMetadata metadata;
    private HandleTracker tracker;
    private StubStringKeyedTable stubDelegate;

    @BeforeEach
    void setUp() {
        schema = JlsmSchema.builder("test", 1).field("id", FieldType.Primitive.STRING)
                .field("value", FieldType.Primitive.STRING).build();
        metadata = new TableMetadata("test_table", schema, Instant.now(),
                TableMetadata.TableState.READY);
        tracker = HandleTracker.builder().allocationTracking(AllocationTracking.OFF).build();
        stubDelegate = new StubStringKeyedTable();
    }

    private LocalTable createTable() {
        final HandleRegistration reg = tracker.register("test_table", "test-source");
        return new LocalTable(stubDelegate, reg, tracker, metadata);
    }

    // ---- Constructor null checks ----

    @Test
    void constructorRejectsNullDelegate() {
        final HandleRegistration reg = tracker.register("t", "s");
        assertThrows(NullPointerException.class,
                () -> new LocalTable(null, reg, tracker, metadata));
    }

    @Test
    void constructorRejectsNullRegistration() {
        assertThrows(NullPointerException.class,
                () -> new LocalTable(stubDelegate, null, tracker, metadata));
    }

    @Test
    void constructorRejectsNullTracker() {
        final HandleRegistration reg = tracker.register("t", "s");
        assertThrows(NullPointerException.class,
                () -> new LocalTable(stubDelegate, reg, null, metadata));
    }

    @Test
    void constructorRejectsNullMetadata() {
        final HandleRegistration reg = tracker.register("t", "s");
        assertThrows(NullPointerException.class,
                () -> new LocalTable(stubDelegate, reg, tracker, null));
    }

    // ---- create delegates ----

    // @spec F05.R32 — create delegates to the underlying table implementation
    @Test
    void createDelegatesToStub() throws IOException {
        try (final LocalTable table = createTable()) {
            final JlsmDocument doc = JlsmDocument.of(schema, "id", "k1", "value", "v1");
            table.create("k1", doc);
            assertTrue(stubDelegate.createCalled, "create should have been called on delegate");
        }
    }

    @Test
    void createRejectsNullKey() {
        try (final LocalTable table = createTable()) {
            final JlsmDocument doc = JlsmDocument.of(schema, "id", "k1", "value", "v1");
            assertThrows(NullPointerException.class, () -> table.create(null, doc));
        }
    }

    @Test
    void createRejectsNullDoc() {
        try (final LocalTable table = createTable()) {
            assertThrows(NullPointerException.class, () -> table.create("k1", null));
        }
    }

    // ---- get delegates ----

    // @spec F05.R33 — get delegates to the underlying table implementation
    @Test
    void getDelegatesToStub() throws IOException {
        try (final LocalTable table = createTable()) {
            final Optional<JlsmDocument> result = table.get("k1");
            assertTrue(stubDelegate.getCalled, "get should have been called on delegate");
            assertTrue(result.isEmpty());
        }
    }

    @Test
    void getRejectsNullKey() {
        try (final LocalTable table = createTable()) {
            assertThrows(NullPointerException.class, () -> table.get(null));
        }
    }

    // ---- update delegates ----

    // @spec F05.R34 — update delegates to the underlying table implementation
    @Test
    void updateDelegatesToStub() throws IOException {
        try (final LocalTable table = createTable()) {
            final JlsmDocument doc = JlsmDocument.of(schema, "id", "k1", "value", "v1");
            table.update("k1", doc, UpdateMode.REPLACE);
            assertTrue(stubDelegate.updateCalled, "update should have been called on delegate");
        }
    }

    @Test
    void updateRejectsNullKey() {
        try (final LocalTable table = createTable()) {
            final JlsmDocument doc = JlsmDocument.of(schema, "id", "k1", "value", "v1");
            assertThrows(NullPointerException.class,
                    () -> table.update(null, doc, UpdateMode.REPLACE));
        }
    }

    @Test
    void updateRejectsNullDoc() {
        try (final LocalTable table = createTable()) {
            assertThrows(NullPointerException.class,
                    () -> table.update("k1", null, UpdateMode.REPLACE));
        }
    }

    @Test
    void updateRejectsNullMode() {
        try (final LocalTable table = createTable()) {
            final JlsmDocument doc = JlsmDocument.of(schema, "id", "k1", "value", "v1");
            assertThrows(NullPointerException.class, () -> table.update("k1", doc, null));
        }
    }

    // ---- delete delegates ----

    // @spec F05.R35 — delete delegates to the underlying table implementation
    @Test
    void deleteDelegatesToStub() throws IOException {
        try (final LocalTable table = createTable()) {
            table.delete("k1");
            assertTrue(stubDelegate.deleteCalled, "delete should have been called on delegate");
        }
    }

    @Test
    void deleteRejectsNullKey() {
        try (final LocalTable table = createTable()) {
            assertThrows(NullPointerException.class, () -> table.delete(null));
        }
    }

    // ---- insert extracts primary key and delegates ----

    @Test
    void insertExtractsPrimaryKeyAndDelegates() throws IOException {
        try (final LocalTable table = createTable()) {
            final JlsmDocument doc = JlsmDocument.of(schema, "id", "pk1", "value", "v1");
            table.insert(doc);
            assertTrue(stubDelegate.createCalled, "create should have been called via insert");
            assertEquals("pk1", stubDelegate.lastCreateKey,
                    "insert should extract first field as primary key");
        }
    }

    @Test
    void insertRejectsNullDoc() {
        try (final LocalTable table = createTable()) {
            assertThrows(NullPointerException.class, () -> table.insert(null));
        }
    }

    // ---- query delegates to underlying table ----

    // @spec F05.R37 — query() now delegates to the underlying JlsmTable.StringKeyed
    // (OBL-F05-R37 resolved by WD-03). The stub delegate does not override query() so it
    // returns the interface default — an unbound TableQuery whose execute() throws UOE.
    @Test
    void queryReturnsUnboundTableQueryFromStub() {
        try (final LocalTable table = createTable()) {
            jlsm.table.TableQuery<String> q = table.query();
            assertNotNull(q, "query() must return a non-null TableQuery");
            q.where("name").eq("Alice");
            assertThrows(UnsupportedOperationException.class, q::execute,
                    "unbound TableQuery.execute() throws UOE (stub delegate provides no binding)");
        }
    }

    // ---- scan delegates ----

    // @spec F05.R38,R39 — scan delegates to the underlying table and checks validity
    @Test
    void scanDelegatesToStub() throws IOException {
        try (final LocalTable table = createTable()) {
            final Iterator<TableEntry<String>> iter = table.scan("a", "z");
            assertTrue(stubDelegate.scanCalled, "scan should have been called on delegate");
            assertNotNull(iter);
        }
    }

    @Test
    void scanRejectsNullFromKey() {
        try (final LocalTable table = createTable()) {
            assertThrows(NullPointerException.class, () -> table.scan(null, "z"));
        }
    }

    @Test
    void scanRejectsNullToKey() {
        try (final LocalTable table = createTable()) {
            assertThrows(NullPointerException.class, () -> table.scan("a", null));
        }
    }

    // ---- metadata returns correct value ----

    @Test
    void metadataReturnsCorrectValue() {
        try (final LocalTable table = createTable()) {
            assertSame(metadata, table.metadata());
        }
    }

    // ---- close releases registration ----

    // @spec F05.R77 — closing a table handle releases its registration
    @Test
    void closeReleasesRegistration() {
        final HandleRegistration reg = tracker.register("test_table", "test-source");
        final LocalTable table = new LocalTable(stubDelegate, reg, tracker, metadata);
        // Before close, tracker should have the handle
        assertEquals(1, tracker.snapshot().totalOpenHandles());
        table.close();
        // After close, tracker should have released
        assertEquals(0, tracker.snapshot().totalOpenHandles());
    }

    // ---- evicted handle throws HandleEvictedException on every method ----

    @Test
    void evictedHandleThrowsOnCreate() {
        final HandleRegistration reg = tracker.register("test_table", "test-source");
        reg.invalidate();
        final LocalTable table = new LocalTable(stubDelegate, reg, tracker, metadata);
        final JlsmDocument doc = JlsmDocument.of(schema, "id", "k1", "value", "v1");
        assertThrows(HandleEvictedException.class, () -> table.create("k1", doc));
    }

    // @spec F05.R36,R47,R48 — evicted handle rejects operations with HandleEvictedException
    @Test
    void evictedHandleThrowsOnGet() {
        final HandleRegistration reg = tracker.register("test_table", "test-source");
        reg.invalidate();
        final LocalTable table = new LocalTable(stubDelegate, reg, tracker, metadata);
        assertThrows(HandleEvictedException.class, () -> table.get("k1"));
    }

    @Test
    void evictedHandleThrowsOnUpdate() {
        final HandleRegistration reg = tracker.register("test_table", "test-source");
        reg.invalidate();
        final LocalTable table = new LocalTable(stubDelegate, reg, tracker, metadata);
        final JlsmDocument doc = JlsmDocument.of(schema, "id", "k1", "value", "v1");
        assertThrows(HandleEvictedException.class,
                () -> table.update("k1", doc, UpdateMode.REPLACE));
    }

    @Test
    void evictedHandleThrowsOnDelete() {
        final HandleRegistration reg = tracker.register("test_table", "test-source");
        reg.invalidate();
        final LocalTable table = new LocalTable(stubDelegate, reg, tracker, metadata);
        assertThrows(HandleEvictedException.class, () -> table.delete("k1"));
    }

    @Test
    void evictedHandleThrowsOnInsert() {
        final HandleRegistration reg = tracker.register("test_table", "test-source");
        reg.invalidate();
        final LocalTable table = new LocalTable(stubDelegate, reg, tracker, metadata);
        final JlsmDocument doc = JlsmDocument.of(schema, "id", "k1", "value", "v1");
        assertThrows(HandleEvictedException.class, () -> table.insert(doc));
    }

    @Test
    void evictedHandleThrowsOnQuery() {
        final HandleRegistration reg = tracker.register("test_table", "test-source");
        reg.invalidate();
        final LocalTable table = new LocalTable(stubDelegate, reg, tracker, metadata);
        assertThrows(HandleEvictedException.class, table::query);
    }

    @Test
    void evictedHandleThrowsOnScan() {
        final HandleRegistration reg = tracker.register("test_table", "test-source");
        reg.invalidate();
        final LocalTable table = new LocalTable(stubDelegate, reg, tracker, metadata);
        assertThrows(HandleEvictedException.class, () -> table.scan("a", "z"));
    }

    // Updated by audit F-R1.cb.2.8: metadata() without validity check was a bug, now correctly
    // throws HandleEvictedException
    @Test
    void metadataThrowsOnEvictedHandle() {
        final HandleRegistration reg = tracker.register("test_table", "test-source");
        reg.invalidate();
        final LocalTable table = new LocalTable(stubDelegate, reg, tracker, metadata);
        assertThrows(HandleEvictedException.class, table::metadata);
    }

    // ---- Stub JlsmTable.StringKeyed ----

    /**
     * Minimal stub that records which methods were called. No real LSM stack.
     */
    private static final class StubStringKeyedTable implements JlsmTable.StringKeyed {

        volatile boolean createCalled;
        volatile boolean getCalled;
        volatile boolean updateCalled;
        volatile boolean deleteCalled;
        volatile boolean scanCalled;
        volatile String lastCreateKey;

        @Override
        public void create(String key, JlsmDocument doc) throws IOException {
            createCalled = true;
            lastCreateKey = key;
        }

        @Override
        public Optional<JlsmDocument> get(String key) throws IOException {
            getCalled = true;
            return Optional.empty();
        }

        @Override
        public void update(String key, JlsmDocument doc, UpdateMode mode) throws IOException {
            updateCalled = true;
        }

        @Override
        public void delete(String key) throws IOException {
            deleteCalled = true;
        }

        @Override
        public Iterator<TableEntry<String>> getAllInRange(String from, String to)
                throws IOException {
            scanCalled = true;
            return java.util.Collections.emptyIterator();
        }

        @Override
        public void close() throws IOException {
            // no-op
        }
    }
}
