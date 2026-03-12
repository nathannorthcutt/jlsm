package jlsm.table;

import java.util.Objects;

/**
 * A key-document pair returned from range iteration over a {@link JlsmTable}.
 *
 * @param <K> the key type (e.g., {@link String} or {@link Long})
 * @param key the entry's key; never null
 * @param document the entry's document; never null
 */
public record TableEntry<K>(K key, JlsmDocument document) {
    public TableEntry {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(document, "document must not be null");
    }
}
