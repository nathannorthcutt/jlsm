package jlsm.engine;

import jlsm.table.JlsmSchema;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Metadata for a table managed by an {@link Engine}.
 *
 * <p>
 * Governed by: {@code .decisions/table-catalog-persistence/adr.md},
 * {@code .decisions/table-handle-scope-exposure/adr.md} v2.
 *
 * @param name the table name; never null
 * @param schema the table schema; never null
 * @param createdAt the instant the table was created; never null
 * @param state the current table state; never null
 * @param encryption optional encryption metadata; never null (use {@link Optional#empty()} for
 *            unencrypted / plaintext tables)
 *
 * @spec sstable.footer-encryption-scope.R8
 */
public record TableMetadata(String name, JlsmSchema schema, Instant createdAt, TableState state,
        Optional<EncryptionMetadata> encryption) {

    public TableMetadata {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(encryption, "encryption must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
    }

    /**
     * Backward-compatibility convenience constructor for unencrypted tables. Equivalent to the
     * canonical 5-arg form with {@code encryption = Optional.empty()} — preserves source
     * compatibility for the dozens of existing 4-arg call sites that predate the encryption
     * extension.
     *
     * @spec sstable.footer-encryption-scope.R8
     */
    public TableMetadata(String name, JlsmSchema schema, Instant createdAt, TableState state) {
        this(name, schema, createdAt, state, Optional.empty());
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
