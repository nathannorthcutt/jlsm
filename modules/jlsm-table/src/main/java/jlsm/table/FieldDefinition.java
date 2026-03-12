package jlsm.table;

import java.util.Objects;

/**
 * Defines a named field and its type within a {@link JlsmSchema}.
 *
 * @param name the field name; must not be null
 * @param type the field type; must not be null
 */
public record FieldDefinition(String name, FieldType type) {
    public FieldDefinition {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
    }
}
