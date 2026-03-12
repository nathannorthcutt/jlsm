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
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Long-keyed {@link JlsmTable} implementation backed by a {@link TypedLsmTree.LongKeyed}.
 */
public final class LongKeyedTable implements JlsmTable.LongKeyed {

    private final TypedLsmTree.LongKeyed<JlsmDocument> tree;
    private final MemorySerializer<JlsmDocument> codec;

    /**
     * Constructs a new LongKeyedTable.
     *
     * @param tree the backing LSM tree; must not be null
     * @param codec the document serializer for scan value deserialization; must not be null
     * @param schema the optional schema (may be null)
     */
    public LongKeyedTable(TypedLsmTree.LongKeyed<JlsmDocument> tree,
            MemorySerializer<JlsmDocument> codec, JlsmSchema schema) {
        assert tree != null : "tree must not be null";
        assert codec != null : "codec must not be null";
        this.tree = tree;
        this.codec = codec;
    }

    @Override
    public void create(long key, JlsmDocument doc) throws IOException {
        Objects.requireNonNull(doc, "doc must not be null");

        final Optional<JlsmDocument> existing = tree.get(key);
        if (existing.isPresent()) {
            throw new DuplicateKeyException(String.valueOf(key));
        }
        tree.put(key, doc);
    }

    @Override
    public Optional<JlsmDocument> get(long key) throws IOException {
        return tree.get(key);
    }

    @Override
    public void update(long key, JlsmDocument doc, UpdateMode mode) throws IOException {
        Objects.requireNonNull(doc, "doc must not be null");
        Objects.requireNonNull(mode, "mode must not be null");

        final Optional<JlsmDocument> existing = tree.get(key);
        if (existing.isEmpty()) {
            throw new KeyNotFoundException(String.valueOf(key));
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
    public void delete(long key) throws IOException {
        tree.delete(key);
    }

    @Override
    public Iterator<TableEntry<Long>> getAllInRange(long from, long to) throws IOException {
        final Iterator<Entry> rawEntries = tree.scan(from, to);
        return new Iterator<>() {
            private TableEntry<Long> next;

            {
                advance();
            }

            private void advance() {
                next = null;
                while (rawEntries.hasNext()) {
                    final Entry entry = rawEntries.next();
                    if (entry instanceof Entry.Put put) {
                        final long decodedKey = decodeKey(entry.key());
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
            public TableEntry<Long> next() {
                if (next == null) {
                    throw new NoSuchElementException();
                }
                final TableEntry<Long> current = next;
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

    /**
     * Decodes a long key from its sign-bit-flipped big-endian encoding.
     *
     * @param seg the encoded key segment; must be 8 bytes
     * @return the decoded long value
     */
    private static long decodeKey(MemorySegment seg) {
        final byte[] bytes = seg.toArray(ValueLayout.JAVA_BYTE);
        assert bytes.length == 8 : "long key segment must be 8 bytes";
        long v = 0L;
        for (final byte b : bytes) {
            v = (v << 8) | (b & 0xFFL);
        }
        return v ^ Long.MIN_VALUE;
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
