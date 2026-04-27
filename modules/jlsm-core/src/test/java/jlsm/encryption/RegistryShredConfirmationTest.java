package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RegistryShredConfirmation} (R83b-2). Single-use token shape: defensive nonce
 * copies; required (tenantId, domainId, generatedAt, nonce16) tuple validated.
 *
 * @spec encryption.primitives-lifecycle R83b-2
 */
class RegistryShredConfirmationTest {

    private static byte[] makeNonce(byte fill) {
        final byte[] n = new byte[16];
        for (int i = 0; i < n.length; i++) {
            n[i] = fill;
        }
        return n;
    }

    @Test
    void nonceLengthConstant() {
        assertEquals(16, RegistryShredConfirmation.NONCE_BYTES);
    }

    @Test
    void validConstruction() {
        final TenantId t = new TenantId("tenant");
        final DomainId d = new DomainId("domain");
        final Instant now = Instant.now();
        final RegistryShredConfirmation c = new RegistryShredConfirmation(t, d, now,
                makeNonce((byte) 1));
        assertEquals(t, c.tenantId());
        assertEquals(d, c.domainId());
        assertEquals(now, c.generatedAt());
        assertArrayEquals(makeNonce((byte) 1), c.nonce16());
    }

    @Test
    void nullTenantRejected() {
        assertThrows(NullPointerException.class, () -> new RegistryShredConfirmation(null,
                new DomainId("d"), Instant.now(), makeNonce((byte) 1)));
    }

    @Test
    void nullDomainRejected() {
        assertThrows(NullPointerException.class,
                () -> new RegistryShredConfirmation(new TenantId("t"), null, Instant.now(),
                        makeNonce((byte) 1)));
    }

    @Test
    void nullInstantRejected() {
        assertThrows(NullPointerException.class,
                () -> new RegistryShredConfirmation(new TenantId("t"), new DomainId("d"), null,
                        makeNonce((byte) 1)));
    }

    @Test
    void nullNonceRejected() {
        assertThrows(NullPointerException.class,
                () -> new RegistryShredConfirmation(new TenantId("t"), new DomainId("d"),
                        Instant.now(), null));
    }

    @Test
    void wrongNonceLengthRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RegistryShredConfirmation(new TenantId("t"), new DomainId("d"),
                        Instant.now(), new byte[15]));
        assertThrows(IllegalArgumentException.class,
                () -> new RegistryShredConfirmation(new TenantId("t"), new DomainId("d"),
                        Instant.now(), new byte[17]));
    }

    @Test
    void nonceCopiedOnConstruction() {
        final byte[] mutable = makeNonce((byte) 0x11);
        final RegistryShredConfirmation c = new RegistryShredConfirmation(new TenantId("t"),
                new DomainId("d"), Instant.now(), mutable);
        mutable[0] = (byte) 0x00;
        assertEquals((byte) 0x11, c.nonce16()[0], "constructor must defensively copy nonce16");
    }

    @Test
    void nonceCopiedOnAccess() {
        final RegistryShredConfirmation c = new RegistryShredConfirmation(new TenantId("t"),
                new DomainId("d"), Instant.now(), makeNonce((byte) 0x22));
        final byte[] view1 = c.nonce16();
        final byte[] view2 = c.nonce16();
        assertNotSame(view1, view2);
        view1[0] = (byte) 0x00;
        assertEquals((byte) 0x22, view2[0], "accessor must defensively copy nonce16 on read");
    }
}
