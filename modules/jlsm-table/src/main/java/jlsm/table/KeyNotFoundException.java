package jlsm.table;

import java.io.IOException;
import java.util.Objects;

/**
 * Thrown when a {@link JlsmTable} operation requires a key that does not exist.
 */
public final class KeyNotFoundException extends IOException {

    private final String key;

    /**
     * Constructs a new KeyNotFoundException for the given key.
     *
     * @param key the key that was not found; must not be null
     */
    public KeyNotFoundException(String key) {
        super("Key not found: " + key);
        Objects.requireNonNull(key, "key must not be null");
        assert key != null : "key must not be null";
        this.key = key;
    }

    /**
     * Returns the key that was not found.
     *
     * @return the key; never null
     */
    public String getKey() {
        return key;
    }
}
