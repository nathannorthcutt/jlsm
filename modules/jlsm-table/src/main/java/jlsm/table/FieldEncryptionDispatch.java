package jlsm.table;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

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
        assert schema != null : "schema must not be null";

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
                    final EncryptionKeyHolder sivKeyHolder;
                    if (keyHolder.keyLength() == 32) {
                        final byte[] halfKey = keyHolder.getKeyBytes();
                        final byte[] sivKey = new byte[64];
                        System.arraycopy(halfKey, 0, sivKey, 0, 32);
                        System.arraycopy(halfKey, 0, sivKey, 32, 32);
                        Arrays.fill(halfKey, (byte) 0);
                        sivKeyHolder = EncryptionKeyHolder.of(sivKey);
                    } else {
                        sivKeyHolder = keyHolder;
                    }
                    final AesSivEncryptor siv = new AesSivEncryptor(sivKeyHolder);
                    final byte[] associatedData = fd.name().getBytes(StandardCharsets.UTF_8);
                    encryptors[i] = plaintext -> siv.encrypt(plaintext, associatedData);
                    decryptors[i] = ciphertext -> siv.decrypt(ciphertext, associatedData);
                }
                case EncryptionSpec.OrderPreserving _ -> {
                    // OrderPreserving requires domain/range configuration.
                    // For generic byte-level dispatch, use long-based OPE.
                    final BoldyrevaOpeEncryptor ope = new BoldyrevaOpeEncryptor(keyHolder,
                            Long.MAX_VALUE / 2, Long.MAX_VALUE);
                    encryptors[i] = plaintext -> opeEncryptBytes(ope, plaintext);
                    decryptors[i] = ciphertext -> opeDecryptBytes(ope, ciphertext);
                }
                case EncryptionSpec.DistancePreserving _ -> {
                    // DistancePreserving operates on float[] vectors, not byte[].
                    // Handled separately in the serializer. Leave null for byte dispatch.
                }
                case EncryptionSpec.Opaque _ -> {
                    // AES-GCM requires a 32-byte key. If the table key is 64 bytes,
                    // derive a 32-byte sub-key from the first half.
                    final EncryptionKeyHolder gcmKeyHolder;
                    if (keyHolder.keyLength() == 64) {
                        final byte[] fullKey = keyHolder.getKeyBytes();
                        final byte[] gcmKey = Arrays.copyOfRange(fullKey, 0, 32);
                        Arrays.fill(fullKey, (byte) 0);
                        gcmKeyHolder = EncryptionKeyHolder.of(gcmKey);
                    } else {
                        gcmKeyHolder = keyHolder;
                    }
                    final AesGcmEncryptor gcm = new AesGcmEncryptor(gcmKeyHolder);
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

    // -- OPE byte helpers -------------------------------------------------

    /**
     * Encodes a byte[] as a positive long, encrypts with OPE, returns the encrypted long as bytes.
     */
    private static byte[] opeEncryptBytes(BoldyrevaOpeEncryptor ope, byte[] plaintext) {
        final long value = bytesToPositiveLong(plaintext);
        final long encrypted = ope.encrypt(value);
        return longToBytes(encrypted);
    }

    /**
     * Decrypts OPE ciphertext bytes back to the original byte[] representation.
     */
    private static byte[] opeDecryptBytes(BoldyrevaOpeEncryptor ope, byte[] ciphertext) {
        final long encValue = bytesTo8ByteLong(ciphertext);
        final long decrypted = ope.decrypt(encValue);
        return positiveLongToBytes(decrypted);
    }

    /**
     * Converts up to 6 bytes of data into a positive long in [1..Long.MAX_VALUE/2]. Format: first
     * byte = original length, followed by up to 6 data bytes packed into a long.
     */
    private static long bytesToPositiveLong(byte[] data) {
        assert data != null : "data must not be null";
        if (data.length > 6) {
            throw new IllegalArgumentException(
                    "OPE byte encryption supports at most 6 bytes of data, got " + data.length);
        }
        long result = 1; // ensure positive, minimum = 1
        result += ((long) data.length) << 48;
        for (int i = 0; i < data.length; i++) {
            result += ((long) (data[i] & 0xFF)) << (40 - i * 8);
        }
        return result;
    }

    private static byte[] positiveLongToBytes(long value) {
        value -= 1; // undo the +1 offset
        final int len = (int) ((value >>> 48) & 0xFF);
        final byte[] result = new byte[len];
        for (int i = 0; i < len; i++) {
            result[i] = (byte) ((value >>> (40 - i * 8)) & 0xFF);
        }
        return result;
    }

    private static byte[] longToBytes(long value) {
        final byte[] buf = new byte[8];
        for (int i = 0; i < 8; i++) {
            buf[i] = (byte) (value >>> (56 - i * 8));
        }
        return buf;
    }

    private static long bytesTo8ByteLong(byte[] buf) {
        assert buf.length == 8 : "expected 8 bytes for long";
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= ((long) (buf[i] & 0xFF)) << (56 - i * 8);
        }
        return result;
    }
}
