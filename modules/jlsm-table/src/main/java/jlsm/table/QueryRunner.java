package jlsm.table;

import java.io.IOException;
import java.util.Iterator;

/**
 * Executes a bound {@link TableQuery}'s predicate tree against a live table and returns matching
 * entries. Implementations are produced internally by {@link JlsmTable.StringKeyed} tables when a
 * schema is configured, so that {@link TableQuery#execute()} can dispatch to the table's indices
 * and primary storage.
 *
 * <p>
 * This interface exists so that {@link TableQuery} can be constructed with an execution backend
 * without exposing the internal query-executor type on the public API. The concrete implementation
 * lives in {@code jlsm.table.internal} and is not exported from the module.
 *
 * @param <K> the primary key type
 */
// @spec engine.in-process-database-engine.R37 — binding contract between
// TableQuery.execute() and the executor that
// resolves index-backed predicates and scan-and-filter fallback.
@FunctionalInterface
public interface QueryRunner<K> {

    /**
     * Executes the given predicate and returns matching entries.
     *
     * @param predicate the root predicate of the query; must not be null
     * @return an iterator over the matching entries; never null
     * @throws IOException if an I/O error occurs during execution
     */
    Iterator<TableEntry<K>> run(Predicate predicate) throws IOException;
}
