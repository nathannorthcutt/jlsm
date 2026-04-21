package jlsm.table;

import jlsm.encryption.EncryptionSpec;

import java.util.Objects;

/**
 * Defines a named field and its type within a {@link JlsmSchema}.
 *
 * @param name the field name; must not be null
 * @param type the field type; must not be null
 * @param encryption the encryption specification for this field; must not be null
 */
// @spec schema.schema-field-definition.R1 — public record in jlsm.table with (name, type,
// encryption) components
public record FieldDefinition(String name, FieldType type, EncryptionSpec encryption) {

    // @spec schema.schema-field-definition.R2 — compact constructor rejects null name, type, or
    // encryption
    /**
     * Compact constructor — validates all components are non-null.
     */
    public FieldDefinition {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(encryption, "encryption must not be null");
    }

    // @spec schema.schema-field-definition.R3 — two-argument constructor defaults encryption to
    // EncryptionSpec.NONE
    /**
     * Backward-compatible constructor that defaults encryption to {@link EncryptionSpec#NONE}.
     *
     * @param name the field name; must not be null
     * @param type the field type; must not be null
     */
    public FieldDefinition(String name, FieldType type) {
        this(name, type, EncryptionSpec.NONE);
    }
}
