package jlsm.table;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Fluent query builder for table lookups. Builds a {@link Predicate} tree and executes it against
 * the table's indices and data.
 *
 * <p>
 * Usage:
 *
 * <pre>{@code
 * Iterator<TableEntry<String>> results = table.query().where("age").gt(30).and("status")
 *         .eq("active").execute();
 * }</pre>
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Receives: field names and values via fluent chaining</li>
 * <li>Returns: {@code Iterator<TableEntry<K>>} on execute()</li>
 * <li>Side effects: none until execute() — query building is pure</li>
 * <li>Error conditions: IllegalArgumentException on invalid field/type combinations</li>
 * </ul>
 *
 * @param <K> the primary key type (String or Long)
 */
// @spec F10.R30 — public final class parameterized by primary key type K
public final class TableQuery<K> {

    private enum CombineMode {
        NONE, AND, OR
    }

    private Predicate root;
    private CombineMode nextMode = CombineMode.NONE;
    // @spec F10.R37,F10.R38 — null runner → unbound → execute() throws UOE;
    // non-null runner → bound → execute() delegates to the runner.
    private final QueryRunner<K> runner;

    private TableQuery() {
        this(null);
    }

    private TableQuery(QueryRunner<K> runner) {
        this.runner = runner;
    }

    /**
     * Creates an unbound {@link TableQuery}. The returned query supports fluent predicate building
     * but throws {@link UnsupportedOperationException} from {@link #execute()} — callers must
     * obtain a bound instance via {@link JlsmTable.StringKeyed#query()} (or another table-provided
     * factory) to execute the query.
     *
     * @param <K> the primary key type
     * @return a new unbound query
     */
    // @spec F10.R38 — explicit unbound constructor; execute() throws UOE.
    public static <K> TableQuery<K> unbound() {
        return new TableQuery<>();
    }

    /**
     * Creates a {@link TableQuery} bound to the supplied {@link QueryRunner}. The returned query's
     * {@link #execute()} method dispatches the built predicate through the runner.
     *
     * <p>
     * This factory is intended for use by table implementations in {@code jlsm.table.internal}; the
     * runner type is public only so implementations can expose a typed execution backend. External
     * callers who need a query should call {@link JlsmTable.StringKeyed#query()} on their table.
     *
     * @param runner the execution backend; must not be null
     * @param <K> the primary key type
     * @return a new bound query
     */
    // @spec F05.R37,F10.R37 — bound factory used by StringKeyedTable to wire QueryExecutor.
    public static <K> TableQuery<K> bound(QueryRunner<K> runner) {
        Objects.requireNonNull(runner, "runner must not be null");
        return new TableQuery<>(runner);
    }

    /**
     * Begins a predicate on the given field.
     *
     * @param fieldName the field to filter on
     * @return a field clause for specifying the comparison operator
     */
    // @spec F10.R31,R32 — reject null fieldName (NPE); return a FieldClause<K>
    public FieldClause<K> where(String fieldName) {
        Objects.requireNonNull(fieldName, "fieldName");
        return new FieldClause<>(this, fieldName);
    }

    /**
     * Executes the query, returning matching entries. Uses available indices where possible; falls
     * back to scan-and-filter.
     *
     * @return iterator over matching table entries
     * @throws IOException if an I/O error occurs during query execution
     */
    // @spec F10.R37,R38 — returns Iterator<TableEntry<K>>; unbound instance throws UOE, bound
    // instance delegates to the QueryRunner wired by the owning table.
    public Iterator<TableEntry<K>> execute() throws IOException {
        if (runner == null) {
            throw new UnsupportedOperationException(
                    "execute() requires table binding — use table.query() to obtain a bound instance");
        }
        if (root == null) {
            // Empty predicate tree — nothing was built. Return an empty iterator rather than
            // forcing callers to special-case the no-predicate form.
            return java.util.Collections.emptyIterator();
        }
        return runner.run(root);
    }

    /**
     * Returns the predicate tree built so far. Useful for inspection, serialization, or future SQL
     * translation.
     *
     * @return the root predicate, or null if no predicates have been added
     */
    // @spec F10.R36 — returns the current root predicate, or null if none have been added
    public Predicate predicate() {
        return root;
    }

    /**
     * Chains an AND predicate on the given field.
     *
     * @param fieldName the next field to filter on
     * @return a field clause for specifying the comparison operator
     */
    // @spec F10.R34 — combines the next predicate with the existing root using Predicate.And
    public FieldClause<K> and(String fieldName) {
        Objects.requireNonNull(fieldName, "fieldName");
        nextMode = CombineMode.AND;
        return new FieldClause<>(this, fieldName);
    }

    /**
     * Chains an OR predicate on the given field.
     *
     * @param fieldName the next field to filter on
     * @return a field clause for specifying the comparison operator
     */
    // @spec F10.R35 — combines the next predicate with the existing root using Predicate.Or
    public FieldClause<K> or(String fieldName) {
        Objects.requireNonNull(fieldName, "fieldName");
        nextMode = CombineMode.OR;
        return new FieldClause<>(this, fieldName);
    }

    TableQuery<K> addPredicate(Predicate predicate) {
        Objects.requireNonNull(predicate, "predicate");
        if (root == null) {
            root = predicate;
        } else if (nextMode == CombineMode.NONE) {
            throw new IllegalStateException(
                    "A predicate is already set — use and() or or() to combine predicates");
        } else if (nextMode == CombineMode.AND) {
            root = new Predicate.And(List.of(root, predicate));
        } else {
            root = new Predicate.Or(List.of(root, predicate));
        }
        nextMode = CombineMode.NONE;
        return this;
    }

    /**
     * A field-level clause that provides comparison operators.
     *
     * @param <K> the primary key type
     */
    // @spec F10.R33 — FieldClause provides eq/ne/gt/gte/lt/lte/between/fullTextMatch/vectorNearest
    // each returning the owning TableQuery<K> for chaining
    public static final class FieldClause<K> {

        private final TableQuery<K> query;
        private final String fieldName;

        FieldClause(TableQuery<K> query, String fieldName) {
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(fieldName, "fieldName");
            this.query = query;
            this.fieldName = fieldName;
        }

        /** Equality: field == value. */
        public TableQuery<K> eq(Object value) {
            return query.addPredicate(new Predicate.Eq(fieldName, value));
        }

        /** Inequality: field != value. */
        public TableQuery<K> ne(Object value) {
            return query.addPredicate(new Predicate.Ne(fieldName, value));
        }

        /** Greater than: field > value. */
        public TableQuery<K> gt(Comparable<?> value) {
            return query.addPredicate(new Predicate.Gt(fieldName, value));
        }

        /** Greater than or equal: field >= value. */
        public TableQuery<K> gte(Comparable<?> value) {
            return query.addPredicate(new Predicate.Gte(fieldName, value));
        }

        /** Less than: field < value. */
        public TableQuery<K> lt(Comparable<?> value) {
            return query.addPredicate(new Predicate.Lt(fieldName, value));
        }

        /** Less than or equal: field <= value. */
        public TableQuery<K> lte(Comparable<?> value) {
            return query.addPredicate(new Predicate.Lte(fieldName, value));
        }

        /** Range: low <= field <= high. */
        public TableQuery<K> between(Comparable<?> low, Comparable<?> high) {
            return query.addPredicate(new Predicate.Between(fieldName, low, high));
        }

        /** Full-text search on this field. */
        public TableQuery<K> fullTextMatch(String queryText) {
            return query.addPredicate(new Predicate.FullTextMatch(fieldName, queryText));
        }

        /** Vector nearest-neighbour search on this field. */
        public TableQuery<K> vectorNearest(float[] queryVector, int topK) {
            return query.addPredicate(new Predicate.VectorNearest(fieldName, queryVector, topK));
        }
    }
}
