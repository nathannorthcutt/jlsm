package jlsm.table;

import jlsm.core.indexing.FullTextIndex;
import jlsm.core.indexing.VectorIndex;
import jlsm.core.io.MemorySerializer;
import jlsm.core.tree.TypedLsmTree;
import jlsm.table.internal.IndexRegistry;
import jlsm.table.internal.LongKeyedTable;
import jlsm.table.internal.StringKeyedTable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
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
        private final List<IndexDefinition> indexDefinitions = new ArrayList<>();
        private FullTextIndex.Factory fullTextFactory;
        private VectorIndex.Factory vectorFactory;

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

        /**
         * Adds a secondary index definition. May be called multiple times. When any definition has
         * {@link IndexType#FULL_TEXT}, a {@link FullTextIndex.Factory} must also be supplied via
         * {@link #fullTextFactory(FullTextIndex.Factory)}. When any definition has
         * {@link IndexType#VECTOR}, a {@link VectorIndex.Factory} must also be supplied via
         * {@link #vectorFactory(VectorIndex.Factory)}.
         *
         * @param definition the index definition; must not be null
         * @return this builder
         */
        public StringKeyedBuilder addIndex(IndexDefinition definition) {
            Objects.requireNonNull(definition, "definition must not be null");
            indexDefinitions.add(definition);
            return this;
        }

        /**
         * Sets the full-text index factory used to materialise {@link IndexType#FULL_TEXT} indices.
         * Required when any registered definition has type FULL_TEXT; ignored otherwise.
         *
         * @param fullTextFactory the factory; must not be null
         * @return this builder
         */
        public StringKeyedBuilder fullTextFactory(FullTextIndex.Factory fullTextFactory) {
            this.fullTextFactory = Objects.requireNonNull(fullTextFactory,
                    "fullTextFactory must not be null");
            return this;
        }

        /**
         * Sets the vector index factory used to materialise {@link IndexType#VECTOR} indices.
         * Required when any registered definition has type VECTOR; ignored otherwise.
         *
         * @param vectorFactory the factory; must not be null
         * @return this builder
         */
        public StringKeyedBuilder vectorFactory(VectorIndex.Factory vectorFactory) {
            this.vectorFactory = Objects.requireNonNull(vectorFactory,
                    "vectorFactory must not be null");
            return this;
        }

        @Override
        public JlsmTable.StringKeyed build() {
            Objects.requireNonNull(tree, "tree must not be null");
            final MemorySerializer<JlsmDocument> codec = schema != null
                    ? DocumentSerializer.forSchema(schema)
                    : null;
            assert codec != null : "codec must not be null when schema is provided";

            IndexRegistry registry = null;
            if (!indexDefinitions.isEmpty() && schema == null) {
                throw new IllegalStateException("indexDefinitions require a schema on the builder");
            }
            // @spec engine.in-process-database-engine.R37 — when a schema is configured, always
            // materialise an IndexRegistry
            // so table.query() can route through QueryExecutor even with zero secondary indices.
            // The registry's documentStore acts as the schema-aware mirror used for
            // scan-and-filter fallback. When no schema is configured, no registry is created and
            // query() falls back to its unbound default.
            if (schema != null) {
                try {
                    registry = new IndexRegistry(schema, List.copyOf(indexDefinitions),
                            fullTextFactory, vectorFactory);
                } catch (IOException e) {
                    // Wrap in UncheckedIOException so the builder signature stays unchanged.
                    throw new UncheckedIOException("Failed to create secondary index registry", e);
                }
            }
            return new StringKeyedTable(tree, codec, schema, registry);
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
