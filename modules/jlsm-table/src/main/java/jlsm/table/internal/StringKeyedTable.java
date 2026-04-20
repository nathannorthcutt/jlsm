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
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * String-keyed {@link JlsmTable} implementation backed by a {@link TypedLsmTree.StringKeyed}.
 *
 * <p>
 * When an {@link IndexRegistry} is supplied, all mutations are routed through the registry so
 * secondary indices (including FULL_TEXT and VECTOR) stay synchronised with the primary tree.
 */
public final class StringKeyedTable implements JlsmTable.StringKeyed {

    private final TypedLsmTree.StringKeyed<JlsmDocument> tree;
    private final MemorySerializer<JlsmDocument> codec;
    private final IndexRegistry indexRegistry; // nullable when no indices are configured

    /**
     * Constructs a new StringKeyedTable.
     *
     * @param tree the backing LSM tree; must not be null
     * @param codec the document serializer for scan value deserialization; must not be null
     * @param schema the optional schema (may be null)
     */
    public StringKeyedTable(TypedLsmTree.StringKeyed<JlsmDocument> tree,
            MemorySerializer<JlsmDocument> codec, JlsmSchema schema) {
        this(tree, codec, schema, null);
    }

    /**
     * Constructs a new StringKeyedTable with an index registry.
     *
     * @param tree the backing LSM tree; must not be null
     * @param codec the document serializer for scan value deserialization; must not be null
     * @param schema the optional schema (may be null)
     * @param indexRegistry the secondary index registry; may be null if no indices are configured
     */
    public StringKeyedTable(TypedLsmTree.StringKeyed<JlsmDocument> tree,
            MemorySerializer<JlsmDocument> codec, JlsmSchema schema, IndexRegistry indexRegistry) {
        assert tree != null : "tree must not be null";
        assert codec != null : "codec must not be null";
        this.tree = tree;
        this.codec = codec;
        this.indexRegistry = indexRegistry;
    }

    @Override
    public void create(String key, JlsmDocument doc) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(doc, "doc must not be null");

        final Optional<JlsmDocument> existing = tree.get(key);
        if (existing.isPresent()) {
            throw new DuplicateKeyException(key);
        }
        if (indexRegistry != null) {
            indexRegistry.onInsert(encodeKey(key), doc);
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
            case REPLACE -> {
                if (indexRegistry != null) {
                    indexRegistry.onUpdate(encodeKey(key), existing.get(), doc);
                }
                tree.put(key, doc);
            }
            case PATCH -> {
                final JlsmDocument merged = mergeDocuments(existing.get(), doc);
                if (indexRegistry != null) {
                    indexRegistry.onUpdate(encodeKey(key), existing.get(), merged);
                }
                tree.put(key, merged);
            }
        }
    }

    @Override
    public void delete(String key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        if (indexRegistry != null) {
            final Optional<JlsmDocument> existing = tree.get(key);
            if (existing.isPresent()) {
                indexRegistry.onDelete(encodeKey(key), existing.get());
            }
        }
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

    /**
     * Returns the index registry, or {@code null} if none is configured. Package-private for
     * {@link jlsm.table.TableQuery} to access during execute() wiring in a later WD.
     */
    public IndexRegistry indexRegistry() {
        return indexRegistry;
    }

    @Override
    public void close() throws IOException {
        IOException deferred = null;
        if (indexRegistry != null) {
            try {
                indexRegistry.close();
            } catch (IOException e) {
                deferred = e;
            }
        }
        try {
            tree.close();
        } catch (IOException e) {
            if (deferred == null) {
                deferred = e;
            } else {
                deferred.addSuppressed(e);
            }
        }
        if (deferred != null) {
            throw deferred;
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Encodes a string key as a UTF-8 {@link MemorySegment} for index-registry routing. Uses a
     * fresh byte[]-backed segment so the bytes escape any per-call arena.
     */
    private static MemorySegment encodeKey(String key) {
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        MemorySegment seg = Arena.ofAuto().allocate(bytes.length);
        seg.copyFrom(MemorySegment.ofArray(bytes));
        return seg;
    }

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
