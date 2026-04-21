package jlsm.core.json;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A JSON primitive value: string, number, or boolean.
 *
 * <p>
 * Numbers are stored as raw text to preserve the original representation (no precision loss from
 * parsing). Conversion methods ({@link #asInt()}, {@link #asLong()}, {@link #asDouble()},
 * {@link #asBigDecimal()}) parse from the raw text on demand, throwing
 * {@link NumberFormatException} if the text does not represent a valid number of the requested
 * type.
 *
 * <p>
 * Type query methods ({@link #isString()}, {@link #isNumber()}, {@link #isBoolean()}) indicate
 * which category this primitive belongs to. Calling a getter for the wrong category throws
 * {@link IllegalStateException}.
 *
 * <p>
 * Equality for number primitives uses {@link BigDecimal#compareTo} == 0, so {@code 1.0} and
 * {@code 1.00} are considered equal.
 *
 * @spec serialization.simd-jsonl.R6 — represents string, number, boolean with type-test methods
 * @spec serialization.simd-jsonl.R13 — immutable after construction
 * @spec serialization.simd-jsonl.R14 — number equality via BigDecimal.compareTo
 */
public final class JsonPrimitive implements JsonValue {

    /**
     * Discriminator for the kind of primitive value.
     */
    private enum Kind {
        STRING, NUMBER, BOOLEAN
    }

    private final Kind kind;
    private final String stringValue;
    private final String numberText;
    private final boolean booleanValue;

    private JsonPrimitive(Kind kind, String stringValue, String numberText, boolean booleanValue) {
        this.kind = kind;
        this.stringValue = stringValue;
        this.numberText = numberText;
        this.booleanValue = booleanValue;
    }

    /**
     * Creates a string-typed JSON primitive.
     *
     * @param value the string value; must not be null
     * @return a new string primitive
     * @throws NullPointerException if value is null
     */
    public static JsonPrimitive ofString(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return new JsonPrimitive(Kind.STRING, value, null, false);
    }

    /**
     * Creates a number-typed JSON primitive from raw text.
     *
     * <p>
     * The raw text is preserved exactly as provided. Validation is performed at construction time
     * to ensure the text represents a valid number (parseable by {@link BigDecimal}).
     *
     * @param rawText the raw number text (e.g., "123", "1.5e10"); must not be null and must be a
     *            valid numeric representation
     * @return a new number primitive
     * @throws NullPointerException if rawText is null
     * @throws NumberFormatException if rawText is not a valid number
     * @spec serialization.simd-jsonl.R47 — rejects invalid JSON numbers at construction
     */
    public static JsonPrimitive ofNumber(String rawText) {
        Objects.requireNonNull(rawText, "rawText must not be null");
        try {
            new BigDecimal(rawText);
        } catch (NumberFormatException e) {
            throw new NumberFormatException("Invalid number text: " + rawText);
        }
        return new JsonPrimitive(Kind.NUMBER, null, rawText, false);
    }

    /**
     * Creates a boolean-typed JSON primitive.
     *
     * @param value the boolean value
     * @return a new boolean primitive
     */
    public static JsonPrimitive ofBoolean(boolean value) {
        return new JsonPrimitive(Kind.BOOLEAN, null, null, value);
    }

    /** Returns {@code true} if this primitive is a string. */
    public boolean isString() {
        return kind == Kind.STRING;
    }

    /** Returns {@code true} if this primitive is a number. */
    public boolean isNumber() {
        return kind == Kind.NUMBER;
    }

    /** Returns {@code true} if this primitive is a boolean. */
    public boolean isBoolean() {
        return kind == Kind.BOOLEAN;
    }

    /**
     * Returns the string value.
     *
     * @return the string value
     * @throws IllegalStateException if this primitive is not a string
     * @spec serialization.simd-jsonl.R7 — returns value or throws IllegalStateException
     */
    public String asString() {
        if (kind != Kind.STRING) {
            throw new IllegalStateException("Not a string primitive (is " + kind + ")");
        }
        return stringValue;
    }

    /**
     * Returns the boolean value.
     *
     * @return the boolean value
     * @throws IllegalStateException if this primitive is not a boolean
     * @spec serialization.simd-jsonl.R7 — returns value or throws IllegalStateException
     */
    public boolean asBoolean() {
        if (kind != Kind.BOOLEAN) {
            throw new IllegalStateException("Not a boolean primitive (is " + kind + ")");
        }
        return booleanValue;
    }

    /**
     * Returns the raw number text exactly as it was provided at construction.
     *
     * @return the raw number text
     * @throws IllegalStateException if this primitive is not a number
     * @spec serialization.simd-jsonl.R8 — stores original text, parses on each call
     */
    public String asNumberText() {
        if (kind != Kind.NUMBER) {
            throw new IllegalStateException("Not a number primitive (is " + kind + ")");
        }
        return numberText;
    }

    /**
     * Parses the number text as an {@code int}.
     *
     * @return the int value
     * @throws IllegalStateException if this primitive is not a number
     * @throws NumberFormatException if the text cannot be parsed as an int
     */
    public int asInt() {
        return Integer.parseInt(asNumberText());
    }

    /**
     * Parses the number text as a {@code long}.
     *
     * @return the long value
     * @throws IllegalStateException if this primitive is not a number
     * @throws NumberFormatException if the text cannot be parsed as a long
     */
    public long asLong() {
        return Long.parseLong(asNumberText());
    }

    /**
     * Parses the number text as a {@code double}.
     *
     * @return the double value
     * @throws IllegalStateException if this primitive is not a number
     * @throws NumberFormatException if the text cannot be parsed as a double
     */
    public double asDouble() {
        return Double.parseDouble(asNumberText());
    }

    /**
     * Parses the number text as a {@link BigDecimal}.
     *
     * @return the BigDecimal value
     * @throws IllegalStateException if this primitive is not a number
     * @throws NumberFormatException if the text cannot be parsed as a BigDecimal
     */
    public BigDecimal asBigDecimal() {
        return new BigDecimal(asNumberText());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof JsonPrimitive other))
            return false;
        if (this.kind != other.kind)
            return false;
        return switch (kind) {
            case STRING -> Objects.equals(stringValue, other.stringValue);
            case BOOLEAN -> booleanValue == other.booleanValue;
            case NUMBER -> {
                try {
                    yield new BigDecimal(numberText)
                            .compareTo(new BigDecimal(other.numberText)) == 0;
                } catch (NumberFormatException _) {
                    // Fall back to raw text comparison if either is not a valid BigDecimal
                    yield Objects.equals(numberText, other.numberText);
                }
            }
        };
    }

    @Override
    public int hashCode() {
        return switch (kind) {
            case STRING -> Objects.hash(kind, stringValue);
            case BOOLEAN -> Objects.hash(kind, booleanValue);
            case NUMBER -> {
                try {
                    // Strip trailing zeros so "1.0" and "1.00" hash the same
                    yield Objects.hash(kind,
                            new BigDecimal(numberText).stripTrailingZeros().toPlainString());
                } catch (NumberFormatException _) {
                    yield Objects.hash(kind, numberText);
                }
            }
        };
    }

    @Override
    public String toString() {
        return switch (kind) {
            case STRING -> "JsonPrimitive[string=" + stringValue + "]";
            case NUMBER -> "JsonPrimitive[number=" + numberText + "]";
            case BOOLEAN -> "JsonPrimitive[boolean=" + booleanValue + "]";
        };
    }
}
