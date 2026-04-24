package jlsm.table;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jlsm.encryption.AesGcmEncryptor;
import jlsm.encryption.AesSivEncryptor;
import jlsm.encryption.BoldyrevaOpeEncryptor;
import jlsm.encryption.DcpeSapEncryptor;
import jlsm.encryption.internal.OffHeapKeyMaterial;
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
     * Per-DCPE-field encryptor. DCPE operates on {@code float[]}, not {@code byte[]}, so it does
     * not fit the generic {@link FieldEncryptor} byte-in/byte-out interface. The serializer reads
     * this array directly and invokes {@link DcpeSapEncryptor#encrypt} inline during vector
     * encode/decode. {@code null} for non-DCPE fields and for any field when no key holder is
     * provided.
     */
    // @spec encryption.primitives-dispatch.R7, R50 — DCPE handled by serializer directly, not
    // byte-level dispatch
    private final DcpeSapEncryptor[] dcpeEncryptors;
    private final byte[][] dcpeAssociatedData;

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
    public FieldEncryptionDispatch(JlsmSchema schema, OffHeapKeyMaterial keyHolder) {
        Objects.requireNonNull(schema, "schema must not be null");

        final List<FieldDefinition> fields = schema.fields();
        final int fieldCount = fields.size();
        this.encryptors = new FieldEncryptor[fieldCount];
        this.decryptors = new FieldDecryptor[fieldCount];
        this.dcpeEncryptors = new DcpeSapEncryptor[fieldCount];
        this.dcpeAssociatedData = new byte[fieldCount][];

        if (keyHolder == null) {
            // @spec encryption.primitives-dispatch.R2 — without a key holder, no field receives an
            // encryptor/decryptor.
            // A DCPE field in this configuration cannot be serialized by the library — the
            // DocumentSerializer rejects such writes per F03.R53.
            return;
        }

        // Build DCPE encryptors up front for any DistancePreserving field.
        // @spec encryption.primitives-dispatch.R7, R50 — DCPE does not use the byte-level
        // FieldEncryptor interface;
        // encryption happens inline in DocumentSerializer using these instances.
        for (int i = 0; i < fieldCount; i++) {
            final FieldDefinition fd = fields.get(i);
            if (fd.encryption() instanceof EncryptionSpec.DistancePreserving) {
                if (!(fd.type() instanceof FieldType.VectorType vt)) {
                    throw new IllegalArgumentException(
                            "DistancePreserving encryption requires a VectorType field, got "
                                    + fd.type() + " for field '" + fd.name() + "'");
                }
                if (vt.elementType() != FieldType.Primitive.FLOAT32) {
                    throw new IllegalArgumentException(
                            "DistancePreserving encryption requires FLOAT32 vector elements, got "
                                    + vt.elementType() + " for field '" + fd.name() + "'");
                }
                dcpeEncryptors[i] = new DcpeSapEncryptor(keyHolder, vt.dimensions());
                dcpeAssociatedData[i] = fd.name().getBytes(StandardCharsets.UTF_8);
            }
        }

        for (int i = 0; i < fieldCount; i++) {
            final FieldDefinition fd = fields.get(i);
            final EncryptionSpec spec = fd.encryption();

            switch (spec) {
                case EncryptionSpec.None _ -> {
                    // No encryption for this field
                }
                case EncryptionSpec.DistancePreserving _ -> {
                    // @spec encryption.primitives-dispatch.R7 — serializer invokes DcpeSapEncryptor
                    // from dcpeEncryptors[i]
                    // directly. No byte-level encryptor/decryptor installed.
                }
                case EncryptionSpec.Deterministic _ -> {
                    // AES-SIV requires a 64-byte key split into independent CMAC and CTR
                    // sub-keys. If the table key is 32 bytes, derive two independent
                    // 32-byte sub-keys via HMAC-SHA256 with domain-separated info strings.
                    // @spec encryption.primitives-key-holder.R8, F41.R16 — zero intermediates in
                    // finally, even on exception
                    final AesSivEncryptor siv;
                    if (keyHolder.keyLength() == 32) {
                        byte[] masterKey = null;
                        byte[] cmacHalf = null;
                        byte[] ctrHalf = null;
                        byte[] sivKey = null;
                        OffHeapKeyMaterial sivKeyHolder = null;
                        try {
                            masterKey = keyHolder.getKeyBytes();
                            cmacHalf = hmacSha256(masterKey, "siv-cmac-key");
                            ctrHalf = hmacSha256(masterKey, "siv-ctr-key");
                            sivKey = new byte[64];
                            System.arraycopy(cmacHalf, 0, sivKey, 0, 32);
                            System.arraycopy(ctrHalf, 0, sivKey, 32, 32);
                            sivKeyHolder = OffHeapKeyMaterial.of(sivKey);
                            siv = new AesSivEncryptor(sivKeyHolder);
                        } finally {
                            if (masterKey != null) {
                                Arrays.fill(masterKey, (byte) 0);
                            }
                            if (cmacHalf != null) {
                                Arrays.fill(cmacHalf, (byte) 0);
                            }
                            if (ctrHalf != null) {
                                Arrays.fill(ctrHalf, (byte) 0);
                            }
                            if (sivKey != null) {
                                Arrays.fill(sivKey, (byte) 0);
                            }
                            if (sivKeyHolder != null) {
                                sivKeyHolder.close();
                            }
                        }
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
                    // @spec encryption.primitives-variants.R54, encryption.ciphertext-envelope.R1 —
                    // derive per-field MAC key
                    // and bind MAC to field name
                    final byte[] masterKey = keyHolder.getKeyBytes();
                    final SecretKeySpec macKeySpec;
                    try {
                        final byte[] opeMacKey = hmacSha256(masterKey, "ope-mac-key");
                        try {
                            macKeySpec = new SecretKeySpec(opeMacKey, "HmacSHA256");
                        } finally {
                            Arrays.fill(opeMacKey, (byte) 0);
                        }
                    } finally {
                        Arrays.fill(masterKey, (byte) 0);
                    }
                    final byte[] associatedData = fd.name().getBytes(StandardCharsets.UTF_8);
                    encryptors[i] = plaintext -> opeEncryptTyped(ope, plaintext, maxBytes,
                            macKeySpec, associatedData);
                    decryptors[i] = ciphertext -> opeDecryptTyped(ope, ciphertext, maxBytes,
                            macKeySpec, associatedData);
                }
                case EncryptionSpec.Opaque _ -> {
                    // AES-GCM requires a 32-byte key. If the table key is 64 bytes,
                    // derive an independent 32-byte sub-key via HMAC-SHA256 with a
                    // domain-separated info string. Plain truncation would make the
                    // GCM key equal to the SIV CMAC sub-key, violating key independence.
                    // @spec encryption.primitives-key-holder.R8, F41.R16 — zero intermediates in
                    // finally, even on exception
                    final AesGcmEncryptor gcm;
                    if (keyHolder.keyLength() == 64) {
                        byte[] fullKey = null;
                        byte[] gcmKey = null;
                        OffHeapKeyMaterial gcmKeyHolder = null;
                        try {
                            fullKey = keyHolder.getKeyBytes();
                            gcmKey = hmacSha256(fullKey, "gcm-opaque-key");
                            gcmKeyHolder = OffHeapKeyMaterial.of(gcmKey);
                            gcm = new AesGcmEncryptor(gcmKeyHolder);
                        } finally {
                            if (fullKey != null) {
                                Arrays.fill(fullKey, (byte) 0);
                            }
                            if (gcmKey != null) {
                                Arrays.fill(gcmKey, (byte) 0);
                            }
                            if (gcmKeyHolder != null) {
                                gcmKeyHolder.close();
                            }
                        }
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
        if (fieldIndex < 0 || fieldIndex >= encryptors.length) {
            throw new IllegalArgumentException("fieldIndex out of bounds: " + fieldIndex);
        }
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
        if (fieldIndex < 0 || fieldIndex >= decryptors.length) {
            throw new IllegalArgumentException("fieldIndex out of bounds: " + fieldIndex);
        }
        return decryptors[fieldIndex];
    }

    /**
     * Returns the DCPE encryptor for the field at the given index, or {@code null} if the field is
     * not DistancePreserving or no key holder was provided. Used by {@link DocumentSerializer} to
     * encrypt vector values inline during serialization.
     *
     * @param fieldIndex the zero-based field index
     * @return the DCPE encryptor, or null
     */
    // @spec encryption.primitives-dispatch.R7, R50 — serializer reads DCPE encryptors directly
    public DcpeSapEncryptor dcpeEncryptorFor(int fieldIndex) {
        if (fieldIndex < 0 || fieldIndex >= dcpeEncryptors.length) {
            throw new IllegalArgumentException("fieldIndex out of bounds: " + fieldIndex);
        }
        return dcpeEncryptors[fieldIndex];
    }

    /**
     * Returns the DCPE associated data (UTF-8 field name) for the field at the given index, or
     * {@code null} if the field is not DistancePreserving.
     */
    public byte[] dcpeAssociatedDataFor(int fieldIndex) {
        if (fieldIndex < 0 || fieldIndex >= dcpeAssociatedData.length) {
            throw new IllegalArgumentException("fieldIndex out of bounds: " + fieldIndex);
        }
        return dcpeAssociatedData[fieldIndex];
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

    /** Detached MAC tag length (bytes) appended to OPE and DCPE ciphertext. */
    static final int MAC_TAG_BYTES = 16;

    /** Inner (pre-MAC) OPE ciphertext size: 1-byte length prefix + 8-byte encrypted long. */
    private static final int OPE_INNER_BYTES = 9;

    /** OPE ciphertext total size: inner ciphertext + detached MAC tag. */
    static final int OPE_CIPHERTEXT_BYTES = OPE_INNER_BYTES + MAC_TAG_BYTES;

    /**
     * Type-aware OPE encryption with detached MAC. Encodes the plaintext bytes as a positive long
     * in the natural domain of the field type (e.g., [1..256] for INT8), encrypts with OPE, and
     * wraps with a detached HMAC-SHA256 tag bound to the field name.
     *
     * <p>
     * The ciphertext format is: {@code [1-byte original-length][8-byte encrypted-long]
     * [16-byte HMAC-SHA256 tag]} — 25 bytes total. The MAC covers the first 9 bytes plus the UTF-8
     * field name (associated data).
     *
     * @see #opeDecryptTyped
     */
    // @spec encryption.primitives-variants.R21,R54, encryption.ciphertext-envelope.R1 — 25-byte OPE
    // format with detached MAC
    private static byte[] opeEncryptTyped(BoldyrevaOpeEncryptor ope, byte[] plaintext, int maxBytes,
            SecretKeySpec macKeySpec, byte[] associatedData) {
        if (plaintext == null) {
            throw new IllegalArgumentException("OPE plaintext must not be null");
        }
        if (plaintext.length > 255) {
            throw new IllegalArgumentException("OPE plaintext length " + plaintext.length
                    + " exceeds maximum of 255 — length must fit in a single unsigned byte");
        }
        final int useBytes = Math.min(plaintext.length, maxBytes);
        final long value = bytesToUnsignedBE(plaintext, useBytes) + 1; // +1: OPE domain starts at 1
        final long encrypted = ope.encrypt(value);
        final byte[] result = new byte[OPE_CIPHERTEXT_BYTES];
        result[0] = (byte) plaintext.length;
        for (int i = 0; i < 8; i++) {
            result[1 + i] = (byte) (encrypted >>> (56 - i * 8));
        }
        final byte[] tag = computeMacTag(macKeySpec, result, 0, OPE_INNER_BYTES, associatedData);
        System.arraycopy(tag, 0, result, OPE_INNER_BYTES, MAC_TAG_BYTES);
        return result;
    }

    /**
     * Type-aware OPE decryption with detached-MAC verification. Verifies the 16-byte MAC tag in
     * constant time before performing the OPE inverse. A tag mismatch throws
     * {@link SecurityException} with a message that does not reveal key or plaintext content.
     *
     * @see #opeEncryptTyped
     */
    // @spec encryption.primitives-variants.R21,R54, encryption.ciphertext-envelope.R1 — verify MAC
    // in constant time, then OPE
    // inverse
    private static byte[] opeDecryptTyped(BoldyrevaOpeEncryptor ope, byte[] ciphertext,
            int maxBytes, SecretKeySpec macKeySpec, byte[] associatedData) {
        if (ciphertext == null || ciphertext.length != OPE_CIPHERTEXT_BYTES) {
            throw new IllegalArgumentException(
                    "OPE ciphertext must be exactly " + OPE_CIPHERTEXT_BYTES + " bytes, got "
                            + (ciphertext == null ? "null" : ciphertext.length));
        }
        final byte[] expectedTag = computeMacTag(macKeySpec, ciphertext, 0, OPE_INNER_BYTES,
                associatedData);
        final byte[] actualTag = Arrays.copyOfRange(ciphertext, OPE_INNER_BYTES,
                OPE_CIPHERTEXT_BYTES);
        if (!MessageDigest.isEqual(expectedTag, actualTag)) {
            throw new SecurityException("OPE MAC verification failed: wrong key, tampered "
                    + "ciphertext, or cross-field substitution");
        }
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
     * Computes a truncated HMAC-SHA256 MAC tag over {@code [inner || associatedData]}. The MAC
     * covers the inner ciphertext byte range {@code [innerOffset, innerOffset + innerLen)} and the
     * UTF-8 field name (associated data). Returns the first {@link #MAC_TAG_BYTES} bytes.
     */
    // @spec encryption.primitives-variants.R54, R79 — detached HMAC-SHA256 tag bound to ciphertext
    // + field name
    static byte[] computeMacTag(SecretKeySpec macKeySpec, byte[] inner, int innerOffset,
            int innerLen, byte[] associatedData) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(macKeySpec);
            mac.update(inner, innerOffset, innerLen);
            if (associatedData != null && associatedData.length > 0) {
                mac.update(associatedData);
            }
            final byte[] full = mac.doFinal();
            return Arrays.copyOf(full, MAC_TAG_BYTES);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 tag computation failed", e);
        }
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
        Objects.requireNonNull(type, "type must not be null");
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

    /**
     * Derives a 32-byte sub-key from the given master key using HMAC-SHA256 with a
     * domain-separation info string. This ensures independent sub-keys even when derived from the
     * same master key.
     */
    private static byte[] hmacSha256(byte[] key, String info) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(info.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 key derivation failed", e);
        }
    }
}
