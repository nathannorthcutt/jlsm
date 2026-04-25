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

    /**
     * Minimum envelope length for AES-SIV (Deterministic): 4-byte BE DEK version prefix + 16-byte
     * synthetic IV. WU-4 bumps the previous 16-byte minimum by 4 bytes for the version prefix per
     * encryption.ciphertext-envelope.R1 / R1b.
     */
    private static final int AES_SIV_MIN_LENGTH = 20;

    /**
     * Minimum envelope length for AES-GCM (Opaque): 4-byte BE DEK version prefix + 12-byte IV +
     * 16-byte tag. WU-4 bumps the previous 28-byte minimum by 4 bytes for the version prefix.
     */
    private static final int AES_GCM_MIN_LENGTH = 32;

    /**
     * Exact envelope length for Boldyreva OPE: 4-byte BE DEK version prefix + 1-byte length prefix
     * + 8-byte encrypted long + 16-byte detached HMAC-SHA256 tag. WU-4 bumps the previous 25-byte
     * fixed length by 4 bytes for the version prefix per encryption.ciphertext-envelope .R1 / R1b.
     */
    // @spec encryption.primitives-variants.R21,R48, encryption.ciphertext-envelope.R1,R1b —
    // 29-byte OPE envelope with detached MAC and 4B DEK version prefix
    private static final int OPE_EXACT_LENGTH = 29;

    /**
     * Fixed overhead for DCPE (DistancePreserving) envelope: 4-byte BE DEK version prefix + 8-byte
     * seed + 16-byte HMAC tag = 28 bytes; total length = 28 + 4 * dimensions per
     * encryption.ciphertext-envelope.R1 / R1b.
     */
    private static final int DCPE_FIXED_OVERHEAD = 28;

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
    // @spec encryption.ciphertext-envelope.R1b,R3a — reader rejects blobs with inconsistent byte
    // count per variant formula; caller-supplied pre-encrypted field values must conform to R1 at
    // the byte level (per-variant length checks enforced below)
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
                // @spec encryption.primitives-variants.R49, encryption.ciphertext-envelope.R1 —
                // DCPE layout = 8B seed + 4N
                // values + 16B MAC
                if (!( field.type() instanceof FieldType.VectorType vt)) {
                    throw new IllegalArgumentException("Field '" + fieldName
                            + "' (DistancePreserving): field type must be VectorType, got "
                            + field.type());
                }
                // 4B BE DEK version prefix + 8B seed + dim*4 ciphertext + 16B MAC = 28 + 4N
                final int expectedLength = DCPE_FIXED_OVERHEAD + vt.dimensions() * 4;
                if (length != expectedLength) {
                    throw new IllegalArgumentException("Field '" + fieldName
                            + "' (DistancePreserving/DCPE): ciphertext length " + length
                            + " must be exactly " + expectedLength
                            + " bytes (4B DEK version + 8B seed + " + vt.dimensions()
                            + " dimensions * 4 + 16B MAC tag)");
                }
            }
        }

        // @spec encryption.ciphertext-envelope.R1c,R2 — symmetric writer-side guard for the
        // 4-byte BE DEK version prefix. Per-variant length checks above guarantee at least
        // VERSION_PREFIX_LENGTH (4) bytes are present for every encrypted variant; decode the
        // prefix and reject 0 / negative versions before the bytes are persisted, mirroring
        // EnvelopeCodec.prefixVersion's writer-side guard and EnvelopeCodec.parseVersion's
        // reader-side guard. Without this check, a corrupt pre-encrypted envelope would be
        // admitted at write time and surface only at read time as an UncheckedIOException —
        // a write-side spec violation that produces poisoned-on-disk entries.
        final int dekVersion = ((ciphertext[0] & 0xFF) << 24) | ((ciphertext[1] & 0xFF) << 16)
                | ((ciphertext[2] & 0xFF) << 8) | (ciphertext[3] & 0xFF);
        if (dekVersion <= 0) {
            throw new IllegalArgumentException("Field '" + fieldName
                    + "': ciphertext DEK version prefix must be a positive integer, got "
                    + dekVersion + " (corrupt envelope)");
        }
    }
}
