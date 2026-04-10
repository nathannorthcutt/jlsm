package jlsm.core.json;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * An immutable JSON array.
 *
 * <p>
 * Elements are non-null {@link JsonValue} instances accessed by index. The array supports
 * {@link Stream} composition via {@link #stream()}.
 *
 * <p>
 * Instances are created via the static factory {@link #of(List)}. The factory validates that no
 * elements are null and makes a defensive copy.
 *
 * @spec F15.R12 — List-like access and Stream composability
 * @spec F15.R13 — deep immutability
 * @spec F15.R14 — deep equality
 * @spec F15.R15 — composable with Stream APIs
 */
public final class JsonArray implements JsonValue {

    private static final JsonArray EMPTY = new JsonArray(List.of());

    private final List<JsonValue> elements;

    private JsonArray(List<JsonValue> elements) {
        this.elements = elements;
    }

    /**
     * Creates a {@link JsonArray} from a list of values.
     *
     * <p>
     * The list is defensively copied. All elements must be non-null.
     *
     * @param elements the values; must not be null, elements must not be null
     * @return a new immutable JsonArray
     * @throws NullPointerException if elements or any element is null
     */
    public static JsonArray of(List<JsonValue> elements) {
        Objects.requireNonNull(elements, "elements must not be null");
        // List.copyOf validates non-null elements
        return new JsonArray(List.copyOf(elements));
    }

    /**
     * Creates a {@link JsonArray} from varargs values.
     *
     * <p>
     * All elements must be non-null.
     *
     * @param elements the values; must not be null, elements must not be null
     * @return a new immutable JsonArray
     * @throws NullPointerException if elements array or any element is null
     */
    public static JsonArray of(JsonValue... elements) {
        Objects.requireNonNull(elements, "elements must not be null");
        return new JsonArray(List.copyOf(Arrays.asList(elements)));
    }

    /**
     * Returns an empty JSON array.
     *
     * @return an empty JsonArray
     */
    public static JsonArray empty() {
        return EMPTY;
    }

    /**
     * Returns the element at the given index.
     *
     * @param index the zero-based index
     * @return the element
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public JsonValue get(int index) {
        return elements.get(index);
    }

    /**
     * Returns the number of elements.
     *
     * @return the size
     */
    public int size() {
        return elements.size();
    }

    /**
     * Returns a sequential {@link Stream} over the elements.
     *
     * @return a stream of JsonValue elements
     */
    public Stream<JsonValue> stream() {
        return elements.stream();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof JsonArray other))
            return false;
        return elements.equals(other.elements);
    }

    @Override
    public int hashCode() {
        return elements.hashCode();
    }

    @Override
    public String toString() {
        return "JsonArray" + elements;
    }
}
