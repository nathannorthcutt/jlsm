package jlsm.table.internal;

import jlsm.encryption.EncryptionSpec;
import jlsm.table.FieldDefinition;
import jlsm.table.FieldType;

import java.util.Objects;

/**
 * Contract: Validates ciphertext structural integrity for pre-encrypted documents. Each encryption
 * scheme has known expansion sizes; ciphertext that doesn't match is rejected as corrupt.
 *
 * Governed by: .decisions/pre-encrypted-document-signaling/adr.md
 */
public final class CiphertextValidator {

    /** Minimum ciphertext length for AES-SIV (16-byte synthetic IV). */
    private static final int AES_SIV_MIN_LENGTH = 16;

    /** Minimum ciphertext length for AES-GCM (12-byte IV + 16-byte tag). */
    private static final int AES_GCM_MIN_LENGTH = 28;

    /**
     * Exact ciphertext length for Boldyreva OPE: 1-byte length prefix + 8-byte encrypted long +
     * 16-byte detached HMAC-SHA256 authentication tag. See F03.R72.
     */
    // @spec encryption.primitives-variants.R21,R48, F41.R22 — 25-byte OPE ciphertext with detached
    // MAC
    private static final int OPE_EXACT_LENGTH = 25;

    private CiphertextValidator() {
        // Utility class
    }

    /**
     * Validates that the given ciphertext is structurally valid for the field's encryption spec.
     *
     * @param field the field definition (carries EncryptionSpec and FieldType)
     * @param ciphertext the raw ciphertext bytes to validate
     * @throws IllegalArgumentException if the ciphertext is structurally invalid
     * @throws NullPointerException if field or ciphertext is null
     */
    public static void validate(FieldDefinition field, byte[] ciphertext) {
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(ciphertext, "ciphertext must not be null");

        final String fieldName = field.name();
        final int length = ciphertext.length;

        if (length == 0) {
            throw new IllegalArgumentException(
                    "Field '" + fieldName + "': ciphertext must not be empty");
        }

        switch (field.encryption()) {
            case EncryptionSpec.None _ -> throw new IllegalArgumentException(
                    "Field '" + fieldName + "': cannot validate ciphertext for unencrypted field "
                            + "(EncryptionSpec.None)");

            case EncryptionSpec.Deterministic _ -> {
                if ( length < AES_SIV_MIN_LENGTH) {
                    throw new IllegalArgumentException("Field '" + fieldName
                            + "' (Deterministic/AES-SIV): ciphertext length " + length
                            + " is less than minimum " + AES_SIV_MIN_LENGTH + " bytes");
                }
            }

            case EncryptionSpec.Opaque _ -> {
                if ( length < AES_GCM_MIN_LENGTH) {
                    throw new IllegalArgumentException("Field '" + fieldName
                            + "' (Opaque/AES-GCM): ciphertext length " + length
                            + " is less than minimum " + AES_GCM_MIN_LENGTH + " bytes");
                }
            }

            case EncryptionSpec.OrderPreserving _ -> {
                if ( length != OPE_EXACT_LENGTH) {
                    throw new IllegalArgumentException(
                            "Field '" + fieldName + "' (OrderPreserving/OPE): ciphertext length "
                                    + length + " must be exactly " + OPE_EXACT_LENGTH + " bytes");
                }
            }

            case EncryptionSpec.DistancePreserving _ -> {
                // @spec encryption.primitives-variants.R49, F41.R22 — DCPE layout = 8B seed + 4N
                // values + 16B MAC
                if (!( field.type() instanceof FieldType.VectorType vt)) {
                    throw new IllegalArgumentException("Field '" + fieldName
                            + "' (DistancePreserving): field type must be VectorType, got "
                            + field.type());
                }
                final int expectedLength = 8 + vt.dimensions() * 4 + 16;
                if (length != expectedLength) {
                    throw new IllegalArgumentException("Field '" + fieldName
                            + "' (DistancePreserving/DCPE): ciphertext length " + length
                            + " must be exactly " + expectedLength + " bytes (8B seed + "
                            + vt.dimensions() + " dimensions * 4 + 16B MAC tag)");
                }
            }
        }
    }
}
