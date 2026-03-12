package jlsm.table.internal;

import jlsm.core.io.MemorySerializer;
import jlsm.core.model.Entry;
import jlsm.core.tree.TypedLsmTree;
import jlsm.table.DuplicateKeyException;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.JlsmTable;
import jlsm.table.KeyNotFoundException;
import jlsm.table.TableEntry;
import jlsm.table.UpdateMode;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * String-keyed {@link JlsmTable} implementation backed by a {@link TypedLsmTree.StringKeyed}.
 */
public final class StringKeyedTable implements JlsmTable.StringKeyed {

    private final TypedLsmTree.StringKeyed<JlsmDocument> tree;
    private final MemorySerializer<JlsmDocument> codec;
    private final JlsmSchema schema;

    /**
     * Constructs a new StringKeyedTable.
     *
     * @param tree the backing LSM tree; must not be null
     * @param codec the document serializer for scan value deserialization; must not be null
     * @param schema the optional schema (may be null)
     */
    public StringKeyedTable(TypedLsmTree.StringKeyed<JlsmDocument> tree,
            MemorySerializer<JlsmDocument> codec, JlsmSchema schema) {
        assert tree != null : "tree must not be null";
        assert codec != null : "codec must not be null";
        this.tree = tree;
        this.codec = codec;
        this.schema = schema;
    }

    @Override
    public void create(String key, JlsmDocument doc) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(doc, "doc must not be null");

        final Optional<JlsmDocument> existing = tree.get(key);
        if (existing.isPresent()) {
            throw new DuplicateKeyException(key);
        }
        tree.put(key, doc);
    }

    @Override
    public Optional<JlsmDocument> get(String key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        return tree.get(key);
    }

    @Override
    public void update(String key, JlsmDocument doc, UpdateMode mode) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(doc, "doc must not be null");
        Objects.requireNonNull(mode, "mode must not be null");

        final Optional<JlsmDocument> existing = tree.get(key);
        if (existing.isEmpty()) {
            throw new KeyNotFoundException(key);
        }

        switch (mode) {
            case REPLACE -> tree.put(key, doc);
            case PATCH -> {
                final JlsmDocument merged = mergeDocuments(existing.get(), doc);
                tree.put(key, merged);
            }
        }
    }

    @Override
    public void delete(String key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        tree.delete(key);
    }

    @Override
    public Iterator<TableEntry<String>> getAllInRange(String from, String to) throws IOException {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");

        final Iterator<Entry> rawEntries = tree.scan(from, to);
        return new Iterator<>() {
            private TableEntry<String> next;

            {
                advance();
            }

            private void advance() {
                next = null;
                while (rawEntries.hasNext()) {
                    final Entry entry = rawEntries.next();
                    if (entry instanceof Entry.Put put) {
                        final String decodedKey = new String(
                                entry.key().toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
                        final JlsmDocument doc = codec.deserialize(put.value());
                        next = new TableEntry<>(decodedKey, doc);
                        return;
                    }
                    // Skip Entry.Delete tombstones
                }
            }

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public TableEntry<String> next() {
                if (next == null) {
                    throw new NoSuchElementException();
                }
                final TableEntry<String> current = next;
                advance();
                return current;
            }
        };
    }

    @Override
    public void close() throws IOException {
        tree.close();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static JlsmDocument mergeDocuments(JlsmDocument existing, JlsmDocument patch) {
        final Object[] existingVals = DocumentAccess.get().values(existing);
        final Object[] newVals = DocumentAccess.get().values(patch);
        assert existingVals.length == newVals.length
                : "existing and patch value arrays must have the same length";
        final Object[] merged = new Object[existingVals.length];
        for (int i = 0; i < merged.length; i++) {
            merged[i] = newVals[i] != null ? newVals[i] : existingVals[i];
        }
        return DocumentAccess.get().create(existing.schema(), merged);
    }
}
