package jlsm.table.internal;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

import jlsm.table.DuplicateKeyException;
import jlsm.table.FieldType;
import jlsm.table.IndexDefinition;
import jlsm.table.IndexType;
import jlsm.table.Predicate;

/**
 * Secondary index for equality, range, and unique lookups on a single field. Uses an in-memory
 * sorted map where keys are sort-preserving encoded field values (via {@link FieldValueCodec}) and
 * values are lists of primary keys.
 */
public final class FieldIndex implements SecondaryIndex {

    private final IndexDefinition definition;
    private final FieldType schemaFieldType;
    private final NavigableMap<ByteArrayKey, List<MemorySegment>> entries;
    private volatile boolean closed;

    /**
     * Creates a FieldIndex with explicit schema field type for correct encoding. Prefer this
     * constructor when the schema field type is known (e.g., from IndexRegistry).
     */
    public FieldIndex(IndexDefinition definition, FieldType schemaFieldType) throws IOException {
        Objects.requireNonNull(definition, "definition");
        IndexType type = definition.indexType();
        assert type == IndexType.EQUALITY || type == IndexType.RANGE || type == IndexType.UNIQUE
                : "FieldIndex only supports EQUALITY, RANGE, or UNIQUE";
        this.definition = definition;
        this.schemaFieldType = schemaFieldType;
        this.entries = new TreeMap<>();
    }

    /**
     * Creates a FieldIndex without explicit schema field type. Falls back to runtime type inference
     * which is ambiguous for Short (INT16 vs FLOAT16).
     */
    public FieldIndex(IndexDefinition definition) throws IOException {
        this(definition, null);
    }

    @Override
    public IndexDefinition definition() {
        return definition;
    }

    /**
     * Checks the unique constraint for the given field value without modifying the index. Only
     * meaningful for UNIQUE indices — no-op for others.
     *
     * @throws DuplicateKeyException if the value already exists in a UNIQUE index
     */
    void checkUnique(Object fieldValue) throws IOException {
        if (definition.indexType() != IndexType.UNIQUE || fieldValue == null) {
            return;
        }
        FieldType fieldType = resolveFieldType(fieldValue);
        ByteArrayKey encoded = encodeKey(fieldValue, fieldType);
        List<MemorySegment> existing = entries.get(encoded);
        if (existing != null && !existing.isEmpty()) {
            throw new DuplicateKeyException(String.valueOf(fieldValue));
        }
    }

    @Override
    public void onInsert(MemorySegment primaryKey, Object fieldValue) throws IOException {
        assert !closed : "Index is closed";
        if (fieldValue == null) {
            return;
        }
        FieldType fieldType = resolveFieldType(fieldValue);
        ByteArrayKey encoded = encodeKey(fieldValue, fieldType);

        if (definition.indexType() == IndexType.UNIQUE) {
            List<MemorySegment> existing = entries.get(encoded);
            if (existing != null && !existing.isEmpty()) {
                throw new DuplicateKeyException(String.valueOf(fieldValue));
            }
        }

        entries.computeIfAbsent(encoded, _ -> new ArrayList<>()).add(copySegment(primaryKey));
    }

    @Override
    public void onUpdate(MemorySegment primaryKey, Object oldFieldValue, Object newFieldValue)
            throws IOException {
        assert !closed : "Index is closed";
        if (oldFieldValue != null) {
            removeEntry(primaryKey, oldFieldValue);
        }
        if (newFieldValue != null) {
            onInsert(primaryKey, newFieldValue);
        }
    }

    @Override
    public void onDelete(MemorySegment primaryKey, Object fieldValue) throws IOException {
        assert !closed : "Index is closed";
        if (fieldValue == null) {
            return;
        }
        removeEntry(primaryKey, fieldValue);
    }

    @Override
    public Iterator<MemorySegment> lookup(Predicate predicate) throws IOException {
        assert !closed : "Index is closed";
        return switch (predicate) {
            case Predicate.Eq eq -> lookupEq(eq.value());
            case Predicate.Ne ne -> lookupNe(ne.value());
            case Predicate.Gt gt -> lookupGt(gt.value());
            case Predicate.Gte gte -> lookupGte(gte.value());
            case Predicate.Lt lt -> lookupLt(lt.value());
            case Predicate.Lte lte -> lookupLte(lte.value());
            case Predicate.Between between -> lookupBetween(between.low(), between.high());
            default -> Collections.emptyIterator();
        };
    }

    @Override
    public boolean supports(Predicate predicate) {
        String field = predicateField(predicate);
        if (field == null || !field.equals(definition.fieldName())) {
            return false;
        }
        return switch (definition.indexType()) {
            case EQUALITY -> predicate instanceof Predicate.Eq || predicate instanceof Predicate.Ne;
            case RANGE, UNIQUE -> predicate instanceof Predicate.Eq
                    || predicate instanceof Predicate.Ne || predicate instanceof Predicate.Gt
                    || predicate instanceof Predicate.Gte || predicate instanceof Predicate.Lt
                    || predicate instanceof Predicate.Lte || predicate instanceof Predicate.Between;
            default -> false;
        };
    }

    @Override
    public void close() throws IOException {
        closed = true;
        entries.clear();
    }

    // ── Private helpers ─────────────────────────────────────────────────

    private void removeEntry(MemorySegment primaryKey, Object fieldValue) {
        FieldType fieldType = resolveFieldType(fieldValue);
        ByteArrayKey encoded = encodeKey(fieldValue, fieldType);
        List<MemorySegment> pks = entries.get(encoded);
        if (pks != null) {
            pks.removeIf(pk -> segmentsEqual(pk, primaryKey));
            if (pks.isEmpty()) {
                entries.remove(encoded);
            }
        }
    }

    private Iterator<MemorySegment> lookupEq(Object value) {
        FieldType fieldType = resolveFieldType(value);
        ByteArrayKey encoded = encodeKey(value, fieldType);
        List<MemorySegment> pks = entries.get(encoded);
        if (pks == null || pks.isEmpty()) {
            return Collections.emptyIterator();
        }
        return List.copyOf(pks).iterator();
    }

    private Iterator<MemorySegment> lookupNe(Object value) {
        FieldType fieldType = resolveFieldType(value);
        ByteArrayKey excluded = encodeKey(value, fieldType);
        List<MemorySegment> result = new ArrayList<>();
        for (var entry : entries.entrySet()) {
            if (!entry.getKey().equals(excluded)) {
                result.addAll(entry.getValue());
            }
        }
        return result.iterator();
    }

    private Iterator<MemorySegment> lookupGt(Object value) {
        FieldType fieldType = resolveFieldType(value);
        ByteArrayKey encoded = encodeKey(value, fieldType);
        return flattenValues(entries.tailMap(encoded, false));
    }

    private Iterator<MemorySegment> lookupGte(Object value) {
        FieldType fieldType = resolveFieldType(value);
        ByteArrayKey encoded = encodeKey(value, fieldType);
        return flattenValues(entries.tailMap(encoded, true));
    }

    private Iterator<MemorySegment> lookupLt(Object value) {
        FieldType fieldType = resolveFieldType(value);
        ByteArrayKey encoded = encodeKey(value, fieldType);
        return flattenValues(entries.headMap(encoded, false));
    }

    private Iterator<MemorySegment> lookupLte(Object value) {
        FieldType fieldType = resolveFieldType(value);
        ByteArrayKey encoded = encodeKey(value, fieldType);
        return flattenValues(entries.headMap(encoded, true));
    }

    private Iterator<MemorySegment> lookupBetween(Object low, Object high) {
        FieldType lowType = resolveFieldType(low);
        FieldType highType = resolveFieldType(high);
        if (!lowType.equals(highType)) {
            throw new IllegalArgumentException("Between low and high must have the same type, got: "
                    + lowType + " and " + highType);
        }
        ByteArrayKey lowKey = encodeKey(low, lowType);
        ByteArrayKey highKey = encodeKey(high, lowType);
        // Guard against inverted range — TreeMap.subMap throws IAE when fromKey > toKey
        if (lowKey.compareTo(highKey) > 0) {
            return Collections.emptyIterator();
        }
        return flattenValues(entries.subMap(lowKey, true, highKey, true));
    }

    private static Iterator<MemorySegment> flattenValues(
            NavigableMap<ByteArrayKey, List<MemorySegment>> map) {
        List<MemorySegment> result = new ArrayList<>();
        for (List<MemorySegment> pks : map.values()) {
            result.addAll(pks);
        }
        return result.iterator();
    }

    private static ByteArrayKey encodeKey(Object value, FieldType fieldType) {
        MemorySegment encoded = FieldValueCodec.encode(value, fieldType);
        return new ByteArrayKey(encoded.toArray(ValueLayout.JAVA_BYTE));
    }

    private static MemorySegment copySegment(MemorySegment src) {
        MemorySegment copy = Arena.ofAuto().allocate(src.byteSize());
        copy.copyFrom(src);
        return copy;
    }

    private static boolean segmentsEqual(MemorySegment a, MemorySegment b) {
        return a.byteSize() == b.byteSize() && a.mismatch(b) == -1;
    }

    private static String predicateField(Predicate predicate) {
        return switch (predicate) {
            case Predicate.Eq eq -> eq.field();
            case Predicate.Ne ne -> ne.field();
            case Predicate.Gt gt -> gt.field();
            case Predicate.Gte gte -> gte.field();
            case Predicate.Lt lt -> lt.field();
            case Predicate.Lte lte -> lte.field();
            case Predicate.Between between -> between.field();
            case Predicate.FullTextMatch ftm -> ftm.field();
            case Predicate.VectorNearest vn -> vn.field();
            case Predicate.And _, Predicate.Or _ -> null;
        };
    }

    /**
     * Resolves the field type for encoding. Prefers the schema field type when available, falling
     * back to runtime type inference. This is critical for Short values which are ambiguous between
     * INT16 and FLOAT16.
     */
    private FieldType resolveFieldType(Object value) {
        if (schemaFieldType != null) {
            return schemaFieldType;
        }
        return inferFieldType(value);
    }

    static FieldType inferFieldType(Object value) {
        return switch (value) {
            case String _ -> FieldType.Primitive.STRING;
            case Integer _ -> FieldType.Primitive.INT32;
            case Long _ -> FieldType.Primitive.INT64;
            case Short _ -> FieldType.Primitive.INT16;
            case Byte _ -> FieldType.Primitive.INT8;
            case Float _ -> FieldType.Primitive.FLOAT32;
            case Double _ -> FieldType.Primitive.FLOAT64;
            case Boolean _ -> FieldType.Primitive.BOOLEAN;
            default -> throw new IllegalArgumentException(
                    "Cannot infer field type for: " + value.getClass().getSimpleName());
        };
    }

    /**
     * Byte array wrapper with unsigned bytewise comparison for use as TreeMap key.
     */
    record ByteArrayKey(byte[] data) implements Comparable<ByteArrayKey> {

        @Override
        public int compareTo(ByteArrayKey other) {
            int len = Math.min(this.data.length, other.data.length);
            for (int i = 0; i < len; i++) {
                int cmp = Byte.toUnsignedInt(this.data[i]) - Byte.toUnsignedInt(other.data[i]);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return Integer.compare(this.data.length, other.data.length);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ByteArrayKey other)) {
                return false;
            }
            return java.util.Arrays.equals(this.data, other.data);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(data);
        }
    }
}
