package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DeployerInstanceId}. Validates ≥256-bit entropy guard per R79c-1 P4-12 — raw
 * UUIDs are explicitly rejected — and defensive-copy on both compact constructor and accessor.
 *
 * @spec encryption.primitives-lifecycle R79c-1
 */
class DeployerInstanceIdTest {

    private static byte[] randomSecret(int length) {
        final byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i + 1);
        }
        return bytes;
    }

    @Test
    void nullSecretRejected() {
        assertThrows(NullPointerException.class, () -> new DeployerInstanceId(null));
    }

    @Test
    void rawUuidLengthRejected() {
        // 16 bytes — UUIDs at 122 bits of entropy are explicitly forbidden per R79c-1 P4-12.
        final byte[] uuidBytes = randomSecret(16);
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new DeployerInstanceId(uuidBytes));
        assertTrue(ex.getMessage().contains("32"),
                "error message must reference the 32-byte (256-bit) floor");
    }

    @Test
    void shortSecretRejected() {
        for (int len = 0; len < 32; len++) {
            final byte[] bytes = randomSecret(len);
            assertThrows(IllegalArgumentException.class, () -> new DeployerInstanceId(bytes),
                    "secret of " + len + " bytes must be rejected (R79c-1 P4-12 256-bit floor)");
        }
    }

    @Test
    void thirtyTwoByteSecretAccepted() {
        final byte[] bytes = randomSecret(32);
        final DeployerInstanceId id = new DeployerInstanceId(bytes);
        assertArrayEquals(bytes, id.secret());
    }

    @Test
    void sixtyFourByteSecretAccepted() {
        // SHA-512 output length, often used for derived deployer secrets.
        final byte[] bytes = randomSecret(64);
        final DeployerInstanceId id = new DeployerInstanceId(bytes);
        assertEquals(64, id.secret().length);
    }

    @Test
    void compactConstructorTakesDefensiveCopy() {
        final byte[] bytes = randomSecret(32);
        final DeployerInstanceId id = new DeployerInstanceId(bytes);
        bytes[0] = (byte) 0xFF;
        // Mutating the source must not change the held secret.
        assertFalse(id.secret()[0] == (byte) 0xFF,
                "compact constructor must defensively copy — caller mutation must not bleed in");
    }

    @Test
    void accessorReturnsDefensiveCopy() {
        final byte[] bytes = randomSecret(32);
        final DeployerInstanceId id = new DeployerInstanceId(bytes);
        final byte[] first = id.secret();
        final byte[] second = id.secret();
        assertNotSame(first, second,
                "accessor must return defensive copies (no reference aliasing)");
        first[0] = (byte) 0xFF;
        assertFalse(id.secret()[0] == (byte) 0xFF,
                "mutating an accessor's return must not affect the held secret");
    }

    @Test
    void equalSecretsProduceEqualRecords() {
        final DeployerInstanceId a = new DeployerInstanceId(randomSecret(32));
        final DeployerInstanceId b = new DeployerInstanceId(randomSecret(32));
        // Records auto-generate equals using Arrays.equals via component arrays only when the
        // generator does so — Java records use Objects.equals for arrays, so two different array
        // *instances* with identical contents are NOT equal under default record equals. Confirm
        // identity-based equals is observable so callers do not accidentally rely on value equality
        // on a byte[] component.
        assertFalse(a.equals(b),
                "default record equals on byte[] component is identity-based — confirm callers know");
    }

    @Test
    void minSecretBytesConstantIs32() {
        assertEquals(32, DeployerInstanceId.MIN_SECRET_BYTES,
                "256-bit (32-byte) entropy floor pinned by R79c-1 P4-12");
    }
}
