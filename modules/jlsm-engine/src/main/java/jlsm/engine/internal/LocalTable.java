package jlsm.engine.internal;

import jlsm.engine.HandleEvictedException;
import jlsm.engine.Table;
import jlsm.engine.TableMetadata;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.JlsmTable;
import jlsm.table.TableEntry;
import jlsm.table.TableQuery;
import jlsm.table.UpdateMode;

import java.io.IOException;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

/**
 * Local implementation of {@link Table} wrapping a {@link JlsmTable.StringKeyed} with handle
 * lifecycle tracking.
 *
 * <p>
 * Every method checks handle validity before delegating to the underlying table. If the handle has
 * been evicted, a {@link HandleEvictedException} is thrown.
 *
 * <p>
 * Governed by: {@code .decisions/engine-api-surface-design/adr.md}
 */
// @spec F05.R22,R32,R33,R34,R35,R36,R38,R39,R47,R48,R70,R77,R83 — delegating handle surface
final class LocalTable implements Table {

    private final JlsmTable.StringKeyed delegate;
    private final HandleRegistration registration;
    private final HandleTracker tracker;
    private final TableMetadata metadata;
    private final JlsmSchema schema;

    LocalTable(JlsmTable.StringKeyed delegate, HandleRegistration registration,
            HandleTracker tracker, TableMetadata metadata) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.registration = Objects.requireNonNull(registration, "registration must not be null");
        this.tracker = Objects.requireNonNull(tracker, "tracker must not be null");
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        this.schema = metadata.schema();
        assert this.schema != null : "metadata schema must not be null";
    }

    @Override
    public void create(String key, JlsmDocument doc) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(doc, "doc must not be null");
        synchronized (registration) {
            checkValid();
            delegate.create(key, doc);
        }
    }

    @Override
    public Optional<JlsmDocument> get(String key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        synchronized (registration) {
            checkValid();
            return delegate.get(key);
        }
    }

    @Override
    public void update(String key, JlsmDocument doc, UpdateMode mode) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(doc, "doc must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        synchronized (registration) {
            checkValid();
            delegate.update(key, doc, mode);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        synchronized (registration) {
            checkValid();
            delegate.delete(key);
        }
    }

    @Override
    public void insert(JlsmDocument doc) throws IOException {
        Objects.requireNonNull(doc, "doc must not be null");
        // Primary key is the first field in the schema by convention
        if (schema.fields().isEmpty()) {
            throw new IllegalStateException(
                    "schema must have at least one field to derive the primary key");
        }
        final var primaryField = schema.fields().getFirst();
        final String primaryKeyField = primaryField.name();
        final var pkType = primaryField.type();
        if (pkType != jlsm.table.FieldType.Primitive.STRING
                && !(pkType instanceof jlsm.table.FieldType.BoundedString)) {
            throw new IllegalArgumentException("primary key field '" + primaryKeyField
                    + "' must be a string type, but has type " + pkType);
        }
        final String key = doc.getString(primaryKeyField);
        if (key == null) {
            throw new IllegalArgumentException(
                    "primary key field '" + primaryKeyField + "' must not be null in document");
        }
        synchronized (registration) {
            checkValid();
            delegate.create(key, doc);
        }
    }

    @Override
    // @spec F05.R37 — query pass-through deferred to OBL-F05-R37 (pending jlsm-table binding)
    public TableQuery<String> query() {
        synchronized (registration) {
            checkValid();
            throw new UnsupportedOperationException(
                    "Query binding not yet implemented — use scan() for now");
        }
    }

    @Override
    public Iterator<TableEntry<String>> scan(String fromKey, String toKey) throws IOException {
        Objects.requireNonNull(fromKey, "fromKey must not be null");
        Objects.requireNonNull(toKey, "toKey must not be null");
        synchronized (registration) {
            checkValid();
            return delegate.getAllInRange(fromKey, toKey);
        }
    }

    @Override
    public TableMetadata metadata() {
        synchronized (registration) {
            checkValid();
            return metadata;
        }
    }

    @Override
    public void close() {
        registration.invalidate(HandleEvictedException.Reason.EVICTION);
        tracker.release(registration);
    }

    /**
     * Checks that this handle has not been evicted or invalidated.
     *
     * @throws HandleEvictedException if the handle is invalid
     */
    private void checkValid() {
        if (registration.isInvalidated()) {
            final HandleEvictedException.Reason reason = registration.invalidationReason();
            assert reason != null : "invalidated registration must have a reason";
            final int openHandles = tracker.handleCountForTable(registration.tableName());
            throw new HandleEvictedException(registration.tableName(), registration.sourceId(),
                    openHandles, registration.allocationSite(), reason);
        }
    }
}
