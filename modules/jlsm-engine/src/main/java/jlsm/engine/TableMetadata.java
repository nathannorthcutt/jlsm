package jlsm.engine;

import jlsm.table.JlsmSchema;

import java.time.Instant;
import java.util.Objects;

/**
 * Metadata for a table managed by an {@link Engine}.
 *
 * <p>
 * Governed by: {@code .decisions/table-catalog-persistence/adr.md}
 *
 * @param name the table name; never null
 * @param schema the table schema; never null
 * @param createdAt the instant the table was created; never null
 * @param state the current table state; never null
 */
public record TableMetadata(String name, JlsmSchema schema, Instant createdAt, TableState state) {

    public TableMetadata {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(state, "state must not be null");
        assert !name.isEmpty() : "name must not be empty";
    }

    /**
     * The lifecycle state of a table.
     */
    public enum TableState {
        /** Table directory exists but full initialization has not completed. */
        LOADING,
        /** Table is fully initialized and ready for operations. */
        READY,
        /** Table has been dropped and is no longer accessible. */
        DROPPED,
        /** Table encountered an error during initialization or operation. */
        ERROR
    }
}
