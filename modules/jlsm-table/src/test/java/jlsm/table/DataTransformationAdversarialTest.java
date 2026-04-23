package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import jlsm.encryption.AesGcmEncryptor;
import jlsm.encryption.AesSivEncryptor;
import jlsm.encryption.internal.OffHeapKeyMaterial;
import jlsm.encryption.EncryptionSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Adversarial tests for data transformation fidelity in jlsm-table.
 */
class DataTransformationAdversarialTest {

    // Finding: F-R1.data_transformation.1.1
    // Bug: SIV key derivation repeats 32-byte key — CMAC key equals CTR key
    // Correct behavior: When a 32-byte master key is provided, the derived 64-byte SIV key
    // must have independent halves so that the CMAC sub-key differs from the CTR sub-key
    // Fix location: FieldEncryptionDispatch constructor, lines 80-84
    // Regression watch: Ensure round-trip encrypt/decrypt still works after key derivation change
    @Test
    void test_FieldEncryptionDispatch_sivKeyDerivation_32byteKey_mustNotRepeatHalves() {
        // Create a 32-byte key
        final byte[] rawKey = new byte[32];
        Arrays.fill(rawKey, (byte) 0xCA);
        final OffHeapKeyMaterial keyHolder32 = OffHeapKeyMaterial.of(rawKey);

        // Build a schema with a single deterministic-encrypted field
        final String fieldName = "secret";
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field(fieldName, FieldType.string(), EncryptionSpec.deterministic()).build();

        // Construct the dispatch with the 32-byte key
        final FieldEncryptionDispatch dispatch = new FieldEncryptionDispatch(schema, keyHolder32);
        final FieldEncryptionDispatch.FieldEncryptor encryptor = dispatch.encryptorFor(0);
        assertNotNull(encryptor, "deterministic field must have an encryptor");

        // Encrypt a test plaintext via the dispatch
        final byte[] plaintext = "test data for SIV key derivation"
                .getBytes(StandardCharsets.UTF_8);
        final byte[] dispatchCiphertext = encryptor.encrypt(plaintext);

        // Now construct a "known-bad" AesSivEncryptor with the key repeated into both halves
        // (this is what the current buggy code does)
        final byte[] repeatedKey = new byte[64];
        final byte[] rawKey2 = new byte[32];
        Arrays.fill(rawKey2, (byte) 0xCA);
        System.arraycopy(rawKey2, 0, repeatedKey, 0, 32);
        System.arraycopy(rawKey2, 0, repeatedKey, 32, 32);
        final OffHeapKeyMaterial badKeyHolder = OffHeapKeyMaterial.of(repeatedKey);
        final AesSivEncryptor badSiv = new AesSivEncryptor(badKeyHolder);
        final byte[] associatedData = fieldName.getBytes(StandardCharsets.UTF_8);
        final byte[] badCiphertext = badSiv.encrypt(plaintext, associatedData);

        // The dispatch's ciphertext must NOT match the known-bad repeated-key ciphertext.
        // If it does, the dispatch is using K_cmac == K_ctr which violates AES-SIV's
        // security requirement for independent sub-keys.
        assertFalse(Arrays.equals(dispatchCiphertext, badCiphertext),
                "Dispatch with 32-byte key must derive independent SIV sub-keys, "
                        + "not repeat the key into both halves (CMAC key must differ from CTR key)");

        // Verify the dispatch still round-trips correctly
        final FieldEncryptionDispatch.FieldDecryptor decryptor = dispatch.decryptorFor(0);
        assertNotNull(decryptor, "deterministic field must have a decryptor");
        final byte[] recovered = decryptor.decrypt(dispatchCiphertext);
        assertArrayEquals(plaintext, recovered,
                "Round-trip must succeed: decrypt(encrypt(plaintext)) == plaintext");

        // Cleanup
        keyHolder32.close();
        badKeyHolder.close();
    }

    // Finding: F-R1.data_transformation.1.2
    // Bug: GCM key truncation discards high-entropy key material without HKDF — when
    // keyHolder is 64 bytes, the GCM key is Arrays.copyOfRange(fullKey, 0, 32), making
    // the GCM key identical to the first half of the SIV key (the CMAC sub-key)
    // Correct behavior: Derive the GCM key via HMAC-SHA256 with a domain-separated info
    // string so that GCM and SIV sub-keys are cryptographically independent
    // Fix location: FieldEncryptionDispatch constructor, Opaque case ~line 125-131
    // Regression watch: GCM round-trip must still work after key derivation change
    @Test
    void test_FieldEncryptionDispatch_gcmKeyTruncation_64byteKey_mustNotUsePlainPrefix() {
        // Create a 64-byte key
        final byte[] rawKey = new byte[64];
        Arrays.fill(rawKey, (byte) 0xBE);
        final OffHeapKeyMaterial keyHolder64 = OffHeapKeyMaterial.of(rawKey);

        // Build a schema with a single opaque-encrypted field
        final String fieldName = "opaque_field";
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field(fieldName, FieldType.string(), EncryptionSpec.opaque()).build();

        // Construct the dispatch with the 64-byte key
        final FieldEncryptionDispatch dispatch = new FieldEncryptionDispatch(schema, keyHolder64);
        final FieldEncryptionDispatch.FieldEncryptor encryptor = dispatch.encryptorFor(0);
        assertNotNull(encryptor, "opaque field must have an encryptor");

        // Encrypt a test plaintext via the dispatch
        final byte[] plaintext = "test data for GCM key derivation"
                .getBytes(StandardCharsets.UTF_8);
        final byte[] dispatchCiphertext = encryptor.encrypt(plaintext);

        // Construct a "known-bad" AesGcmEncryptor using the plain truncated first-32-bytes
        // (this is what the current buggy code does: Arrays.copyOfRange(fullKey, 0, 32))
        final byte[] truncatedKey = new byte[32];
        final byte[] rawKey2 = new byte[64];
        Arrays.fill(rawKey2, (byte) 0xBE);
        System.arraycopy(rawKey2, 0, truncatedKey, 0, 32);
        final OffHeapKeyMaterial badKeyHolder = OffHeapKeyMaterial.of(truncatedKey);
        final AesGcmEncryptor badGcm = new AesGcmEncryptor(badKeyHolder);

        // If the dispatch used the plain truncated key (the bug), the bad encryptor
        // can successfully decrypt the dispatch's ciphertext. After fix, the dispatch
        // uses an HMAC-derived key, so decryption with the truncated key must fail
        // with a SecurityException (GCM auth tag mismatch).
        assertThrows(SecurityException.class, () -> badGcm.decrypt(dispatchCiphertext),
                "Dispatch with 64-byte key must derive GCM key via HMAC, not plain truncation — "
                        + "decryption with truncated prefix key must fail (keys must differ)");

        // Verify the dispatch still round-trips correctly
        final FieldEncryptionDispatch.FieldDecryptor decryptor = dispatch.decryptorFor(0);
        assertNotNull(decryptor, "opaque field must have a decryptor");
        final byte[] recovered = decryptor.decrypt(dispatchCiphertext);
        assertArrayEquals(plaintext, recovered,
                "Round-trip must succeed: decrypt(encrypt(plaintext)) == plaintext");

        // Cleanup
        keyHolder64.close();
        badKeyHolder.close();
    }

    // Finding: F-R1.data_transformation.1.3
    // Bug: opeEncryptTyped casts plaintext.length to (byte) — truncates lengths > 127 to wrong
    // value
    // Correct behavior: opeEncryptTyped must reject plaintext with length > 255 via
    // IllegalArgumentException since the length is stored as a single unsigned byte
    // Fix location: FieldEncryptionDispatch.opeEncryptTyped, line 219 (result[0] = (byte)
    // plaintext.length)
    // Regression watch: Ensure normal OPE round-trips (length <= 2) still work after adding the
    // guard
    @Test
    @Timeout(10)
    void test_opeEncryptTyped_plaintextLengthExceeds255_mustRejectNotTruncate() {
        // Create a 32-byte key for OPE
        final byte[] rawKey = new byte[32];
        Arrays.fill(rawKey, (byte) 0xAB);
        final OffHeapKeyMaterial keyHolder = OffHeapKeyMaterial.of(rawKey);

        // Build a schema with an INT8 OPE field (valid — fits in 1 byte)
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("ope_field", FieldType.Primitive.INT8, EncryptionSpec.orderPreserving())
                .build();

        final FieldEncryptionDispatch dispatch = new FieldEncryptionDispatch(schema, keyHolder);
        final FieldEncryptionDispatch.FieldEncryptor encryptor = dispatch.encryptorFor(0);
        assertNotNull(encryptor, "OPE field must have an encryptor");

        // Create a 256-byte plaintext — length 256 cast to (byte) yields 0
        // On decrypt, originalLen would be 0, producing an empty array — data loss
        final byte[] oversizedPlaintext = new byte[256];
        Arrays.fill(oversizedPlaintext, (byte) 0x42);

        // The encryptor must reject plaintext whose length cannot be stored in a single
        // unsigned byte (> 255). The current code silently truncates via (byte) cast.
        assertThrows(IllegalArgumentException.class, () -> encryptor.encrypt(oversizedPlaintext),
                "OPE encrypt must reject plaintext with length > 255 — "
                        + "(byte) cast truncates length, corrupting round-trip");

        // Cleanup
        keyHolder.close();
    }

    // Finding: F-R1.data_transformation.1.4
    // Bug: opeDecryptTyped validates ciphertext length only via assert — no runtime guard
    // Correct behavior: opeDecryptTyped must reject null or wrong-length ciphertext with
    // IllegalArgumentException, not ArrayIndexOutOfBoundsException or NullPointerException
    // Fix location: FieldEncryptionDispatch.opeDecryptTyped, line 239 (assert-only guard)
    // Regression watch: Ensure normal OPE decrypt round-trips still work after adding the guard
    @Test
    @Timeout(10)
    void test_opeDecryptTyped_wrongLengthCiphertext_mustRejectWithIllegalArgument() {
        // Create a 32-byte key for OPE
        final byte[] rawKey = new byte[32];
        Arrays.fill(rawKey, (byte) 0xAB);
        final OffHeapKeyMaterial keyHolder = OffHeapKeyMaterial.of(rawKey);

        // Build a schema with an INT8 OPE field (valid — fits in 1 byte)
        final JlsmSchema schema = JlsmSchema.builder("test", 1)
                .field("ope_field", FieldType.Primitive.INT8, EncryptionSpec.orderPreserving())
                .build();

        final FieldEncryptionDispatch dispatch = new FieldEncryptionDispatch(schema, keyHolder);
        final FieldEncryptionDispatch.FieldDecryptor decryptor = dispatch.decryptorFor(0);
        assertNotNull(decryptor, "OPE field must have a decryptor");

        // A 5-byte ciphertext — wrong length (must be exactly 9 bytes).
        // With assertions disabled, this would cause ArrayIndexOutOfBoundsException
        // instead of a descriptive error.
        final byte[] shortCiphertext = new byte[]{ 0x01, 0x02, 0x03, 0x04, 0x05 };

        // The decryptor must reject wrong-length ciphertext with IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> decryptor.decrypt(shortCiphertext),
                "OPE decrypt must reject ciphertext of wrong length with IllegalArgumentException, "
                        + "not ArrayIndexOutOfBoundsException");

        // Also verify null ciphertext is rejected with IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> decryptor.decrypt(null),
                "OPE decrypt must reject null ciphertext with IllegalArgumentException, "
                        + "not NullPointerException");

        // Cleanup
        keyHolder.close();
    }

    // Finding: F-R1.data_transformation.1.6
    // Bug: deriveOpeBounds uses only assert for type null check — no runtime guard
    // Correct behavior: deriveOpeBounds(null) must throw NullPointerException, not
    // fall through to opeMaxBytes which produces a misleading IllegalArgumentException
    // ("not supported for field type null")
    // Fix location: FieldEncryptionDispatch.deriveOpeBounds, line 329 (assert-only null check)
    // Regression watch: Ensure normal OPE bounds derivation for valid types still works
    @Test
    @Timeout(10)
    void test_deriveOpeBounds_nullType_mustThrowNullPointerException() {
        // With assertions disabled (production), passing null to deriveOpeBounds falls through
        // the assert to opeMaxBytes, which returns a misleading error message about
        // "field type null" instead of a clear null rejection.
        // The method is package-private (static), so we can call it directly.
        assertThrows(NullPointerException.class,
                () -> FieldEncryptionDispatch.deriveOpeBounds(null),
                "deriveOpeBounds(null) must throw NullPointerException — "
                        + "assert alone does not guard in production (assertions disabled)");
    }
}
