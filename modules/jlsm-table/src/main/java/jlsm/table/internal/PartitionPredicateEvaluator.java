package jlsm.table.internal;

import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.FieldType;
import jlsm.table.Predicate;

import java.util.Objects;

/**
 * Scan-and-filter predicate evaluator for in-process partition clients.
 *
 * <p>
 * Evaluates scalar predicates (Eq, Ne, Gt, Gte, Lt, Lte, Between) and logical composites (And, Or)
 * against a {@link JlsmDocument}. Vector and full-text predicates require per-partition indices and
 * throw {@link UnsupportedOperationException} — callers that need those query kinds must wire a
 * partition client that owns the appropriate indices.
 *
 * <p>
 * Null fields are considered non-matching for all scalar predicates (SQL-like NULL semantics),
 * consistent with {@code QueryExecutor.matchesPredicate}.
 */
final class PartitionPredicateEvaluator {

    private PartitionPredicateEvaluator() {
    }

    /**
     * Returns {@code true} if the document matches the predicate.
     *
     * @param document the document being evaluated; must not be null
     * @param predicate the predicate AST; must not be null
     * @return whether the document matches
     * @throws UnsupportedOperationException if the predicate tree contains a FullTextMatch or
     *             VectorNearest leaf (those require per-partition indices not wired through the
     *             in-process client's table reference)
     */
    static boolean matches(JlsmDocument document, Predicate predicate) {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(predicate, "predicate must not be null");
        return switch (predicate) {
            case Predicate.Eq eq -> {
                final Object fv = fieldValue(document, eq.field());
                yield fv != null && fv.equals(eq.value());
            }
            case Predicate.Ne ne -> {
                final Object fv = fieldValue(document, ne.field());
                yield fv != null && !fv.equals(ne.value());
            }
            case Predicate.Gt gt -> {
                final Object fv = fieldValue(document, gt.field());
                yield fv instanceof Comparable && compareCoerced(fv, gt.value()) > 0;
            }
            case Predicate.Gte gte -> {
                final Object fv = fieldValue(document, gte.field());
                yield fv instanceof Comparable && compareCoerced(fv, gte.value()) >= 0;
            }
            case Predicate.Lt lt -> {
                final Object fv = fieldValue(document, lt.field());
                yield fv instanceof Comparable && compareCoerced(fv, lt.value()) < 0;
            }
            case Predicate.Lte lte -> {
                final Object fv = fieldValue(document, lte.field());
                yield fv instanceof Comparable && compareCoerced(fv, lte.value()) <= 0;
            }
            case Predicate.Between between -> {
                final Object fv = fieldValue(document, between.field());
                yield fv instanceof Comparable && compareCoerced(fv, between.low()) >= 0
                        && compareCoerced(fv, between.high()) <= 0;
            }
            case Predicate.And and -> {
                for (final Predicate child : and.children()) {
                    if (!matches(document, child)) {
                        yield false;
                    }
                }
                yield true;
            }
            case Predicate.Or or -> {
                for (final Predicate child : or.children()) {
                    if (matches(document, child)) {
                        yield true;
                    }
                }
                yield false;
            }
            case Predicate.FullTextMatch ftm ->
                throw new UnsupportedOperationException("FullTextMatch predicate on field '"
                        + ftm.field()
                        + "' requires a FULL_TEXT index; partition-local scan cannot evaluate it");
            case Predicate.VectorNearest vn -> throw new UnsupportedOperationException(
                    "VectorNearest predicate on field '" + vn.field()
                            + "' requires a VECTOR index; partition-local scan cannot evaluate it");
        };
    }

    private static Object fieldValue(JlsmDocument document, String fieldName) {
        if (document.isNull(fieldName)) {
            return null;
        }
        final JlsmSchema schema = document.schema();
        final int idx = schema.fieldIndex(fieldName);
        if (idx < 0) {
            throw new IllegalArgumentException(
                    "Field '" + fieldName + "' does not exist in schema '" + schema.name() + "'");
        }
        final FieldType type = schema.fields().get(idx).type();
        if (type instanceof FieldType.Primitive p) {
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
        }
        if (type instanceof FieldType.BoundedString) {
            return document.getString(fieldName);
        }
        throw new UnsupportedOperationException("Partition scan cannot extract field '" + fieldName
                + "' of type " + type.getClass().getSimpleName()
                + "; query requires an appropriate index for this field type");
    }

    @SuppressWarnings("unchecked")
    private static int compareCoerced(Object fieldValue, Object predicateValue) {
        if (fieldValue.getClass() == predicateValue.getClass()) {
            return ((Comparable<Object>) fieldValue).compareTo(predicateValue);
        }
        if (fieldValue instanceof Number fn && predicateValue instanceof Number pn) {
            if (fn instanceof Double || fn instanceof Float || pn instanceof Double
                    || pn instanceof Float) {
                return Double.compare(fn.doubleValue(), pn.doubleValue());
            }
            return Long.compare(fn.longValue(), pn.longValue());
        }
        return Integer.MIN_VALUE;
    }
}
