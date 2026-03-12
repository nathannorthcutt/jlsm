package jlsm.table;

import jlsm.core.io.MemorySerializer;
import jlsm.core.tree.TypedLsmTree;
import jlsm.table.internal.LongKeyedTable;
import jlsm.table.internal.StringKeyedTable;

import java.util.Objects;

/**
 * Factory for creating {@link JlsmTable} instances.
 *
 * <p>
 * Provides static builder methods for string-keyed and long-keyed tables. Each builder accepts a
 * typed LSM tree and an optional schema, then constructs the appropriate table implementation.
 */
public final class StandardJlsmTable {

    private StandardJlsmTable() {
    }

    /**
     * Returns a builder for a string-keyed {@link JlsmTable}.
     *
     * @return a builder; never null
     */
    public static StringKeyedBuilder stringKeyedBuilder() {
        return new StringKeyedBuilder();
    }

    /**
     * Returns a builder for a long-keyed {@link JlsmTable}.
     *
     * @return a builder; never null
     */
    public static LongKeyedBuilder longKeyedBuilder() {
        return new LongKeyedBuilder();
    }

    // -------------------------------------------------------------------------
    // StringKeyed builder
    // -------------------------------------------------------------------------

    /**
     * Builder for {@link JlsmTable.StringKeyed} tables.
     */
    public static final class StringKeyedBuilder implements JlsmTable.StringKeyed.Builder {

        private TypedLsmTree.StringKeyed<JlsmDocument> tree;
        private JlsmSchema schema;

        private StringKeyedBuilder() {
        }

        /**
         * Sets the backing LSM tree.
         *
         * @param tree the string-keyed LSM tree; must not be null
         * @return this builder
         */
        public StringKeyedBuilder lsmTree(TypedLsmTree.StringKeyed<JlsmDocument> tree) {
            this.tree = Objects.requireNonNull(tree, "tree must not be null");
            return this;
        }

        /**
         * Sets the optional schema for write-time validation.
         *
         * @param schema the schema; may be null
         * @return this builder
         */
        public StringKeyedBuilder schema(JlsmSchema schema) {
            this.schema = schema;
            return this;
        }

        @Override
        public JlsmTable.StringKeyed build() {
            Objects.requireNonNull(tree, "tree must not be null");
            final MemorySerializer<JlsmDocument> codec = schema != null
                    ? DocumentSerializer.forSchema(schema)
                    : null;
            assert codec != null : "codec must not be null when schema is provided";
            return new StringKeyedTable(tree, codec, schema);
        }
    }

    // -------------------------------------------------------------------------
    // LongKeyed builder
    // -------------------------------------------------------------------------

    /**
     * Builder for {@link JlsmTable.LongKeyed} tables.
     */
    public static final class LongKeyedBuilder implements JlsmTable.LongKeyed.Builder {

        private TypedLsmTree.LongKeyed<JlsmDocument> tree;
        private JlsmSchema schema;

        private LongKeyedBuilder() {
        }

        /**
         * Sets the backing LSM tree.
         *
         * @param tree the long-keyed LSM tree; must not be null
         * @return this builder
         */
        public LongKeyedBuilder lsmTree(TypedLsmTree.LongKeyed<JlsmDocument> tree) {
            this.tree = Objects.requireNonNull(tree, "tree must not be null");
            return this;
        }

        /**
         * Sets the optional schema for write-time validation.
         *
         * @param schema the schema; may be null
         * @return this builder
         */
        public LongKeyedBuilder schema(JlsmSchema schema) {
            this.schema = schema;
            return this;
        }

        @Override
        public JlsmTable.LongKeyed build() {
            Objects.requireNonNull(tree, "tree must not be null");
            final MemorySerializer<JlsmDocument> codec = schema != null
                    ? DocumentSerializer.forSchema(schema)
                    : null;
            assert codec != null : "codec must not be null when schema is provided";
            return new LongKeyedTable(tree, codec, schema);
        }
    }
}
