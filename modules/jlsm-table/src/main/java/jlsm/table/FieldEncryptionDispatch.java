package jlsm.table;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import jlsm.encryption.AesGcmEncryptor;
import jlsm.encryption.AesSivEncryptor;
import jlsm.encryption.BoldyrevaOpeEncryptor;
import jlsm.encryption.EncryptionKeyHolder;
import jlsm.encryption.EncryptionSpec;
import jlsm.table.FieldDefinition;
import jlsm.table.JlsmSchema;

/**
 * Dispatch table mapping field positions to their encryption/decryption functions. Constructed once
 * at serializer creation time; immutable and thread-safe after construction.
 *
 * <p>
 * For each field in the schema, checks its {@link EncryptionSpec} and builds the appropriate
 * encryptor/decryptor pair. If the key holder is {@code null}, all entries are {@code null} (no
 * encryption).
 *
 * <p>
 * Governed by: .decisions/field-encryption-api-design/adr.md
 */
public final class FieldEncryptionDispatch {

    private final FieldEncryptor[] encryptors;
    private final FieldDecryptor[] decryptors;

    /**
     * Encrypts a byte array field value.
     */
    @FunctionalInterface
    public interface FieldEncryptor {
        byte[] encrypt(byte[] plaintext);
    }

    /**
     * Decrypts a byte array field value.
     */
    @FunctionalInterface
    public interface FieldDecryptor {
        byte[] decrypt(byte[] ciphertext);
    }

    /**
     * Constructs a dispatch table for the given schema and key holder.
     *
     * @param schema the schema describing the document structure; must not be null
     * @param keyHolder the key holder providing encryption keys; may be null (no encryption)
     */
    public FieldEncryptionDispatch(JlsmSchema schema, EncryptionKeyHolder keyHolder) {
        Objects.requireNonNull(schema, "schema must not be null");

        final List<FieldDefinition> fields = schema.fields();
        final int fieldCount = fields.size();
        this.encryptors = new FieldEncryptor[fieldCount];
        this.decryptors = new FieldDecryptor[fieldCount];

        if (keyHolder == null) {
            // No encryption — all entries remain null
            return;
        }

        for (int i = 0; i < fieldCount; i++) {
            final FieldDefinition fd = fields.get(i);
            final EncryptionSpec spec = fd.encryption();

            switch (spec) {
                case EncryptionSpec.None _ -> {
                    // No encryption for this field
                }
                case EncryptionSpec.Deterministic _ -> {
                    // AES-SIV requires a 64-byte key. If the table key is 32 bytes,
                    // derive a 64-byte key by repeating it.
                    final AesSivEncryptor siv;
                    if (keyHolder.keyLength() == 32) {
                        final byte[] halfKey = keyHolder.getKeyBytes();
                        final byte[] sivKey = new byte[64];
                        System.arraycopy(halfKey, 0, sivKey, 0, 32);
                        System.arraycopy(halfKey, 0, sivKey, 32, 32);
                        Arrays.fill(halfKey, (byte) 0);
                        final EncryptionKeyHolder sivKeyHolder = EncryptionKeyHolder.of(sivKey);
                        siv = new AesSivEncryptor(sivKeyHolder);
                        sivKeyHolder.close(); // encryptor copied key material at construction
                    } else {
                        siv = new AesSivEncryptor(keyHolder);
                    }
                    final byte[] associatedData = fd.name().getBytes(StandardCharsets.UTF_8);
                    encryptors[i] = plaintext -> siv.encrypt(plaintext, associatedData);
                    decryptors[i] = ciphertext -> siv.decrypt(ciphertext, associatedData);
                }
                case EncryptionSpec.OrderPreserving _ -> {
                    // Validate field type is narrow enough for lossless OPE round-trip
                    validateOpeFieldType(fd);
                    // Derive OPE domain/range from the field type for optimal recursion depth.
                    final long[] bounds = deriveOpeBounds(fd.type());
                    final BoldyrevaOpeEncryptor ope = new BoldyrevaOpeEncryptor(keyHolder,
                            bounds[0], bounds[1]);
                    final int maxBytes = opeMaxBytes(fd.type());
                    encryptors[i] = plaintext -> opeEncryptTyped(ope, plaintext, maxBytes);
                    decryptors[i] = ciphertext -> opeDecryptTyped(ope, ciphertext, maxBytes);
                }
                case EncryptionSpec.DistancePreserving _ -> {
                    // DistancePreserving operates on float[] vectors, not byte[].
                    // Handled separately in the serializer. Leave null for byte dispatch.
                }
                case EncryptionSpec.Opaque _ -> {
                    // AES-GCM requires a 32-byte key. If the table key is 64 bytes,
                    // derive a 32-byte sub-key from the first half.
                    final AesGcmEncryptor gcm;
                    if (keyHolder.keyLength() == 64) {
                        final byte[] fullKey = keyHolder.getKeyBytes();
                        final byte[] gcmKey = Arrays.copyOfRange(fullKey, 0, 32);
                        Arrays.fill(fullKey, (byte) 0);
                        final EncryptionKeyHolder gcmKeyHolder = EncryptionKeyHolder.of(gcmKey);
                        gcm = new AesGcmEncryptor(gcmKeyHolder);
                        gcmKeyHolder.close(); // encryptor copied key material at construction
                    } else {
                        gcm = new AesGcmEncryptor(keyHolder);
                    }
                    encryptors[i] = gcm::encrypt;
                    decryptors[i] = gcm::decrypt;
                }
            }
        }
    }

    /**
     * Returns the encryptor for the field at the given index, or {@code null} if no encryption is
     * configured.
     *
     * @param fieldIndex the zero-based field index
     * @return the encryptor, or null
     */
    public FieldEncryptor encryptorFor(int fieldIndex) {
        assert fieldIndex >= 0 && fieldIndex < encryptors.length
                : "fieldIndex out of bounds: " + fieldIndex;
        return encryptors[fieldIndex];
    }

    /**
     * Returns the decryptor for the field at the given index, or {@code null} if no encryption is
     * configured.
     *
     * @param fieldIndex the zero-based field index
     * @return the decryptor, or null
     */
    public FieldDecryptor decryptorFor(int fieldIndex) {
        assert fieldIndex >= 0 && fieldIndex < decryptors.length
                : "fieldIndex out of bounds: " + fieldIndex;
        return decryptors[fieldIndex];
    }

    /**
     * Validates that the field type is narrow enough for lossless OPE round-trip. OPE is capped at
     * {@code MAX_OPE_BYTES=2}, so types wider than 2 bytes would suffer silent data truncation.
     */
    private static void validateOpeFieldType(FieldDefinition fd) {
        final FieldType type = fd.type();
        if (type instanceof FieldType.Primitive p) {
            switch (p) {
                case INT8, INT16 -> {
                    /* allowed — fits in 2 bytes */ }
                case INT32, INT64, TIMESTAMP -> throw new IllegalArgumentException(
                        "OrderPreserving encryption on " + p + " field '" + fd.name()
                                + "' is not supported — OPE is limited to 2-byte values; "
                                + "wider types would suffer silent data truncation on round-trip");
                default -> throw new IllegalArgumentException("OrderPreserving encryption on " + p
                        + " field '" + fd.name() + "' is not supported");
            }
        } else if (type instanceof FieldType.BoundedString bs) {
            if (bs.maxLength() > MAX_OPE_BYTES) {
                throw new IllegalArgumentException(
                        "OrderPreserving encryption on BoundedString(maxLength=" + bs.maxLength()
                                + ") field '" + fd.name()
                                + "' is not supported — maxLength exceeds OPE limit of "
                                + MAX_OPE_BYTES);
            }
        } else {
            throw new IllegalArgumentException("OrderPreserving encryption on " + type + " field '"
                    + fd.name() + "' is not supported");
        }
    }

    // -- OPE type-aware helpers -----------------------------------------------

    /**
     * Type-aware OPE encryption. Encodes the plaintext bytes as a positive long in the natural
     * domain of the field type (e.g., [1..256] for INT8), encrypts with OPE, and returns the
     * ciphertext.
     *
     * <p>
     * The ciphertext format is: {@code [1-byte original-length][8-byte encrypted-long]}. The
     * original length byte enables exact round-trip for variable-length types (strings).
     */
    private static byte[] opeEncryptTyped(BoldyrevaOpeEncryptor ope, byte[] plaintext,
            int maxBytes) {
        assert plaintext != null : "plaintext must not be null";
        final int useBytes = Math.min(plaintext.length, maxBytes);
        final long value = bytesToUnsignedBE(plaintext, useBytes) + 1; // +1: OPE domain starts at 1
        final long encrypted = ope.encrypt(value);
        final byte[] result = new byte[9]; // 1 byte length + 8 bytes encrypted long
        result[0] = (byte) plaintext.length;
        for (int i = 0; i < 8; i++) {
            result[1 + i] = (byte) (encrypted >>> (56 - i * 8));
        }
        return result;
    }

    /**
     * Type-aware OPE decryption. Reads the original length from byte 0, decrypts the 8-byte
     * encrypted long, and converts back to a byte array of the original length.
     */
    private static byte[] opeDecryptTyped(BoldyrevaOpeEncryptor ope, byte[] ciphertext,
            int maxBytes) {
        assert ciphertext != null && ciphertext.length == 9 : "OPE ciphertext must be 9 bytes";
        final int originalLen = ciphertext[0] & 0xFF;
        long encValue = 0;
        for (int i = 0; i < 8; i++) {
            encValue |= ((long) (ciphertext[1 + i] & 0xFF)) << (56 - i * 8);
        }
        long decrypted = ope.decrypt(encValue) - 1; // undo +1 offset
        final int useBytes = Math.min(originalLen, maxBytes);
        // Convert the unsigned long back to bytes in the original representation
        final byte[] result = new byte[originalLen];
        for (int i = useBytes - 1; i >= 0; i--) {
            result[i] = (byte) (decrypted & 0xFF);
            decrypted >>>= 8;
        }
        return result;
    }

    /**
     * Interprets the first {@code count} bytes of data as an unsigned big-endian integer.
     */
    private static long bytesToUnsignedBE(byte[] data, int count) {
        assert data != null : "data must not be null";
        long result = 0;
        for (int i = 0; i < count; i++) {
            result = (result << 8) | (data[i] & 0xFFL);
        }
        return result;
    }

    // -- OPE domain derivation -----------------------------------------------

    /** Default range/domain ratio. */
    private static final long OPE_RANGE_RATIO = 10L;

    /**
     * Maximum OPE byte count. The Boldyreva scheme's hypergeometric sampling is O(range/2) per
     * recursion level, so the domain (256^bytes) must stay small enough for practical
     * encrypt/decrypt times. At 2 bytes (domain 65536, range 655360), each level does ~300K AES
     * operations — acceptable. At 3 bytes (domain 16M), each level would do ~80M operations — too
     * slow for synchronous use.
     */
    private static final int MAX_OPE_BYTES = 2;

    /**
     * Returns the byte count for OPE encoding of the given field type. This determines how many
     * bytes of the serialized value are used for OPE ordering. Capped at {@link #MAX_OPE_BYTES} to
     * keep OPE performance practical.
     *
     * <p>
     * For types wider than MAX_OPE_BYTES (INT32, INT64, TIMESTAMP), only the most-significant bytes
     * participate in OPE ordering. Values that differ only in their lower bytes may map to the same
     * ciphertext order.
     */
    private static int opeMaxBytes(FieldType type) {
        if (type instanceof FieldType.Primitive p) {
            return switch (p) {
                case INT8 -> 1;
                case INT16 -> Math.min(2, MAX_OPE_BYTES);
                case INT32 -> Math.min(4, MAX_OPE_BYTES);
                case INT64, TIMESTAMP -> Math.min(8, MAX_OPE_BYTES);
                default -> throw new IllegalArgumentException(
                        "OrderPreserving encryption is not supported for field type " + p);
            };
        } else if (type instanceof FieldType.BoundedString bs) {
            return Math.min(bs.maxLength(), MAX_OPE_BYTES);
        }
        throw new IllegalArgumentException(
                "OrderPreserving encryption is not supported for field type " + type);
    }

    /**
     * Derives the OPE domain and range from the field type.
     *
     * <p>
     * The domain is {@code 256^byteCount} — the number of distinct unsigned values representable in
     * the field's byte count. We add 1 because OPE operates on [1..domain] and the +1 offset in
     * encryption maps unsigned 0 to OPE value 1.
     *
     * <p>
     * Range is {@code domain * 10}, capped at Long.MAX_VALUE.
     *
     * @param type the field type; must be OPE-compatible
     * @return a two-element long array {@code [domain, range]}
     * @throws IllegalArgumentException if the field type is not compatible with OPE
     */
    static long[] deriveOpeBounds(FieldType type) {
        assert type != null : "type must not be null";

        final int byteCount = opeMaxBytes(type);

        // Domain = 256^byteCount (number of distinct unsigned values for byteCount bytes)
        long domain = 1L;
        for (int i = 0; i < byteCount; i++) {
            domain *= 256L;
            if (domain > (Long.MAX_VALUE / OPE_RANGE_RATIO) - 1) {
                // Cap to prevent range overflow
                domain = (Long.MAX_VALUE / OPE_RANGE_RATIO) - 1;
                break;
            }
        }
        assert domain >= 1 : "domain must be >= 1";

        final long range = domain * OPE_RANGE_RATIO;
        assert range > domain : "range must be > domain";

        return new long[]{ domain, range };
    }
}
