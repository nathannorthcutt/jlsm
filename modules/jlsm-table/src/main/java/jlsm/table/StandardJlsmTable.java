package jlsm.table;

/**
 * Factory for creating {@link JlsmTable} instances.
 *
 * <p>
 * This is a placeholder stub; the full implementation will be provided in Task 5.
 */
public final class StandardJlsmTable {

    private StandardJlsmTable() {
    }

    /**
     * Returns a builder for a string-keyed {@link JlsmTable}.
     *
     * @return a builder; never null
     * @throws UnsupportedOperationException always — not yet implemented
     */
    public static JlsmTable.StringKeyed.Builder stringKeyedBuilder() {
        throw new UnsupportedOperationException("not yet implemented");
    }

    /**
     * Returns a builder for a long-keyed {@link JlsmTable}.
     *
     * @return a builder; never null
     * @throws UnsupportedOperationException always — not yet implemented
     */
    public static JlsmTable.LongKeyed.Builder longKeyedBuilder() {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
