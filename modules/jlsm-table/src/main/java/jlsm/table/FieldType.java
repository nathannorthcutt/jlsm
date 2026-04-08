package jlsm.table;

import java.util.List;
import java.util.Objects;

/**
 * Represents the type of a field in a {@link JlsmSchema}.
 *
 * <p>
 * FieldType is a sealed interface with three permitted implementations:
 * <ul>
 * <li>{@link Primitive} — an enum of scalar types</li>
 * <li>{@link ArrayType} — a homogeneous array of a given element type</li>
 * <li>{@link ObjectType} — a nested object with its own field definitions</li>
 * </ul>
 */
public sealed interface FieldType permits FieldType.Primitive, FieldType.ArrayType,
        FieldType.ObjectType, FieldType.VectorType, FieldType.BoundedString {

    /** Scalar primitive field types. */
    enum Primitive implements FieldType {
        STRING, INT8, INT16, INT32, INT64, FLOAT16, FLOAT32, FLOAT64, BOOLEAN, TIMESTAMP
    }

    /**
     * A homogeneous array of elements, all sharing the same {@link FieldType}.
     *
     * @param elementType the type of each element in the array; must not be null
     */
    record ArrayType(FieldType elementType) implements FieldType {
        public ArrayType {
            Objects.requireNonNull(elementType, "elementType must not be null");
        }
    }

    /**
     * A nested object type with an ordered list of named fields.
     *
     * @param fields the field definitions of the nested object; must not be null
     */
    record ObjectType(List<FieldDefinition> fields) implements FieldType {
        public ObjectType {
            Objects.requireNonNull(fields, "fields must not be null");
            fields = List.copyOf(fields);
        }

        /**
         * Creates a {@link JlsmSchema} from this ObjectType's field definitions, using the default
         * maximum nesting depth.
         *
         * @param name the schema name; must not be null
         * @param version the schema version
         * @return a new JlsmSchema with this ObjectType's fields
         */
        public JlsmSchema toSchema(String name, int version) {
            Objects.requireNonNull(name, "name must not be null");
            JlsmSchema.Builder builder = JlsmSchema.builder(name, version);
            for (FieldDefinition fd : fields) {
                builder.field(fd.name(), fd.type(), fd.encryption());
            }
            return builder.build();
        }

        /**
         * Creates a {@link JlsmSchema} from this ObjectType's field definitions, with the specified
         * maximum nesting depth. Use this overload to propagate a parent schema's depth
         * configuration.
         *
         * @param name the schema name; must not be null
         * @param version the schema version
         * @param maxDepth the maximum nesting depth for the resulting schema
         * @return a new JlsmSchema with this ObjectType's fields and the given maxDepth
         */
        public JlsmSchema toSchema(String name, int version, int maxDepth) {
            Objects.requireNonNull(name, "name must not be null");
            JlsmSchema.Builder builder = JlsmSchema.builder(name, version).maxDepth(maxDepth);
            for (FieldDefinition fd : fields) {
                builder.field(fd.name(), fd.type(), fd.encryption());
            }
            return builder.build();
        }
    }

    /**
     * A string with a declared maximum byte length. Behaves identically to {@link Primitive#STRING}
     * in all contexts except encryption dispatch (OPE domain derivation) and index validation
     * (OrderPreserving compatibility).
     *
     * @param maxLength the maximum byte length; must be positive
     */
    record BoundedString(int maxLength) implements FieldType {
        public BoundedString {
            if (maxLength <= 0) {
                throw new IllegalArgumentException("maxLength must be positive, got: " + maxLength);
            }
        }
    }

    /**
     * A fixed-dimension vector of floating-point elements.
     *
     * @param elementType the element precision; must be {@link Primitive#FLOAT16} or
     *            {@link Primitive#FLOAT32}
     * @param dimensions the fixed number of elements per vector; must be positive
     */
    record VectorType(Primitive elementType, int dimensions) implements FieldType {
        public VectorType {
            Objects.requireNonNull(elementType, "elementType must not be null");
            if (elementType != Primitive.FLOAT16 && elementType != Primitive.FLOAT32) {
                throw new IllegalArgumentException(
                        "VectorType elementType must be FLOAT16 or FLOAT32, got: " + elementType);
            }
            if (dimensions <= 0) {
                throw new IllegalArgumentException(
                        "VectorType dimensions must be positive, got: " + dimensions);
            }
        }
    }

    // --- Static factory shortcuts ---

    /** Returns {@link Primitive#STRING} (unbounded). */
    static FieldType string() {
        return Primitive.STRING;
    }

    /**
     * Returns a {@link BoundedString} with the given max byte length.
     *
     * @param maxLength the maximum byte length; must be positive
     * @return a new BoundedString
     */
    static FieldType string(int maxLength) {
        return new BoundedString(maxLength);
    }

    /** Returns {@link Primitive#INT32}. */
    static FieldType int32() {
        return Primitive.INT32;
    }

    /** Returns {@link Primitive#INT64}. */
    static FieldType int64() {
        return Primitive.INT64;
    }

    /** Returns {@link Primitive#FLOAT32}. */
    static FieldType float32() {
        return Primitive.FLOAT32;
    }

    /** Returns {@link Primitive#FLOAT64}. */
    static FieldType float64() {
        return Primitive.FLOAT64;
    }

    /** Returns {@link Primitive#BOOLEAN}. */
    static FieldType boolean_() {
        return Primitive.BOOLEAN;
    }

    /** Returns {@link Primitive#TIMESTAMP}. */
    static FieldType timestamp() {
        return Primitive.TIMESTAMP;
    }

    /**
     * Returns a {@link VectorType} with the given element type and dimensions.
     *
     * @param elementType the element precision; must be FLOAT16 or FLOAT32
     * @param dimensions the fixed number of elements per vector; must be positive
     * @return a new VectorType
     */
    static FieldType vector(Primitive elementType, int dimensions) {
        return new VectorType(elementType, dimensions);
    }

    /**
     * Returns an {@link ArrayType} with the given element type.
     *
     * @param elementType the element type; must not be null
     * @return a new ArrayType
     */
    static FieldType arrayOf(FieldType elementType) {
        Objects.requireNonNull(elementType, "elementType must not be null");
        return new ArrayType(elementType);
    }

    /**
     * Returns an {@link ObjectType} with the given field definitions.
     *
     * @param fields the field definitions; must not be null
     * @return a new ObjectType
     */
    static FieldType objectOf(List<FieldDefinition> fields) {
        Objects.requireNonNull(fields, "fields must not be null");
        return new ObjectType(fields);
    }
}
