package jlsm.table;

import java.io.IOException;
import java.util.Objects;

/**
 * Thrown when a {@link JlsmTable#create} call encounters a key that already exists.
 */
public final class DuplicateKeyException extends IOException {

    private final String key;

    /**
     * Constructs a new DuplicateKeyException for the given key.
     *
     * @param key the key that caused the conflict; must not be null
     */
    public DuplicateKeyException(String key) {
        super("Duplicate key: " + key);
        Objects.requireNonNull(key, "key must not be null");
        assert key != null : "key must not be null";
        this.key = key;
    }

    /**
     * Returns the key that caused the duplicate conflict.
     *
     * @return the key; never null
     */
    public String getKey() {
        return key;
    }
}
