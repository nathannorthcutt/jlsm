package jlsm.table.internal;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import jlsm.table.JlsmSchema;
import jlsm.table.Predicate;
import jlsm.table.TableEntry;

/**
 * Plans and executes queries against a table's indices and data.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Receives: a Predicate tree, an IndexRegistry, and access to the main table data</li>
 * <li>Returns: Iterator of matching TableEntry instances</li>
 * <li>For each leaf predicate, checks IndexRegistry for a matching index</li>
 * <li>Index-backed predicates use index lookup; others fall back to scan-and-filter</li>
 * <li>And: intersects results from children</li>
 * <li>Or: unions results from children</li>
 * <li>Error conditions: IOException on I/O errors during execution</li>
 * </ul>
 *
 * @param <K> the primary key type (String or Long)
 */
public final class QueryExecutor<K> {

    private final JlsmSchema schema;
    private final IndexRegistry indexRegistry;
    private final Function<MemorySegment, K> keyDecoder;

    /**
     * Creates a query executor with an explicit key decoder.
     *
     * @param schema the table's schema (for field type resolution)
     * @param indexRegistry the table's index registry
     * @param keyDecoder function to decode primary key bytes into the key type
     */
    public QueryExecutor(JlsmSchema schema, IndexRegistry indexRegistry,
            Function<MemorySegment, K> keyDecoder) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(indexRegistry, "indexRegistry");
        Objects.requireNonNull(keyDecoder, "keyDecoder");
        this.schema = schema;
        this.indexRegistry = indexRegistry;
        this.keyDecoder = keyDecoder;
    }

    /**
     * Creates a query executor that decodes primary keys as UTF-8 strings. Use the three-argument
     * constructor for Long-keyed tables or custom key encodings.
     *
     * @param schema the table's schema (for field type resolution)
     * @param indexRegistry the table's index registry
     * @return a string-keyed query executor
     */
    public static QueryExecutor<String> forStringKeys(JlsmSchema schema,
            IndexRegistry indexRegistry) {
        return new QueryExecutor<>(schema, indexRegistry, QueryExecutor::decodeStringKey);
    }

    /**
     * Executes the given predicate and returns matching entries.
     *
     * @param predicate the predicate to evaluate
     * @return iterator over matching table entries
     * @throws IOException on I/O error
     */
    public Iterator<TableEntry<K>> execute(Predicate predicate) throws IOException {
        Objects.requireNonNull(predicate, "predicate");
        Set<PkKey> matchingKeys = executePredicate(predicate);
        List<TableEntry<K>> results = new ArrayList<>(matchingKeys.size());
        for (PkKey pk : matchingKeys) {
            IndexRegistry.StoredEntry stored = indexRegistry.resolveEntry(toSegment(pk));
            if (stored != null) {
                K key = keyDecoder.apply(stored.primaryKey());
                results.add(new TableEntry<>(key, stored.document()));
            }
        }
        return results.iterator();
    }

    // ── Query planning ──────────────────────────────────────────────────

    private Set<PkKey> executePredicate(Predicate predicate) throws IOException {
        return switch (predicate) {
            case Predicate.And and -> executeAnd(and);
            case Predicate.Or or -> executeOr(or);
            default -> executeLeaf(predicate);
        };
    }

    private Set<PkKey> executeAnd(Predicate.And and) throws IOException {
        Set<PkKey> result = null;
        for (Predicate child : and.children()) {
            Set<PkKey> childResult = executePredicate(child);
            if (result == null) {
                result = new LinkedHashSet<>(childResult);
            } else {
                result.retainAll(childResult);
            }
        }
        return result != null ? result : Set.of();
    }

    private Set<PkKey> executeOr(Predicate.Or or) throws IOException {
        Set<PkKey> result = new LinkedHashSet<>();
        for (Predicate child : or.children()) {
            result.addAll(executePredicate(child));
        }
        return result;
    }

    private Set<PkKey> executeLeaf(Predicate predicate) throws IOException {
        List<MemorySegment> results = indexRegistry.findAndLookup(predicate);
        if (results != null) {
            Set<PkKey> keys = new LinkedHashSet<>();
            for (MemorySegment seg : results) {
                keys.add(toPkKey(seg));
            }
            return keys;
        }
        return scanAndFilter(predicate);
    }

    private Set<PkKey> scanAndFilter(Predicate predicate) {
        Set<PkKey> result = new LinkedHashSet<>();
        Iterator<IndexRegistry.StoredEntry> it = indexRegistry.allEntries();
        while (it.hasNext()) {
            IndexRegistry.StoredEntry entry = it.next();
            if (matchesPredicate(entry, predicate)) {
                result.add(toPkKey(entry.primaryKey()));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private boolean matchesPredicate(IndexRegistry.StoredEntry entry, Predicate predicate) {
        return switch (predicate) {
            case Predicate.Eq eq -> {
                Object fieldValue = extractFieldValue(entry, eq.field());
                yield fieldValue != null && fieldValue.equals(eq.value());
            }
            case Predicate.Ne ne -> {
                Object fieldValue = extractFieldValue(entry, ne.field());
                yield fieldValue != null && !fieldValue.equals(ne.value());
            }
            case Predicate.Gt gt -> {
                Object fieldValue = extractFieldValue(entry, gt.field());
                yield fieldValue instanceof Comparable
                        && compareCoerced(fieldValue, gt.value()) > 0;
            }
            case Predicate.Gte gte -> {
                Object fieldValue = extractFieldValue(entry, gte.field());
                yield fieldValue instanceof Comparable
                        && compareCoerced(fieldValue, gte.value()) >= 0;
            }
            case Predicate.Lt lt -> {
                Object fieldValue = extractFieldValue(entry, lt.field());
                yield fieldValue instanceof Comparable
                        && compareCoerced(fieldValue, lt.value()) < 0;
            }
            case Predicate.Lte lte -> {
                Object fieldValue = extractFieldValue(entry, lte.field());
                yield fieldValue instanceof Comparable
                        && compareCoerced(fieldValue, lte.value()) <= 0;
            }
            case Predicate.Between between -> {
                Object fieldValue = extractFieldValue(entry, between.field());
                yield fieldValue instanceof Comparable
                        && compareCoerced(fieldValue, between.low()) >= 0
                        && compareCoerced(fieldValue, between.high()) <= 0;
            }
            case Predicate.FullTextMatch ftm -> throw new UnsupportedOperationException(
                    "FullTextMatch predicate on field '" + ftm.field()
                            + "' requires a FULL_TEXT index; scan-and-filter cannot evaluate it");
            case Predicate.VectorNearest vn -> throw new UnsupportedOperationException(
                    "VectorNearest predicate on field '" + vn.field()
                            + "' requires a VECTOR index; scan-and-filter cannot evaluate it");
            case Predicate.And and -> {
                for (Predicate child : and.children()) {
                    if (!matchesPredicate(entry, child)) {
                        yield false;
                    }
                }
                yield true;
            }
            case Predicate.Or or -> {
                for (Predicate child : or.children()) {
                    if (matchesPredicate(entry, child)) {
                        yield true;
                    }
                }
                yield false;
            }
        };
    }

    /**
     * Compares two values, coercing numeric types to a common type when necessary. If both values
     * are {@link Number} instances, they are widened to the broadest common numeric type before
     * comparison. For non-numeric types, falls back to same-class {@code compareTo} (returns
     * {@link Integer#MIN_VALUE} if types differ).
     */
    @SuppressWarnings("unchecked")
    private static int compareCoerced(Object fieldValue, Object predicateValue) {
        if (fieldValue.getClass() == predicateValue.getClass()) {
            return ((Comparable<Object>) fieldValue).compareTo(predicateValue);
        }
        if (fieldValue instanceof Number fn && predicateValue instanceof Number pn) {
            // Widen to the broadest type: if either is floating-point, use double;
            // otherwise use long for integer types.
            if (fn instanceof Double || fn instanceof Float || pn instanceof Double
                    || pn instanceof Float) {
                return Double.compare(fn.doubleValue(), pn.doubleValue());
            }
            return Long.compare(fn.longValue(), pn.longValue());
        }
        // Non-numeric type mismatch — cannot compare
        return Integer.MIN_VALUE;
    }

    private Object extractFieldValue(IndexRegistry.StoredEntry entry, String fieldName) {
        var document = entry.document();
        if (document.isNull(fieldName)) {
            return null;
        }
        int idx = schema.fieldIndex(fieldName);
        if (idx < 0) {
            throw new IllegalArgumentException(
                    "Field '" + fieldName + "' does not exist in schema '" + schema.name() + "'");
        }
        var fieldDef = schema.fields().get(idx);
        var fieldType = fieldDef.type();

        if (fieldType instanceof jlsm.table.FieldType.Primitive p) {
            return switch (p) {
                case STRING -> document.getString(fieldName);
                case INT8 -> document.getByte(fieldName);
                case INT16 -> document.getShort(fieldName);
                case INT32 -> document.getInt(fieldName);
                case INT64 -> document.getLong(fieldName);
                case FLOAT16 -> document.getFloat16Bits(fieldName);
                case FLOAT32 -> document.getFloat(fieldName);
                case FLOAT64 -> document.getDouble(fieldName);
                case BOOLEAN -> document.getBoolean(fieldName);
                case TIMESTAMP -> document.getTimestamp(fieldName);
            };
        } else if (fieldType instanceof jlsm.table.FieldType.BoundedString) {
            return document.getString(fieldName);
        }
        throw new UnsupportedOperationException("Scan-and-filter cannot extract field '" + fieldName
                + "' of type " + fieldType.getClass().getSimpleName()
                + "; an appropriate index is required for this field type");
    }

    // ── Key encoding helpers ────────────────────────────────────────────

    private static String decodeStringKey(MemorySegment pk) {
        return new String(pk.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    private record PkKey(byte[] data) {
        @Override
        public boolean equals(Object obj) {
            return obj instanceof PkKey other && Arrays.equals(this.data, other.data);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(data);
        }
    }

    private static PkKey toPkKey(MemorySegment seg) {
        return new PkKey(seg.toArray(ValueLayout.JAVA_BYTE));
    }

    private static MemorySegment toSegment(PkKey pk) {
        var seg = java.lang.foreign.Arena.ofAuto().allocate(pk.data.length);
        MemorySegment.copy(pk.data, 0, seg, ValueLayout.JAVA_BYTE, 0, pk.data.length);
        return seg;
    }
}
