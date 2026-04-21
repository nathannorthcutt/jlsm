package jlsm.table;

import jlsm.encryption.EncryptionSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Describes the structure of documents stored in a {@link JlsmTable}.
 *
 * <p>
 * A schema has a name, a version, an ordered list of {@link FieldDefinition}s, and a maximum
 * nesting depth limit for object fields.
 */
// @spec schema.schema-construction.R1 — final class in jlsm.table, not subclassable
// @spec schema.schema-invariants.R1 — all fields private final, effectively immutable
// @spec schema.schema-invariants.R4 — safe for concurrent read access (immutable after
// construction)
// @spec schema.schema-field-access.R8 — no toString() override (absent behavior)
// @spec schema.schema-field-access.R9 — no Serializable (absent behavior)
// @spec schema.schema-field-access.R10 — no field-count upper bound at schema level
public final class JlsmSchema {

    // @spec schema.schema-construction.R8 — DEFAULT_MAX_DEPTH=10, ABSOLUTE_MAX_DEPTH=25
    private static final int DEFAULT_MAX_DEPTH = 10;
    private static final int ABSOLUTE_MAX_DEPTH = 25;

    private final String name;
    private final int version;
    private final List<FieldDefinition> fields;
    private final int maxDepth;
    private final Map<String, Integer> fieldIndexMap;

    // @spec schema.schema-construction.R2 — private constructor, instances only via builder
    // @spec schema.schema-construction.R4,R5,R6,R7 — assertion checks for name, fields, maxDepth,
    // version
    // @spec schema.schema-construction.R9 — fields defensively copied via List.copyOf
    // @spec schema.schema-construction.R10 — duplicate field names rejected with
    // IllegalArgumentException
    // @spec schema.schema-construction.R11 — empty field list accepted without error
    // @spec schema.schema-construction.R12 — field ordering preserved (List.copyOf maintains order)
    // @spec schema.schema-field-access.R4 — fieldIndexMap unmodifiable via Map.copyOf
    // @spec schema.schema-invariants.R2,R3 — fields and fieldIndexMap stored as unmodifiable copies
    private JlsmSchema(String name, int version, List<FieldDefinition> fields, int maxDepth) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(fields, "fields must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative, got: " + version);
        }
        if (maxDepth < 0 || maxDepth > ABSOLUTE_MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "maxDepth out of range [0, " + ABSOLUTE_MAX_DEPTH + "], got: " + maxDepth);
        }

        this.name = name;
        this.version = version;
        this.fields = List.copyOf(fields);
        this.maxDepth = maxDepth;

        // Build field index map for O(1) lookup — reject duplicates
        final Map<String, Integer> indexMap = new HashMap<>(fields.size() * 2);
        for (int i = 0; i < fields.size(); i++) {
            final String fieldName = fields.get(i).name();
            if (indexMap.containsKey(fieldName)) {
                throw new IllegalArgumentException(
                        "Duplicate field name '" + fieldName + "' in schema '" + name + "'");
            }
            indexMap.put(fieldName, i);
        }
        this.fieldIndexMap = Map.copyOf(indexMap);
    }

    // @spec schema.schema-field-access.R5
    /** Returns the schema name. */
    public String name() {
        return name;
    }

    // @spec schema.schema-field-access.R6
    /** Returns the schema version. */
    public int version() {
        return version;
    }

    // @spec schema.schema-construction.R9 — returned list is unmodifiable
    /** Returns an unmodifiable ordered list of field definitions. */
    public List<FieldDefinition> fields() {
        return fields;
    }

    // @spec schema.schema-field-access.R7 — defaults to 10 if not explicitly set
    /** Returns the maximum allowed nesting depth for object fields. */
    public int maxDepth() {
        return maxDepth;
    }

    // @spec schema.schema-invariants.R5 — equals based on name, version, fields, maxDepth
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof JlsmSchema other)) {
            return false;
        }
        return version == other.version && maxDepth == other.maxDepth
                && Objects.equals(name, other.name) && Objects.equals(fields, other.fields);
    }

    // @spec schema.schema-invariants.R6 — hashCode consistent with equals
    @Override
    public int hashCode() {
        return Objects.hash(name, version, fields, maxDepth);
    }

    // @spec schema.schema-field-access.R1 — zero-based index via case-sensitive HashMap lookup
    // @spec schema.schema-field-access.R2 — returns -1 when no field exists
    // @spec schema.schema-field-access.R3 — rejects null with NullPointerException
    /**
     * Returns the zero-based index of the field with the given name, or {@code -1} if no such field
     * exists.
     *
     * @param name the field name to look up; must not be null
     * @return the field index, or -1 if not found
     */
    public int fieldIndex(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return fieldIndexMap.getOrDefault(name, -1);
    }

    // @spec schema.schema-construction.R2 — instances created exclusively through builder
    // @spec schema.schema-construction.R3 — rejects null name with NullPointerException
    // @spec schema.schema-construction.R7 — rejects negative version with IllegalArgumentException
    // @spec schema.schema-construction.R20 — default maxDepth is 10
    /**
     * Creates a new {@link Builder} for a schema with the given name and version.
     *
     * @param name the schema name; must not be null
     * @param version the schema version
     * @return a new Builder
     */
    public static Builder builder(String name, int version) {
        Objects.requireNonNull(name, "name must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative, got: " + version);
        }
        return new Builder(name, version, 0, DEFAULT_MAX_DEPTH, null);
    }

    // @spec schema.schema-construction.R22 — Builder is not thread-safe
    /**
     * Builder for {@link JlsmSchema}.
     *
     * <p>
     * The builder tracks a current nesting depth; {@link #objectField} creates a nested builder at
     * {@code depth + 1}. {@link #build()} validates that no level exceeds the configured
     * {@code maxDepth}.
     */
    public static final class Builder {

        private final String name;
        private final int version;
        private final int currentDepth;
        private final List<FieldDefinition> fields = new ArrayList<>();

        // maxDepth is tracked on the root builder; nested builders reference the root
        private int maxDepth;
        // parentBuilder is null for the root; non-null for nested builders
        private final Builder rootBuilder;

        private Builder(String name, int version, int currentDepth, int maxDepth,
                Builder rootBuilder) {
            this.name = name;
            this.version = version;
            this.currentDepth = currentDepth;
            this.maxDepth = maxDepth;
            this.rootBuilder = rootBuilder;
        }

        // @spec schema.schema-construction.R13 — rejects null name or type with
        // NullPointerException
        // @spec schema.schema-construction.R15 — creates FieldDefinition with EncryptionSpec.NONE
        // default
        // @spec schema.schema-construction.R23 — rejects blank field names with
        // IllegalArgumentException
        /**
         * Adds a primitive or array field to this schema level.
         *
         * @param name the field name; must not be null
         * @param type the field type; must not be null
         * @return this builder
         */
        public Builder field(String name, FieldType type) {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(type, "type must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("field name must not be blank");
            }
            assert name != null : "name must not be null";
            assert type != null : "type must not be null";
            fields.add(new FieldDefinition(name, type));
            return this;
        }

        // @spec schema.schema-construction.R14 — rejects null name, type, or encryption with
        // NullPointerException
        // @spec schema.schema-construction.R23 — rejects blank field names
        /**
         * Adds a primitive or array field with an explicit encryption specification.
         *
         * @param name the field name; must not be null
         * @param type the field type; must not be null
         * @param encryption the encryption spec; must not be null
         * @return this builder
         */
        public Builder field(String name, FieldType type, EncryptionSpec encryption) {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(encryption, "encryption must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("field name must not be blank");
            }
            assert name != null : "name must not be null";
            assert type != null : "type must not be null";
            assert encryption != null : "encryption must not be null";
            fields.add(new FieldDefinition(name, type, encryption));
            return this;
        }

        // @spec schema.schema-construction.R16 — rejects null name or elementType with
        // NullPointerException
        // @spec schema.schema-construction.R17 — delegates to FieldType.vector for validation
        // @spec vector.field-type.R9 — adds a VectorType field to the schema
        // @spec vector.field-type.R10 — rejects null name with NullPointerException
        // @spec vector.field-type.R11 — rejects null elementType with NullPointerException
        // @spec vector.field-type.R12 — delegates to FieldType.vector, centralizing validation
        /**
         * Adds a vector field with the given element type and fixed dimensions.
         *
         * @param name the field name; must not be null
         * @param elementType the element precision; must be FLOAT16 or FLOAT32
         * @param dimensions the fixed number of elements per vector; must be positive
         * @return this builder
         */
        public Builder vectorField(String name, FieldType.Primitive elementType, int dimensions) {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(elementType, "elementType must not be null");
            assert name != null : "name must not be null";
            assert elementType != null : "elementType must not be null";
            return field(name, FieldType.vector(elementType, dimensions));
        }

        // @spec schema.schema-nesting.R1 — creates nested builder at currentDepth + 1
        // @spec schema.schema-nesting.R2 — rejects null name with NullPointerException
        // @spec schema.schema-nesting.R3 — rejects null nested consumer with NullPointerException
        // @spec schema.schema-nesting.R4 — depth exceeding maxDepth throws IllegalArgumentException
        // @spec schema.schema-nesting.R5 — nested fields represented as ObjectType with List.copyOf
        // @spec schema.schema-construction.R24 — rejects blank field names
        /**
         * Adds a nested object field by building its inner schema inline via a Consumer.
         *
         * <p>
         * The nested builder is passed to the consumer at {@code currentDepth + 1}. If the nested
         * depth exceeds maxDepth, {@link #build()} will throw.
         *
         * @param name the field name; must not be null
         * @param nested a consumer that populates the nested schema's builder; must not be null
         * @return this builder
         */
        public Builder objectField(String name, Consumer<Builder> nested) {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(nested, "nested must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("field name must not be blank");
            }
            assert name != null : "name must not be null";
            assert nested != null : "nested consumer must not be null";

            final int nestedDepth = currentDepth + 1;
            final Builder root = (rootBuilder != null) ? rootBuilder : this;
            final Builder nestedBuilder = new Builder(name, version, nestedDepth, maxDepth, root);
            nested.accept(nestedBuilder);

            // Build the nested ObjectType — validation happens here
            final List<FieldDefinition> nestedFields = nestedBuilder.buildFields(root.maxDepth);
            fields.add(new FieldDefinition(name, new FieldType.ObjectType(nestedFields)));
            return this;
        }

        // @spec schema.schema-construction.R18 — rejects values >25 or negative with
        // IllegalArgumentException
        // @spec schema.schema-construction.R19 — accepts values in range [0, 25] inclusive
        /**
         * Sets the maximum nesting depth allowed for object fields. Must be between 0 and 25
         * (inclusive).
         *
         * @param maxDepth the maximum depth
         * @return this builder
         * @throws IllegalArgumentException if {@code maxDepth} exceeds 25
         */
        public Builder maxDepth(int maxDepth) {
            if (rootBuilder != null) {
                throw new IllegalStateException("maxDepth can only be set on the root builder — "
                        + "nested builders inherit maxDepth from the root");
            }
            if (maxDepth > ABSOLUTE_MAX_DEPTH) {
                throw new IllegalArgumentException("maxDepth " + maxDepth
                        + " exceeds absolute maximum of " + ABSOLUTE_MAX_DEPTH);
            }
            if (maxDepth < 0) {
                throw new IllegalArgumentException("maxDepth must not be negative");
            }
            this.maxDepth = maxDepth;
            return this;
        }

        // @spec schema.schema-construction.R21 — re-validates maxDepth before construction
        /**
         * Builds and returns the {@link JlsmSchema}.
         *
         * @return the constructed schema
         * @throws IllegalArgumentException if maxDepth exceeds 25 or if any field's nesting exceeds
         *             maxDepth
         */
        public JlsmSchema build() {
            if (maxDepth > ABSOLUTE_MAX_DEPTH) {
                throw new IllegalArgumentException("maxDepth " + maxDepth
                        + " exceeds absolute maximum of " + ABSOLUTE_MAX_DEPTH);
            }
            // Depth validation is already done eagerly in objectField → buildFields
            return new JlsmSchema(name, version, fields, maxDepth);
        }

        /**
         * Internal helper: returns the fields accumulated by this builder, after validating that
         * {@code currentDepth <= allowedMaxDepth}.
         *
         * @param allowedMaxDepth the depth limit from the root builder
         * @return the list of field definitions
         * @throws IllegalArgumentException if this builder's depth exceeds allowedMaxDepth
         */
        private List<FieldDefinition> buildFields(int allowedMaxDepth) {
            if (currentDepth > allowedMaxDepth) {
                throw new IllegalArgumentException("Nested object depth " + currentDepth
                        + " exceeds maxDepth " + allowedMaxDepth);
            }
            return List.copyOf(fields);
        }
    }
}
