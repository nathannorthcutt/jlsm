package jlsm.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;
import jlsm.table.Predicate;

/**
 * Translates a {@link SqlAst.SelectStatement} into a {@link SqlQuery}.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Receives: a parsed AST and the target table's {@link JlsmSchema}</li>
 * <li>Returns: a {@link SqlQuery} with the predicate tree, projections, ordering, and limits</li>
 * <li>Side effects: none</li>
 * <li>Error conditions: throws {@link SqlParseException} if:
 * <ul>
 * <li>A column name in SELECT/WHERE/ORDER BY does not exist in the schema</li>
 * <li>A literal type is incompatible with the field type</li>
 * <li>MATCH is used on a non-STRING field</li>
 * <li>VECTOR_DISTANCE is used on a non-vector field</li>
 * <li>An unsupported expression structure is encountered</li>
 * </ul>
 * </li>
 * </ul>
 *
 * <p>
 * Translation rules:
 * <ul>
 * <li>Comparison expressions → leaf Predicates (Eq, Ne, Gt, Gte, Lt, Lte)</li>
 * <li>BETWEEN → Predicate.Between</li>
 * <li>AND/OR → Predicate.And / Predicate.Or</li>
 * <li>MATCH(field, query) → Predicate.FullTextMatch</li>
 * <li>VECTOR_DISTANCE(field, vec, metric) in ORDER BY → SqlQuery.VectorDistanceOrder</li>
 * <li>Column aliases are preserved in SqlQuery for result projection</li>
 * </ul>
 *
 * <p>
 * Governed by: domains.md — Vector Similarity SQL Syntax section,
 * .kb/algorithms/sql-extensions/vector-similarity-sql-syntax.md
 */
// @spec query.sql-query-support.R98 — SqlTranslator holds no mutable state; each translate() call is independent
public final class SqlTranslator {

    /** Known distance metrics accepted by VECTOR_DISTANCE. */
    private static final Set<String> KNOWN_METRICS = Set.of("cosine", "euclidean", "dot");

    /**
     * Translates a parsed SQL AST into a SqlQuery.
     *
     * @param statement the parsed SELECT statement, must not be null
     * @param schema the target table schema for validation, must not be null
     * @return the translated query
     * @throws SqlParseException if the AST references invalid fields or has type mismatches
     */
    // @spec query.sql-query-support.R54,R55,R56,R59,R69,R95 — non-null args; ORDER BY field validation; VECTOR_DISTANCE
    // routing; reject other forms
    public SqlQuery translate(SqlAst.SelectStatement statement, JlsmSchema schema)
            throws SqlParseException {
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(schema, "schema");

        final List<String> projections = translateColumns(statement.columns(), schema);
        final List<String> aliases = translateAliases(statement.columns());

        final Optional<Predicate> predicate = statement.where().isPresent()
                ? Optional.of(translateExpression(statement.where().get(), schema))
                : Optional.empty();

        final var orderBy = new ArrayList<SqlQuery.OrderBy>();
        Optional<SqlQuery.VectorDistanceOrder> vectorDistance = Optional.empty();

        for (final SqlAst.OrderByClause clause : statement.orderBy()) {
            if (clause.expression() instanceof SqlAst.Expression.FunctionCall fn
                    && fn.name().equals("VECTOR_DISTANCE")) {
                vectorDistance = Optional
                        .of(translateVectorDistance(fn, schema, clause.ascending()));
            } else if (clause.expression() instanceof SqlAst.Expression.ColumnRef ref) {
                validateField(ref.name(), schema);
                orderBy.add(new SqlQuery.OrderBy(ref.name(), clause.ascending()));
            } else {
                throw new SqlParseException("Unsupported ORDER BY expression", -1);
            }
        }

        final OptionalInt limit = statement.limit().isPresent()
                ? OptionalInt.of(statement.limit().get())
                : OptionalInt.empty();

        final OptionalInt offset = statement.offset().isPresent()
                ? OptionalInt.of(statement.offset().get())
                : OptionalInt.empty();

        return new SqlQuery(predicate, projections, aliases, orderBy, limit, offset,
                vectorDistance);
    }

    // ── Column translation ───────────────────────────────────────

    // @spec query.sql-query-support.R57,R75 — validate each SELECT column against schema; SELECT * yields empty
    // projections list
    private List<String> translateColumns(List<SqlAst.Column> columns, JlsmSchema schema)
            throws SqlParseException {
        if (columns.size() == 1 && columns.getFirst() instanceof SqlAst.Column.Wildcard) {
            return List.of();
        }

        final var names = new ArrayList<String>();
        for (final SqlAst.Column col : columns) {
            if (col instanceof SqlAst.Column.Named named) {
                validateField(named.name(), schema);
                names.add(named.name());
            }
        }
        return names;
    }

    // @spec query.sql-query-support.R76 — aliases list parallel to projections; empty string when no AS clause
    private List<String> translateAliases(List<SqlAst.Column> columns) {
        if (columns.size() == 1 && columns.getFirst() instanceof SqlAst.Column.Wildcard) {
            return List.of();
        }

        final var aliases = new ArrayList<String>();
        for (final SqlAst.Column col : columns) {
            if (col instanceof SqlAst.Column.Named named) {
                aliases.add(named.alias().orElse(""));
            }
        }
        return aliases;
    }

    // ── Expression translation ───────────────────────────────────

    // @spec query.sql-query-support.R58,R92,R93 — WHERE expressions dispatched; NOT / IS NULL explicitly rejected as
    // unsupported
    private Predicate translateExpression(SqlAst.Expression expr, JlsmSchema schema)
            throws SqlParseException {
        return switch (expr) {
            case SqlAst.Expression.Comparison cmp -> translateComparison(cmp, schema);
            case SqlAst.Expression.Logical log -> translateLogical(log, schema);
            case SqlAst.Expression.Between bet -> translateBetween(bet, schema);
            case SqlAst.Expression.FunctionCall fn -> translateFunctionCall(fn, schema);
            case SqlAst.Expression.Not not ->
                throw new SqlParseException("NOT predicates are not yet supported", -1);
            case SqlAst.Expression.IsNull isNull ->
                throw new SqlParseException("IS NULL predicates are not yet supported", -1);
            case SqlAst.Expression.ColumnRef ref -> throw new SqlParseException(
                    "ColumnRef cannot be used as a standalone predicate: " + ref.name(), -1);
            case SqlAst.Expression.StringLiteral lit -> throw new SqlParseException(
                    "StringLiteral cannot be used as a standalone predicate: '" + lit.value() + "'",
                    -1);
            case SqlAst.Expression.NumberLiteral lit -> throw new SqlParseException(
                    "NumberLiteral cannot be used as a standalone predicate: " + lit.text(), -1);
            case SqlAst.Expression.BooleanLiteral lit -> throw new SqlParseException(
                    "BooleanLiteral cannot be used as a standalone predicate: " + lit.value(), -1);
            case SqlAst.Expression.Parameter param -> throw new SqlParseException(
                    "Parameter cannot be used as a standalone predicate: ?" + param.index(), -1);
        };
    }

    // @spec query.sql-query-support.R58,R60,R61,R62,R74 — column/type validation; reversed (value,column) swap+flip;
    // predicate mapping
    private Predicate translateComparison(SqlAst.Expression.Comparison cmp, JlsmSchema schema)
            throws SqlParseException {
        // Support both "field op value" and "value op field" (reversed) comparisons
        final String field;
        final Object value;
        final SqlAst.ComparisonOp op;

        if (isValueExpression(cmp.left()) && isValueExpression(cmp.right())) {
            throw new SqlParseException(
                    "Comparison requires at least one column reference, but both operands are"
                            + " literal values",
                    -1);
        } else if (isFieldExpression(cmp.left()) && isFieldExpression(cmp.right())) {
            throw new SqlParseException(
                    "Comparison between two column references is not supported — one operand"
                            + " must be a literal value or bind parameter",
                    -1);
        } else if (isValueExpression(cmp.left()) && isFieldExpression(cmp.right())) {
            // Reversed: literal on left, column on right — swap and flip operator
            field = extractFieldName(cmp.right());
            value = extractValue(cmp.left());
            op = flipOp(cmp.op());
        } else {
            field = extractFieldName(cmp.left());
            value = extractValue(cmp.right());
            op = cmp.op();
        }

        validateField(field, schema);
        validateValueType(field, value, schema);

        return switch (op) {
            case EQ -> new Predicate.Eq(field, value);
            case NE -> new Predicate.Ne(field, value);
            case GT -> new Predicate.Gt(field, toComparable(value));
            case GTE -> new Predicate.Gte(field, toComparable(value));
            case LT -> new Predicate.Lt(field, toComparable(value));
            case LTE -> new Predicate.Lte(field, toComparable(value));
        };
    }

    private boolean isValueExpression(SqlAst.Expression expr) {
        return expr instanceof SqlAst.Expression.StringLiteral
                || expr instanceof SqlAst.Expression.NumberLiteral
                || expr instanceof SqlAst.Expression.BooleanLiteral
                || expr instanceof SqlAst.Expression.Parameter;
    }

    private boolean isFieldExpression(SqlAst.Expression expr) {
        return expr instanceof SqlAst.Expression.ColumnRef;
    }

    /**
     * Flips a comparison operator for reversed comparisons (e.g., {@code 5 < age} →
     * {@code age > 5}).
     */
    // @spec query.sql-query-support.R74 — GT↔LT, GTE↔LTE flipped; EQ/NE unchanged
    private SqlAst.ComparisonOp flipOp(SqlAst.ComparisonOp op) {
        return switch (op) {
            case EQ -> SqlAst.ComparisonOp.EQ;
            case NE -> SqlAst.ComparisonOp.NE;
            case GT -> SqlAst.ComparisonOp.LT;
            case GTE -> SqlAst.ComparisonOp.LTE;
            case LT -> SqlAst.ComparisonOp.GT;
            case LTE -> SqlAst.ComparisonOp.GTE;
        };
    }

    // @spec query.sql-query-support.R64 — AND → Predicate.And; OR → Predicate.Or; both with two-element child lists
    private Predicate translateLogical(SqlAst.Expression.Logical log, JlsmSchema schema)
            throws SqlParseException {
        final Predicate left = translateExpression(log.left(), schema);
        final Predicate right = translateExpression(log.right(), schema);

        return switch (log.op()) {
            case AND -> new Predicate.And(List.of(left, right));
            case OR -> new Predicate.Or(List.of(left, right));
        };
    }

    // @spec query.sql-query-support.R63 — BETWEEN translates to Predicate.Between after type-checked bounds
    private Predicate translateBetween(SqlAst.Expression.Between bet, JlsmSchema schema)
            throws SqlParseException {
        final String field = extractFieldName(bet.field());
        validateField(field, schema);
        final Object lowValue = extractValue(bet.low());
        final Object highValue = extractValue(bet.high());
        validateValueType(field, lowValue, schema);
        validateValueType(field, highValue, schema);
        final Comparable<?> low = toComparable(lowValue);
        final Comparable<?> high = toComparable(highValue);

        @SuppressWarnings("unchecked")
        final int cmp = ((Comparable<Object>) low).compareTo(high);
        if (cmp > 0) {
            throw new SqlParseException(
                    "BETWEEN low bound (" + low + ") must not exceed high bound (" + high + ")",
                    -1);
        }

        return new Predicate.Between(field, low, high);
    }

    // @spec query.sql-query-support.R65,R66,R67,R68,R94 — MATCH → FullTextMatch, arity/field/type checks; unknown
    // function rejected
    private Predicate translateFunctionCall(SqlAst.Expression.FunctionCall fn, JlsmSchema schema)
            throws SqlParseException {
        return switch (fn.name()) {
            case "MATCH" -> {
                if (fn.arguments().size() != 2) {
                    throw new SqlParseException("MATCH requires exactly 2 arguments: field, query",
                            -1);
                }
                final String field = extractFieldName(fn.arguments().get(0));
                validateField(field, schema);
                validateFieldIsString(field, schema);
                final Object queryValue = extractValue(fn.arguments().get(1));
                if (!(queryValue instanceof String queryText)) {
                    throw new SqlParseException("MATCH query argument must be a string literal",
                            -1);
                }
                yield new Predicate.FullTextMatch(field, queryText);
            }
            case "VECTOR_DISTANCE" -> throw new SqlParseException(
                    "VECTOR_DISTANCE must appear in ORDER BY, not WHERE", -1);
            default -> throw new SqlParseException(
                    "Unsupported function in WHERE clause: " + fn.name(), -1);
        };
    }

    // ── VECTOR_DISTANCE translation ──────────────────────────────

    // @spec query.sql-query-support.R69,R70,R71,R72,R73 — VECTOR_DISTANCE arity=3, field type, bind-param vector,
    // string metric
    private SqlQuery.VectorDistanceOrder translateVectorDistance(SqlAst.Expression.FunctionCall fn,
            JlsmSchema schema, boolean ascending) throws SqlParseException {
        if (fn.arguments().size() != 3) {
            throw new SqlParseException(
                    "VECTOR_DISTANCE requires exactly 3 arguments: field, vector, metric", -1);
        }

        final String field = extractFieldName(fn.arguments().get(0));
        validateField(field, schema);
        validateFieldIsVector(field, schema);

        final SqlAst.Expression vecExpr = fn.arguments().get(1);
        final int paramIndex;
        if (vecExpr instanceof SqlAst.Expression.Parameter param) {
            paramIndex = param.index();
        } else {
            throw new SqlParseException(
                    "VECTOR_DISTANCE vector argument must be a bind parameter (?)", -1);
        }

        final Object metricValue = extractValue(fn.arguments().get(2));
        if (!(metricValue instanceof String metric)) {
            throw new SqlParseException("VECTOR_DISTANCE metric argument must be a string literal",
                    -1);
        }
        if (!KNOWN_METRICS.contains(metric)) {
            throw new SqlParseException(
                    "Unknown VECTOR_DISTANCE metric '" + metric + "'; supported: " + KNOWN_METRICS,
                    -1);
        }

        return new SqlQuery.VectorDistanceOrder(field, paramIndex, metric, ascending);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String extractFieldName(SqlAst.Expression expr) throws SqlParseException {
        if (expr instanceof SqlAst.Expression.ColumnRef ref) {
            return ref.name();
        }
        throw new SqlParseException(
                "Expected column reference but found " + expr.getClass().getSimpleName(), -1);
    }

    private Object extractValue(SqlAst.Expression expr) throws SqlParseException {
        return switch (expr) {
            case SqlAst.Expression.StringLiteral lit -> lit.value();
            case SqlAst.Expression.NumberLiteral num -> parseNumber(num.text());
            case SqlAst.Expression.BooleanLiteral bool -> bool.value();
            case SqlAst.Expression.Parameter param -> new SqlQuery.BindMarker(param.index());
            default -> throw new SqlParseException(
                    "Unsupported value expression: " + expr.getClass().getSimpleName(), -1);
        };
    }

    // @spec query.sql-query-support.R77,R78 — integer widening Int→Long→fail; decimal overflow must throw, not Infinity
    private Number parseNumber(String text) throws SqlParseException {
        assert text != null : "numeric text must not be null";
        try {
            if (text.contains(".") || text.contains("e") || text.contains("E")) {
                final double d = Double.parseDouble(text);
                if (Double.isInfinite(d) || Double.isNaN(d)) {
                    throw new SqlParseException("Numeric literal out of range: '" + text + "'", -1);
                }
                return d;
            }
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException _) {
                return Long.parseLong(text);
            }
        } catch (NumberFormatException e) {
            throw new SqlParseException("Numeric literal out of range: '" + text + "'", -1, e);
        }
    }

    private Comparable<?> toComparable(Object value) throws SqlParseException {
        if (value instanceof Comparable<?> c) {
            return c;
        }
        throw new SqlParseException("Value is not comparable: " + value.getClass().getSimpleName(),
                -1);
    }

    // @spec query.sql-query-support.R57,R58,R59 — unknown field surfaced as SqlParseException naming field and schema
    private void validateField(String fieldName, JlsmSchema schema) throws SqlParseException {
        assert fieldName != null : "fieldName must not be null";
        if (schema.fieldIndex(fieldName) < 0) {
            throw new SqlParseException("Unknown field '" + fieldName + "' — not found in schema '"
                    + schema.name() + "'", -1);
        }
    }

    private FieldType resolveFieldType(String fieldName, JlsmSchema schema) {
        final int idx = schema.fieldIndex(fieldName);
        assert idx >= 0 : "field must exist — call validateField first";
        return schema.fields().get(idx).type();
    }

    // @spec query.sql-query-support.R60,R61,R88,R89,R90,R91 — literal type compatibility table; bind markers skip type
    // checking
    private void validateValueType(String fieldName, Object value, JlsmSchema schema)
            throws SqlParseException {
        // Bind markers are resolved at execution time — skip type checking
        if (value instanceof SqlQuery.BindMarker) {
            return;
        }

        final FieldType fieldType = resolveFieldType(fieldName, schema);
        final boolean compatible = switch (fieldType) {
            case FieldType.Primitive p -> switch (p) {
                case STRING -> value instanceof String;
                case INT8 ->
                    value instanceof Number n && inRange(n, Byte.MIN_VALUE, Byte.MAX_VALUE);
                case INT16 ->
                    value instanceof Number n && inRange(n, Short.MIN_VALUE, Short.MAX_VALUE);
                case INT32 ->
                    value instanceof Number n && inRange(n, Integer.MIN_VALUE, Integer.MAX_VALUE);
                case INT64 -> value instanceof Number;
                case FLOAT16, FLOAT32, FLOAT64 -> value instanceof Number;
                case BOOLEAN -> value instanceof Boolean;
                case TIMESTAMP -> value instanceof Number || value instanceof String;
            };
            case FieldType.BoundedString _ -> value instanceof String;
            case FieldType.ArrayType _, FieldType.ObjectType _, FieldType.VectorType _ -> false;
        };

        if (! compatible) {
            final String detail = (value instanceof Number && isIntegerType(fieldType))
                    ? " (out of range for " + fieldType + ")"
                    : "";
            throw new SqlParseException("Type mismatch: field '" + fieldName + "' is " + fieldType
                    + " but value is " + value.getClass().getSimpleName() + detail, -1);
        }
    }

    private static boolean inRange(Number n, long min, long max) {
        final long v = n.longValue();
        return v >= min && v <= max;
    }

    private static boolean isIntegerType(FieldType type) {
        return type == FieldType.Primitive.INT8 || type == FieldType.Primitive.INT16
                || type == FieldType.Primitive.INT32;
    }

    // @spec query.sql-query-support.R67 — MATCH target must be STRING or BoundedString
    private void validateFieldIsString(String fieldName, JlsmSchema schema)
            throws SqlParseException {
        final FieldType type = resolveFieldType(fieldName, schema);
        if (!(type == FieldType.Primitive.STRING || type instanceof FieldType.BoundedString)) {
            throw new SqlParseException(
                    "MATCH requires a STRING field but '" + fieldName + "' is " + type, -1);
        }
    }

    // @spec query.sql-query-support.R71 — VECTOR_DISTANCE target must be VectorType or ArrayType of FLOAT16/FLOAT32
    private void validateFieldIsVector(String fieldName, JlsmSchema schema)
            throws SqlParseException {
        final FieldType type = resolveFieldType(fieldName, schema);
        final boolean isVector = switch (type) {
            case FieldType.VectorType _ -> true;
            case FieldType.ArrayType a -> a.elementType() == FieldType.Primitive.FLOAT16
                    || a.elementType() == FieldType.Primitive.FLOAT32;
            default -> false;
        };
        if (!isVector) {
            throw new SqlParseException(
                    "VECTOR_DISTANCE requires a vector field but '" + fieldName + "' is " + type,
                    -1);
        }
    }
}
