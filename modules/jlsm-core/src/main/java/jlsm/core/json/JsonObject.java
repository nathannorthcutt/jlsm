package jlsm.core.json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable, insertion-ordered JSON object.
 *
 * <p>
 * Keys are unique, non-null strings; duplicate keys are rejected at construction. Values are
 * non-null {@link JsonValue} instances. Iteration order matches insertion order (as specified by
 * JSON interchange practice and RFC 8259 interoperability recommendations).
 *
 * <p>
 * Instances are created via the static factory {@link #of(Map)} or the {@link Builder}. Both
 * enforce non-null keys and values and reject duplicates.
 *
 * @spec F15.R9 — Map-like access: get, containsKey, keys, size, entrySet, getOrDefault
 * @spec F15.R10 — preserves insertion order
 * @spec F15.R11 — rejects duplicate keys at construction
 * @spec F15.R13 — deeply immutable after construction
 * @spec F15.R14 — structural deep equality
 * @spec F15.R15 — static factory/builder only, no public constructor
 */
public final class JsonObject implements JsonValue {

    private static final JsonObject EMPTY = new JsonObject(Map.of());

    private final Map<String, JsonValue> members;

    private JsonObject(Map<String, JsonValue> members) {
        this.members = members;
    }

    /**
     * Creates a {@link JsonObject} from an existing map.
     *
     * <p>
     * The map is defensively copied. Insertion order is preserved if the source map has a defined
     * iteration order (e.g., {@link LinkedHashMap}).
     *
     * @param members the key-value pairs; must not be null, keys and values must not be null
     * @return a new immutable JsonObject
     * @throws NullPointerException if members, any key, or any value is null
     */
    // @spec serialization.simd-jsonl.R15 — keys must be non-null and non-blank; blank keys are rejected eagerly
    // (stricter than RFC 8259; see F15.R25 for the list of stricter-than-RFC parser behaviors).
    public static JsonObject of(Map<String, JsonValue> members) {
        Objects.requireNonNull(members, "members must not be null");
        var copy = new LinkedHashMap<String, JsonValue>(members.size());
        for (var entry : members.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "key must not be null");
            if (key.isBlank()) {
                throw new IllegalArgumentException("key must not be blank");
            }
            JsonValue value = Objects.requireNonNull(entry.getValue(),
                    "value must not be null for key: " + key);
            copy.put(key, value);
        }
        return new JsonObject(Collections.unmodifiableMap(copy));
    }

    /**
     * Returns an empty JSON object.
     *
     * @return an empty JsonObject
     */
    public static JsonObject empty() {
        return EMPTY;
    }

    /**
     * Returns a new {@link Builder} for constructing a JsonObject incrementally.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the value associated with the given key, or {@code null} if absent.
     *
     * @param key the key; must not be null
     * @return the associated value, or null if absent
     * @throws NullPointerException if key is null
     */
    public JsonValue get(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return members.get(key);
    }

    /**
     * Returns the value associated with the given key, or the default if absent.
     *
     * @param key the key; must not be null
     * @param defaultValue the value to return if the key is absent; may be null
     * @return the associated value, or defaultValue if absent
     * @throws NullPointerException if key is null
     */
    public JsonValue getOrDefault(String key, JsonValue defaultValue) {
        Objects.requireNonNull(key, "key must not be null");
        return members.getOrDefault(key, defaultValue);
    }

    /**
     * Returns {@code true} if this object contains the given key.
     *
     * @param key the key; must not be null
     * @return true if present
     * @throws NullPointerException if key is null
     */
    public boolean containsKey(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return members.containsKey(key);
    }

    /**
     * Returns the keys in insertion order.
     *
     * @return an unmodifiable list of keys in insertion order
     */
    public List<String> keys() {
        return List.copyOf(members.keySet());
    }

    /**
     * Returns the number of key-value pairs.
     *
     * @return the size
     */
    public int size() {
        return members.size();
    }

    /**
     * Returns an unmodifiable set of entries in insertion order.
     *
     * @return the entry set
     */
    public Set<Map.Entry<String, JsonValue>> entrySet() {
        return Collections.unmodifiableSet(members.entrySet());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof JsonObject other))
            return false;
        return members.equals(other.members);
    }

    @Override
    public int hashCode() {
        return members.hashCode();
    }

    @Override
    public String toString() {
        return "JsonObject" + members;
    }

    /**
     * Builder for constructing {@link JsonObject} instances incrementally.
     *
     * <p>
     * Rejects duplicate keys eagerly at {@link #put} time. Keys and values must not be null.
     *
     * @spec F15.R48 — single-use builder, throws after build()
     */
    public static final class Builder {

        private final LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
        private boolean built;

        private Builder() {
        }

        /**
         * Adds a key-value pair. Rejects duplicate keys.
         *
         * @param key the key; must not be null
         * @param value the value; must not be null
         * @return this builder
         * @throws NullPointerException if key or value is null
         * @throws IllegalArgumentException if the key is already present
         */
        // @spec serialization.simd-jsonl.R15 — keys must be non-null and non-blank
        public Builder put(String key, JsonValue value) {
            if (built) {
                throw new IllegalStateException(
                        "builder already used — cannot add entries after build()");
            }
            Objects.requireNonNull(key, "key must not be null");
            if (key.isBlank()) {
                throw new IllegalArgumentException("key must not be blank");
            }
            Objects.requireNonNull(value, "value must not be null");
            if (members.containsKey(key)) {
                throw new IllegalArgumentException("duplicate key: " + key);
            }
            members.put(key, value);
            return this;
        }

        /**
         * Builds the {@link JsonObject}. The builder may not be reused after this call.
         *
         * @return a new immutable JsonObject
         */
        public JsonObject build() {
            if (built) {
                throw new IllegalStateException("builder already used — cannot call build() again");
            }
            built = true;
            return new JsonObject(Collections.unmodifiableMap(new LinkedHashMap<>(members)));
        }
    }
}
